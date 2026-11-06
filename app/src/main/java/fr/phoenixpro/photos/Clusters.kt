package fr.phoenixpro.photos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import android.graphics.Bitmap

/** Regroupement des visages par personne (même logique que la version PC). */
object Clusters {
    private val lock = Any()
    private var ids = mutableListOf<Long>()
    private var centroids = mutableListOf<FloatArray>()
    private var counts = mutableListOf<Int>()
    private var loaded = false

    fun toBytes(v: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) b.putFloat(f)
        return b.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.getFloat() }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (f in v) n += f * f
        n = sqrt(n)
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun load() {
        ids.clear(); centroids.clear(); counts.clear()
        Db.db.rawQuery("SELECT id, centroid, n_faces FROM persons WHERE centroid IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                ids += c.getLong(0); centroids += fromBytes(c.getBlob(1)); counts += maxOf(1, c.getInt(2))
            }
        }
        loaded = true
    }

    fun reset() = synchronized(lock) { loaded = false }

    private fun assign(db: SQLiteDatabase, faceId: Long, e: FloatArray, score: Float, threshold: Float, exclude: Set<Long>): Long {
        if (!loaded) load()
        var best = -1
        var bestSim = -1f
        for (i in ids.indices) {
            if (ids[i] in exclude) continue // deux visages d'une même photo = deux personnes différentes
            val s = dot(centroids[i], e)
            if (s > bestSim) { bestSim = s; best = i }
        }
        val pid: Long
        if (best < 0 || bestSim < threshold) {
            pid = db.insert("persons", null, Db.values("cover_face_id" to faceId, "n_faces" to 0))
            ids += pid; centroids += e.copyOf(); counts += 1
            db.update("persons", Db.values("centroid" to toBytes(e), "n_faces" to 1), "id=?", arrayOf(pid.toString()))
        } else {
            pid = ids[best]
            val n = counts[best]
            val m = normalize(FloatArray(e.size) { centroids[best][it] * n + e[it] })
            centroids[best] = m; counts[best] = n + 1
            db.update("persons", Db.values("centroid" to toBytes(m), "n_faces" to n + 1), "id=?", arrayOf(pid.toString()))
            val coverScore = db.rawQuery(
                "SELECT f.score FROM persons p JOIN faces f ON f.id=p.cover_face_id WHERE p.id=?", arrayOf(pid.toString())
            ).use { c -> if (c.moveToFirst()) c.getFloat(0) else 0f }
            if (score > coverScore + 0.02f) db.update("persons", Db.values("cover_face_id" to faceId), "id=?", arrayOf(pid.toString()))
        }
        db.update("faces", Db.values("person_id" to pid), "id=?", arrayOf(faceId.toString()))
        return pid
    }

    /** Enregistre les visages d'une photo et les range par personne. */
    fun addPhotoFaces(ctx: Context, photoId: Long, found: List<DetectedFace>) = synchronized(lock) {
        val db = Db.db
        val threshold = Prefs(ctx).faceThreshold
        db.beginTransaction()
        try {
            val used = mutableSetOf<Long>()
            for (f in found.sortedByDescending { it.score }) {
                val fid = db.insert(
                    "faces", null, Db.values(
                        "photo_id" to photoId, "x" to f.x, "y" to f.y, "w" to f.w, "h" to f.h,
                        "score" to f.score, "embedding" to toBytes(f.embedding)
                    )
                )
                FileOutputStream(FaceEngine.faceFile(ctx, fid)).use { f.thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                used += assign(db, fid, f.embedding, f.score, threshold, used)
            }
            db.update("photos", Db.values("faces_done" to 1), "id=?", arrayOf(photoId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recompute(db: SQLiteDatabase, pid: Long) {
        val rows = mutableListOf<Triple<Long, FloatArray, Float>>()
        db.rawQuery("SELECT id, embedding, score FROM faces WHERE person_id=?", arrayOf(pid.toString())).use { c ->
            while (c.moveToNext()) rows += Triple(c.getLong(0), fromBytes(c.getBlob(1)), c.getFloat(2))
        }
        if (rows.isEmpty()) {
            db.delete("persons", "id=? AND name IS NULL", arrayOf(pid.toString()))
            db.update("persons", Db.values("centroid" to null, "n_faces" to 0), "id=?", arrayOf(pid.toString()))
            return
        }
        val sum = FloatArray(128)
        for ((_, e, _) in rows) for (i in sum.indices) sum[i] += e[i]
        db.update("persons", Db.values("centroid" to toBytes(normalize(sum)), "n_faces" to rows.size), "id=?", arrayOf(pid.toString()))
        val coverOk = db.rawQuery(
            "SELECT 1 FROM faces WHERE id=(SELECT cover_face_id FROM persons WHERE id=?) AND person_id=?",
            arrayOf(pid.toString(), pid.toString())
        ).use { it.moveToFirst() }
        if (!coverOk) db.update("persons", Db.values("cover_face_id" to rows.maxBy { it.third }.first), "id=?", arrayOf(pid.toString()))
    }

    fun cleanup() = synchronized(lock) {
        val db = Db.db
        db.execSQL("DELETE FROM persons WHERE name IS NULL AND id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id IS NOT NULL)")
        val pids = mutableListOf<Long>()
        db.rawQuery("SELECT id FROM persons", null).use { c -> while (c.moveToNext()) pids += c.getLong(0) }
        for (p in pids) recompute(db, p)
        loaded = false
    }

    /** Refait les groupes automatiques en gardant les noms et corrections de l'utilisateur. */
    fun recluster(ctx: Context) = synchronized(lock) {
        val db = Db.db
        val threshold = Prefs(ctx).faceThreshold
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET person_id=NULL WHERE confirmed=0")
            db.execSQL("DELETE FROM persons WHERE name IS NULL AND id NOT IN (SELECT DISTINCT person_id FROM faces WHERE person_id IS NOT NULL)")
            val pids = mutableListOf<Long>()
            db.rawQuery("SELECT id FROM persons", null).use { c -> while (c.moveToNext()) pids += c.getLong(0) }
            for (p in pids) recompute(db, p)
            load()
            val used = HashMap<Long, MutableSet<Long>>()
            db.rawQuery("SELECT photo_id, person_id FROM faces WHERE person_id IS NOT NULL", null).use { c ->
                while (c.moveToNext()) used.getOrPut(c.getLong(0)) { mutableSetOf() } += c.getLong(1)
            }
            val todo = mutableListOf<Pair<Long, Pair<Long, Pair<FloatArray, Float>>>>()
            db.rawQuery("SELECT id, photo_id, embedding, score FROM faces WHERE person_id IS NULL ORDER BY score DESC", null).use { c ->
                while (c.moveToNext()) todo += c.getLong(0) to (c.getLong(1) to (fromBytes(c.getBlob(2)) to c.getFloat(3)))
            }
            for ((fid, rest) in todo) {
                val (photoId, ef) = rest
                val set = used.getOrPut(photoId) { mutableSetOf() }
                set += assign(db, fid, ef.first, ef.second, threshold, set)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loaded = false
        PhoenixApp.changed()
    }

    // ------------------------------------------------------------------ actions de l'utilisateur

    fun rename(personId: Long, name: String?): Long = synchronized(lock) {
        val db = Db.db
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        var pid = personId
        db.beginTransaction()
        try {
            if (clean != null) {
                val other = db.rawQuery("SELECT id FROM persons WHERE name_n=? AND id<>?", arrayOf(norm(clean), pid.toString()))
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                if (other != null) { merge(db, pid, other); pid = other } // même nom = même personne
            }
            db.update("persons", Db.values("name" to clean, "name_n" to clean?.let { norm(it) }), "id=?", arrayOf(pid.toString()))
            if (clean != null) db.execSQL("UPDATE faces SET confirmed=1 WHERE person_id=?", arrayOf(pid))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loaded = false
        PhoenixApp.changed()
        pid
    }

    private fun merge(db: SQLiteDatabase, src: Long, dst: Long) {
        db.execSQL("UPDATE faces SET person_id=? WHERE person_id=?", arrayOf(dst, src))
        db.delete("persons", "id=?", arrayOf(src.toString()))
        recompute(db, dst)
    }

    fun mergeInto(src: Long, dst: Long) = synchronized(lock) {
        val db = Db.db
        db.beginTransaction()
        try {
            merge(db, src, dst)
            db.execSQL("UPDATE faces SET confirmed=1 WHERE person_id=? AND EXISTS (SELECT 1 FROM persons WHERE id=? AND name IS NOT NULL)", arrayOf(dst, dst))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loaded = false
        PhoenixApp.changed()
    }

    /** Attribue un visage à une personne nommée (créée si besoin). Sans nom : visage détaché. */
    fun assignFace(faceId: Long, name: String?) = synchronized(lock) {
        val db = Db.db
        db.beginTransaction()
        try {
            val old = db.rawQuery("SELECT person_id FROM faces WHERE id=?", arrayOf(faceId.toString()))
                .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            val clean = name?.trim()?.takeIf { it.isNotEmpty() }
            val pid = if (clean != null) {
                db.rawQuery("SELECT id FROM persons WHERE name_n=?", arrayOf(norm(clean))).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    ?: db.insert("persons", null, Db.values("name" to clean, "name_n" to norm(clean), "cover_face_id" to faceId))
            } else db.insert("persons", null, Db.values("cover_face_id" to faceId))
            db.update("faces", Db.values("person_id" to pid, "confirmed" to 1), "id=?", arrayOf(faceId.toString()))
            recompute(db, pid)
            if (old != null && old != pid) recompute(db, old)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        loaded = false
        PhoenixApp.changed()
    }

    fun deleteFace(ctx: Context, faceId: Long) = synchronized(lock) {
        val db = Db.db
        val old = db.rawQuery("SELECT person_id FROM faces WHERE id=?", arrayOf(faceId.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        db.delete("faces", "id=?", arrayOf(faceId.toString()))
        if (old != null) recompute(db, old)
        FaceEngine.faceFile(ctx, faceId).delete()
        loaded = false
        PhoenixApp.changed()
    }

    /** Autres groupes qui ressemblent à cette personne. */
    fun suggestions(personId: Long): List<Person> {
        val me = Db.db.rawQuery("SELECT centroid FROM persons WHERE id=?", arrayOf(personId.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) fromBytes(c.getBlob(0)) else null } ?: return emptyList()
        val out = mutableListOf<Pair<Float, Person>>()
        Db.db.rawQuery(
            "SELECT id, name, cover_face_id, n_faces, centroid FROM persons WHERE id<>? AND centroid IS NOT NULL AND hidden=0",
            arrayOf(personId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val s = dot(me, fromBytes(c.getBlob(4)))
                if (s > 0.30f) out += s to Person(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), false)
            }
        }
        return out.sortedByDescending { it.first }.take(6).map { it.second }
    }
}
