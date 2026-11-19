package fr.phoenixpro.testeur

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class TesteurApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

enum class Status { OK, KO, NA }

/** Résultat d'un test. NA = non disponible sur ce téléphone ou non testable. */
data class Result(val status: Status, val detail: String, val at: Long = System.currentTimeMillis())

enum class Kind { AUTO, GUIDED }

data class TestDef(val id: String, val title: String, val desc: String, val group: String, val kind: Kind, val byUser: Boolean = false)

val TESTS = listOf(
    TestDef("info", "Fiche de l'appareil", "Modèle, Android, mémoire, stockage, écran", "Appareil", Kind.AUTO),
    TestDef("batterie", "Batterie", "Santé estimée, cycles, température, tension", "Appareil", Kind.AUTO),
    TestDef("root", "Sécurité du système", "Recherche de signes de root ou de système modifié", "Appareil", Kind.AUTO),
    TestDef("ecran", "Écran et pixels morts", "6 couleurs plein écran, cherchez les défauts", "Écran", Kind.GUIDED, byUser = true),
    TestDef("tactile", "Tactile", "Coloriez toutes les cases avec le doigt", "Écran", Kind.GUIDED),
    TestDef("multitouch", "Multitouch", "Posez plusieurs doigts en même temps", "Écran", Kind.GUIDED),
    TestDef("hp", "Haut-parleur", "Comptez les bips", "Son", Kind.GUIDED),
    TestDef("ecouteur", "Écouteur d'appel", "Oreille contre l'écouteur, comptez les bips", "Son", Kind.GUIDED),
    TestDef("micro", "Micro", "Parlez, puis réécoutez-vous", "Son", Kind.GUIDED, byUser = true),
    TestDef("vibreur", "Vibreur", "Comptez les vibrations", "Son", Kind.GUIDED),
    TestDef("volume", "Boutons de volume", "Appuyez sur + puis sur −", "Boutons", Kind.GUIDED),
    TestDef("cam_arriere", "Caméra arrière", "Image nette et sans tache", "Caméras", Kind.GUIDED, byUser = true),
    TestDef("cam_avant", "Caméra avant", "Image nette et sans tache", "Caméras", Kind.GUIDED, byUser = true),
    TestDef("flash", "Flash", "Comptez les clignotements", "Caméras", Kind.GUIDED),
    TestDef("capteurs", "Capteurs", "Accéléromètre, gyroscope, boussole, proximité, luminosité", "Capteurs", Kind.GUIDED),
    TestDef("empreinte", "Capteur biométrique", "Empreinte ou visage", "Capteurs", Kind.GUIDED),
    TestDef("wifi", "Wi-Fi", "Activation et force du signal", "Connexions", Kind.AUTO),
    TestDef("bluetooth", "Bluetooth", "Présence et état", "Connexions", Kind.AUTO),
    TestDef("nfc", "NFC (paiement sans contact)", "Présence et état", "Connexions", Kind.AUTO),
    TestDef("gps", "GPS", "Satellites captés et position", "Connexions", Kind.GUIDED),
    TestDef("charge", "Port de charge", "Branchez un chargeur", "Connexions", Kind.GUIDED),
)

object Store {
    private lateinit var prefs: SharedPreferences
    val results = mutableMapOf<String, Result>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("testeur", Context.MODE_PRIVATE)
        try {
            val o = JSONObject(prefs.getString("results", "{}") ?: "{}")
            for (k in o.keys()) {
                val r = o.getJSONObject(k)
                results[k] = Result(Status.valueOf(r.getString("s")), r.getString("d"), r.getLong("at"))
            }
        } catch (_: Exception) { }
    }

    fun put(id: String, r: Result) {
        results[id] = r
        persist()
    }

    fun reset() {
        results.clear()
        persist()
        prefs.edit().remove("imei").remove("note").remove("startedAt").apply()
    }

    private fun persist() {
        val o = JSONObject()
        for ((k, r) in results) o.put(k, JSONObject().put("s", r.status.name).put("d", r.detail).put("at", r.at))
        prefs.edit().putString("results", o.toString()).apply()
    }

    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(v) = prefs.edit().putString("model", v).apply()
    var imei: String
        get() = prefs.getString("imei", "") ?: ""
        set(v) = prefs.edit().putString("imei", v).apply()
    var note: String
        get() = prefs.getString("note", "") ?: ""
        set(v) = prefs.edit().putString("note", v).apply()
}
