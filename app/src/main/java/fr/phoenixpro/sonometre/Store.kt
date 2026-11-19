package fr.phoenixpro.sonometre

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class SonoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/** Un dépassement du seuil, horodaté, avec son extrait sonore. */
class EventRec(
    val start: Long,
    var end: Long,
    var leq: Double,
    var lmax: Double,
    var clip: String? = null,
    var sha: String? = null,
) {
    val durationMs get() = end - start

    fun toJson() = JSONObject().apply {
        put("start", start); put("end", end); put("leq", leq); put("lmax", lmax)
        clip?.let { put("clip", it) }
        sha?.let { put("sha", it) }
    }

    companion object {
        fun from(o: JSONObject) = EventRec(
            o.getLong("start"), o.getLong("end"), o.getDouble("leq"), o.getDouble("lmax"),
            o.optString("clip").ifBlank { null }, o.optString("sha").ifBlank { null },
        )
    }
}

/** Une séance de mesure. levels = un niveau LAeq par seconde, en dB(A). */
class Session(
    val id: Long,
    val start: Long,
    var end: Long,
    val threshold: Int,
    val calibration: Double,
    val minDuration: Int,
    val device: String,
    val source: String,
    var place: String = "",
    var nuisance: String = "",
    var lmax: Double = 0.0,
    val levels: MutableList<Float> = mutableListOf(),
    val events: MutableList<EventRec> = mutableListOf(),
) {
    val durationMs get() = max(0L, end - start)

    fun leq(): Double {
        if (levels.isEmpty()) return 0.0
        var s = 0.0
        for (l in levels) s += 10.0.pow(l / 10.0)
        return 10 * log10(s / levels.size)
    }

    /** Niveau dépassé pendant p % du temps (L90 = bruit de fond, L10 = bruit fort). */
    fun lp(p: Int): Double {
        if (levels.isEmpty()) return 0.0
        val sorted = levels.sortedDescending()
        val i = ((p / 100.0) * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[i].toDouble()
    }

    fun lmin(): Double = levels.minOrNull()?.toDouble() ?: 0.0

    fun secondsAbove(): Int = levels.count { it >= threshold }

    fun toJson() = JSONObject().apply {
        put("id", id); put("start", start); put("end", end)
        put("threshold", threshold); put("calibration", calibration); put("minDuration", minDuration)
        put("device", device); put("source", source)
        put("place", place); put("nuisance", nuisance); put("lmax", lmax)
        put("levels", JSONArray().apply { levels.forEach { put(((it * 10).roundToInt()) / 10.0) } })
        put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
    }

    companion object {
        fun from(o: JSONObject): Session {
            val s = Session(
                o.getLong("id"), o.getLong("start"), o.getLong("end"),
                o.getInt("threshold"), o.getDouble("calibration"), o.getInt("minDuration"),
                o.optString("device"), o.optString("source"),
                o.optString("place"), o.optString("nuisance"), o.optDouble("lmax", 0.0),
            )
            val lv = o.optJSONArray("levels") ?: JSONArray()
            for (i in 0 until lv.length()) s.levels.add(lv.getDouble(i).toFloat())
            val ev = o.optJSONArray("events") ?: JSONArray()
            for (i in 0 until ev.length()) s.events.add(EventRec.from(ev.getJSONObject(i)))
            return s
        }
    }
}

object Store {
    private lateinit var prefs: SharedPreferences
    lateinit var root: File
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("sonometre", Context.MODE_PRIVATE)
        root = File(ctx.filesDir, "sessions").apply { mkdirs() }
    }

    /** Seuil de nuisance en dB(A). */
    var threshold: Int
        get() = prefs.getInt("threshold", 55)
        set(v) = prefs.edit().putInt("threshold", v).apply()

    /** Durée minimale au-dessus du seuil pour créer un événement (secondes). */
    var minDuration: Int
        get() = prefs.getInt("minDuration", 2)
        set(v) = prefs.edit().putInt("minDuration", v).apply()

    /** Secondes d'audio gardées avant le début d'un événement. */
    var preSeconds: Int
        get() = prefs.getInt("preSeconds", 5)
        set(v) = prefs.edit().putInt("preSeconds", v).apply()

    /** Correction de calibrage en dB, ajoutée à toutes les mesures. */
    var calibration: Float
        get() = prefs.getFloat("calibration", 0f)
        set(v) = prefs.edit().putFloat("calibration", v).apply()

    var lastPlace: String
        get() = prefs.getString("lastPlace", "") ?: ""
        set(v) = prefs.edit().putString("lastPlace", v).apply()

    fun dir(id: Long) = File(root, id.toString()).apply { mkdirs() }

    @Synchronized
    fun save(s: Session) {
        val d = dir(s.id)
        val tmp = File(d, "session.json.tmp")
        tmp.writeText(s.toJson().toString())
        tmp.renameTo(File(d, "session.json"))
    }

    fun load(id: Long): Session? = load(File(root, id.toString()))

    private fun load(d: File): Session? = try {
        Session.from(JSONObject(File(d, "session.json").readText()))
    } catch (e: Exception) {
        null
    }

    fun list(): List<Session> =
        (root.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it) } ?: emptyList())
            .sortedByDescending { it.start }

    fun delete(id: Long) {
        File(root, id.toString()).deleteRecursively()
    }
}
