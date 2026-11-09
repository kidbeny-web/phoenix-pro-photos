package fr.phoenixpro.agenda

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Une période de vacances : [from] = premier jour sans école, [to] = dernier jour (veille de la reprise). */
data class SchoolBreak(val name: String, val from: LocalDate, val to: LocalDate) {
    fun contains(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
}

/**
 * Vacances scolaires. Dates intégrées pour la zone B (académie d'Orléans-Tours),
 * puis mises à jour depuis l'open data de l'Éducation nationale (toutes zones).
 */
object School {
    val version = MutableStateFlow(0)

    private fun b(name: String, from: String, to: String) = SchoolBreak(name, LocalDate.parse(from), LocalDate.parse(to))

    private val builtInB = listOf(
        b("Vacances de la Toussaint", "2025-10-18", "2025-11-02"),
        b("Vacances de Noël", "2025-12-20", "2026-01-04"),
        b("Vacances d'hiver", "2026-02-14", "2026-03-01"),
        b("Vacances de printemps", "2026-04-11", "2026-04-26"),
        b("Vacances d'été", "2026-07-04", "2026-08-31"),
        b("Vacances de la Toussaint", "2026-10-17", "2026-11-01"),
        b("Vacances de Noël", "2026-12-19", "2027-01-03"),
        b("Vacances d'hiver", "2027-02-20", "2027-03-07"),
        b("Vacances de printemps", "2027-04-17", "2027-05-02"),
        b("Pont de l'Ascension", "2027-05-06", "2027-05-09"),
        b("Vacances d'été", "2027-07-03", "2027-09-01"),
        b("Vacances de la Toussaint", "2027-10-23", "2027-11-07"),
    )

    /** Académie de référence pour chaque zone (les dates sont identiques dans toute la zone). */
    private val academy = mapOf("A" to "Lyon", "B" to "Orléans-Tours", "C" to "Paris")

    @Volatile private var loaded: Map<String, List<SchoolBreak>> = emptyMap()

    fun breaks(zone: String): List<SchoolBreak> {
        val online = loaded[zone]
        if (!online.isNullOrEmpty()) {
            // on garde les dates intégrées pour les périodes que le site ne couvre pas
            if (zone != "B") return online
            val extra = builtInB.filter { bi -> online.none { it.contains(bi.from) || bi.contains(it.from) } }
            return (online + extra).sortedBy { it.from }
        }
        return if (zone == "B") builtInB else emptyList()
    }

    fun on(d: LocalDate, zone: String): SchoolBreak? = breaks(zone).firstOrNull { it.contains(d) }

    private fun cacheFile(ctx: Context, zone: String) = File(ctx.filesDir, "vacances_$zone.json")

    fun loadCache(ctx: Context) {
        val m = HashMap<String, List<SchoolBreak>>()
        for (z in academy.keys) {
            val f = cacheFile(ctx, z)
            if (f.exists()) runCatching { m[z] = parse(JSONObject(f.readText()).getJSONArray("list")) }
        }
        loaded = m
        version.value++
    }

    private fun parse(a: JSONArray): List<SchoolBreak> = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        SchoolBreak(o.getString("n"), LocalDate.parse(o.getString("f")), LocalDate.parse(o.getString("t")))
    }

    /** Télécharge les dates (au plus une fois par semaine, sauf [force]). À appeler hors du fil principal. */
    fun refresh(ctx: Context, zone: String, force: Boolean = false): Boolean {
        val f = cacheFile(ctx, zone)
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 24 * 3600 * 1000) return true
        val acad = academy[zone] ?: return false
        return try {
            val since = LocalDate.now().minusYears(1).withDayOfMonth(1)
            val where = "location=\"$acad\" and end_date>=date'$since'"
            val url = "https://data.education.gouv.fr/api/explore/v2.1/catalog/datasets/fr-en-calendrier-scolaire/records" +
                "?select=description,start_date,end_date,population" +
                "&where=" + URLEncoder.encode(where, "UTF-8") +
                "&order_by=start_date&limit=100"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val results = JSONObject(body).getJSONArray("results")
            val paris = ZoneId.of("Europe/Paris")
            val seen = HashSet<String>()
            val list = ArrayList<SchoolBreak>()
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                val pop = o.optString("population", "-")
                if (pop.contains("Enseignant", ignoreCase = true)) continue
                val startS = o.optString("start_date", "")
                val endS = o.optString("end_date", "")
                if (startS.isBlank() || endS.isBlank()) continue
                val from = OffsetDateTime.parse(startS).atZoneSameInstant(paris).toLocalDate()
                val end = OffsetDateTime.parse(endS).atZoneSameInstant(paris).toLocalDate()
                val to = if (end.isAfter(from)) end.minusDays(1) else from
                val name = o.optString("description", "Vacances").trim()
                if (seen.add("$name$from")) list.add(SchoolBreak(name, from, to))
            }
            if (list.isEmpty()) return false
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("n", it.name).put("f", it.from.toString()).put("t", it.to.toString())) }
            f.writeText(JSONObject().put("list", arr).toString())
            loaded = loaded + (zone to list.sortedBy { it.from })
            version.value++
            true
        } catch (e: Exception) {
            false
        }
    }
}
