package fr.phoenixpro.photos

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class Progress(val running: Boolean = false, val phase: String = "", val done: Int = 0, val total: Int = 0)

/** Analyse de la galerie : nouvelles photos, GPS, lieux, visages, puis envoi vers le PC. */
object Indexer {
    val progress = MutableStateFlow(Progress())
    private val mutex = Mutex()

    suspend fun runOnce(ctx: Context) = withContext(Dispatchers.IO) {
        if (mutex.isLocked) return@withContext
        mutex.withLock {
            try {
                set("Recherche des photos", 0, 0)
                Scanner.scan(ctx) { d, t -> set("Recherche des photos", d, t) }
                set("Lecture des positions", 0, 0)
                Scanner.readGps(ctx) { d, t -> set("Lecture des positions", d, t) }
                set("Recherche des lieux", 0, 0)
                Scanner.geocode(ctx)
                detectFaces(ctx)
                if (Prefs(ctx).paired && Prefs(ctx).autoSync) {
                    Sync.uploadPending(ctx) { d, t -> set("Envoi vers le PC", d, t) }
                }
                Unit
            } catch (e: Throwable) {
                Log.e("Phoenix", "Indexation", e)
            } finally {
                progress.value = Progress()
                PhoenixApp.changed()
            }
        }
    }

    private fun set(phase: String, done: Int, total: Int) {
        progress.value = Progress(true, phase, done, total)
    }

    private fun detectFaces(ctx: Context) {
        val ids = mutableListOf<Pair<Long, Int>>()
        Db.db.rawQuery("SELECT id, width FROM photos WHERE faces_done=0 ORDER BY taken DESC", null).use { c ->
            while (c.moveToNext()) ids += c.getLong(0) to c.getInt(1)
        }
        if (ids.isEmpty()) return
        val engine = try { FaceEngine(ctx) } catch (e: Throwable) {
            Log.e("Phoenix", "Modèles de visages", e); return
        }
        ids.forEachIndexed { i, (id, width) ->
            set("Reconnaissance des visages", i, ids.size)
            try {
                val bmp = engine.loadBitmap(id)
                val found = engine.analyse(bmp, if (width > 0) width else bmp.width)
                Clusters.addPhotoFaces(ctx, id, found)
                bmp.recycle()
            } catch (e: Throwable) {
                Log.w("Phoenix", "Visages $id : ${e.message}")
                Db.db.update("photos", Db.values("faces_done" to 2), "id=?", arrayOf(id.toString()))
            }
            if (i % 20 == 19) PhoenixApp.changed()
        }
    }

    fun start(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "analyse", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().build()
        )
    }

    fun schedulePeriodic(ctx: Context) {
        val prefs = Prefs(ctx)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "synchro", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build()
        )
    }
}

class IndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Indexer.runOnce(applicationContext)
        return Result.success()
    }
}
