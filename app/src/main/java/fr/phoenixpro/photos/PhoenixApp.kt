package fr.phoenixpro.photos

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.opencv.android.OpenCVLoader
import org.osmdroid.config.Configuration

class PhoenixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            if (!OpenCVLoader.initLocal()) Log.w("Phoenix", "OpenCV non chargé")
        } catch (e: Throwable) {
            Log.e("Phoenix", "OpenCV", e)
        }
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = cacheDir.resolve("tuiles")
        Db.init(this)
        Indexer.schedulePeriodic(this)
    }

    companion object {
        lateinit var instance: PhoenixApp
            private set

        /** Incrémenté à chaque changement de la base : les écrans se rechargent. */
        val dataVersion = MutableStateFlow(0)
        fun changed() { dataVersion.value = dataVersion.value + 1 }
    }
}
