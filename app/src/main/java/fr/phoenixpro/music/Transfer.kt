package fr.phoenixpro.music

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.storage.StorageManager
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class StorageChoice(val volume: String, val label: String)

/**
 * Réception de musique depuis le PC : le téléphone sert une petite page web ;
 * sur le PC, on ouvre l'adresse dans le navigateur et on glisse ses dossiers d'albums.
 */
object Transfer {
    const val PORT = 8484

    data class State(
        val running: Boolean = false,
        val address: String? = null,
        val code: String = "",
        val received: Int = 0,
        val skipped: Int = 0,
        val current: String = "",
        val currentPct: Int = 0,
        val bytes: Long = 0,
        val error: String? = null,
        val lastDone: Long = 0,
    )

    val state = MutableStateFlow(State())

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private lateinit var app: Context

    val audioExt = setOf("flac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "wv", "alac")
    private val imageExt = setOf("jpg", "jpeg", "png")

    fun storages(ctx: Context): List<StorageChoice> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val vols = MediaStore.getExternalVolumeNames(ctx)
        return vols.map { v ->
            val sv = sm.storageVolumes.firstOrNull { sv ->
                if (android.os.Build.VERSION.SDK_INT >= 30) sv.mediaStoreVolumeName == v
                else (if (sv.isPrimary) "external_primary" else sv.uuid?.lowercase()) == v
            }
            val label = when {
                v == MediaStore.VOLUME_EXTERNAL_PRIMARY -> "Mémoire du téléphone"
                sv?.isRemovable == true -> "Carte microSD" + (sv.getDescription(ctx)?.let { " ($it)" } ?: "")
                else -> sv?.getDescription(ctx) ?: v
            }
            StorageChoice(v, label)
        }.sortedBy { if (it.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) 0 else 1 }
    }

