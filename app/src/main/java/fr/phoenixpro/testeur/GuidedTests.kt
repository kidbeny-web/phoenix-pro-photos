package fr.phoenixpro.testeur

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Boutons de volume capturés pendant le test (voir MainActivity.dispatchKeyEvent). */
object Keys {
    @Volatile var capture = false
    val flow = MutableSharedFlow<Int>(extraBufferCapacity = 8)
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Masque les barres système pendant un test plein écran. */
@Composable
fun FullScreen() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val win = view.context.findActivity()?.window
        val ctl = win?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Écran de test standard : titre, consigne, contenu, boutons. */
@Composable
fun TestFrame(title: String, hint: String, onCancel: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextC, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Annuler", color = Muted) }
        }
        Text(hint, fontSize = 15.sp, color = Muted)
        content()
    }
}

@Composable
fun Choice(text: String, color: Color = Card2, textColor: Color = TextC, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(54.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = textColor),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Question Oui / Non posée après un test visuel ou sonore. */
@Composable
fun YesNo(question: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    Panel {
        Text(question, fontSize = 17.sp, color = TextC, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice(yes, Green, Color(0xFF06140E), Modifier.weight(1f), onYes)
            Choice(no, Red, Color(0xFF1A0607), Modifier.weight(1f), onNo)
        }
    }
}

/** Le testeur compte des bips, vibrations ou flashs : on ne peut pas répondre au hasard. */
@Composable
fun CountQuestion(question: String, expected: Int, onAnswer: (Int) -> Unit) {
    Panel {
        Text(question, fontSize = 17.sp, color = TextC, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n -> Choice("$n", modifier = Modifier.weight(1f)) { onAnswer(n) } }
        }
        Spacer(Modifier.height(8.dp))
        Choice("Rien du tout", Red, Color(0xFF1A0607), Modifier.fillMaxWidth()) { onAnswer(0) }
    }
}

// ---------------------------------------------------------------- Écran

@Composable
fun ScreenTest(done: (Result?) -> Unit) {
    val colors = listOf(Color.Black to "Noir : cherchez un point lumineux", Color.White to "Blanc : cherchez un point sombre ou une tache",
        Color.Red to "Rouge", Color(0xFF00FF00) to "Vert", Color.Blue to "Bleu", Color(0xFF808080) to "Gris : cherchez des zones plus claires (marquage)")
    var i by remember { mutableIntStateOf(0) }
    if (i < colors.size) {
        FullScreen()
        Box(
            Modifier.fillMaxSize().background(colors[i].first).pointerInput(i) {
                awaitEachGesture { awaitFirstDown(); i++ }
            },
        ) {
            Text(
                "${colors[i].second}\nTouchez l'écran pour continuer (${i + 1}/${colors.size})",
                color = if (i == 1) Color.Black else Color.White, textAlign = TextAlign.Center, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color(0x55000000).takeIf { i != 1 } ?: Color(0x33FFFFFF)).padding(10.dp),
            )
        }
    } else {
        TestFrame("Écran", "Vous avez vu les 6 couleurs.", { done(null) }) {
            YesNo("Avez-vous vu un pixel mort, une tache, une ligne ou une image fantôme ?", "Aucun défaut", "Défaut vu",
                onYes = { done(Result(Status.OK, "Aucun défaut vu sur 6 couleurs (vérifié par le testeur)")) },
                onNo = { done(Result(Status.KO, "Défaut d'écran signalé par le testeur")) })
            TextButton(onClick = { i = 0 }) { Text("Revoir les couleurs") }
        }
    }
}

