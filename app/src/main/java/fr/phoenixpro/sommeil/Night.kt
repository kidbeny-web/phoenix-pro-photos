package fr.phoenixpro.sommeil

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

data class Live(
    val running: Boolean = false,
    val startMs: Long = 0,
    val listenFrom: Long = 0,
    val events: Int = 0,
    val snores: Int = 0,
    val level: Float = 0f,
    val background: Float = 0f,
    val error: String? = null,
)

/** Événement sonore en cours d'enregistrement. */
private class Pending(val startMs: Long, pre: ShortArray, private val max: Int) {
    var audio = ShortArray(pre.size + 16000 * 4)
    var size = 0
    var peak = -200f
    var zcr = 0.0
    var blocks = 0
    var below = 0
    var lastLoud = startMs
    init { add(pre, pre.size) }
    fun add(b: ShortArray, n: Int) {
        val take = min(n, max - size); if (take <= 0) return
        if (size + take > audio.size) audio = audio.copyOf(maxOf(audio.size * 2, size + take))
        System.arraycopy(b, 0, audio, size, take); size += take
    }
}

object Recorder {
    private const val FS = 16000
    private const val BLOCK = FS / 10          // 100 ms
    private const val PRE = FS * 2             // 2 s avant
    private const val MAX_CLIP = FS * 15       // 15 s max par extrait
    private const val MAX_CLIPS = 80           // par nuit
    private const val BASE = 100f              // dBFS + 100 = niveau relatif affiché

    private val _state = MutableStateFlow(Live())
    val state: StateFlow<Live> = _state
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var lastNightId = 0L
        private set

    fun isRunning() = running

