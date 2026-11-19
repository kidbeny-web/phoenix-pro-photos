package fr.phoenixpro.cam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

data class CamStatus(
    val running: Boolean = false,
    val armed: Boolean = true,
    val motion: Boolean = false,
    val events: Int = 0,
    val ip: String? = null,
    val error: String? = null,
)

/** Ce que l'écran du téléphone-caméra affiche. */
object CamState {
    val status = MutableStateFlow(CamStatus())
    val frame = MutableStateFlow<Bitmap?>(null)
}

fun localIp(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
}

/** Les événements enregistrés : un dossier par passage, une image JPEG par demi-seconde. */
object Events {
    fun list(): List<File> = (Prefs.events.listFiles()?.filter { it.isDirectory } ?: emptyList()).sortedByDescending { it.name.toLongOrNull() ?: 0 }
    fun frames(dir: File): List<File> = (dir.listFiles()?.filter { it.name.endsWith(".jpg") } ?: emptyList()).sortedBy { it.name }
    fun cleanup() {
        val all = list()
        all.drop(300).forEach { it.deleteRecursively() }
    }
    fun json(): String = JSONArray().apply {
        list().take(200).forEach { d -> put(JSONObject().put("id", d.name.toLong()).put("frames", frames(d).size)) }
    }.toString()
}