@Composable
fun TouchTest(done: (Result?) -> Unit) {
    val cols = 8; val rows = 14
    val filled = remember { mutableStateListOf<Boolean>().apply { repeat(cols * rows) { add(false) } } }
    val count = filled.count { it }
    FullScreen()
    LaunchedEffect(count) {
        if (count == cols * rows) { delay(300); done(Result(Status.OK, "Les ${cols * rows} zones de l'écran répondent au toucher")) }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF15181B))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun mark(p: Offset) {
                        val c = (p.x / size.width * cols).toInt(); val r = (p.y / size.height * rows).toInt()
                        if (c in 0 until cols && r in 0 until rows) filled[r * cols + c] = true
                    }
                    mark(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { mark(it.position) }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
            },
        ) {
            val w = size.width / cols; val h = size.height / rows
            for (r in 0 until rows) for (c in 0 until cols) {
                drawRect(if (filled[r * cols + c]) Orange else Color(0xFF22272B), Offset(c * w + 1.5f, r * h + 1.5f), Size(w - 3f, h - 3f))
            }
        }
        Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xCC0E1012)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count / ${cols * rows}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
            Text("Coloriez toutes les cases, bords compris", fontSize = 13.sp, color = Muted)
            TextButton(onClick = {
                val pct = count * 100 / (cols * rows)
                done(Result(Status.KO, "$pct % de l'écran a répondu : ${cols * rows - count} zones sans réponse ou non testées"))
            }) { Text("Une zone ne répond pas", color = Red) }
            TextButton(onClick = { done(null) }) { Text("Annuler", color = Muted) }
        }
    }
}

@Composable
fun MultiTouchTest(done: (Result?) -> Unit) {
    var current by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(0) }
    var points by remember { mutableStateOf(listOf<Offset>()) }
    TestFrame("Multitouch", "Posez 2, 3, 4 doigts ou plus en même temps dans la zone.", { done(null) }) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Card2).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        points = pressed.map { it.position }
                        current = pressed.size
                        best = max(best, current)
                        if (pressed.isEmpty()) break
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) { points.forEach { drawCircle(Orange, 70f, it) } }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$current", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = TextC)
                Text("doigt${if (current > 1) "s" else ""} détecté${if (current > 1) "s" else ""} · record $best", color = Muted)
            }
        }
        Choice("Terminer", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) {
            done(if (best >= 2) Result(Status.OK, "$best doigts détectés en même temps") else Result(Status.KO, "Un seul doigt détecté à la fois"))
        }
    }
}

// ---------------------------------------------------------------- Son et vibreur

