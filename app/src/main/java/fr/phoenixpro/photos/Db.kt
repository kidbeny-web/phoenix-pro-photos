package fr.phoenixpro.photos

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore
import java.text.Normalizer

data class Photo(
    val id: Long,
    val name: String,
    val taken: Long,
    val width: Int,
    val height: Int,
    val lat: Double?,
    val lon: Double?,
    val place: String?,
    val region: String?,
    val country: String?,
    val fav: Boolean,
    val size: Long,
    val folder: String?,
    val rating: Int = 0,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

data class Person(val id: Long, val name: String?, val coverFaceId: Long, val photos: Int, val hidden: Boolean)
data class Face(val id: Long, val photoId: Long, val personId: Long?, val x: Float, val y: Float, val w: Float, val h: Float, val name: String?)
data class Place(val name: String, val region: String?, val country: String?, val count: Int, val coverId: Long, val lat: Double, val lon: Double)
data class Stats(val photos: Int, val faces: Int, val people: Int, val geo: Int, val toSync: Int)

fun norm(s: String?): String {
    if (s == null) return ""
    val n = Normalizer.normalize(s, Normalizer.Form.NFD)
    return n.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
}

object Db {
    private lateinit var helper: Helper
    val db: SQLiteDatabase get() = helper.writableDatabase

    fun init(context: Context) {
        helper = Helper(context)
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "phoenix.db", null, 3) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE photos (
                id INTEGER PRIMARY KEY, name TEXT, folder TEXT, taken INTEGER, modified INTEGER,
                width INTEGER, height INTEGER, size INTEGER,
                lat REAL, lon REAL, place TEXT, region TEXT, country TEXT, name_n TEXT, place_n TEXT,
                fav INTEGER DEFAULT 0, rating INTEGER DEFAULT 0, noted_at INTEGER DEFAULT 0, gps_done INTEGER DEFAULT 0, faces_done INTEGER DEFAULT 0, synced INTEGER DEFAULT 0)"""
            )
            db.execSQL("CREATE INDEX idx_taken ON photos(taken)")
            db.execSQL(
                """CREATE TABLE persons (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, name_n TEXT,
                cover_face_id INTEGER, hidden INTEGER DEFAULT 0, centroid BLOB, n_faces INTEGER DEFAULT 0)"""
            )
            db.execSQL(
                """CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_id INTEGER NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
                person_id INTEGER REFERENCES persons(id) ON DELETE SET NULL,
                x REAL, y REAL, w REAL, h REAL, score REAL, embedding BLOB, confirmed INTEGER DEFAULT 0)"""
            )
            db.execSQL("CREATE INDEX idx_faces_photo ON faces(photo_id)")
            db.execSQL("CREATE INDEX idx_faces_person ON faces(person_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE photos ADD COLUMN rating INTEGER DEFAULT 0")
            if (oldVersion < 3) db.execSQL("ALTER TABLE photos ADD COLUMN noted_at INTEGER DEFAULT 0")
        }
    }

    // ------------------------------------------------------------------ lecture

    private fun Cursor.toPhoto() = Photo(
        id = getLong(0), name = getString(1) ?: "", taken = getLong(2), width = getInt(3), height = getInt(4),
        lat = if (isNull(5)) null else getDouble(5), lon = if (isNull(6)) null else getDouble(6),
        place = getString(7), region = getString(8), country = getString(9), fav = getInt(10) == 1,
        size = getLong(11), folder = getString(12), rating = getInt(13),
    )

    private const val PHOTO_COLS = "id, name, taken, width, height, lat, lon, place, region, country, fav, size, folder, rating"

    /** Tris proposés : clé → (libellé, ORDER BY). */
    val SORTS = linkedMapOf(
        "date_desc" to ("Plus récentes" to "taken DESC, id DESC"),
        "date_asc" to ("Plus anciennes" to "taken ASC, id ASC"),
        "note" to ("Mieux notées" to "rating DESC, taken DESC, id DESC"),
        "nom" to ("Nom du fichier" to "name_n ASC, id ASC"),
        "taille" to ("Plus lourdes" to "size DESC, id DESC"),
    )

    private val MONTHS = mapOf(
        "janvier" to 1, "fevrier" to 2, "mars" to 3, "avril" to 4, "mai" to 5, "juin" to 6, "juillet" to 7,
        "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12,
    )

    /** Liste des photos, filtrée par personne, favoris, lieu ou texte de recherche. */
    fun photos(personId: Long? = null, favOnly: Boolean = false, place: String? = null, query: String? = null, sort: String = "date_desc"): List<Photo> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (personId != null) {
            where += "EXISTS (SELECT 1 FROM faces f WHERE f.photo_id=photos.id AND f.person_id=?)"; args += personId.toString()
        }
        if (favOnly) where += "fav=1"
        if (place != null) { where += "place=?"; args += place }
        if (!query.isNullOrBlank()) {
            for (tok in norm(query).split(Regex("[\\s,]+")).filter { it.isNotBlank() }) {
                when {
                    tok.matches(Regex("(19|20)\\d{2}")) -> {
                        where += "strftime('%Y', taken/1000, 'unixepoch', 'localtime')=?"; args += tok
                    }
                    MONTHS.containsKey(tok) -> {
                        where += "CAST(strftime('%m', taken/1000, 'unixepoch', 'localtime') AS INTEGER)=?"; args += MONTHS[tok].toString()
                    }
                    tok == "favoris" || tok == "favori" -> where += "fav=1"
                    else -> {
                        val like = "%$tok%"
                        where += "(place_n LIKE ? OR name_n LIKE ? OR lower(folder) LIKE ? OR lower(country) LIKE ? OR EXISTS (SELECT 1 FROM faces f JOIN persons p ON p.id=f.person_id WHERE f.photo_id=photos.id AND p.name_n LIKE ?))"
                        repeat(5) { args += like }
                    }
                }
            }
        }
        val w = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val order = (SORTS[sort] ?: SORTS.getValue("date_desc")).second
        db.rawQuery("SELECT $PHOTO_COLS FROM photos$w ORDER BY $order", args.toTypedArray()).use { c ->
            val out = ArrayList<Photo>(c.count)
            while (c.moveToNext()) out += c.toPhoto()
            return out
        }
    }

    fun photo(id: Long): Photo? =
        db.rawQuery("SELECT $PHOTO_COLS FROM photos WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.toPhoto() else null }

    fun facesOf(photoId: Long): List<Face> =
        db.rawQuery(
            "SELECT f.id, f.photo_id, f.person_id, f.x, f.y, f.w, f.h, p.name FROM faces f LEFT JOIN persons p ON p.id=f.person_id WHERE f.photo_id=? ORDER BY f.x",
            arrayOf(photoId.toString())
        ).use { c ->
            val out = mutableListOf<Face>()
            while (c.moveToNext()) out += Face(
                c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2),
                c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getString(7)
            )
            out
        }

    fun personFaces(personId: Long): List<Face> =
        db.rawQuery(
            "SELECT id, photo_id, person_id, x, y, w, h FROM faces WHERE person_id=? ORDER BY confirmed, score LIMIT 300",
            arrayOf(personId.toString())
        ).use { c ->
            val out = mutableListOf<Face>()
            while (c.moveToNext()) out += Face(c.getLong(0), c.getLong(1), c.getLong(2), c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6), null)
            out
        }

    fun persons(all: Boolean = false): List<Person> =
        db.rawQuery(
            """SELECT p.id, p.name, p.cover_face_id, COUNT(DISTINCT f.photo_id) n, p.hidden FROM persons p
               JOIN faces f ON f.person_id=p.id GROUP BY p.id ORDER BY (p.name IS NULL), n DESC""", null
        ).use { c ->
            val out = mutableListOf<Person>()
            while (c.moveToNext()) {
                val p = Person(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), c.getInt(4) == 1)
                if (all || (!p.hidden && (p.name != null || p.photos >= 2))) out += p
            }
            out
        }

    fun person(id: Long): Person? =
        db.rawQuery(
            "SELECT p.id, p.name, p.cover_face_id, (SELECT COUNT(DISTINCT photo_id) FROM faces WHERE person_id=p.id), p.hidden FROM persons p WHERE p.id=?",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) Person(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), c.getInt(4) == 1) else null }

    fun places(): List<Place> =
        db.rawQuery(
            """SELECT place, region, country, COUNT(*) n, MAX(id), AVG(lat), AVG(lon) FROM photos
               WHERE place IS NOT NULL GROUP BY place, country ORDER BY n DESC""", null
        ).use { c ->
            val out = mutableListOf<Place>()
            while (c.moveToNext()) out += Place(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getDouble(5), c.getDouble(6))
            out
        }

    fun stats(): Stats {
        fun n(sql: String) = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return Stats(
            n("SELECT COUNT(*) FROM photos"), n("SELECT COUNT(*) FROM faces"),
            n("SELECT COUNT(*) FROM persons WHERE name IS NOT NULL"), n("SELECT COUNT(*) FROM photos WHERE lat IS NOT NULL"),
            n("SELECT COUNT(*) FROM photos WHERE synced=0"),
        )
    }

    fun suggestions(query: String): Pair<List<Person>, List<Place>> {
        val like = "%${norm(query)}%"
        val people = db.rawQuery(
            "SELECT id, name, cover_face_id, n_faces, hidden FROM persons WHERE name IS NOT NULL AND name_n LIKE ? ORDER BY name LIMIT 5",
            arrayOf(like)
        ).use { c ->
            val out = mutableListOf<Person>()
            while (c.moveToNext()) out += Person(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), c.getInt(4) == 1)
            out
        }
        val places = places().filter { norm(it.name).contains(norm(query)) }.take(5)
        return people to places
    }

    fun namedPersons(): List<Person> = persons(all = true).filter { it.name != null }.sortedBy { norm(it.name) }

    // ------------------------------------------------------------------ écriture simple

    fun toggleFav(id: Long): Boolean {
        db.execSQL("UPDATE photos SET fav=1-fav, noted_at=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id))
        PhoenixApp.changed()
        return photo(id)?.fav ?: false
    }

    fun setRating(ids: Collection<Long>, value: Int) {
        val v = value.coerceIn(0, 5)
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (id in ids) db.execSQL("UPDATE photos SET rating=?, noted_at=? WHERE id=?", arrayOf<Any>(v, now, id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        PhoenixApp.changed()
    }

    /** Retire de la base des photos qui viennent d'être mises à la corbeille. */
    fun removePhotos(ctx: Context, ids: Collection<Long>) {
        db.beginTransaction()
        try {
            for (id in ids) {
                db.rawQuery("SELECT id FROM faces WHERE photo_id=?", arrayOf(id.toString())).use { c ->
                    while (c.moveToNext()) FaceEngine.faceFile(ctx, c.getLong(0)).delete()
                }
                db.delete("photos", "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Clusters.cleanup()
        PhoenixApp.changed()
    }

    fun setHidden(personId: Long, hidden: Boolean) {
        db.execSQL("UPDATE persons SET hidden=? WHERE id=?", arrayOf(if (hidden) 1 else 0, personId))
        PhoenixApp.changed()
    }

    fun setCover(personId: Long, faceId: Long) {
        db.execSQL("UPDATE persons SET cover_face_id=? WHERE id=?", arrayOf(faceId, personId))
        PhoenixApp.changed()
    }

    fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        for ((k, v) in pairs) when (v) {
            null -> putNull(k)
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Double -> put(k, v)
            is Float -> put(k, v)
            is Boolean -> put(k, if (v) 1 else 0)
            is ByteArray -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}
