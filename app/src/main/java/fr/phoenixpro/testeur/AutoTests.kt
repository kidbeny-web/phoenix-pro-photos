package fr.phoenixpro.testeur

import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.WindowManager
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private val FR = Locale.FRANCE
fun gb(bytes: Long): String = String.format(FR, "%.1f Go", bytes / 1_000_000_000.0)

fun deviceName(): String {
    val man = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$man ${Build.MODEL}"
}

object AutoTests {

    fun run(ctx: Context, id: String): Result? = try {
        when (id) {
            "info" -> info(ctx)
            "batterie" -> battery(ctx)
            "root" -> root()
            "wifi" -> wifi(ctx)
            "bluetooth" -> bluetooth(ctx)
            "nfc" -> nfc(ctx)
            else -> null
        }
    } catch (e: Exception) {
        Result(Status.NA, "Test impossible sur ce téléphone (${e.javaClass.simpleName})")
    }

    fun runAll(ctx: Context) {
        TESTS.filter { it.kind == Kind.AUTO }.forEach { t -> run(ctx, t.id)?.let { Store.put(t.id, it) } }
    }

    private fun info(ctx: Context): Result {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val st = StatFs(Environment.getDataDirectory().path)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val disp = wm.defaultDisplay
        val mode = disp.mode
        val hz = disp.supportedModes.maxOfOrNull { it.refreshRate } ?: disp.refreshRate
        val soc = if (Build.VERSION.SDK_INT >= 31) " · puce ${Build.SOC_MODEL}" else ""
        val cams = try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.size
        } catch (e: Exception) { 0 }
        val parts = listOf(
            "${deviceName()}",
            "Android ${Build.VERSION.RELEASE} (mise à jour de sécurité ${Build.VERSION.SECURITY_PATCH})",
            "Mémoire vive ${gb(mi.totalMem)}",
            "Stockage ${gb(st.totalBytes)}, dont ${gb(st.availableBytes)} libres",
            "Écran ${mode.physicalWidth}×${mode.physicalHeight} px, jusqu'à ${hz.roundToInt()} Hz",
            "${Runtime.getRuntime().availableProcessors()} cœurs$soc",
            "$cams caméra${if (cams > 1) "s" else ""} déclarée${if (cams > 1) "s" else ""}",
        )
        return Result(Status.OK, parts.joinToString(" · "))
    }

    /** Capacité d'origine déclarée par le fabricant (mAh), si le système la donne. */
    private fun designCapacity(ctx: Context): Double? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val pp = cls.getConstructor(Context::class.java).newInstance(ctx)
        val v = cls.getMethod("getBatteryCapacity").invoke(pp) as Double
        if (v > 500) v else null
    } catch (e: Throwable) { null }

    private fun battery(ctx: Context): Result {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Result(Status.NA, "Informations batterie indisponibles")
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0
        val tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""
        val cycles = if (Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else -1

        val healthTxt = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "bon état selon le système"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "SURCHAUFFE"
            BatteryManager.BATTERY_HEALTH_DEAD -> "HORS SERVICE"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "SURTENSION"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "DÉFAUT"
            BatteryManager.BATTERY_HEALTH_COLD -> "trop froide"
            else -> "état non communiqué"
        }
        val bad = health in listOf(
            BatteryManager.BATTERY_HEALTH_OVERHEAT, BatteryManager.BATTERY_HEALTH_DEAD,
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE, BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE,
        )

        // Estimation de la capacité réelle : charge restante (µAh) ramenée à 100 %.
        val design = designCapacity(ctx)
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        var estimate = ""
        var lowHealth = false
        if (design != null && counter > 0 && level >= 20) {
            val full = counter / 1000.0 / (level / 100.0)
            val pct = (full / design * 100).roundToInt()
            if (pct in 40..120) {
                estimate = "capacité estimée ${full.roundToInt()} / ${design.roundToInt()} mAh (≈ ${pct.coerceAtMost(100)} %)"
                lowHealth = pct < 75
            }
        } else if (design != null) {
            estimate = "capacité d'origine ${design.roundToInt()} mAh"
        }

        val parts = mutableListOf("$level %", healthTxt)
        if (estimate.isNotEmpty()) parts.add(estimate)
        if (cycles >= 0) parts.add("$cycles cycles de charge")
        parts.add(String.format(FR, "%.1f °C", temp))
        if (volt > 0) parts.add(String.format(FR, "%.2f V", volt))
        if (tech.isNotEmpty()) parts.add(tech)
        val status = if (bad || lowHealth || temp >= 45) Status.KO else Status.OK
        return Result(status, parts.joinToString(" · "))
    }

    private fun root(): Result {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sd/xbin/su", "/data/local/bin/su", "/data/local/xbin/su", "/system/app/Superuser.apk", "/data/adb/magisk")
        val found = paths.filter { File(it).exists() }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        val signs = found.map { "fichier $it" } + if (testKeys) listOf("système signé avec des clés de test") else emptyList()
        return if (signs.isEmpty()) Result(Status.OK, "Aucun signe de root ni de système modifié")
        else Result(Status.KO, "Signes de modification : " + signs.joinToString(", "))
    }

    private fun wifi(ctx: Context): Result {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!ctx.packageManager.hasSystemFeature("android.hardware.wifi")) return Result(Status.NA, "Pas de Wi-Fi sur ce téléphone")
        if (!wm.isWifiEnabled) return Result(Status.NA, "Wi-Fi désactivé : activez-le et relancez le test")
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Result(Status.OK, "Wi-Fi activé, pas connecté à un réseau")
        val rssi = if (Build.VERSION.SDK_INT >= 29) caps.signalStrength else Int.MIN_VALUE
        val down = caps.linkDownstreamBandwidthKbps / 1000
        val quality = when {
            rssi == Int.MIN_VALUE -> ""
            rssi >= -60 -> " · signal fort ($rssi dBm)"
            rssi >= -72 -> " · signal correct ($rssi dBm)"
            else -> " · signal faible ($rssi dBm)"
        }
        return Result(Status.OK, "Connecté au Wi-Fi$quality${if (down > 0) " · lien jusqu'à $down Mb/s" else ""}")
    }

    private fun bluetooth(ctx: Context): Result {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val ad = bm?.adapter ?: return Result(Status.NA, "Pas de Bluetooth sur ce téléphone")
        val on = try { ad.isEnabled } catch (e: SecurityException) { null }
        val le = ctx.packageManager.hasSystemFeature("android.hardware.bluetooth_le")
        return Result(Status.OK, "Puce Bluetooth présente${if (le) " (Bluetooth basse consommation)" else ""} · " +
            when (on) { true -> "activé"; false -> "désactivé"; null -> "état non lisible" })
    }

    private fun nfc(ctx: Context): Result {
        val ad = NfcAdapter.getDefaultAdapter(ctx) ?: return Result(Status.NA, "Pas de NFC sur ce téléphone")
        return Result(Status.OK, "Puce NFC présente · ${if (ad.isEnabled) "activée" else "désactivée"}")
    }

    fun hasFlash(ctx: Context): String? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) { null }
}
