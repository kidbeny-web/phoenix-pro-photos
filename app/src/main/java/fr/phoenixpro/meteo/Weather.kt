package fr.phoenixpro.meteo

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

data class Place(val name: String, val region: String, val lat: Double, val lon: Double) {
    fun sameAs(o: Place) = kotlin.math.abs(lat - o.lat) < 0.01 && kotlin.math.abs(lon - o.lon) < 0.01

    fun toJson(): JSONObject = JSONObject().put("name", name).put("region", region).put("lat", lat).put("lon", lon)

    companion object {
        val DEFAULT = Place("Foëcy", "Centre-Val de Loire, France", 47.1764, 2.1622)
        fun fromJson(o: JSONObject) = Place(o.optString("name"), o.optString("region"), o.optDouble("lat"), o.optDouble("lon"))
    }
}

data class Current(
    val time: String, val temp: Double, val feels: Double, val humidity: Int, val isDay: Boolean, val code: Int,
    val wind: Double, val windDir: Double, val gusts: Double, val pressure: Double,
)

data class Hour(
    val time: String, val temp: Double, val pop: Int, val precip: Double, val code: Int,
    val wind: Double, val gusts: Double, val isDay: Boolean,
)

data class Day(
    val date: String, val code: Int, val tMax: Double, val tMin: Double, val precip: Double, val pop: Int,
    val windMax: Double, val gustMax: Double, val windDir: Double, val uv: Double,
    val sunrise: String, val sunset: String, val daylight: Double,
)

data class Forecast(val current: Current, val hours: List<Hour>, val days: List<Day>)

object Api {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "PhoenixProMeteo/1.0 (Android)")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Télécharge les prévisions brutes (JSON) pour 10 jours. */
    fun fetchJson(p: Place): String = get(
        String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,uv_index_max,sunrise,sunset,daylight_duration" +
                "&timezone=auto&forecast_days=10&wind_speed_unit=kmh",
            p.lat, p.lon,
        ),
    )

    fun parse(json: String): Forecast {
        val r = JSONObject(json)
        val c = r.getJSONObject("current")
        val cur = Current(
            time = c.optString("time"),
            temp = c.optDouble("temperature_2m", 0.0),
            feels = c.optDouble("apparent_temperature", 0.0),
            humidity = c.optDouble("relative_humidity_2m", 0.0).roundToInt(),
            isDay = c.optInt("is_day", 1) == 1,
            code = c.optInt("weather_code", 0),
            wind = c.optDouble("wind_speed_10m", 0.0),
            windDir = c.optDouble("wind_direction_10m", 0.0),
            gusts = c.optDouble("wind_gusts_10m", 0.0),
            pressure = c.optDouble("pressure_msl", 0.0),
        )
        val h = r.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = (0 until ht.length()).map { i ->
            Hour(
                time = ht.getString(i),
                temp = h.d("temperature_2m", i),
                pop = h.d("precipitation_probability", i).roundToInt(),
                precip = h.d("precipitation", i),
                code = h.d("weather_code", i).toInt(),
                wind = h.d("wind_speed_10m", i),
                gusts = h.d("wind_gusts_10m", i),
                isDay = h.d("is_day", i) == 1.0,
            )
        }
        val d = r.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = (0 until dt.length()).map { i ->
            Day(
                date = dt.getString(i),
                code = d.d("weather_code", i).toInt(),
                tMax = d.d("temperature_2m_max", i),
                tMin = d.d("temperature_2m_min", i),
                precip = d.d("precipitation_sum", i),
                pop = d.d("precipitation_probability_max", i).roundToInt(),
                windMax = d.d("wind_speed_10m_max", i),
                gustMax = d.d("wind_gusts_10m_max", i),
                windDir = d.d("wind_direction_10m_dominant", i),
                uv = d.d("uv_index_max", i),
                sunrise = d.s("sunrise", i),
                sunset = d.s("sunset", i),
                daylight = d.d("daylight_duration", i),
            )
        }
        return Forecast(cur, hours, days)
    }

    fun search(q: String): List<Place> {
        val json = get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=fr&format=json&name=" + URLEncoder.encode(q, "UTF-8"))
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val parts = listOf(o.optString("admin2"), o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.distinct()
            Place(o.optString("name"), parts.joinToString(", "), o.optDouble("latitude"), o.optDouble("longitude"))
        }
    }

    /** Nom de la commune à partir des coordonnées GPS. */
    fun reverse(lat: Double, lon: Double): Place {
        return try {
            val o = JSONObject(
                get(String.format(Locale.US, "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.5f&longitude=%.5f&localityLanguage=fr", lat, lon)),
            )
            val name = o.optString("city").ifBlank { o.optString("locality") }.ifBlank { "Ma position" }
            val region = listOf(o.optString("principalSubdivision"), o.optString("countryName")).filter { it.isNotBlank() }.joinToString(", ")
            Place(name, region, lat, lon)
        } catch (e: Exception) {
            Place("Ma position", "", lat, lon)
        }
    }

    private fun JSONObject.d(k: String, i: Int): Double {
        val a: JSONArray = optJSONArray(k) ?: return 0.0
        if (i >= a.length() || a.isNull(i)) return 0.0
        return a.optDouble(i, 0.0)
    }

    private fun JSONObject.s(k: String, i: Int): String {
        val a: JSONArray = optJSONArray(k) ?: return ""
        return if (i < a.length()) a.optString(i, "") else ""
    }
}

/** Codes météo WMO → texte en français et icône. */
object Wmo {
    fun text(code: Int): String = when (code) {
        0 -> "Ciel dégagé"
        1 -> "Plutôt dégagé"
        2 -> "Partiellement nuageux"
        3 -> "Couvert"
        45 -> "Brouillard"
        48 -> "Brouillard givrant"
        51 -> "Bruine légère"
        53 -> "Bruine"
        55 -> "Bruine forte"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        66, 67 -> "Pluie verglaçante"
        71 -> "Neige faible"
        73 -> "Neige"
        75 -> "Forte neige"
        77 -> "Grains de neige"
        80 -> "Averses faibles"
        81 -> "Averses"
        82 -> "Violentes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage avec grêle"
        else -> "—"
    }

    fun icon(code: Int, day: Boolean = true): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1 -> if (day) "🌤️" else "🌙"
        2 -> if (day) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 61, 80 -> if (day) "🌦️" else "🌧️"
        55, 56, 57, 63, 65, 66, 67, 81 -> "🌧️"
        71, 73, 77, 85, 86 -> "🌨️"
        75 -> "❄️"
        82, 95, 96, 99 -> "⛈️"
        else -> "☁️"
    }

    fun isRain(code: Int) = code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
    fun isSnow(code: Int) = code in listOf(71, 73, 75, 77, 85, 86)
}

fun Double.r(): Int = roundToInt().let { if (it == 0) 0 else it }
fun Double.deg(): String = "${r()}°"
fun windDir(deg: Double): String = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[((deg / 45.0).roundToInt()) % 8]
fun uvLabel(u: Double) = when {
    u < 3 -> "faible"
    u < 6 -> "modéré"
    u < 8 -> "élevé"
    u < 11 -> "très élevé"
    else -> "extrême"
}
fun hm(iso: String): String = if (iso.length >= 16) iso.substring(11, 16).replace(":", "h") else "--"