private suspend fun playBeeps(ctx: Context, n: Int, earpiece: Boolean) = withContext(Dispatchers.IO) {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val oldMode = am.mode
    if (earpiece) {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
    }
    val rate = 44100
    val beep = (rate * 0.22).toInt(); val gap = (rate * 0.38).toInt()
    val total = n * (beep + gap) + rate / 5
    val pcm = ShortArray(total)
    for (k in 0 until n) {
        val off = k * (beep + gap)
        for (i in 0 until beep) {
            val env = min(1.0, min(i, beep - i) / 400.0)
            pcm[off + i] = (sin(2 * PI * 1000 * i / rate) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
    }
    val usage = if (earpiece) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(total * 2).build()
    try {
        track.write(pcm, 0, total)
        track.play()
        delay(total * 1000L / rate + 150)
    } finally {
        track.release()
        if (earpiece) {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            am.mode = oldMode
        }
    }
}

@Composable
fun BeepTest(earpiece: Boolean, done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expected by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val name = if (earpiece) "Écouteur d'appel" else "Haut-parleur"
    fun go() {
        expected = Random.nextInt(2, 5); wrong = false; playing = true
        scope.launch { playBeeps(ctx, expected, earpiece); playing = false }
    }
    TestFrame(
        name,
        if (earpiece) "Montez le volume, collez l'oreille contre l'écouteur en haut de l'écran, puis lancez les bips." else "Montez le volume, puis lancez les bips. Comptez-les.",
        { done(null) },
    ) {
        Choice(if (playing) "Lecture…" else if (expected == 0) "Lancer les bips" else "Réécouter", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) { if (!playing) go() }
        if (expected > 0 && !playing) {
            CountQuestion("Combien de bips avez-vous entendus ?", expected) { a ->
                when {
                    a == expected -> done(Result(Status.OK, "$expected bips entendus clairement"))
                    a == 0 -> done(Result(Status.KO, "Aucun son entendu"))
                    else -> wrong = true
                }
            }
            if (wrong) {
                Text("Ce n'est pas le bon nombre. Le son est peut-être faible ou haché.", color = Red, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice("Réessayer", modifier = Modifier.weight(1f)) { go() }
                    Choice("Marquer en défaut", Red, Color(0xFF1A0607), Modifier.weight(1f)) {
                        done(Result(Status.KO, "Son mal perçu (nombre de bips incorrect)"))
                    }
                }
            }
        }
    }
}

@Composable
fun VibrationTest(done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    val vib = remember {
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    LaunchedEffect(Unit) { if (!vib.hasVibrator()) done(Result(Status.NA, "Pas de vibreur sur ce téléphone")) }
    var expected by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    fun go() {
        expected = Random.nextInt(2, 5); wrong = false
        val t = LongArray(expected * 2) { if (it % 2 == 0) (if (it == 0) 0L else 450L) else 250L }
        vib.vibrate(VibrationEffect.createWaveform(t, -1))
    }
    TestFrame("Vibreur", "Tenez le téléphone en main et comptez les vibrations.", { done(null) }) {
        Choice(if (expected == 0) "Lancer les vibrations" else "Recommencer", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) { go() }
        if (expected > 0) {
            CountQuestion("Combien de vibrations ?", expected) { a ->
                when {
                    a == expected -> done(Result(Status.OK, "$expected vibrations ressenties"))
                    a == 0 -> done(Result(Status.KO, "Aucune vibration ressentie"))
                    else -> wrong = true
                }
            }
            if (wrong) Text("Pas le bon nombre : recommencez en tenant bien le téléphone.", color = Red, fontSize = 14.sp)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MicTest(done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableIntStateOf(0) } // 0 prêt, 1 enregistre, 2 relit, 3 question
    var level by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(-100f) }
    var floor by remember { mutableFloatStateOf(0f) }
    fun go() {
        phase = 1; peak = -100f; floor = 0f
        scope.launch(Dispatchers.IO) {
            val rate = 44100
            val buf = ShortArray(rate * 3)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                withContext(Dispatchers.Main) { done(Result(Status.KO, "Le micro ne s'ouvre pas")) }; return@launch
            }
            rec.startRecording()
            var off = 0
            val chunk = rate / 10
            while (off < buf.size) {
                val n = rec.read(buf, off, min(chunk, buf.size - off))
                if (n <= 0) break
                var s = 0.0
                for (i in off until off + n) s += buf[i].toDouble() * buf[i]
                val db = (10 * log10(max(s / n, 1.0) / (32768.0 * 32768.0))).toFloat()
                level = ((db + 70) / 70).coerceIn(0f, 1f)
                peak = max(peak, db)
                floor = if (floor == 0f) db else min(floor, db)
                off += n
            }
            rec.stop(); rec.release()
            withContext(Dispatchers.Main) { phase = 2 }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(off * 2).build()
            track.write(buf, 0, off); track.play()
            delay(off * 1000L / rate + 200)
            track.release()
            withContext(Dispatchers.Main) { level = 0f; phase = 3 }
        }
    }
    TestFrame("Micro", "Appuyez, puis parlez normalement pendant 3 secondes. L'enregistrement est ensuite rejoué.", { done(null) }) {
        Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).background(Card2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level).clip(RoundedCornerShape(13.dp)).background(Orange))
        }
        Choice(
            when (phase) { 1 -> "Parlez maintenant…"; 2 -> "Lecture de l'enregistrement…"; 0 -> "Enregistrer 3 secondes"; else -> "Recommencer" },
            Orange, Color(0xFF1A1206), Modifier.fillMaxWidth(),
        ) { if (phase == 0 || phase == 3) go() }
        if (phase == 3) {
            val range = (peak - floor).roundToInt()
            if (range < 15) Text("Le micro a capté très peu de différence ($range dB) : parlez plus fort ou plus près.", color = Red, fontSize = 14.sp)
            YesNo("Avez-vous entendu votre voix clairement ?", "Oui, clairement", "Non ou grésille",
                onYes = { done(Result(if (range >= 15) Status.OK else Status.KO, "Voix enregistrée et rejouée · écart de $range dB entre silence et parole")) },
                onNo = { done(Result(Status.KO, "Enregistrement absent, faible ou déformé")) })
        }
    }
}

@Composable
fun VolumeKeysTest(done: (Result?) -> Unit) {
    var up by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { Keys.capture = true; onDispose { Keys.capture = false } }
    LaunchedEffect(Unit) {
        Keys.flow.collect { if (it == KeyEvent.KEYCODE_VOLUME_UP) up = true else if (it == KeyEvent.KEYCODE_VOLUME_DOWN) down = true }
    }
    LaunchedEffect(up, down) { if (up && down) { delay(400); done(Result(Status.OK, "Boutons volume + et − fonctionnels")) } }
    TestFrame("Boutons de volume", "Appuyez une fois sur volume + puis une fois sur volume −.", { done(null) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyBox("Volume +", up, Modifier.weight(1f)); KeyBox("Volume −", down, Modifier.weight(1f))
        }
        TextButton(onClick = {
            done(Result(Status.KO, "Bouton ${if (!up) "volume +" else ""}${if (!up && !down) " et " else ""}${if (!down) "volume −" else ""} sans réponse"))
        }) { Text("Un bouton ne répond pas", color = Red) }
    }
}

