package fr.phoenixpro.music

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Format réel d'un fichier : codec, bits, fréquence. */
data class AudioInfo(val codec: String, val bits: Int, val sampleRate: Int, val channels: Int, val kbps: Int) {
    val hiRes: Boolean get() = bits > 16 || sampleRate > 48000

    fun label(): String {
        val parts = mutableListOf(codec)
        if (bits > 0) parts += "$bits bits"
        if (sampleRate > 0) parts += khz(sampleRate)
        if (bits <= 0 && kbps > 0) parts += "$kbps kbit/s"
        return parts.joinToString(" · ")
    }

    companion object {
        fun khz(rate: Int): String {
            val k = rate / 1000.0
            return (if (rate % 1000 == 0) "${rate / 1000}" else String.format(java.util.Locale.FRANCE, "%.1f", k)) + " kHz"
        }
    }
}

object AudioInfos {
    private val cache = HashMap<String, AudioInfo>()

    suspend fun read(ctx: Context, t: Track): AudioInfo? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[t.key] }?.let { return@withContext it }
        val info = try {
            readFlac(ctx, t) ?: readGeneric(ctx, t)
        } catch (_: Exception) {
            null
        }
        if (info != null) synchronized(cache) { cache[t.key] = info }
        info
    }

    private fun codecOf(t: Track): String {
        val ext = t.fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "flac" || t.mime.contains("flac") -> "FLAC"
            ext == "mp3" || t.mime.contains("mpeg") -> "MP3"
            ext == "m4a" || ext == "mp4" || t.mime.contains("mp4") -> "AAC/ALAC"
            ext == "ogg" -> "Ogg Vorbis"
            ext == "opus" -> "Opus"
            ext == "wav" || t.mime.contains("wav") -> "WAV"
            else -> ext.uppercase().ifBlank { "Audio" }
        }
    }

    private fun InputStream.readFully(n: Int): ByteArray? {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = read(b, off, n - off); if (r < 0) return null; off += r }
        return b
    }

    /** Lit le bloc STREAMINFO d'un FLAC : valeurs exactes du fichier. */
    private fun readFlac(ctx: Context, t: Track): AudioInfo? {
        if (codecOf(t) != "FLAC") return null
        ctx.contentResolver.openInputStream(t.uri)?.use { raw ->
            val ins = raw.buffered()
            var head = ins.readFully(10) ?: return null
            if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                    ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
                var skip = size.toLong()
                while (skip > 0) { val s = ins.skip(skip); if (s <= 0) return null; skip -= s }
                head = ins.readFully(10) ?: return null
            }
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
            val rest = ins.readFully(32) ?: return null
            val b = head.copyOfRange(4, 10) + rest // b[0..3] = en-tête de bloc, b[4..] = STREAMINFO
            fun u(i: Int) = b[i].toInt() and 0xff
            val si = 4
            val rate = (u(si + 10) shl 12) or (u(si + 11) shl 4) or (u(si + 12) shr 4)
            val ch = ((u(si + 12) shr 1) and 7) + 1
            val bps = (((u(si + 12) and 1) shl 4) or (u(si + 13) shr 4)) + 1
            return AudioInfo("FLAC", bps, rate, ch, if (t.bitrate > 0) t.bitrate / 1000 else 0)
        }
        return null
    }

    private fun readGeneric(ctx: Context, t: Track): AudioInfo? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, t.uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                val bits = when {
                    f.containsKey("bits-per-sample") -> f.getInteger("bits-per-sample")
                    mime.contains("alac") || mime.contains("raw") || mime.contains("wav") -> 16
                    else -> 0
                }
                val br = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) else t.bitrate
                val codec = if (mime.contains("alac")) "ALAC" else if (mime.contains("mp4a")) "AAC" else codecOf(t)
                return AudioInfo(codec, bits, rate, ch, br / 1000)
            }
        } finally {
            ex.release()
        }
        return null
    }
}
