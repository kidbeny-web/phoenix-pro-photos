package fr.phoenixpro.sonometre

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Ce que l'écran affiche pendant la mesure. */
data class Live(
    val running: Boolean = false,
    val level: Double = 0.0,
    val leq: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val startMs: Long = 0L,
    val events: Int = 0,
    val inEvent: Boolean = false,
    val calibration: Double = 0.0,
    val history: List<Float> = emptyList(),
    val error: String? = null,
)

/** Filtre du premier ordre obtenu par transformation bilinéaire. */
private class FirstOrder(val b0: Double, val b1: Double, val a1: Double) {
    private var x1 = 0.0
    private var y1 = 0.0

    fun step(x: Double): Double {
        val y = b0 * x + b1 * x1 - a1 * y1
        x1 = x; y1 = y
        return y
    }

    fun magnitude(w: Double): Double {
        val nr = b0 + b1 * cos(w); val ni = -b1 * sin(w)
        val dr = 1 + a1 * cos(w); val di = -a1 * sin(w)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }
}

/** Pondération A (IEC 61672), normalisée à 0 dB à 1 kHz. */
class AWeighting(fs: Double) {
    private val sections: List<FirstOrder>
    private val gain: Double

    init {
        val k = 2 * fs
        // Fréquences pré-déformées : la réponse reste à moins de 1 dB de la norme jusqu'à 12,5 kHz.
        fun warp(f: Double) = k * tan(PI * f / fs)
        fun hp(f: Double): FirstOrder { val w = warp(f); return FirstOrder(k / (k + w), -k / (k + w), (w - k) / (k + w)) }
        fun lp(f: Double): FirstOrder { val w = warp(f); return FirstOrder(1 / (k + w), 1 / (k + w), (w - k) / (k + w)) }
        sections = listOf(hp(20.598997), hp(20.598997), hp(12194.217), hp(12194.217), lp(107.65265), lp(737.86223))
        val w1k = 2 * PI * 1000 / fs
        var m = 1.0
        for (s in sections) m *= s.magnitude(w1k)
        gain = 1.0 / m
    }

    fun process(x: Double): Double {
        var y = x * gain
        for (s in sections) y = s.step(y)
        return y
    }
}

/** Mémoire circulaire des dernières secondes de son (pour l'avant-événement). */
private class Ring(capacity: Int) {
    private val data = ShortArray(max(1, capacity))
    private var pos = 0
    private var filled = 0

    fun write(src: ShortArray, n: Int) {
        for (i in 0 until n) {
            data[pos] = src[i]
            pos = (pos + 1) % data.size
        }
        filled = min(data.size, filled + n)
    }

    fun snapshot(): ShortArray {
        val out = ShortArray(filled)
        val startIdx = (pos - filled + data.size) % data.size
        for (i in 0 until filled) out[i] = data[(startIdx + i) % data.size]
        return out
    }
}

/** Événement en cours de construction. */
private class Pending(val startMs: Long, pre: ShortArray, private val maxSamples: Int) {
    var audio = ShortArray(max(pre.size * 2, 48000))
    var size = 0
    var aboveMs = 0
    var belowMs = 0
    var confirmed = false
    var energy = 0.0
    var blocks = 0
    var lmax = 0.0
    var lastAboveMs = startMs

    init { append(pre, pre.size) }

    fun append(src: ShortArray, n: Int) {
        val take = min(n, maxSamples - size)
        if (take <= 0) return
        if (size + take > audio.size) audio = audio.copyOf(max(audio.size * 2, size + take))
        System.arraycopy(src, 0, audio, size, take)
        size += take
    }
}

object Meter {
    private val _state = MutableStateFlow(Live())
    val state: StateFlow<Live> = _state

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Dernière séance terminée (pour l'ouvrir après l'arrêt). */
    @Volatile var lastSessionId: Long = 0L
        private set