class CamService : LifecycleService() {
    companion object {
        private const val CH = "camera"
        private const val NID = 11
        private const val STOP = "fr.phoenixpro.cam.STOP"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, CamService::class.java))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, CamService::class.java).setAction(STOP)) }
    }

    private lateinit var exec: ExecutorService
    private var server: CamServer? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var wake: PowerManager.WakeLock? = null

    @Volatile var latestJpeg: ByteArray? = null
    private var bg: FloatArray? = null
    private var lastProcess = 0L
    private var hits = 0
    private var lastMotion = 0L
    private var eventDir: File? = null
    private var eventFrames = 0
    private var lastSaved = 0L
    private val pre = ArrayDeque<ByteArray>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Caméra active", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CamService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Phoenix Pro Cam surveille « ${Prefs.camName} »")
            .setContentText("Détection de mouvement active").setOngoing(true).setContentIntent(open)
            .addAction(0, "Arrêter", stop).setColor(0xFFF08A1C.toInt()).build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NID, n, type)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoenixPro:Cam")
            .apply { setReferenceCounted(false); acquire(7 * 24 * 3600 * 1000L) }
        exec = Executors.newSingleThreadExecutor()
        Events.cleanup()
        startCamera()
        try {
            server = CamServer(this).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: Exception) {
            CamState.status.value = CamStatus(error = "Le serveur local ne démarre pas (port $PORT occupé ?)")
        }
        announce()
        CamState.status.value = CamStatus(true, Prefs.armed, false, Events.list().size, localIp(this))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        try { nsdListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        if (::exec.isInitialized) exec.shutdown()
        CamState.status.value = CamStatus()
        CamState.frame.value = null
        super.onDestroy()
    }

    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            try {
                val prov = fut.get()
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(exec) { img -> try { process(img) } catch (_: Exception) {} finally { img.close() } }
                val sel = if (Prefs.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                prov.unbindAll()
                prov.bindToLifecycle(this, sel, analysis)
            } catch (e: Exception) {
                CamState.status.value = CamState.status.value.copy(error = "La caméra ne démarre pas (${e.javaClass.simpleName})")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Analyse ~4 images par seconde : mouvement + image pour le direct + enregistrement. */
    private fun process(img: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastProcess < 250) return
        lastProcess = now

        // 1) Mouvement : grille 32×24 de luminosité, comparée à un fond qui s'adapte lentement.
        val y = img.planes[0]
        val buf = y.buffer; val rs = y.rowStride; val ps = y.pixelStride
        val gw = 32; val gh = 24
        val cur = FloatArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val px = (i * 2 + 1) * img.width / (gw * 2); val py = (j * 2 + 1) * img.height / (gh * 2)
            cur[j * gw + i] = (buf.get(py * rs + px * ps).toInt() and 0xFF).toFloat()
        }
        val b = bg
        var moving = false
        if (b != null) {
            var mean = 0f
            for (k in cur.indices) mean += cur[k] - b[k]
            mean /= cur.size // corrige les changements de lumière globaux
            var count = 0
            for (k in cur.indices) if (abs(cur[k] - b[k] - mean) > 24) count++
            val frac = count * 100f / cur.size
            hits = if (frac >= Prefs.sensitivity) hits + 1 else 0
            moving = hits >= 2
            for (k in cur.indices) b[k] = b[k] * 0.92f + cur[k] * 0.08f
        } else bg = cur

        // 2) Image JPEG redressée pour le direct et les enregistrements.
        val jpeg = toJpeg(img)
        latestJpeg = jpeg
        CamState.frame.value = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

        // 3) Enregistrement des passages (si la détection est armée).
        if (moving) lastMotion = now
        val armed = Prefs.armed
        val active = armed && now - lastMotion < 5_000 && lastMotion > 0
        if (active && eventDir == null) {
            eventDir = File(Prefs.events, now.toString()).apply { mkdirs() }
            eventFrames = 0
            pre.forEach { saveFrame(it) } // les 2 secondes d'avant
            Events.cleanup()
        }
        val d = eventDir
        if (d != null) {
            if (!active || eventFrames >= 90) { eventDir = null }
            else if (now - lastSaved >= 500) { saveFrame(jpeg); lastSaved = now }
        }
        if (now % 500 < 260) { pre.addLast(jpeg); while (pre.size > 4) pre.removeFirst() }

        val st = CamState.status.value
        val m = active && armed
        if (st.motion != m || st.armed != armed) CamState.status.value = st.copy(motion = m, armed = armed, events = Events.list().size)
    }

    private fun saveFrame(j: ByteArray) {
        val d = eventDir ?: return
        File(d, "%03d.jpg".format(eventFrames)).writeBytes(j)
        eventFrames++
    }

    private fun toJpeg(img: ImageProxy): ByteArray {
        val w = img.width; val h = img.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        var pos = 0
        for (row in 0 until h) for (col in 0 until w) nv21[pos++] = yb.get(row * yP.rowStride + col * yP.pixelStride)
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            nv21[pos++] = vb.get(row * vP.rowStride + col * vP.pixelStride)
            nv21[pos++] = ub.get(row * uP.rowStride + col * uP.pixelStride)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 70, out)
        val rot = img.imageInfo.rotationDegrees
        if (rot == 0 && !Prefs.front) return out.toByteArray()
        val raw = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val m = Matrix().apply { postRotate(rot.toFloat()); if (Prefs.front) postScale(-1f, 1f) }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val o2 = ByteArrayOutputStream()
        r.compress(Bitmap.CompressFormat.JPEG, 72, o2)
        bmp.recycle(); r.recycle()
        return o2.toByteArray()
    }

    /** Annonce la caméra sur le Wi-Fi pour que la visionneuse la trouve seule. */
    private fun announce() {
        val m = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val info = NsdServiceInfo().apply { serviceName = "PhoenixCam ${Prefs.camName}"; serviceType = NSD_TYPE; port = PORT }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsdListener = l
        try { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    fun battery(): Int = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

/** Petit serveur local : chaque demande doit contenir le code de la caméra (?k=123456). */
class CamServer(private val svc: CamService) : NanoHTTPD(PORT) {
    private fun text(s: String, mime: String = "application/json") = newFixedLengthResponse(Response.Status.OK, mime, s)
    private fun jpeg(b: ByteArray) = newFixedLengthResponse(Response.Status.OK, "image/jpeg", b.inputStream(), b.size.toLong())
    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "introuvable")

    override fun serve(s: IHTTPSession): Response {
        if (s.parameters["k"]?.firstOrNull() != Prefs.code) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "code")
        val u = s.uri
        return when {
            u == "/status" -> text(
                JSONObject().put("name", Prefs.camName).put("armed", Prefs.armed).put("motion", CamState.status.value.motion)
                    .put("events", Events.list().size).put("battery", svc.battery()).toString()
            )
            u == "/snap.jpg" -> svc.latestJpeg?.let { jpeg(it) } ?: notFound()
            u == "/events" -> text(Events.json())
            u == "/arm" -> {
                Prefs.armed = s.parameters["on"]?.firstOrNull() == "1"
                CamState.status.value = CamState.status.value.copy(armed = Prefs.armed)
                text("{\"armed\":${Prefs.armed}}")
            }
            u.startsWith("/frame/") -> {
                val parts = u.removePrefix("/frame/").split("/")
                val id = parts.getOrNull(0)?.toLongOrNull(); val n = parts.getOrNull(1)?.toIntOrNull()
                if (id == null || n == null) notFound()
                else Events.frames(File(Prefs.events, id.toString())).getOrNull(n)?.let { jpeg(it.readBytes()) } ?: notFound()
            }
            u.startsWith("/delete/") -> {
                val id = u.removePrefix("/delete/").toLongOrNull()
                if (id != null) File(Prefs.events, id.toString()).deleteRecursively()
                text("{\"ok\":true}")
            }
            else -> notFound()
        }
    }
}
