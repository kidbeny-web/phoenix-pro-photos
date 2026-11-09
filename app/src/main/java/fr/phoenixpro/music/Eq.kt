package fr.phoenixpro.music

import android.media.audiofx.DynamicsProcessing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

/** Égaliseur 10 bandes (traitement du système, sur la session audio du lecteur). */
object Eq {
    val freqs = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    val presets: Map<String, List<Float>> = linkedMapOf(
        "Plat" to List(10) { 0f },
        "Basses +" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Aigus +" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 4f, 5f),
        "Loudness" to listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Rock" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 2f, 3f, 3f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Classique" to listOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, 1f, 2f, 3f),
        "Voix" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
        "Électro" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f),
        "Casque" to listOf(2f, 2f, 1f, 0f, 0f, -1f, -1f, 1f, 2f, 1f),
    )

    /** Message affiché si l'égaliseur ne peut pas agir. */
    val notice = MutableStateFlow<String?>(null)

    private var dp: DynamicsProcessing? = null
    private var session = 0

    /** Fréquence haute de chaque bande (moyenne géométrique avec la suivante). */
    private fun cutoff(i: Int): Float =
        if (i == freqs.lastIndex) 20000f else (freqs[i] * sqrt(freqs[i + 1].toFloat() / freqs[i]))

    fun attach(audioSession: Int) {
        if (audioSession == session && dp != null) return
        release()
        session = audioSession
        if (audioSession == 0) return
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, freqs.size, false, 0, false, 0, true,
            ).build()
            dp = DynamicsProcessing(0, audioSession, cfg)
            notice.value = null
        } catch (e: Exception) {
            dp = null
            notice.value = "L'égaliseur n'est pas disponible sur ce téléphone"
        }
        reapply()
    }

    fun reapply() = apply(Store.eq.value)

    fun apply(s: EqState) {
        val d = dp ?: return
        try {
            for (i in freqs.indices) {
                d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoff(i), s.gains[i]))
            }
            d.setInputGainAllChannelsTo(s.preamp)
            // limiteur pour éviter la saturation quand on monte des bandes
            d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -0.5f, 0f))
            val bp = BitPerfect.status.value.active
            d.setEnabled(s.enabled && !bp)
            notice.value = if (s.enabled && bp) "Égaliseur coupé : le mode bit-perfect envoie le son tel quel au DAC" else null
        } catch (_: Exception) {
        }
    }

    private fun release() {
        try { dp?.release() } catch (_: Exception) {}
        dp = null
    }
}
