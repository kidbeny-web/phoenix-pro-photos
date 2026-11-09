package fr.phoenixpro.photos

import android.content.Context
import android.net.Uri

/** Réglages de l'application. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    var pcUrl: String
        get() = sp.getString("pc_url", "") ?: ""
        set(v) = sp.edit().putString("pc_url", v).apply()

    var pcKey: String
        get() = sp.getString("pc_key", "") ?: ""
        set(v) = sp.edit().putString("pc_key", v).apply()

    var autoSync: Boolean
        get() = sp.getBoolean("auto_sync", true)
        set(v) = sp.edit().putBoolean("auto_sync", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", true)
        set(v) = sp.edit().putBoolean("wifi_only", v).apply()

    var faceThreshold: Float
        get() = sp.getFloat("face_threshold", 0.42f)
        set(v) = sp.edit().putFloat("face_threshold", v).apply()

    var sort: String
        get() = sp.getString("tri", "date_desc") ?: "date_desc"
        set(v) = sp.edit().putString("tri", v).apply()

    var gridColumns: Int
        get() = sp.getInt("colonnes", 4)
        set(v) = sp.edit().putInt("colonnes", v).apply()

    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    /** Autres adresses possibles du PC (fournies par le QR code). */
    var pcAltHosts: List<String>
        get() = (sp.getString("pc_alt", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = sp.edit().putString("pc_alt", v.joinToString(",")).apply()

    val pcPort: Int get() = try { Uri.parse(pcUrl).port.takeIf { it > 0 } ?: 8765 } catch (e: Exception) { 8765 }

    val paired: Boolean get() = pcUrl.isNotBlank() && pcKey.isNotBlank()

    /** Lit le lien du QR code affiché sur le PC : http://192.168.1.20:8765/?cle=XXXX */
    fun pairFromLink(link: String): Boolean {
        return try {
            val u = Uri.parse(link.trim())
            val key = u.getQueryParameter("cle") ?: return false
            if (u.host.isNullOrBlank()) return false
            val port = if (u.port > 0) ":${u.port}" else ""
            pcUrl = "${u.scheme ?: "http"}://${u.host}$port"
            pcKey = key
            pcAltHosts = (u.getQueryParameter("ips") ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun unpair() {
        sp.edit().remove("pc_url").remove("pc_key").apply()
    }
}
