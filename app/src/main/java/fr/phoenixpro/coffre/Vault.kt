package fr.phoenixpro.coffre

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CoffreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vault.init(this)
    }
}

data class Entry(
    val id: Long,
    val cat: String,
    val name: String,
    val value: String,
    val extra: String,
    val note: String,
    val updated: Long,
)

val CATS = listOf("Wi-Fi", "Digicode", "Alarme", "Cadenas", "N° de série", "Pièces", "Autre")

/** Libellé du champ « identifiant » selon la catégorie. */
fun extraLabel(cat: String) = when (cat) {
    "Wi-Fi" -> "Nom du réseau (SSID)"
    "Digicode", "Alarme" -> "Lieu / adresse"
    "N° de série" -> "Appareil"
    "Pièces" -> "Pour quel appareil"
    else -> "Précision"
}

fun valueLabel(cat: String) = when (cat) {
    "Wi-Fi" -> "Mot de passe"
    "N° de série" -> "Numéro de série"
    "Pièces" -> "Référence"
    else -> "Code"
}

/**
 * Chiffrement : une clé de données aléatoire (256 bits) chiffre le contenu en AES-GCM.
 * Cette clé est elle-même enveloppée deux fois : par une clé dérivée du code (PBKDF2)
 * et, si l'empreinte est activée, par une clé du Keystore Android qui exige la biométrie.
 */
object Vault {
    private const val ITER = 150_000
    private const val BIO_KEY = "phoenix_coffre_bio"
    private lateinit var prefs: SharedPreferences
    private lateinit var file: File
    private val rnd = SecureRandom()

    private var dek: ByteArray? = null
    var entries: List<Entry> = emptyList()
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("coffre", Context.MODE_PRIVATE)
        file = File(ctx.filesDir, "coffre.bin")
    }

    val isSetup get() = prefs.contains("pinWrap")
    val unlocked get() = dek != null
    val bioEnabled get() = prefs.contains("bioWrap")

    fun lock() {
        dek?.fill(0)
        dek = null
        entries = emptyList()
    }

    // ---------------------------------------------------------------- outils

    private fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun derive(secret: String, salt: ByteArray, iter: Int = ITER): SecretKey {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, iter, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    /** Renvoie iv (12 octets) + texte chiffré. */
    private fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    private fun open(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    // ---------------------------------------------------------------- code PIN

    fun setup(pin: String) {
        val d = random(32)
        wrapWithPin(d, pin)
        dek = d
        entries = emptyList()
        save()
    }

    private fun wrapWithPin(d: ByteArray, pin: String) {
        val salt = random(16)
        val wrapped = seal(derive(pin, salt), d)
        prefs.edit().putString("salt", b64(salt)).putString("pinWrap", b64(wrapped)).apply()
    }

    /** Secondes à attendre avant un nouvel essai (après plusieurs codes faux). */
    fun waitSeconds(): Long = ((prefs.getLong("lockUntil", 0) - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    fun unlockWithPin(pin: String): Boolean {
        if (waitSeconds() > 0) return false
        return try {
            val d = open(derive(pin, unb64(prefs.getString("salt", "")!!)), unb64(prefs.getString("pinWrap", "")!!))
            dek = d
            load()
            prefs.edit().putInt("fails", 0).putLong("lockUntil", 0).apply()
            true
        } catch (e: Exception) {
            val fails = prefs.getInt("fails", 0) + 1
            val wait = if (fails >= 5) 30_000L * (1L shl (fails - 5).coerceAtMost(6)) else 0L
            prefs.edit().putInt("fails", fails).putLong("lockUntil", System.currentTimeMillis() + wait).apply()
            false
        }
    }

    fun failCount() = prefs.getInt("fails", 0)

    fun changePin(newPin: String) {
        val d = dek ?: return
        wrapWithPin(d, newPin)
    }

    // ---------------------------------------------------------------- empreinte

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun bioKey(create: Boolean): SecretKey? {
        val ks = keyStore()
        (ks.getKey(BIO_KEY, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val b = KeyGenParameterSpec.Builder(BIO_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else b.setUserAuthenticationValidityDurationSeconds(-1)
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(b.build())
        return g.generateKey()
    }

    /** Chiffreur à faire valider par l'empreinte pour activer le déverrouillage biométrique. */
    fun bioEnrollCipher(): Cipher {
        disableBio()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, bioKey(create = true))
        return c
    }

    fun finishBioEnroll(c: Cipher) {
        val d = dek ?: return
        val ct = c.doFinal(d)
        prefs.edit().putString("bioWrap", b64(c.iv + ct)).apply()
    }

    /** Déchiffreur à faire valider par l'empreinte ; null si l'empreinte doit être réactivée. */
    fun bioUnlockCipher(): Cipher? {
        val data = prefs.getString("bioWrap", null)?.let { unb64(it) } ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, bioKey(create = false) ?: run { disableBio(); return null }, GCMParameterSpec(128, data, 0, 12))
            c
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBio(); null
        } catch (e: Exception) {
            null
        }
    }

    fun finishBioUnlock(c: Cipher): Boolean = try {
        val data = unb64(prefs.getString("bioWrap", "")!!)
        dek = c.doFinal(data, 12, data.size - 12)
        load()
        prefs.edit().putInt("fails", 0).putLong("lockUntil", 0).apply()
        true
    } catch (e: Exception) { false }

    fun disableBio() {
        prefs.edit().remove("bioWrap").apply()
        try { keyStore().deleteEntry(BIO_KEY) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- contenu

    private fun toJson(list: List<Entry>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("id", it.id).put("cat", it.cat).put("name", it.name).put("value", it.value)
                .put("extra", it.extra).put("note", it.note).put("updated", it.updated))
        }
    }

    private fun fromJson(a: JSONArray) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Entry(o.getLong("id"), o.getString("cat"), o.getString("name"), o.getString("value"),
            o.optString("extra"), o.optString("note"), o.optLong("updated"))
    }

    private fun load() {
        val d = dek ?: return
        entries = if (!file.exists()) emptyList() else fromJson(JSONArray(String(open(SecretKeySpec(d, "AES"), file.readBytes()))))
    }

    private fun save() {
        val d = dek ?: return
        val tmp = File(file.parentFile, "coffre.bin.tmp")
        tmp.writeBytes(seal(SecretKeySpec(d, "AES"), toJson(entries).toString().toByteArray()))
        tmp.renameTo(file)
    }

    fun put(e: Entry) {
        entries = entries.filter { it.id != e.id } + e
        save()
    }

    fun delete(id: Long) {
        entries = entries.filter { it.id != id }
        save()
    }

    // ---------------------------------------------------------------- sauvegarde

    fun exportBackup(password: String): ByteArray {
        val salt = random(16)
        val sealed = seal(derive(password, salt, 200_000), toJson(entries).toString().toByteArray())
        return JSONObject().put("app", "Phoenix Pro Coffre").put("v", 1).put("salt", b64(salt)).put("data", b64(sealed))
            .toString().toByteArray()
    }

    /** Renvoie le nombre de codes ajoutés, ou -1 si le mot de passe est faux / le fichier illisible. */
    fun importBackup(bytes: ByteArray, password: String): Int = try {
        val o = JSONObject(String(bytes))
        val plain = open(derive(password, unb64(o.getString("salt")), 200_000), unb64(o.getString("data")))
        val incoming = fromJson(JSONArray(String(plain)))
        val known = entries.map { it.id }.toSet()
        val added = incoming.filter { it.id !in known }
        entries = entries + added
        save()
        added.size
    } catch (e: Exception) { -1 }
}
