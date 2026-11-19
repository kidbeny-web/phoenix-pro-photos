package fr.phoenixpro.detecteur

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun MetalScreen() {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    // Capteur « non calibré » : le système ne compense pas lentement le champ d'un objet métallique.
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) ?: sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    if (sensor == null) {
        Panel(Modifier.padding(16.dp)) { Text("Ce téléphone n'a pas de capteur magnétique : la détection de métaux est impossible.", color = TextC) }
        return
    }
    var field by remember { mutableFloatStateOf(0f) }
    var baseline by remember { mutableFloatStateOf(0f) }
    var calibrating by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(true) }
    var vibrate by remember { mutableStateOf(false) }
    var sens by remember { mutableFloatStateOf(15f) } // µT au-dessus du fond pour donner l'alerte
    val history = remember { mutableStateListOf<Float>() }
    val calib = remember { mutableListOf<Float>() }

    DisposableEffect(sensor) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                field = if (field == 0f) m else field * 0.7f + m * 0.3f
                if (calibrating) {
                    calib.add(m)
                    if (calib.size >= 40) { baseline = calib.sorted()[calib.size / 2]; calib.clear(); calibrating = false }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }

    val delta = if (calibrating) 0f else abs(field - baseline)
    val strength = (delta / max(sens * 4, 1f)).coerceIn(0f, 1f)
    val alert = !calibrating && delta >= sens

    LaunchedEffect(Unit) { while (true) { delay(100); history.add(delta); if (history.size > 150) history.removeAt(0) } }

    // Bips et vibrations : plus le métal est proche, plus c'est rapide.
    val tone = remember { try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: Exception) { null } }
    val vib = remember {
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val latestAlert by rememberUpdatedState(alert)
    val latestStrength by rememberUpdatedState(strength)
    val latestSound by rememberUpdatedState(sound)
    val latestVib by rememberUpdatedState(vibrate)
    LaunchedEffect(Unit) {
        while (true) {
            if (latestAlert) {
                if (latestSound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                if (latestVib && vib.hasVibrator()) vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                delay((600 - 520 * latestStrength).toLong())
            } else delay(100)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Métaux", if (calibrating) "Étalonnage : éloignez le téléphone de tout métal…" else "Passez lentement le haut du téléphone contre le mur")
        Panel {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                    val d = min(size.width, size.height * 1.6f) - 24f
                    val tl = Offset((size.width - d) / 2, 12f)
                    drawArc(LineC, 160f, 220f, false, tl, Size(d, d), style = Stroke(26f, cap = StrokeCap.Round))
                    drawArc(if (alert) Red else Orange, 160f, max(1f, 220f * strength), false, tl, Size(d, d), style = Stroke(26f, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 30.dp)) {
                    Text(if (calibrating) "…" else "${delta.roundToInt()}", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = if (alert) Red else TextC)
                    Text("µT au-dessus du fond", color = Muted, fontSize = 13.sp)
                    Text(
                        when { calibrating -> "Étalonnage"; delta >= sens * 3 -> "Métal tout proche"; alert -> "Métal détecté"; else -> "Rien de notable" },
                        color = if (alert) Red else Green, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Canvas(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(Card2)) {
                val top = max(sens * 4, history.maxOrNull() ?: 1f)
                val y = { v: Float -> size.height - (v / top).coerceIn(0f, 1f) * size.height }
                drawLine(Red.copy(alpha = 0.6f), Offset(0f, y(sens)), Offset(size.width, y(sens)), 2f)
                if (history.size > 1) {
                    val p = Path()
                    history.forEachIndexed { i, v -> val x = i.toFloat() / 149f * size.width; if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v)) }
                    drawPath(p, Orange, style = Stroke(3f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Champ total ${field.roundToInt()} µT · fond ${baseline.roundToInt()} µT", fontSize = 12.sp, color = Muted)
        }
        Button(
            onClick = { calib.clear(); calibrating = true },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange),
        ) { Text("Réétalonner (loin de tout métal)", fontWeight = FontWeight.Bold) }
        Panel {
            Text("Sensibilité : alerte à partir de ${sens.roundToInt()} µT", color = TextC)
            Slider(value = sens, onValueChange = { sens = it }, valueRange = 3f..60f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bips", color = TextC, modifier = Modifier.weight(1f)); Switch(checked = sound, onCheckedChange = { sound = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibrations", color = TextC, modifier = Modifier.weight(1f)); Switch(checked = vibrate, onCheckedChange = { vibrate = it })
            }
        }
        Panel {
            Text("Bien l'utiliser", fontWeight = FontWeight.Bold, color = TextC)
            Text(
                "• Le capteur est le plus souvent en haut du téléphone : c'est cette partie qu'on passe contre le mur.\n" +
                    "• Retirez une coque à aimant et éloignez montre, clés et haut-parleurs.\n" +
                    "• Détecte surtout les métaux ferreux à quelques centimètres : clous, vis, rails, tuyaux et gaines en acier.\n" +
                    "• Un câble électrique en cuivre n'est PAS détecté de façon fiable. Avant de percer, coupez le courant au tableau et utilisez un détecteur de câbles dédié.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
