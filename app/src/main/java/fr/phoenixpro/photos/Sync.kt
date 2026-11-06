package fr.phoenixpro.photos

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Envoi des photos du téléphone vers Phoenix Pro Photos sur le PC (même Wi-Fi). */
object Sync {

    sealed class PingResult {
        data class Ok(val version: String) : PingResult()
        data class Error(val message: String) : PingResult()
    }

    fun ping(ctx: Context): PingResult {
        val p = Prefs(ctx)
        if (!p.paired) return PingResult.Error("Pas encore relié au PC")
        return try {
            val c = (URL("${p.pcUrl}/api/ping").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 6000
                setRequestProperty("X-Cle", p.pcKey)
            }
            when (c.responseCode) {
                200 -> PingResult.Ok(JSONObject(c.inputStream.bufferedReader().readText()).optString("version"))
                403 -> PingResult.Error("Accès refusé : activez l'accès téléphone sur le PC, ou rescannez le QR code")
                else -> PingResult.Error("Le PC répond avec l'erreur ${c.responseCode}")
            }
        } catch (e: Exception) {
            PingResult.Error("PC injoignable : vérifiez qu'il est allumé, que Phoenix Pro Photos est ouvert et que vous êtes sur le même Wi-Fi")
        }
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Envoie les photos pas encore transmises. Renvoie le nombre envoyé, ou -1 si le PC est injoignable. */
    fun uploadPending(ctx: Context, progress: (Int, Int) -> Unit): Int {
        val p = Prefs(ctx)
        if (!p.paired) return -1
        if (p.wifiOnly && !onWifi(ctx)) return -1
        if (ping(ctx) !is PingResult.Ok) return -1
        val rows = mutableListOf<Pair<Long, String>>()
        Db.db.rawQuery("SELECT id, name FROM photos WHERE synced=0 ORDER BY taken", null).use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to (c.getString(1) ?: "photo.jpg")
        }
        var sent = 0
        rows.forEachIndexed { i, (id, name) ->
            progress(i, rows.size)
            if (upload(ctx, p, id, name)) {
                Db.db.update("photos", Db.values("synced" to 1), "id=?", arrayOf(id.toString()))
                sent++
            } else return sent // le PC ne répond plus : on reprendra plus tard
        }
        p.lastSync = System.currentTimeMillis()
        return sent
    }

    private fun upload(ctx: Context, p: Prefs, id: Long, name: String): Boolean {
        val boundary = "----Phoenix${System.nanoTime()}"
        return try {
            var uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            if (Scanner.hasLocationPermission(ctx)) uri = MediaStore.setRequireOriginal(uri) // garde la position GPS
            val c = (URL("${p.pcUrl}/api/upload").openConnection() as HttpURLConnection).apply {
                doOutput = true
                requestMethod = "POST"
                connectTimeout = 5000; readTimeout = 60000
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("X-Cle", p.pcKey)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(c.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.write("Content-Disposition: form-data; name=\"files\"; filename=\"${name.replace("\"", "")}\"\r\n".toByteArray(Charsets.UTF_8))
                out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
                ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(out, 64 * 1024) } ?: return false
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            c.responseCode == 200
        } catch (e: Exception) {
            Log.w("Phoenix", "Envoi $id : ${e.message}")
            false
        }
    }

    /** Marque toutes les photos du téléphone comme « à envoyer ». */
    fun resendAll() {
        Db.db.execSQL("UPDATE photos SET synced=0")
        PhoenixApp.changed()
    }
}
