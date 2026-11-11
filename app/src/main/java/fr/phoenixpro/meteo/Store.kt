package fr.phoenixpro.meteo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Données gardées sur le téléphone : ville affichée, favoris, dernières prévisions. */
object Store {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("meteo", Context.MODE_PRIVATE)
    }

    var current: Place
        get() = prefs.getString("current", null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() } ?: Place.DEFAULT
        set(v) = prefs.edit().putString("current", v.toJson().toString()).apply()

    var favorites: List<Place>
        get() {
            val s = prefs.getString("favorites", null) ?: return listOf(Place.DEFAULT)
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(listOf(Place.DEFAULT))
        }
        set(v) = prefs.edit().putString("favorites", JSONArray().apply { v.forEach { put(it.toJson()) } }.toString()).apply()

    /** Dernières prévisions téléchargées (pour l'affichage hors ligne et le widget). */
    fun saveCache(p: Place, json: String) {
        prefs.edit()
            .putString("cache_place", p.toJson().toString())
            .putString("cache_json", json)
            .putLong("cache_time", System.currentTimeMillis())
            .apply()
    }

    fun cache(): Triple<Place, Forecast, Long>? = runCatching {
        val p = Place.fromJson(JSONObject(prefs.getString("cache_place", null)!!))
        Triple(p, Api.parse(prefs.getString("cache_json", null)!!), prefs.getLong("cache_time", 0))
    }.getOrNull()
}

class MeteoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        RefreshWorker.schedule(this)
    }
}

/** Met à jour les prévisions du widget toutes les 30 minutes. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        val p = Store.current
        return try {
            val json = withContext(Dispatchers.IO) { Api.fetchJson(p) }
            Api.parse(json) // vérifie que la réponse est valide
            Store.saveCache(p, json)
            WeatherWidget.updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("meteo_refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("meteo_now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
