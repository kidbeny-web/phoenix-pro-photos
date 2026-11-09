package fr.phoenixpro.photos

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Envoi des photos du téléphone vers Phoenix Pro Photos sur le PC (même Wi-Fi). */
object Sync {

    sealed class PingResult {
        data class Ok(val version: String) : PingResult()
        data class Error(val message: String) : PingResult()
    }

    private sealed class Probe {
        data class Ok(val version: String) : Probe()
        object Forbidden : Probe()
        data class Http(val code: Int) : Probe()
        object Unreachable : Probe()
    }

    private fun probe(base: String, key: String, timeout: Int = 2500): Probe = try {
        val c = (URL("$base/api/ping").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout + 2000
            setRequestProperty("X-Cle", key)
        }
        when (c.responseCode) {
            200 -> {
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                if (j.optString("app").contains("Phoenix")) Probe.Ok(j.optString("version")) else Probe.Http(200)
            }
            403 -> Probe.Forbidden
            else -> Probe.Http(c.responseCode)
        }
    } catch (e: Exception) {
        Probe.Unreachable
    }

    /** Adresse IPv4 du téléphone sur le Wi-Fi, ex. 192.168.1.37. */
    private fun phoneWifiIp(ctx: Context): String? {
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

    /** Cherche le PC sur tout le réseau Wi-Fi (ex. 192.168.1.1 → 254) quand son adresse a changé. */
    private fun scanSubnet(ctx: Context, port: Int, key: String): String? {
        val ip = phoneWifiIp(ctx) ?: return null
        val prefix = ip.substringBeforeLast(".")
        val pool = Executors.newFixedThreadPool(48)
        try {
            val open = pool.invokeAll((1..254).map { i ->
                Callable {
                    val host = "$prefix.$i"
                    if (host == ip) return@Callable null
                    try {
                        Socket().use { sock -> sock.connect(InetSocketAddress(host, port), 350) }
                        host
                    } catch (e: Exception) {
                        null
                    }
                }
            }).mapNotNull { it.get() }
            for (host in open) {
                val base = "http://$host:$port"
                if (probe(base, key) is Probe.Ok) return base
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    /** Vérifie la connexion ; essaie les autres adresses du PC puis tout le Wi-Fi si besoin. */
    fun ping(ctx: Context): PingResult {
        val p = Prefs(ctx)
        if (!p.paired) return PingResult.Error("Pas encore relié au PC")
        val wifiIp = phoneWifiIp(ctx)
            ?: return PingResult.Error("Le téléphone n'est pas connecté en Wi-Fi. Activez le Wi-Fi de la maison (le même que celui du PC).")
        var forbidden = false
        val candidates = listOf(p.pcUrl) + p.pcAltHosts.map { "http://$it:${p.pcPort}" }
        for (base in candidates.distinct()) {
            when (val r = probe(base, p.pcKey)) {
                is Probe.Ok -> { if (base != p.pcUrl) p.pcUrl = base; return PingResult.Ok(r.version) }
                Probe.Forbidden -> forbidden = true
                else -> {}
            }
        }
        if (forbidden) return PingResult.Error(
            "Le PC refuse l'accès. Sur le PC : Réglages → Téléphone Android → activez « Autoriser l'accès », puis rescannez le QR code (la clé a peut-être changé)."
        )
        scanSubnet(ctx, p.pcPort, p.pcKey)?.let { found ->
            p.pcUrl = found
            return PingResult.Ok("")
        }
        val tried = p.pcUrl.removePrefix("http://")
        return PingResult.Error(
            "Impossible de joindre le PC ($tried) depuis ce téléphone ($wifiIp).\n\n" +
                "Vérifiez que :\n• le PC est allumé et Phoenix Pro Photos est ouvert ;\n" +
                "• les deux sont sur le même Wi-Fi ;\n" +
                "• sur le PC, Réglages → Téléphone Android → « Autoriser Phoenix Pro Photos dans le pare-feu » a été fait."
        )
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Notes et favoris : envoie ceux du téléphone, reçoit ceux du PC (le plus récent gagne). */
    fun syncNotes(ctx: Context): Int {
        val p = Prefs(ctx)
        if (!p.paired) return 0
        val rows = mutableListOf<JSONObject>()
        Db.db.rawQuery("SELECT name, size, rating, fav, noted_at FROM photos WHERE synced=1", null).use { c ->
            while (c.moveToNext()) rows += JSONObject().apply {
                put("name", c.getString(0)); put("size", c.getLong(1)); put("rating", c.getInt(2))
                put("fav", c.getInt(3) == 1); put("t", c.getLong(4))
            }
        }
        var changed = 0
        for (chunk in rows.chunked(400)) {
            try {
                val c = (URL("${p.pcUrl}/api/sync/notes").openConnection() as HttpURLConnection).apply {
                    doOutput = true; requestMethod = "POST"; connectTimeout = 4000; readTimeout = 20000
                    setRequestProperty("X-Cle", p.pcKey)
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(JSONObject().put("items", JSONArray(chunk)).toString().toByteArray()) }
                if (c.responseCode != 200) return changed
                val back = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("items") ?: continue
                for (i in 0 until back.length()) {
                    val o = back.getJSONObject(i)
                    Db.db.execSQL(
                        "UPDATE photos SET rating=?, fav=?, noted_at=? WHERE name=? AND size=? AND synced=1",
                        arrayOf<Any>(o.optInt("rating"), if (o.optBoolean("fav")) 1 else 0, o.optLong("t"), o.optString("name"), o.optLong("size"))
                    )
                    changed++
                }
            } catch (e: Exception) {
                Log.w("Phoenix", "Synchro des notes : ${e.message}")
                return changed
            }
        }
        if (changed > 0) PhoenixApp.changed()
        return changed
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
        syncNotes(ctx)
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
