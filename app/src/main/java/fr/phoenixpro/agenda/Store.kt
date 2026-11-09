package fr.phoenixpro.agenda

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Toutes les données de l'agenda, enregistrées dans un fichier sur le téléphone. */
object Store {
    private lateinit var app: Context
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _settings = MutableStateFlow(Settings())
    val events: StateFlow<List<Event>> = _events
    val notes: StateFlow<List<Note>> = _notes
    val settings: StateFlow<Settings> = _settings

    fun init(ctx: Context) {
        synchronized(lock) {
            if (::file.isInitialized) return
            app = ctx.applicationContext
            file = File(app.filesDir, "agenda.json")
            if (file.exists()) runCatching { load(JSONObject(file.readText())) }
        }
        School.loadCache(app)
    }

    fun newId(): Long = System.currentTimeMillis() * 10 + (0..9).random()

    // ---------- modifications ----------

    fun saveEvent(e: Event) {
        _events.value = _events.value.filter { it.id != e.id } + e
        persist()
    }

    fun deleteEvent(id: Long) {
        _events.value = _events.value.filter { it.id != id }
        persist()
    }

    /** Supprime un seul jour d'une série répétée. */
    fun skipDay(id: Long, day: Long) {
        _events.value = _events.value.map { if (it.id == id) it.copy(skips = it.skips + day) else it }
        persist()
    }

    fun saveNote(n: Note) {
        _notes.value = _notes.value.filter { it.id != n.id } + n
        persist()
    }

    fun deleteNote(id: Long) {
        _notes.value = _notes.value.filter { it.id != id }
        persist()
    }

    fun updateSettings(s: Settings) {
        _settings.value = s
        persist()
    }

    // ---------- sauvegarde ----------

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun importJson(text: String): Boolean = try {
        load(JSONObject(text))
        persist()
        true
    } catch (e: Exception) {
        false
    }

    private fun persist() {
        val json = synchronized(lock) { toJson().toString() }
        io.execute {
            synchronized(lock) {
                val tmp = File(file.parentFile, "agenda.json.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) { file.writeText(json); tmp.delete() }
            }
            Reminders.rescheduleAll(app)
            AgendaWidget.updateAll(app)
        }
    }

    // ---------- JSON ----------

    private fun toJson(): JSONObject {
        val ev = JSONArray()
        _events.value.forEach { e ->
            ev.put(JSONObject().apply {
                put("id", e.id); put("title", e.title); put("day", e.day)
                put("start", e.start); put("end", e.end)
                put("place", e.place); put("note", e.note); put("cat", e.category)
                put("repeat", e.repeat.name); put("until", e.until); put("reminder", e.reminder)
                put("skips", JSONArray(e.skips.toList()))
            })
        }
        val no = JSONArray()
        _notes.value.forEach { n ->
            no.put(JSONObject().apply {
                put("id", n.id); put("title", n.title); put("text", n.text)
                put("day", n.day); put("updated", n.updated)
            })
        }
        val s = _settings.value
        val st = JSONObject().apply {
            put("zone", s.zone); put("showSchool", s.showSchool)
            put("defaultReminder", s.defaultReminder)
            put("categories", JSONArray(s.categories))
        }
        return JSONObject().put("version", 1).put("events", ev).put("notes", no).put("settings", st)
    }

    private fun load(o: JSONObject) {
        val ev = o.optJSONArray("events") ?: JSONArray()
        _events.value = (0 until ev.length()).map { i ->
            val e = ev.getJSONObject(i)
            val sk = e.optJSONArray("skips") ?: JSONArray()
            Event(
                id = e.getLong("id"),
                title = e.optString("title"),
                day = e.getLong("day"),
                start = e.optInt("start", -1),
                end = e.optInt("end", -1),
                place = e.optString("place"),
                note = e.optString("note"),
                category = e.optInt("cat", 0),
                repeat = runCatching { Repeat.valueOf(e.optString("repeat", "NONE")) }.getOrDefault(Repeat.NONE),
                until = e.optLong("until", -1),
                reminder = e.optInt("reminder", -1),
                skips = (0 until sk.length()).map { sk.getLong(it) }.toSet(),
            )
        }
        val no = o.optJSONArray("notes") ?: JSONArray()
        _notes.value = (0 until no.length()).map { i ->
            val n = no.getJSONObject(i)
            Note(n.getLong("id"), n.optString("title"), n.optString("text"), n.optLong("day", -1), n.optLong("updated", 0))
        }
        val st = o.optJSONObject("settings")
        if (st != null) {
            val cats = st.optJSONArray("categories")
            val names = DEFAULT_CATEGORIES.indices.map { i ->
                cats?.optString(i)?.takeIf { it.isNotBlank() } ?: DEFAULT_CATEGORIES[i]
            }
            _settings.value = Settings(
                zone = st.optString("zone", "B"),
                showSchool = st.optBoolean("showSchool", true),
                defaultReminder = st.optInt("defaultReminder", 15),
                categories = names,
            )
        }
    }
}
