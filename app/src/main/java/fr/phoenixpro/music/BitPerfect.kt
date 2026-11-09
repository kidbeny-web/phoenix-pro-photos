package fr.phoenixpro.music

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sortie bit-perfect vers un DAC USB (Android 14+) : le téléphone envoie le flux tel quel,
 * sans mixage ni rééchantillonnage, à la fréquence du morceau.
 */
object BitPerfect {
    data class Status(val dac: String? = null, val active: Boolean = false, val message: String = "")

    val status = MutableStateFlow(Status())

    private lateinit var am: AudioManager
    private var lastRate = 0
    private var lastChannels = 2
    private var applied: AudioDeviceInfo? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 34

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun init(ctx: Context) {
        if (::am.isInitialized) return
        am = ctx.getSystemService(AudioManager::class.java)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (removed.any { it.id == applied?.id }) applied = null
                refresh()
            }
        }, Handler(Looper.getMainLooper()))
        refresh()
    }

    private fun usb(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    /** Appelé quand le format du morceau en cours est connu. */
    fun onFormat(sampleRate: Int, channels: Int) {
        if (sampleRate > 0) lastRate = sampleRate
        if (channels > 0) lastChannels = channels
        refresh()
    }

    fun refresh() {
        if (!::am.isInitialized) return
        val dev = usb()
        val name = dev?.productName?.toString()?.takeIf { it.isNotBlank() } ?: dev?.let { "DAC USB" }
        if (dev == null) {
            status.value = Status(null, false, "")
            Eq.reapply()
            return
        }
        if (!supported) {
            status.value = Status(name, false, "Bit-perfect : Android 14 minimum")
            return
        }
        apply34(dev, name)
        Eq.reapply()
    }

    @SuppressLint("NewApi")
    private fun apply34(dev: AudioDeviceInfo, name: String?) {
        val s = Store.settings.value
        if (!s.bitPerfect) {
            clear34()
            status.value = Status(name, false, "Mode bit-perfect désactivé")
            return
        }
        if (lastRate <= 0) {
            status.value = Status(name, false, "En attente d'un morceau")
            return
        }
        val list = try {
            am.getSupportedMixerAttributes(dev).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isEmpty()) {
            clear34()
            status.value = Status(name, false, "Ce DAC ne propose pas le mode bit-perfect")
            return
        }
        val wanted = if (s.hiRes) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val sameRate = list.filter { it.format.sampleRate == lastRate }
        val pick = sameRate.firstOrNull { it.format.encoding == wanted && it.format.channelCount == lastChannels }
            ?: sameRate.firstOrNull { it.format.encoding == wanted }
        if (pick == null) {
            clear34()
            val why = when {
                sameRate.isEmpty() -> "le DAC n'accepte pas ${AudioInfo.khz(lastRate)} en bit-perfect"
                s.hiRes -> "le DAC n'accepte pas le 32 bits flottant : désactive « Sortie haute résolution » pour les fichiers 16 bits"
                else -> "format non accepté par le DAC"
            }
            status.value = Status(name, false, "Bit-perfect impossible : $why")
            return
        }
        val ok = try {
            am.setPreferredMixerAttributes(attrs, dev, pick)
        } catch (_: Exception) {
            false
        }
        applied = if (ok) dev else null
        val fmt = if (pick.format.encoding == AudioFormat.ENCODING_PCM_FLOAT) "32 bits flottant" else "16 bits"
        status.value = if (ok) Status(name, true, "Bit-perfect · ${AudioInfo.khz(lastRate)} · $fmt")
        else Status(name, false, "Le téléphone a refusé le mode bit-perfect")
    }

    @SuppressLint("NewApi")
    private fun clear34() {
        val d = applied ?: return
        try { am.clearPreferredMixerAttributes(attrs, d) } catch (_: Exception) {}
        applied = null
    }
}
