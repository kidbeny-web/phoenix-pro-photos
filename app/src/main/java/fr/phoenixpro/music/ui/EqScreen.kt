@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import fr.phoenixpro.music.BitPerfect
import fr.phoenixpro.music.Eq
import fr.phoenixpro.music.EqState
import fr.phoenixpro.music.Store
import kotlin.math.roundToInt

private fun freqLabel(f: Int) = if (f >= 1000) "${f / 1000} kHz" else "$f Hz"
private fun db(v: Float): String {
    val r = (v * 2).roundToInt() / 2f
    val s = if (r % 1f == 0f) "${r.toInt()}" else String.format(FR, "%.1f", r)
    return (if (r > 0) "+" else "") + s + " dB"
}

@Composable
fun EqScreen() {
    val eq by Store.eq.collectAsState()
    val notice by Eq.notice.collectAsState()
    val bp by BitPerfect.status.collectAsState()

    fun set(e: EqState) { Store.setEq(e); Eq.apply(e) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Égaliseur", if (eq.enabled) "Préréglage : ${eq.preset}" else "Désactivé") {
            Switch(eq.enabled, { set(eq.copy(enabled = it)) }, Modifier.padding(end = 12.dp))
        }
        val msg = notice ?: if (bp.active) "Le mode bit-perfect est actif : l'égaliseur est contourné pour garder le son d'origine." else null
        if (msg != null) {
            Text(
                msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp),
            )
        }

        // courbe de réponse
        val line = MaterialTheme.colorScheme.primary
        val grid = MaterialTheme.colorScheme.outlineVariant
        val alpha = if (eq.enabled) 1f else 0.35f
        Canvas(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().height(110.dp)
                .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val mid = size.height / 2
            drawLine(grid, Offset(0f, mid), Offset(size.width, mid), 2f)
            val step = size.width / (Eq.freqs.size - 1)
            val pts = eq.gains.mapIndexed { i, g -> Offset(i * step, mid - (g + eq.preamp).coerceIn(-12f, 12f) / 12f * (mid - 10f)) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    cubicTo((a.x + b.x) / 2, a.y, (a.x + b.x) / 2, b.y, b.x, b.y)
                }
            }
            drawPath(path, line.copy(alpha = alpha), style = Stroke(width = 6f, cap = StrokeCap.Round))
        }

        SectionLabel("Préréglages")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Eq.presets.keys.toList()) { name ->
                FilterChip(
                    selected = eq.preset == name,
                    onClick = { set(eq.copy(enabled = true, preset = name, gains = Eq.presets.getValue(name))) },
                    label = { Text(name) },
                )
            }
        }

        SectionLabel("Préampli")
        BandRow("Gain", eq.preamp, eq.enabled) { set(eq.copy(preamp = it)) }

        SectionLabel("Bandes")
        Eq.freqs.forEachIndexed { i, f ->
            BandRow(freqLabel(f), eq.gains[i], eq.enabled) { v ->
                set(eq.copy(gains = eq.gains.toMutableList().also { it[i] = v }, preset = "Personnalisé"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { set(eq.copy(preamp = 0f, gains = List(10) { 0f }, preset = "Plat")) }) { Text("Remettre à zéro") }
        }
        Text(
            "Astuce : si le son sature quand tu montes des bandes, baisse un peu le préampli.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BandRow(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(64.dp))
        Slider(
            value = value, onValueChange = { onChange((it * 2).roundToInt() / 2f) },
            valueRange = -12f..12f, enabled = enabled, modifier = Modifier.weight(1f),
        )
        Text(db(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp).padding(start = 8.dp))
    }
}
