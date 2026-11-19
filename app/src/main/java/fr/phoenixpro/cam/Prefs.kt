package fr.phoenixpro.cam

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class CamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

const val PORT = 8765
const val NSD_TYPE = "_phoenixcam._tcp."

/** Caméra enregistrée côté visionneuse. */
data class Cam(val name: String, val host: String, val port: Int, val code: String) {
    fun url(path: String) = "http://$host:$port$path${if ('?' in path) "&" else "?"}k=$code"
    fun json() = JSONObject().put("name", name).put("host", host).put("port", port).put("code", code)
}

object Prefs {
    private lateinit var p: SharedPreferences
    lateinit var events: File
        private set

    fun init(ctx: Context) {
        if (::p.isInitialized) return
        p = ctx.getSharedPreferences("cam", Context.MODE_PRIVATE)
        events = File(ctx.filesDir, "evenements").apply { mkdirs() }
        if (!p.contains("code")) newCode()
    }

    /** "camera", "viewer" ou "" (pas encore choisi). */
    var role: String
        get() = p.getString("role", "") ?: ""
        set(v) = p.edit().putString("role", v).apply()

    // ---- côté caméra
    var camName: String
        get() = p.getString("camName", "Salon") ?: "Salon"
        set(v) = p.edit().putString("camName", v).apply()
    val code: String get() = p.getString("code", "000000") ?: "000000"
    fun newCode() { p.edit().putString("code", (100000 + SecureRandom().nextInt(900000)).toString()).apply() }
    var front: Boolean
        get() = p.getBoolean("front", false)
        set(v) = p.edit().putBoolean("front", v).apply()
    /** Part de l'image qui doit bouger pour déclencher (en %). */
    var sensitivity: Int
        get() = p.getInt("sens", 3)
        set(v) = p.edit().putInt("sens", v).apply()
    var armed: Boolean
        get() = p.getBoolean("armed", true)
        set(v) = p.edit().putBoolean("armed", v).apply()

    // ---- côté visionneuse
    var cams: List<Cam>
        get() = try {
            val a = JSONArray(p.getString("cams", "[]"))
            (0 until a.length()).map { val o = a.getJSONObject(it); Cam(o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("code")) }
        } catch (e: Exception) { emptyList() }
        set(v) = p.edit().putString("cams", JSONArray().apply { v.forEach { put(it.json()) } }.toString()).apply()

    fun saveCam(c: Cam) { cams = cams.filter { it.name != c.name && it.host != c.host } + c }
    fun removeCam(c: Cam) { cams = cams.filter { it != c } }

    var alerts: Boolean
        get() = p.getBoolean("alerts", false)
        set(v) = p.edit().putBoolean("alerts", v).apply()
    fun lastSeen(c: Cam): Long = p.getLong("seen_${c.host}", 0)
    fun setLastSeen(c: Cam, v: Long) { p.edit().putLong("seen_${c.host}", v).apply() }
}