    @Suppress("DEPRECATION")
    fun wifiIp(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            cm.getLinkProperties(n)?.linkAddresses?.forEach { la ->
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
            }
        }
        return null
    }

    @Synchronized
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (server != null) return
        val ip = wifiIp(app)
        if (ip == null) {
            state.value = State(error = "Connecte le téléphone au Wi-Fi (le même que le PC)")
            return
        }
        val code = (1000..9999).random().toString()
        val ss = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            state.value = State(error = "Impossible d'ouvrir le port $PORT : ${e.message}")
            return
        }
        server = ss
        state.value = State(running = true, address = "http://$ip:$PORT", code = code)
        thread(name = "transfer", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} } }
            }
        }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        state.value = state.value.copy(running = false, address = null)
    }

    // ---------- HTTP minimal ----------

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16384) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 400 -> "Bad Request"; else -> "Error" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, code: Int, j: Any) =
        respond(out, code, "application/json; charset=utf-8", j.toString().toByteArray())

    private fun handle(s: Socket) {
        s.soTimeout = 60000
        val ins = BufferedInputStream(s.getInputStream(), 256 * 1024)
        val out = s.getOutputStream()
        val req = readLine(ins) ?: return
        val parts = req.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(ins) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val st = state.value

        if (method == "GET" && (path == "/" || path == "/index.html")) {
            val html = app.assets.open("envoi.html").use { it.readBytes() }
            respond(out, 200, "text/html; charset=utf-8", html); return
        }
        if (headers["x-code"] != st.code) {
            json(out, 403, JSONObject().put("error", "Code incorrect")); return
        }
        when {
            method == "GET" && path == "/api/info" -> {
                val vol = Store.settings.value.volume
                json(out, 200, JSONObject().put("app", "Phoenix Pro Music")
                    .put("storage", storages(app).firstOrNull { it.volume == vol }?.label ?: vol))
            }
            method == "GET" && path == "/api/list" -> json(out, 200, JSONArray(existing()))
            method == "POST" && path == "/api/upload" -> {
                val len = headers["content-length"]?.toLongOrNull()
                val p = query["p"]
                if (len == null || p == null) { json(out, 400, JSONObject().put("error", "Requête incomplète")); return }
                val r = receive(p, len, ins)
                json(out, if (r == null) 200 else 400, JSONObject().put("ok", r == null).put("error", r ?: ""))
            }
            method == "POST" && path == "/api/done" -> {
                state.value = state.value.copy(current = "", lastDone = System.currentTimeMillis())
                json(out, 200, JSONObject().put("ok", true))
            }
            else -> json(out, 404, JSONObject().put("error", "Introuvable"))
        }
    }

    /** Fichiers déjà présents dans Music/ (chemins relatifs), pour ne pas les renvoyer. */
    private fun existing(): List<String> {
        val vol = Store.settings.value.volume
        val out = ArrayList<String>()
        try {
            app.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(vol),
                arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("Music/%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += (c.getString(0) ?: "").removePrefix("Music/") + (c.getString(1) ?: "")
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun safeParts(p: String): List<String> =
        p.replace('\\', '/').split('/')
            .map { it.trim().replace(Regex("""[:*?"<>|\u0000-\u001f]"""), "_") }
            .filter { it.isNotEmpty() && it != "." && it != ".." }

    private fun drain(ins: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(65536)
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    /** Reçoit un fichier ; renvoie null si tout va bien, sinon le message d'erreur. */
    private fun receive(p: String, len: Long, ins: InputStream): String? {
        val parts = safeParts(p)
        if (parts.isEmpty()) { drain(ins, len); return "Nom de fichier vide" }
        val name = parts.last()
        val ext = name.substringAfterLast('.', "").lowercase()
        val dirs = parts.dropLast(1)
        val rel = "Music/" + dirs.joinToString("") { "$it/" }
        val vol = Store.settings.value.volume

        if (ext in imageExt) {
            val folder = "$vol/$rel"
            val f = Covers.folderFile(app, folder)
            val preferred = name.substringBeforeLast('.').lowercase() in setOf("cover", "folder", "front", "album")
            if (f.exists() && !preferred) { drain(ins, len); return null }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { copy(ins, it, len, name) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            val ok = bounds.outWidth > 0
            if (ok) tmp.renameTo(f) else tmp.delete()
            Covers.clearMemory()
            return null
        }
        if (ext !in audioExt) { drain(ins, len); return null }

        val mime = when (ext) {
            "flac" -> "audio/flac"; "mp3" -> "audio/mpeg"; "m4a", "alac" -> "audio/mp4"; "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"; "opus" -> "audio/opus"; "wav" -> "audio/wav"; "aiff", "aif" -> "audio/aiff"
            else -> "audio/*"
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val cr = app.contentResolver
        val uri = try {
            cr.insert(MediaStore.Audio.Media.getContentUri(vol), values)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            drain(ins, len)
            state.value = state.value.copy(error = "Impossible d'enregistrer $name")
            return "Impossible d'enregistrer le fichier sur le téléphone"
        }
        return try {
            val written = cr.openOutputStream(uri)!!.use { copy(ins, it, len, name) }
            if (written != len) throw IllegalStateException("Transfert interrompu")
            cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            state.value = state.value.copy(received = state.value.received + 1, error = null)
            null
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            state.value = state.value.copy(error = "Échec : $name")
            e.message ?: "Erreur"
        }
    }

    private fun copy(ins: InputStream, out: OutputStream, len: Long, name: String): Long {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastPct = -1
        while (done < len) {
            val r = ins.read(buf, 0, minOf(buf.size.toLong(), len - done).toInt())
            if (r < 0) break
            out.write(buf, 0, r)
            done += r
            val pct = if (len > 0) (done * 100 / len).toInt() else 100
            if (pct != lastPct) {
                lastPct = pct
                val s = state.value
                state.value = s.copy(current = name, currentPct = pct, bytes = s.bytes + 0)
            }
        }
        state.value = state.value.copy(bytes = state.value.bytes + done)
        return done
    }
}
