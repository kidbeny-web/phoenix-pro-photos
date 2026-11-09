package fr.phoenixpro.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class AlbumInfo(val date: String, val label: String, val country: String)
data class ArtistInfo(val title: String, val extract: String, val url: String)

/** Pochettes (dossier, image intégrée, Internet) et infos en ligne (MusicBrainz, Wikipédia). */
object Covers {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Incrémenté quand une nouvelle pochette arrive, pour rafraîchir l'affichage. */
    val version = MutableStateFlow(0)

    private const val UA = "PhoenixProMusic/1.0 (https://github.com/kidbeny-web/phoenix-pro-photos)"
    private val netLock = Mutex()
    private var lastCall = 0L
    private val tried = HashSet<String>()

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }

    /** Pochette envoyée depuis le PC avec l'album (cover.jpg, folder.jpg…). */
    fun folderFile(ctx: Context, folder: String) = File(dir(ctx), "dir_${hash(folder)}.jpg")
    private fun embeddedFile(ctx: Context, a: Album) = File(dir(ctx), "emb_${hash(a.coverKey)}.jpg")
    private fun onlineFile(ctx: Context, a: Album) = File(dir(ctx), "web_${hash(a.coverKey)}.jpg")
    private fun noneMarker(ctx: Context, a: Album) = File(dir(ctx), "none_${hash(a.coverKey)}")
    private fun albumInfoFile(ctx: Context, a: Album) = File(dir(ctx), "info_${hash(a.coverKey)}.json")
    private fun artistFile(ctx: Context, name: String) = File(dir(ctx), "artist_${hash(Library.norm(name))}.json")
    fun artistPhoto(ctx: Context, name: String) = File(dir(ctx), "artist_${hash(Library.norm(name))}.jpg")

    /** Fichier de pochette déjà prêt (pour la notification), sans calcul. */
    fun readyFile(ctx: Context, a: Album): File? =
        listOf(folderFile(ctx, a.folder), embeddedFile(ctx, a), onlineFile(ctx, a)).firstOrNull { it.exists() }

    suspend fun load(ctx: Context, a: Album, px: Int = 600): Bitmap? = withContext(Dispatchers.IO) {
        val memKey = "${a.coverKey}@$px"
        mem.get(memKey)?.let { return@withContext it }
        var f = readyFile(ctx, a)
        if (f == null && !noneMarker(ctx, a).exists()) {
            f = extractEmbedded(ctx, a)
            if (f == null) noneMarker(ctx, a).writeText("")
        }
        val bmp = f?.let { decode(it, px) }
        if (bmp != null) mem.put(memKey, bmp)
        bmp
    }

    fun loadFile(f: File, px: Int): Bitmap? {
        val k = "${f.path}@$px"
        mem.get(k)?.let { return it }
        return decode(f, px)?.also { mem.put(k, it) }
    }

    private fun decode(f: File, px: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) {
        null
    }

    private fun save(bmp: Bitmap, f: File) {
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun extractEmbedded(ctx: Context, a: Album): File? {
        val out = embeddedFile(ctx, a)
        for (t in a.tracks.take(3)) {
            try {
                val bmp = ctx.contentResolver.loadThumbnail(t.uri, Size(800, 800), null)
                save(bmp, out); return out
            } catch (_: Exception) {
            }
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(ctx, t.uri)
                val pic = r.embeddedPicture
                r.release()
                if (pic != null) { out.writeBytes(pic); return out }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun canUseNetwork(ctx: Context): Boolean {
        val s = Store.settings.value
        if (!s.onlineCovers) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !s.wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private suspend fun get(url: String): Pair<Int, ByteArray?> = netLock.withLock {
        // MusicBrainz demande au plus une requête par seconde
        val wait = 1100 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json, image/*")
                }
                var code = c.responseCode
                var conn = c
                var hops = 0
                while (code in 301..308 && hops < 5) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn = (URL(URL(conn.url, loc).toString()).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000; readTimeout = 15000
                        setRequestProperty("User-Agent", UA)
                    }
                    code = conn.responseCode; hops++
                }
                code to (if (code == 200) conn.inputStream.use { it.readBytes() } else null)
            } catch (_: Exception) {
                -1 to null
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun luceneEsc(s: String) = s.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)"""), "\\\\$1")

    /** Cherche la pochette et les infos de l'album sur MusicBrainz / Cover Art Archive. */
    suspend fun fetchOnline(ctx: Context, a: Album, force: Boolean = false): Boolean {
        if (!force && (onlineFile(ctx, a).exists() || folderFile(ctx, a.folder).exists())) return true
        synchronized(tried) { if (!force && !tried.add(a.coverKey)) return false }
        if (!canUseNetwork(ctx)) return false
        val q = "release:\"${luceneEsc(a.title)}\"" +
            (if (a.artist != Library.UNKNOWN_ARTIST) " AND artist:\"${luceneEsc(a.artist)}\"" else "")
        val (code, body) = get("https://musicbrainz.org/ws/2/release/?query=${enc(q)}&fmt=json&limit=6")
        if (code != 200 || body == null) return false
        val rels = try { JSONObject(String(body)).optJSONArray("releases") } catch (_: Exception) { null } ?: return false
        var gotCover = false
        var gotInfo = false
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            if (r.optInt("score", 0) < 85) continue
            if (!gotInfo) {
                val label = r.optJSONArray("label-info")?.optJSONObject(0)?.optJSONObject("label")?.optString("name") ?: ""
                val info = JSONObject().put("date", r.optString("date")).put("label", label).put("country", r.optString("country"))
                withContext(Dispatchers.IO) { albumInfoFile(ctx, a).writeText(info.toString()) }
                gotInfo = true
            }
            val rg = r.optJSONObject("release-group")?.optString("id")
            val urls = listOfNotNull(
                rg?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release-group/$it/front-500" },
                "https://coverartarchive.org/release/${r.getString("id")}/front-500",
            )
            for (u in urls) {
                val (c, img) = get(u)
                if (c == 200 && img != null && img.size > 1000) {
                    withContext(Dispatchers.IO) { onlineFile(ctx, a).writeBytes(img) }
                    gotCover = true
                    break
                }
            }
            if (gotCover) break
        }
        if (gotCover) {
            mem.evictAll()
            version.value++
        }
        return gotCover
    }

    fun albumInfo(ctx: Context, a: Album): AlbumInfo? = try {
        val j = JSONObject(albumInfoFile(ctx, a).readText())
        AlbumInfo(j.optString("date"), j.optString("label"), j.optString("country"))
    } catch (_: Exception) {
        null
    }

    fun artistInfo(ctx: Context, name: String): ArtistInfo? = try {
        val j = JSONObject(artistFile(ctx, name).readText())
        ArtistInfo(j.optString("title"), j.optString("extract"), j.optString("url"))
    } catch (_: Exception) {
        null
    }

    /** Résumé Wikipédia (en français) et photo de l'artiste. */
    suspend fun fetchArtist(ctx: Context, name: String): ArtistInfo? {
        artistInfo(ctx, name)?.let { return it }
        if (name == Library.UNKNOWN_ARTIST || !canUseNetwork(ctx)) return null
        synchronized(tried) { if (!tried.add("artist:$name")) return null }
        val (c, body) = get("https://fr.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=" +
            enc("$name musique"))
        if (c != 200 || body == null) return null
        val title = try {
            JSONObject(String(body)).getJSONObject("query").getJSONArray("search").getJSONObject(0).getString("title")
        } catch (_: Exception) { return null }
        val (c2, b2) = get("https://fr.wikipedia.org/api/rest_v1/page/summary/" + enc(title.replace(' ', '_')).replace("+", "%20"))
        if (c2 != 200 || b2 == null) return null
        val j = try { JSONObject(String(b2)) } catch (_: Exception) { return null }
        if (j.optString("type") == "disambiguation") return null
        val info = ArtistInfo(
            j.optString("title", title),
            j.optString("extract"),
            j.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page") ?: "",
        )
        withContext(Dispatchers.IO) {
            artistFile(ctx, name).writeText(
                JSONObject().put("title", info.title).put("extract", info.extract).put("url", info.url).toString()
            )
        }
        val img = j.optJSONObject("thumbnail")?.optString("source")
        if (!img.isNullOrBlank()) {
            val (c3, b3) = get(img)
            if (c3 == 200 && b3 != null) withContext(Dispatchers.IO) { artistPhoto(ctx, name).writeBytes(b3) }
        }
        version.value++
        return info
    }

    /** Complète toutes les pochettes manquantes (lancé depuis les réglages). */
    suspend fun fetchAllMissing(ctx: Context, progress: (Int, Int) -> Unit): Int {
        val todo = Library.albums.value.filter { a ->
            load(ctx, a, 200) == null
        }
        var found = 0
        todo.forEachIndexed { i, a ->
            progress(i, todo.size)
            if (fetchOnline(ctx, a, force = true)) found++
        }
        progress(todo.size, todo.size)
        return found
    }

    fun clearMemory() { mem.evictAll(); version.value++ }
}
