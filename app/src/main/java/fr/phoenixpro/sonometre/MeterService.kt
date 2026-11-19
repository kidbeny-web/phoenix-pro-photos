package fr.phoenixpro.sonometre

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Service de premier plan : la mesure continue écran éteint. */
class MeterService : Service() {

    companion object {
        private const val CHANNEL = "mesure"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "fr.phoenixpro.sonometre.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MeterService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MeterService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Démarrage de la mesure…"), type)

        if (!Meter.isRunning()) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoenixPro:Sonometre").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
            Meter.start(this)
        }

        ticker?.cancel()
        ticker = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (true) {
                delay(2000)
                val s = Meter.state.value
                if (!s.running && s.error != null) { stopEverything(); break }
                if (s.running) {
                    val txt = "${s.level.roundToInt()} dB(A) · ${s.events} événement${if (s.events > 1) "s" else ""}"
                    nm.notify(NOTIF_ID, buildNotification(txt))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopEverything() {
        ticker?.cancel()
        Thread {
            Meter.stop()
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        ticker?.cancel()
        if (Meter.isRunning()) Thread { Meter.stop() }.start()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Mesure en cours", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Affichée pendant que le sonomètre enregistre"
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Phoenix Pro Sonomètre mesure")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .setColor(0xFFF08A1C.toInt())
            .build()
    }
}
