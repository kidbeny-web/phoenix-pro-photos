package fr.phoenixpro.mesure

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MesureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Rooms.init(this)
    }
}

val TYPES = listOf("Fenêtre", "Mur", "Porte", "Meuble", "Emplacement", "Autre")

data class Item(val id: Long, val type: String, val name: String, val w: Double, val h: Double?, val at: Long)
data class Room(val name: String, val items: List<Item>)

fun cm(v: Double): String = if (v % 1.0 == 0.0) String.format(Locale.FRANCE, "%.0f", v) else String.format(Locale.FRANCE, "%.1f", v)
fun dims(i: Item): String = if (i.h != null) "${cm(i.w)} × ${cm(i.h)} cm" else "${cm(i.w)} cm"

/** Repère courant pour des rideaux : tringle 15 cm plus large de chaque côté, tissu 2 fois la largeur. */
fun curtains(i: Item): String = "Rideaux : tringle ≈ ${cm(i.w + 30)} cm, tissu ≈ ${cm(i.w * 2)} cm de large" +
    (i.h?.let { ", hauteur ≈ ${cm(it + 15)} cm depuis la tringle" } ?: "")

object Rooms {
    private lateinit var prefs: SharedPreferences
    var list: List<Room> = emptyList()
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("mesure", Context.MODE_PRIVATE)
        list = try {
            val a = JSONArray(prefs.getString("rooms", "[]"))
            (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                val its = r.getJSONArray("items")
                Room(r.getString("name"), (0 until its.length()).map { j ->
                    val o = its.getJSONObject(j)
                    Item(o.getLong("id"), o.getString("type"), o.getString("name"), o.getDouble("w"),
                        if (o.has("h")) o.getDouble("h") else null, o.getLong("at"))
                })
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save() {
        val a = JSONArray()
        list.forEach { r ->
            a.put(JSONObject().put("name", r.name).put("items", JSONArray().apply {
                r.items.forEach { i ->
                    put(JSONObject().put("id", i.id).put("type", i.type).put("name", i.name).put("w", i.w).put("at", i.at).apply { i.h?.let { put("h", it) } })
                }
            }))
        }
        prefs.edit().putString("rooms", a.toString()).apply()
    }

    /** Ajoute ou remplace une mesure (même id) dans la pièce donnée. */
    fun put(room: String, item: Item) {
        val cleaned = list.map { r -> r.copy(items = r.items.filter { it.id != item.id }) }
        list = if (cleaned.any { it.name.equals(room, ignoreCase = true) })
            cleaned.map { if (it.name.equals(room, ignoreCase = true)) it.copy(items = it.items + item) else it }
        else cleaned + Room(room, listOf(item))
        list = list.filter { it.items.isNotEmpty() }
        save()
    }

    fun delete(id: Long) {
        list = list.map { r -> r.copy(items = r.items.filter { it.id != id }) }.filter { it.items.isNotEmpty() }
        save()
    }

    fun roomOf(id: Long): String? = list.firstOrNull { r -> r.items.any { it.id == id } }?.name

    fun text(r: Room): String = r.name + "\n" + r.items.joinToString("\n") { "- ${it.name} (${it.type.lowercase()}) : ${dims(it)}" }
}
