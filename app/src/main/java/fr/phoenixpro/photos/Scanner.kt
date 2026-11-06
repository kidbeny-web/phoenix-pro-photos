package fr.phoenixpro.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

/** Lit la galerie du téléphone (MediaStore), la position GPS et le nom des lieux. */
object Scanner {

    fun hasMediaPermission(ctx: Context): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun hasLocationPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Ajoute les nouvelles photos, retire celles qui ont été supprimées. Renvoie le nombre de nouvelles. */
    fun scan(ctx: Context, progress: (Int, Int) -> Unit): Int {
        if (!hasMediaPermission(ctx)) return 0
        val db = Db.db
        val known = HashMap<Long, Long>()
        db.rawQuery("SELECT id, modified FROM photos", null).use { c -> while (c.moveToNext()) known[c.getLong(0)] = c.getLong(1) }
        val seen = HashSet<Long>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.ORIENTATION,
        )
        var added = 0
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
            val total = c.count
            db.beginTransaction()
            try {
                var i = 0
                while (c.moveToNext()) {
                    i++
                    if (i % 200 == 0) progress(i, total)
                    val id = c.getLong(0)
                    seen += id
                    val modified = c.getLong(3)
                    if (known[id] == modified) continue
                    val name = c.getString(1) ?: "photo"
                    var taken = c.getLong(2)
                    if (taken <= 0) taken = modified * 1000
                    var w = c.getInt(4)
                    var h = c.getInt(5)
                    if (c.getInt(8) == 90 || c.getInt(8) == 270) { val t = w; w = h; h = t }
                    val folder = c.getString(7)
                    val isNew = !known.containsKey(id)
                    val v = Db.values(
                        "id" to id, "name" to name, "folder" to folder, "taken" to taken, "modified" to modified,
                        "width" to w, "height" to h, "size" to c.getLong(6), "name_n" to norm(name),
                    )
                    if (isNew) {
                        // les photos déjà présentes au premier jumelage avec le PC ne sont pas renvoyées automatiquement
                        v.put("synced", if (Prefs(ctx).lastSync == 0L && !Prefs(ctx).paired) 1 else 0)
                        db.insert("photos", null, v)
                        added++
                    } else {
                        v.put("gps_done", 0); v.put("faces_done", 0)
                        db.update("photos", v, "id=?", arrayOf(id.toString()))
                        db.delete("faces", "photo_id=?", arrayOf(id.toString()))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        val gone = known.keys.filter { it !in seen }
        if (gone.isNotEmpty()) {
            db.beginTransaction()
            try {
                for (id in gone) {
                    db.delete("photos", "id=?", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Clusters.cleanup()
        }
        if (added > 0 || gone.isNotEmpty()) PhoenixApp.changed()
        return added
    }

    /** Position GPS des photos (nécessite l'autorisation « position des médias »). */
    fun readGps(ctx: Context, progress: (Int, Int) -> Unit) {
        if (!hasLocationPermission(ctx)) return
        val ids = mutableListOf<Long>()
        Db.db.rawQuery("SELECT id FROM photos WHERE gps_done=0 ORDER BY taken DESC", null).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        ids.forEachIndexed { i, id ->
            if (i % 25 == 0) progress(i, ids.size)
            var lat: Double? = null
            var lon: Double? = null
            try {
                val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    exif.latLong?.let { if (!(it[0] == 0.0 && it[1] == 0.0)) { lat = it[0]; lon = it[1] } }
                }
            } catch (e: Exception) {
                Log.w("Phoenix", "GPS $id : ${e.message}")
            }
            Db.db.update("photos", Db.values("lat" to lat, "lon" to lon, "gps_done" to 1), "id=?", arrayOf(id.toString()))
        }
        if (ids.isNotEmpty()) PhoenixApp.changed()
    }

    /** Nom des villes à partir des coordonnées (regroupées pour limiter les appels). */
    @Suppress("DEPRECATION")
    fun geocode(ctx: Context) {
        if (!Geocoder.isPresent()) return
        val geocoder = Geocoder(ctx, Locale.FRANCE)
        val rows = mutableListOf<Triple<Long, Double, Double>>()
        Db.db.rawQuery("SELECT id, lat, lon FROM photos WHERE lat IS NOT NULL AND place IS NULL", null).use { c ->
            while (c.moveToNext()) rows += Triple(c.getLong(0), c.getDouble(1), c.getDouble(2))
        }
        val cache = HashMap<String, Triple<String?, String?, String?>>()
        for ((id, lat, lon) in rows) {
            val key = "%.2f,%.2f".format(Locale.US, lat, lon) // ~1 km
            val res = cache.getOrPut(key) {
                try {
                    val a = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                    if (a == null) Triple(null, null, null)
                    else Triple(a.locality ?: a.subAdminArea ?: a.adminArea, a.subAdminArea ?: a.adminArea, a.countryName)
                } catch (e: Exception) {
                    return // pas de réseau : on réessaiera plus tard
                }
            }
            if (res.first == null) continue
            Db.db.update(
                "photos", Db.values("place" to res.first, "region" to res.second, "country" to res.third, "place_n" to norm(res.first)),
                "id=?", arrayOf(id.toString())
            )
        }
        if (rows.isNotEmpty()) PhoenixApp.changed()
    }
}
