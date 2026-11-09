package fr.phoenixpro.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    /** Décodage en 32 bits flottant : garde la pleine résolution des fichiers 24 bits. */
    val hiRes: Boolean = true,
    /** Bit-perfect vers un DAC USB (Android 14 et plus). */
    val bitPerfect: Boolean = true,
    val onlineCovers: Boolean = true,
    val wifiOnly: Boolean = true,
    /** artist, title, recent, year */
    val albumSort: String = "artist",
    /** Volume où ranger la musique reçue du PC. */
    val volume: String = "external_primary",
)

data class Playlist(val id: Long, val name: String, val keys: List<String>)

data class EqState(
    val enabled: Boolean = false,
    val preamp: Float = 0f,
    val gains: List<Float> = List(10) { 0f },
    val preset: String = "Plat",
)

data class Play(val key: String, val time: Long)

object Store {
    val settings = MutableStateFlow(Settings())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val history = MutableStateFlow<List<Play>>(emptyList())
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val eq = MutableStateFlow(EqState())

    private lateinit var file: File

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "music.json")
        try {
            if (file.exists()) load(JSONObject(file.readText()))
        } catch (_: Exception) {
        }
    }

    private fun load(j: JSONObject) {
        j.optJSONObject("settings")?.let { s ->
            val d = Settings()
            settings.value = Settings(
                hiRes = s.optBoolean("hiRes", d.hiRes),
                bitPerfect = s.optBoolean("bitPerfect", d.bitPerfect),
                onlineCovers = s.optBoolean("onlineCovers", d.onlineCovers),
                wifiOnly = s.optBoolean("wifiOnly", d.wifiOnly),
                albumSort = s.optString("albumSort", d.albumSort),
                volume = s.optString("volume", d.volume),
            )
        }
        j.optJSONArray("playlists")?.let { a ->
            playlists.value = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                Playlist(p.getLong("id"), p.getString("name"), p.getJSONArray("keys").strings())
            }
        }
        j.optJSONArray("favorites")?.let { favorites.value = it.strings().toSet() }
        j.optJSONArray("history")?.let { a ->
            history.value = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i); Play(p.getString("k"), p.getLong("t"))
            }
        }
        j.optJSONObject("counts")?.let { o ->
            counts.value = o.keys().asSequence().associateWith { o.getInt(it) }
        }
        j.optJSONObject("eq")?.let { e ->
            val g = e.optJSONArray("gains")
            eq.value = EqState(
                enabled = e.optBoolean("enabled"),
                preamp = e.optDouble("preamp", 0.0).toFloat(),
                gains = if (g != null && g.length() == 10) (0 until 10).map { g.getDouble(it).toFloat() } else List(10) { 0f },
                preset = e.optString("preset", "Plat"),
            )
        }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    @Synchronized
    fun save() {
        if (!::file.isInitialized) return
        val s = settings.value
        val j = JSONObject()
            .put("settings", JSONObject()
                .put("hiRes", s.hiRes).put("bitPerfect", s.bitPerfect)
                .put("onlineCovers", s.onlineCovers).put("wifiOnly", s.wifiOnly)
                .put("albumSort", s.albumSort).put("volume", s.volume))
            .put("playlists", JSONArray().apply {
                playlists.value.forEach { p ->
                    put(JSONObject().put("id", p.id).put("name", p.name).put("keys", JSONArray(p.keys)))
                }
            })
            .put("favorites", JSONArray(favorites.value.toList()))
            .put("history", JSONArray().apply {
                history.value.forEach { put(JSONObject().put("k", it.key).put("t", it.time)) }
            })
            .put("counts", JSONObject().apply { counts.value.forEach { (k, v) -> put(k, v) } })
            .put("eq", eq.value.let { e ->
                JSONObject().put("enabled", e.enabled).put("preamp", e.preamp.toDouble())
                    .put("gains", JSONArray().apply { e.gains.forEach { put(it.toDouble()) } })
                    .put("preset", e.preset)
            })
        val tmp = File(file.parentFile, "music.json.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(file)
    }

    fun updateSettings(f: (Settings) -> Settings) { settings.value = f(settings.value); save() }

    fun setEq(e: EqState) { eq.value = e; save() }

    fun toggleFavorite(key: String) {
        val f = favorites.value
        favorites.value = if (key in f) f - key else f + key
        save()
    }

    fun recordPlay(key: String) {
        val h = history.value.filter { it.key != key }
        history.value = (listOf(Play(key, System.currentTimeMillis())) + h).take(300)
        counts.value = counts.value + (key to ((counts.value[key] ?: 0) + 1))
        save()
    }

    fun clearHistory() { history.value = emptyList(); counts.value = emptyMap(); save() }

    fun newPlaylist(name: String, keys: List<String> = emptyList()): Playlist {
        val p = Playlist(System.currentTimeMillis(), name.ifBlank { "Nouvelle playlist" }, keys.distinct())
        playlists.value = playlists.value + p
        save()
        return p
    }

    fun addToPlaylist(id: Long, keys: List<String>) {
        playlists.value = playlists.value.map { p ->
            if (p.id == id) p.copy(keys = (p.keys + keys.filter { it !in p.keys })) else p
        }
        save()
    }

    fun updatePlaylist(p: Playlist) {
        playlists.value = playlists.value.map { if (it.id == p.id) p else it }
        save()
    }

    fun deletePlaylist(id: Long) {
        playlists.value = playlists.value.filter { it.id != id }
        save()
    }
}