    fun start(ctx: Context, delayMin: Int) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        thread = Thread({ loop(app, delayMin) }, "phoenix-sommeil").also { it.start() }
    }

    fun stop() { running = false; thread?.join(4000); thread = null }

    @SuppressLint("MissingPermission")
    private fun loop(ctx: Context, delayMin: Int) {
        val now0 = System.currentTimeMillis()
        val night = Night(now0, now0, now0 + delayMin * 60_000L, now0)
        _state.value = Live(true, now0, night.listenFrom)

        // Délai d'endormissement : on attend sans écouter.
        while (running && System.currentTimeMillis() < night.listenFrom) Thread.sleep(500)
        if (!running) { _state.value = Live(); return }

        val minBuf = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FS))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            running = false; _state.value = Live(error = "Micro indisponible : vérifiez l'autorisation Micro."); return
        }
        try { rec.startRecording() } catch (e: Exception) {
            rec.release(); running = false; _state.value = Live(error = "Le micro est utilisé par une autre appli."); return
        }

        night.listenFrom = System.currentTimeMillis()
        val dir = Store.dir(night.id)
        val margin = Store.margin
        val buf = ShortArray(BLOCK)
        val ring = ShortArray(PRE); var ringPos = 0; var ringFill = 0
        val recent = ArrayDeque<Float>()   // niveaux des 60 dernières secondes
        var background = -60f
        var minuteSum = 0.0; var minuteN = 0; var minuteStart = night.listenFrom
        var pending: Pending? = null
        val graveEnds = ArrayDeque<Long>() // fins des sons graves récents (pour repérer un rythme)
        var clips = 0
        var lastSave = night.listenFrom
        var tick = 0

        fun snapshot(): ShortArray {
            val out = ShortArray(ringFill)
            val s = (ringPos - ringFill + PRE) % PRE
            for (i in 0 until ringFill) out[i] = ring[(s + i) % PRE]
            return out
        }

        fun finish(p: Pending) {
            val end = p.lastLoud
            val dur = end - p.startMs
            // Fréquence dominante approximative par les passages à zéro.
            val freq = if (p.blocks > 0) p.zcr / p.blocks * FS / 2 else 0.0
            var kind = when {
                freq in 1.0..420.0 && dur in 300..4500 -> Kind.SNORE
                freq > 900 && dur >= 600 -> Kind.TALK
                else -> Kind.NOISE
            }
            if (kind == Kind.SNORE) {
                // Un ronflement se répète régulièrement : on exige au moins un son grave 1,5 à 10 s avant.
                while (graveEnds.isNotEmpty() && end - graveEnds.first() > 30_000) graveEnds.removeFirst()
                val rhythmic = graveEnds.any { end - it in 1_500..10_000 }
                graveEnds.addLast(end)
                if (!rhythmic) kind = Kind.NOISE
                else night.events.lastOrNull()?.let { prev ->
                    // Le son grave précédent faisait partie de la série : on le requalifie.
                    if (prev.kind == Kind.NOISE && end - prev.end in 1_500..10_000) prev.kind = Kind.SNORE
                }
            }
            var clipName: String? = null
            if (clips < MAX_CLIPS && p.size > 0) {
                clips++
                clipName = "son_%03d.wav".format(clips)
                writeWav(File(dir, clipName), p.audio, p.size)
            }
            night.events.add(Ev(p.startMs, end, kind, p.peak, clipName))
        }

        while (running) {
            val n = rec.read(buf, 0, BLOCK)
            if (n <= 0) continue
            var sum = 0.0; var zc = 0
            for (i in 0 until n) {
                val x = buf[i].toDouble()
                sum += x * x
                if (i > 0 && (buf[i] >= 0) != (buf[i - 1] >= 0)) zc++
            }
            val level = (10 * log10(max(sum / n, 1.0) / (32768.0 * 32768.0))).toFloat() + BASE
            val now = System.currentTimeMillis()

            // Bruit de fond = médiane des 60 dernières secondes, recalculée chaque seconde.
            recent.addLast(level); if (recent.size > 600) recent.removeFirst()
            if (++tick % 10 == 0) background = recent.sorted()[recent.size / 2]
            val loud = level >= background + margin && level > 25f

            var p = pending
            if (loud) {
                if (p == null) { p = Pending(now - 100, snapshot(), MAX_CLIP); pending = p }
                p.add(buf, n); p.below = 0; p.lastLoud = now
                p.peak = max(p.peak, level); p.zcr += zc.toDouble() / n; p.blocks++
            } else if (p != null) {
                p.add(buf, n)
                if (++p.below >= 15) { // 1,5 s de calme : fin de l'événement
                    if (p.lastLoud - p.startMs >= 250) finish(p)
                    pending = null
                }
            }
            for (i in 0 until n) { ring[ringPos] = buf[i]; ringPos = (ringPos + 1) % PRE }
            ringFill = min(PRE, ringFill + n)

            minuteSum += level; minuteN++
            if (now - minuteStart >= 60_000) {
                night.minutes.add((minuteSum / minuteN).toFloat())
                minuteSum = 0.0; minuteN = 0; minuteStart = now
            }
            night.end = now
            if (tick % 5 == 0) _state.value = Live(
                true, night.start, night.listenFrom, night.events.size, night.events.count { it.kind == Kind.SNORE }, level, background,
            )
            if (now - lastSave > 60_000) { Store.save(night); lastSave = now }
        }

        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        pending?.let { if (it.lastLoud - it.startMs >= 250) finish(it) }
        if (minuteN > 0) night.minutes.add((minuteSum / minuteN).toFloat())
        night.end = System.currentTimeMillis()
        night.factors.addAll(Store.lastFactors)
        Store.save(night)
        lastNightId = night.id
        _state.value = Live()
    }

    private fun writeWav(f: File, pcm: ShortArray, n: Int) {
        val len = n * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + len); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(FS); h.putInt(FS * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(len)
        val body = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) body.putShort(pcm[i])
        FileOutputStream(f).use { it.write(h.array()); it.write(body.array()) }
    }
}

/** Service de premier plan : écoute toute la nuit, écran éteint. */
class NightService : Service() {
    companion object {
        private const val CH = "nuit"
        private const val ID = 7
        private const val STOP = "fr.phoenixpro.sommeil.STOP"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, NightService::class.java))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, NightService::class.java).setAction(STOP)) }
    }

    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Thread {
                Recorder.stop()
                lock?.let { if (it.isHeld) it.release() }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Nuit en cours", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, ID, notif(), type)
        if (!Recorder.isRunning()) {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoenixPro:Sommeil")
                .apply { setReferenceCounted(false); acquire(14 * 60 * 60 * 1000L) }
            Recorder.start(this, Store.delayMin)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Recorder.isRunning()) Thread { Recorder.stop() }.start()
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NightService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Bonne nuit")
            .setContentText("Phoenix Pro Sommeil écoute la nuit. Touchez « Réveil » le matin.")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Réveil", stop)
            .setColor(0xFFF08A1C.toInt())
            .build()
    }
}