@Composable
private fun KeyBox(label: String, ok: Boolean, modifier: Modifier) {
    Column(
        modifier.height(120.dp).clip(RoundedCornerShape(18.dp)).background(if (ok) Green.copy(alpha = 0.2f) else Card2),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(if (ok) "✓" else "…", fontSize = 34.sp, color = if (ok) Green else Muted)
        Text(label, color = TextC, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- Caméras et flash

@Composable
fun CameraTest(front: Boolean, done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(front) {
        val fut = ProcessCameraProvider.getInstance(ctx)
        fut.addListener({
            try {
                val prov = fut.get()
                val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!prov.hasCamera(sel)) { error = "Aucune caméra ${if (front) "avant" else "arrière"} trouvée"; return@addListener }
                prov.unbindAll()
                val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                prov.bindToLifecycle(owner, sel, p)
            } catch (e: Exception) {
                error = "La caméra ne démarre pas (${e.javaClass.simpleName})"
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { try { fut.get().unbindAll() } catch (_: Exception) {} }
    }
    val name = if (front) "Caméra avant" else "Caméra arrière"
    TestFrame(name, "Visez un texte ou un objet proche puis lointain : l'image doit être nette, sans tache ni ligne.", { done(null) }) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
            AndroidView({ preview }, Modifier.fillMaxSize())
            error?.let { Text(it, color = Red, modifier = Modifier.align(Alignment.Center).padding(20.dp), textAlign = TextAlign.Center) }
        }
        if (error != null) {
            Choice("Marquer en défaut", Red, Color(0xFF1A0607), Modifier.fillMaxWidth()) { done(Result(Status.KO, error!!)) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("Image nette", Green, Color(0xFF06140E), Modifier.weight(1f)) { done(Result(Status.OK, "Image nette, sans défaut (vérifié par le testeur)")) }
                Choice("Problème", Red, Color(0xFF1A0607), Modifier.weight(1f)) { done(Result(Status.KO, "Image floue, tachée ou défectueuse (signalé par le testeur)")) }
            }
        }
    }
}

@Composable
fun FlashTest(done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val camId = remember { AutoTests.hasFlash(ctx) }
    LaunchedEffect(Unit) { if (camId == null) done(Result(Status.NA, "Pas de flash sur ce téléphone")) }
    var expected by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val cm = remember { ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    fun go() {
        val id = camId ?: return
        expected = Random.nextInt(2, 5); wrong = false; running = true
        scope.launch {
            try {
                repeat(expected) { cm.setTorchMode(id, true); delay(300); cm.setTorchMode(id, false); delay(450) }
            } catch (e: Exception) { done(Result(Status.KO, "Le flash ne s'allume pas")) }
            running = false
        }
    }
    DisposableEffect(Unit) { onDispose { camId?.let { try { cm.setTorchMode(it, false) } catch (_: Exception) {} } } }
    TestFrame("Flash", "Regardez le dos du téléphone et comptez les éclairs.", { done(null) }) {
        Choice(if (running) "Clignotement…" else if (expected == 0) "Faire clignoter" else "Recommencer", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) { if (!running) go() }
        if (expected > 0 && !running) {
            CountQuestion("Combien d'éclairs ?", expected) { a ->
                when {
                    a == expected -> done(Result(Status.OK, "Flash fonctionnel ($expected éclairs)"))
                    a == 0 -> done(Result(Status.KO, "Le flash ne s'allume pas"))
                    else -> wrong = true
                }
            }
            if (wrong) Text("Pas le bon nombre : recommencez.", color = Red, fontSize = 14.sp)
        }
    }
}

// ---------------------------------------------------------------- Capteurs

private class SensorProbe(val type: Int, val name: String, val unit: String) {
    var present = false
    var value by mutableStateOf("")
    var ok by mutableStateOf(false)
    var first: FloatArray? = null
}

@Composable
fun SensorsTest(done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val probes = remember {
        listOf(
            SensorProbe(Sensor.TYPE_ACCELEROMETER, "Accéléromètre", "m/s²"),
            SensorProbe(Sensor.TYPE_GYROSCOPE, "Gyroscope", "rad/s"),
            SensorProbe(Sensor.TYPE_MAGNETIC_FIELD, "Boussole (magnétomètre)", "µT"),
            SensorProbe(Sensor.TYPE_PROXIMITY, "Proximité", "cm"),
            SensorProbe(Sensor.TYPE_LIGHT, "Luminosité", "lux"),
            SensorProbe(Sensor.TYPE_PRESSURE, "Baromètre", "hPa"),
        )
    }
    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val p = probes.firstOrNull { it.type == e.sensor.type } ?: return
                val v = e.values
                p.value = when (p.type) {
                    Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD ->
                        String.format(Locale.FRANCE, "%.1f", sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
                    else -> String.format(Locale.FRANCE, "%.0f", v[0])
                }
                val f = p.first
                if (f == null) { p.first = v.copyOf(); if (p.type == Sensor.TYPE_PRESSURE && v[0] > 300) p.ok = true; return }
                val diff = (0 until min(3, v.size)).maxOf { abs(v[it] - f[it]) }
                val need = when (p.type) {
                    Sensor.TYPE_ACCELEROMETER -> 2f; Sensor.TYPE_GYROSCOPE -> 0.6f; Sensor.TYPE_MAGNETIC_FIELD -> 8f
                    Sensor.TYPE_PROXIMITY -> 0.5f; Sensor.TYPE_LIGHT -> max(5f, f[0] * 0.5f); else -> 1000f
                }
                if (diff >= need) p.ok = true
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        probes.forEach { p ->
            sm.getDefaultSensor(p.type)?.let { s -> p.present = true; sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI) }
        }
        onDispose { sm.unregisterListener(listener) }
    }
    TestFrame("Capteurs", "Bougez et tournez le téléphone, puis couvrez le haut de l'écran avec la main (proximité et luminosité).", { done(null) }) {
        Panel {
            probes.forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(when { !p.present -> LineC; p.ok -> Green; else -> Card2 }),
                        contentAlignment = Alignment.Center) {
                        Text(when { !p.present -> "–"; p.ok -> "✓"; else -> "…" }, color = if (p.ok) Color(0xFF06140E) else Muted, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(p.name, color = TextC, modifier = Modifier.weight(1f))
                    Text(if (!p.present) "absent" else if (p.value.isEmpty()) "…" else "${p.value} ${p.unit}", color = Muted, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Choice("Terminer", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) {
            val present = probes.filter { it.present }
            val bad = present.filter { !it.ok }
            val absent = probes.filter { !it.present }.joinToString(", ") { it.name.substringBefore(" (").lowercase() }
            val okTxt = present.filter { it.ok }.joinToString(", ") { it.name.substringBefore(" (").lowercase() }
            val detail = buildString {
                if (okTxt.isNotEmpty()) append("Fonctionnent : $okTxt")
                if (bad.isNotEmpty()) append("${if (isNotEmpty()) " · " else ""}Sans réaction : ${bad.joinToString(", ") { it.name.substringBefore(" (").lowercase() }}")
                if (absent.isNotEmpty()) append(" · Absents de ce modèle : $absent")
            }
            done(Result(if (bad.isEmpty() && present.isNotEmpty()) Status.OK else Status.KO, detail))
        }
    }
}

// ---------------------------------------------------------------- GPS, charge, biométrie

@SuppressLint("MissingPermission")
@Composable
fun GpsTest(done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var seen by remember { mutableIntStateOf(0) }
    var used by remember { mutableIntStateOf(0) }
    var fix by remember { mutableStateOf<Location?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    val enabled = remember { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
    DisposableEffect(Unit) {
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                seen = status.satelliteCount
                used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            }
        }
        val ll = LocationListener { fix = it }
        val main = Handler(Looper.getMainLooper())
        try {
            lm.registerGnssStatusCallback(cb, main)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ll, Looper.getMainLooper())
        } catch (_: Exception) { }
        onDispose { try { lm.unregisterGnssStatusCallback(cb); lm.removeUpdates(ll) } catch (_: Exception) { } }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    TestFrame("GPS", "Placez-vous près d'une fenêtre ou dehors. La première position peut prendre jusqu'à une minute.", { done(null) }) {
        if (!enabled) Text("La localisation est désactivée : activez-la dans les réglages rapides.", color = Red)
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Satellites vus", "$seen", Modifier.weight(1f))
                Stat("Utilisés", "$used", Modifier.weight(1f))
                Stat("Temps", "$seconds s", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(fix?.let { "Position trouvée, précision ${it.accuracy.roundToInt()} m" } ?: "Recherche de la position…",
                color = if (fix != null) Green else Muted, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Choice("Terminer", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) {
            val f = fix
            done(when {
                f != null -> Result(Status.OK, "Position trouvée en ${seconds} s, précision ${f.accuracy.roundToInt()} m · $used/$seen satellites")
                seen > 0 -> Result(Status.OK, "$seen satellites captés, pas encore de position (test fait à l'intérieur ?)")
                else -> Result(Status.KO, "Aucun satellite capté en $seconds s${if (!enabled) " (localisation désactivée)" else ""}")
            })
        }
    }
}

@Composable
fun ChargeTest(done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    var plugged by remember { mutableIntStateOf(0) }
    var charging by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        ctx.registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { ctx.unregisterReceiver(rx) }
    }
    LaunchedEffect(plugged) {
        if (plugged != 0) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            repeat(4) { delay(800); current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
        }
    }
    val type = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "chargeur secteur"; BatteryManager.BATTERY_PLUGGED_USB -> "port USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "chargeur sans fil"; 0 -> ""; else -> "chargeur"
    }
    // Selon les fabricants, le courant est donné en µA ou en mA.
    val ma = if (abs(current) > 20000) abs(current) / 1000 else abs(current)
    TestFrame("Port de charge", "Branchez un chargeur ou un câble USB. Bougez doucement la prise : la charge ne doit pas se couper.", { done(null) }) {
        Panel {
            Text(if (plugged == 0) "En attente du branchement…" else "Branché sur $type", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (plugged == 0) Muted else Green)
            if (plugged != 0) Text(
                (if (charging) "En charge" else "Branché mais ne charge pas") + if (ma > 0) " · environ $ma mA" else "",
                color = if (charging) TextC else Red, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (plugged != 0) Choice("Valider", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) {
            done(if (charging) Result(Status.OK, "Charge détectée sur $type${if (ma > 0) " · environ $ma mA" else ""}")
            else Result(Status.KO, "Branché sur $type mais la batterie ne charge pas"))
        }
        TextButton(onClick = { done(Result(Status.KO, "Branchement non détecté")) }) { Text("Le branchement n'est pas détecté", color = Red) }
    }
}

@Composable
fun BiometricTest(done: (Result?) -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Appuyez pour lancer le test.") }
    fun go() {
        val act = ctx.findActivity() ?: return
        val ex = ContextCompat.getMainExecutor(act)
        try {
            val prompt = BiometricPrompt.Builder(act)
                .setTitle("Test du capteur biométrique")
                .setSubtitle("Posez le doigt enregistré ou regardez l'écran")
                .setNegativeButton("Annuler", ex) { _, _ -> status = "Test annulé." }
                .build()
            prompt.authenticate(CancellationSignal(), ex, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    done(Result(Status.OK, "Reconnaissance biométrique réussie"))
                }
                override fun onAuthenticationFailed() { status = "Non reconnu : réessayez." }
                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    when (code) {
                        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ->
                            done(Result(Status.NA, "Capteur présent mais aucune empreinte ni visage enregistré : à tester après enregistrement"))
                        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT -> done(Result(Status.NA, "Pas de capteur biométrique"))
                        BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE -> done(Result(Status.KO, "Capteur biométrique indisponible ou en panne"))
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> status = "Test annulé."
                        else -> status = msg?.toString() ?: "Erreur $code"
                    }
                }
            })
        } catch (e: Exception) {
            done(Result(Status.NA, "Test biométrique impossible sur ce téléphone"))
        }
    }
    TestFrame("Capteur biométrique", "Le téléphone doit avoir une empreinte ou un visage enregistré.", { done(null) }) {
        Panel { Text(status, color = TextC) }
        Choice("Lancer le test", Orange, Color(0xFF1A1206), Modifier.fillMaxWidth()) { go() }
        TextButton(onClick = { done(Result(Status.KO, "Empreinte ou visage jamais reconnu")) }) { Text("Le capteur ne reconnaît rien", color = Red) }
    }
}
