package fr.phoenixpro.cam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RemoteEvent(val id: Long, val frames: Int)

/** Accès aux caméras depuis la visionneuse. */
object Net {
    fun get(url: String, timeout: Int = 3000): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
    } catch (e: Exception) { null }

    suspend fun snap(cam: Cam): Bitmap? = withContext(Dispatchers.IO) { get(cam.url("/snap.jpg"))?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    suspend fun frame(cam: Cam, id: Long, n: Int): Bitmap? = withContext(Dispatchers.IO) { get(cam.url("/frame/$id/$n"))?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    suspend fun status(cam: Cam): JSONObject? = withContext(Dispatchers.IO) { get(cam.url("/status"))?.let { try { JSONObject(String(it)) } catch (e: Exception) { null } } }
    suspend fun events(cam: Cam): List<RemoteEvent>? = withContext(Dispatchers.IO) {
        get(cam.url("/events"))?.let { b ->
            try { val a = JSONArray(String(b)); (0 until a.length()).map { RemoteEvent(a.getJSONObject(it).getLong("id"), a.getJSONObject(it).getInt("frames")) } } catch (e: Exception) { null }
        }
    }
    suspend fun arm(cam: Cam, on: Boolean) = withContext(Dispatchers.IO) { get(cam.url("/arm?on=${if (on) 1 else 0}")) }
    suspend fun delete(cam: Cam, id: Long) = withContext(Dispatchers.IO) { get(cam.url("/delete/$id")) }
}

/** Recherche des caméras Phoenix Pro Cam annoncées sur le Wi-Fi. */
class Discovery(private val ctx: Context, private val found: (String, String, Int) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("phoenixcam")
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
        override fun onServiceLost(i: NsdServiceInfo) {}
        override fun onServiceFound(i: NsdServiceInfo) { synchronized(queue) { queue.addLast(i) }; next() }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        val i = synchronized(queue) { if (resolving || queue.isEmpty()) null else { resolving = true; queue.removeFirst() } } ?: return
        nsd.resolveService(i, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { resolving = false; next() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val host = s.host?.hostAddress
                if (host != null && !host.contains(':')) found(s.serviceName.removePrefix("PhoenixCam "), host, s.port)
                resolving = false; next()
            }
        })
    }

    fun start() {
        try { lock.setReferenceCounted(false); lock.acquire() } catch (_: Exception) {}
        try { nsd.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) {}
    }

    fun stop() {
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        try { if (lock.isHeld) lock.release() } catch (_: Exception) {}
    }
}

/** Alertes en arrière-plan : interroge les caméras toutes les 15 s et prévient à chaque nouveau passage. */
class AlertService : Service() {
    companion object {
        private const val CH = "veille"
        private const val CH_ALERT = "alertes"
        private const val NID = 21
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, AlertService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlertService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Veille des caméras", NotificationManager.IMPORTANCE_MIN))
        if (nm.getNotificationChannel(CH_ALERT) == null) nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Mouvements détectés", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle("Phoenix Pro Cam veille vos caméras")
            .setContentText("Vous serez prévenu à chaque mouvement").setOngoing(true).setContentIntent(open).build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NID, n, type)
        if (job == null) job = scope.launch {
            val fmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
            while (true) {
                Prefs.cams.forEachIndexed { idx, cam ->
                    val ev = Net.events(cam) ?: return@forEachIndexed
                    val newest = ev.maxOfOrNull { it.id } ?: 0L
                    val seen = Prefs.lastSeen(cam)
                    if (seen == 0L) Prefs.setLastSeen(cam, newest)
                    else if (newest > seen) {
                        Prefs.setLastSeen(cam, newest)
                        val count = ev.count { it.id > seen }
                        val a = NotificationCompat.Builder(this@AlertService, CH_ALERT).setSmallIcon(R.drawable.ic_stat)
                            .setContentTitle("Mouvement : ${cam.name}")
                            .setContentText("${if (count > 1) "$count passages" else "Passage"} à ${fmt.format(Date(newest))}")
                            .setAutoCancel(true).setContentIntent(open).setColor(0xFFF08A1C.toInt()).build()
                        try { nm.notify(100 + idx, a) } catch (_: SecurityException) {}
                    }
                }
                delay(15_000)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