    fun isRunning() = running

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        thread = Thread({ loop(app) }, "phoenix-sonometre").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(4000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(ctx: Context): Triple<AudioRecord, Int, String>? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = if (unprocessed)
            listOf(MediaRecorder.AudioSource.UNPROCESSED to "micro brut (non traité)", MediaRecorder.AudioSource.VOICE_RECOGNITION to "micro reconnaissance vocale")
        else listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION to "micro reconnaissance vocale")
        for ((src, label) in sources) for (fs in listOf(48000, 44100)) {
            val minBuf = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            try {
                val r = AudioRecord(src, fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, fs / 2))
                if (r.state == AudioRecord.STATE_INITIALIZED) return Triple(r, fs, label)
                r.release()
            } catch (e: Exception) { /* essai suivant */ }
        }
        return null
    }

    private fun loop(ctx: Context) {
        val opened = openRecorder(ctx)
        if (opened == null) {
            running = false
            _state.value = Live(error = "Le micro n'est pas disponible. Vérifiez l'autorisation Micro de l'appli.")
            return
        }
        val (rec, fs, sourceLabel) = opened
        val block = fs / 10 // 100 ms
        val buf = ShortArray(block)
        val filter = AWeighting(fs.toDouble())
        val alpha = 1 - exp(-1.0 / (fs * 0.125)) // constante « rapide » 125 ms
        val calib = Store.calibration.toDouble()
        val base = 120.0 + calib
        fun db(ms: Double) = 10 * log10(max(ms, 1e-14)) + base

        val threshold = Store.threshold
        val minDur = Store.minDuration
        val now0 = System.currentTimeMillis()
        val session = Session(
            id = now0, start = now0, end = now0,
            threshold = threshold, calibration = calib, minDuration = minDur,
            device = "${Build.MANUFACTURER} ${Build.MODEL}", source = "$sourceLabel, $fs Hz",
            place = Store.lastPlace,
        )
        val dir = Store.dir(session.id)
        val ring = Ring(fs * Store.preSeconds)
        val maxClip = fs * 120
        var pending: Pending? = null

        var fast = 1e-12
        var secE = 0.0; var secN = 0
        var lmin = Double.MAX_VALUE
        val history = ArrayDeque<Float>()
        var lastSave = now0

        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release(); running = false
            _state.value = Live(error = "Impossible de démarrer le micro (déjà utilisé par une autre appli ?)")
            return
        }

        while (running) {
            val n = rec.read(buf, 0, block)
            if (n <= 0) continue
            var sum = 0.0
            var peak = fast
            for (i in 0 until n) {
                val y = filter.process(buf[i] / 32768.0)
                val y2 = y * y
                sum += y2
                fast += (y2 - fast) * alpha
                if (fast > peak) peak = fast
            }
            val now = System.currentTimeMillis()
            val ms = sum / n
            val leqBlock = db(ms)
            val laf = db(fast)
            val lafMax = db(peak)
            if (lafMax > session.lmax) session.lmax = lafMax

            // Détection d'événement (au-dessus du seuil pendant la durée minimale)
            val above = leqBlock >= threshold
            var p = pending
            if (above) {
                if (p == null) { p = Pending(now - 100, ring.snapshot(), maxClip); pending = p }
                p.append(buf, n)
                p.belowMs = 0
                p.aboveMs += 100
                p.energy += ms; p.blocks++
                p.lmax = max(p.lmax, lafMax)
                p.lastAboveMs = now
                if (!p.confirmed && p.aboveMs >= minDur * 1000) {
                    p.confirmed = true
                }
            } else if (p != null) {
                p.append(buf, n)
                p.belowMs += 100
                if (!p.confirmed && p.belowMs >= 600) pending = null
                else if (p.confirmed && p.belowMs >= 2000) {
                    finishEvent(session, p, dir, fs, ::db)
                    pending = null
                }
            }
            ring.write(buf, n)

            // Niveau par seconde
            secE += ms; secN++
            if (secN == 10) {
                val l = db(secE / secN)
                session.levels.add(l.toFloat())
                lmin = minOf(lmin, l)
                secE = 0.0; secN = 0
            }
            session.end = now

            history.addLast(laf.toFloat())
            while (history.size > 1200) history.removeFirst()

            val evCount = session.events.size + if (pending?.confirmed == true) 1 else 0
            _state.value = Live(
                running = true, level = laf, leq = session.leq(), min = if (lmin == Double.MAX_VALUE) laf else lmin,
                max = session.lmax, startMs = session.start, events = evCount, inEvent = pending?.confirmed == true,
                calibration = calib, history = history.toList(),
            )

            if (now - lastSave > 30_000) { Store.save(session); lastSave = now }
        }

        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        pending?.let { if (it.confirmed) finishEvent(session, it, dir, fs, ::db) }
        session.end = System.currentTimeMillis()
        Store.save(session)
        lastSessionId = session.id
        _state.value = _state.value.copy(running = false, inEvent = false)
    }

    private fun finishEvent(s: Session, p: Pending, dir: File, fs: Int, db: (Double) -> Double) {
        val idx = s.events.size + 1
        val ev = EventRec(start = p.startMs, end = p.lastAboveMs, leq = db(p.energy / max(1, p.blocks)), lmax = p.lmax)
        if (p.size > 0) {
            val f = File(dir, "evenement_%03d.wav".format(idx))
            writeWav(f, p.audio, p.size, fs)
            ev.clip = f.name
            ev.sha = sha256(f)
        }
        s.events.add(ev)
        Store.save(s)
    }

    private fun writeWav(f: File, pcm: ShortArray, n: Int, fs: Int) {
        val dataLen = n * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(fs); h.putInt(fs * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen)
        val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) body.putShort(pcm[i])
        FileOutputStream(f).use { it.write(h.array()); it.write(body.array()) }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val b = ByteArray(65536)
            while (true) { val r = ins.read(b); if (r <= 0) break; md.update(b, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
