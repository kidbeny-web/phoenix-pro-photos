package fr.phoenixpro.sommeil

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SommeilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Store.cleanup()
    }
}

enum class Kind(val label: String) { SNORE("Ronflement"), TALK("Parole ou son aigu"), NOISE("Bruit") }

class Ev(
    val start: Long,
    var end: Long,
    var kind: Kind,
    var peak: Float,      // dB relatif
    var clip: String?,
    var keep: Boolean = false,
) {
    fun json() = JSONObject().put("start", start).put("end", end).put("kind", kind.name).put("peak", peak.toDouble())
        .put("keep", keep).apply { clip?.let { put("clip", it) } }

    companion object {
        fun from(o: JSONObject) = Ev(
            o.getLong("start"), o.getLong("end"), Kind.valueOf(o.getString("kind")), o.getDouble("peak").toFloat(),
            o.optString("clip").ifBlank { null }, o.optBoolean("keep"),
        )
    }
}

/** Une nuit. minutes = niveau moyen par minute (dB relatif), à partir de l'heure de début d'écoute. */
class Night(
    val id: Long,
    val start: Long,          // appui sur « Bonne nuit »
    var listenFrom: Long,     // fin du délai d'endormissement
    var end: Long,
    val minutes: MutableList<Float> = mutableListOf(),
    val events: MutableList<Ev> = mutableListOf(),
    val factors: MutableSet<String> = mutableSetOf(),
    var note: String = "",
) {
    val listenMs get() = (end - listenFrom).coerceAtLeast(0)

    /** Minutes contenant au moins un ronflement. */
    fun snoreMinutes(): Int = events.filter { it.kind == Kind.SNORE }.map { (it.start - listenFrom) / 60_000 }.toSet().size

    /** Part de la nuit avec ronflements, en %. */
    fun snorePct(): Int {
        val total = (listenMs / 60_000).toInt()
        return if (total <= 0) 0 else (snoreMinutes() * 100 / total).coerceIn(0, 100)
    }

    fun json() = JSONObject().put("id", id).put("start", start).put("listenFrom", listenFrom).put("end", end)
        .put("minutes", JSONArray().apply { minutes.forEach { put(Math.round(it * 10) / 10.0) } })
        .put("events", JSONArray().apply { events.forEach { put(it.json()) } })
        .put("factors", JSONArray(factors.toList()))
        .put("note", note)

    companion object {
        fun from(o: JSONObject): Night {
            val n = Night(o.getLong("id"), o.getLong("start"), o.getLong("listenFrom"), o.getLong("end"), note = o.optString("note"))
            o.optJSONArray("minutes")?.let { a -> for (i in 0 until a.length()) n.minutes.add(a.getDouble(i).toFloat()) }
            o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) n.events.add(Ev.from(a.getJSONObject(i))) }
            o.optJSONArray("factors")?.let { a -> for (i in 0 until a.length()) n.factors.add(a.getString(i)) }
            return n
        }
    }
}

val FACTORS = listOf("Alcool", "Repas tardif", "Rhume / nez bouché", "Grosse fatigue", "Sport le soir", "Café ou thé tard", "Stress", "Somnifère", "Dos (sur le dos)", "Allergies")

object Store {
    private lateinit var prefs: SharedPreferences
    lateinit var root: File
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("sommeil", Context.MODE_PRIVATE)
        root = File(ctx.filesDir, "nuits").apply { mkdirs() }
    }

    /** Délai avant de commencer l'écoute (minutes). */
    var delayMin: Int
        get() = prefs.getInt("delay", 15)
        set(v) = prefs.edit().putInt("delay", v).apply()

    /** Marge au-dessus du bruit de fond pour déclencher un enregistrement (dB). Plus petit = plus sensible. */
    var margin: Int
        get() = prefs.getInt("margin", 9)
        set(v) = prefs.edit().putInt("margin", v).apply()

    /** Durée de conservation des extraits (jours). Les extraits marqués « à garder » ne sont jamais effacés. */
    var keepDays: Int
        get() = prefs.getInt("keepDays", 14)
        set(v) = prefs.edit().putInt("keepDays", v).apply()

    var lastFactors: Set<String>
        get() = prefs.getStringSet("lastFactors", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("lastFactors", v).apply()

    fun dir(id: Long) = File(root, id.toString()).apply { mkdirs() }

    @Synchronized
    fun save(n: Night) {
        val d = dir(n.id)
        val tmp = File(d, "nuit.json.tmp")
        tmp.writeText(n.json().toString())
        tmp.renameTo(File(d, "nuit.json"))
    }

    fun load(id: Long): Night? = try { Night.from(JSONObject(File(File(root, id.toString()), "nuit.json").readText())) } catch (e: Exception) { null }

    fun list(): List<Night> = (root.listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
        try { Night.from(JSONObject(File(d, "nuit.json").readText())) } catch (e: Exception) { null }
    } ?: emptyList()).sortedByDescending { it.start }

    fun delete(id: Long) { File(root, id.toString()).deleteRecursively() }

    /** Efface les extraits trop anciens (sauf ceux à garder) ; les résumés restent. */
    fun cleanup() {
        val limit = System.currentTimeMillis() - keepDays * 86_400_000L
        list().filter { it.end < limit }.forEach { n ->
            var changed = false
            n.events.forEach { e ->
                val c = e.clip
                if (c != null && !e.keep) { File(dir(n.id), c).delete(); e.clip = null; changed = true }
            }
            if (changed) save(n)
        }
    }
}
