package fr.phoenixpro.sonometre

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Couleurs Phoenix Pro
val Bg = Color(0xFF0E1012)
val Card = Color(0xE6181B1E)
val Card2 = Color(0xFF202427)
val LineC = Color(0xFF2A2F35)
val TextC = Color(0xFFF1F3F4)
val Muted = Color(0xFF9AA1A8)
val Orange = Color(0xFFF08A1C)
val Blue = Color(0xFF6F9BFF)
val Red = Color(0xFFFF5A5F)
val Green = Color(0xFF5FD6A8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = Color(0xFF1A1206), secondary = Blue,
                    background = Bg, surface = Bg, onBackground = TextC, onSurface = TextC,
                    outline = LineC, surfaceVariant = Card2, onSurfaceVariant = Muted,
                ),
            ) { PhoenixSono() }
        }
    }
}

@Composable
fun PhoenixSono() {
    var tab by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<Long?>(null) }
    BackHandler(enabled = openId != null) { openId = null }

    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            val id = openId
            when {
                id != null -> SessionScreen(id, onBack = { openId = null })
                tab == 0 -> MeterScreen(onOpen = { openId = it })
                tab == 1 -> HistoryScreen(onOpen = { openId = it })
                else -> SettingsScreen()
            }
        }
        NavigationBar(containerColor = Card2, tonalElevation = 0.dp) {
            val items = listOf(
                Triple("Mesure", Icons.Filled.GraphicEq, 0),
                Triple("Historique", Icons.Filled.History, 1),
                Triple("Réglages", Icons.Filled.Tune, 2),
            )
            items.forEach { (label, icon, i) ->
                NavigationBarItem(
                    selected = tab == i && openId == null,
                    onClick = { tab = i; openId = null },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Orange, selectedTextColor = Orange, indicatorColor = Color(0x33F08A1C),
                        unselectedIconColor = Muted, unselectedTextColor = Muted,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- éléments communs

@Composable
fun Title(text: String, sub: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
        if (sub != null) Text(sub, fontSize = 14.sp, color = Muted)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
            .border(1.dp, LineC, RoundedCornerShape(18.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = TextC) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Card2).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, fontSize = 12.sp, color = Muted, maxLines = 1)
    }
}

@Composable
fun Gauge(level: Double, threshold: Int, active: Boolean, modifier: Modifier = Modifier) {
    val over = active && level >= threshold
    Box(modifier.fillMaxWidth().aspectRatio(1.45f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = size.minDimension * 0.075f
            val d = min(size.width, size.height * 1.35f) - stroke
            val topLeft = Offset((size.width - d) / 2, stroke / 2 + 4)
            val arcSize = Size(d, d)
            fun frac(v: Double) = ((v - 20) / 100).coerceIn(0.0, 1.0).toFloat()
            drawArc(LineC, 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Red.copy(alpha = 0.35f), 150f + 240f * frac(threshold.toDouble()), 240f * (1 - frac(threshold.toDouble())), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            if (active) drawArc(if (over) Red else Orange, 150f, max(0.5f, 240f * frac(level)), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 24.dp)) {
            Text(if (active) "${level.roundToInt()}" else "–", fontSize = 76.sp, fontWeight = FontWeight.Bold, color = if (over) Red else TextC)
            Text("dB(A)", fontSize = 16.sp, color = Muted)
        }
    }
}

@Composable
fun LevelChart(values: List<Float>, threshold: Int, modifier: Modifier = Modifier, color: Color = Orange) {
    Canvas(modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)).background(Card2)) {
        val lo = 20f; val hi = 110f
        fun y(v: Float) = size.height - (v.coerceIn(lo, hi) - lo) / (hi - lo) * size.height
        listOf(40f, 60f, 80f, 100f).forEach {
            drawLine(LineC, Offset(0f, y(it)), Offset(size.width, y(it)), 1f)
        }
        drawLine(Red, Offset(0f, y(threshold.toFloat())), Offset(size.width, y(threshold.toFloat())), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        if (values.size > 1) {
            val n = values.size
            val step = max(1, n / size.width.toInt().coerceAtLeast(1))
            val p = Path()
            var i = 0; var first = true
            while (i < n) {
                var m = 0f
                for (j in i until min(n, i + step)) m = max(m, values[j])
                val x = i.toFloat() / (n - 1) * size.width
                if (first) { p.moveTo(x, y(m)); first = false } else p.lineTo(x, y(m))
                i += step
            }
            drawPath(p, color, style = Stroke(3f))
        }
    }
}

fun hasMic(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------- Mesure

@Composable
fun MeterScreen(onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val live by Meter.state.collectAsState()
    var threshold by remember { mutableIntStateOf(Store.threshold) }
    var stopping by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(500) } }

    val perms = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) MeterService.start(ctx)
        else Toast.makeText(ctx, "Sans le micro, le sonomètre ne peut pas mesurer.", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(live.running) { if (!live.running) stopping = false }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Title(
            "Sonomètre",
            when {
                live.running && live.inEvent -> "Nuisance en cours d'enregistrement"
                live.running -> "Mesure en cours, même écran éteint"
                else -> "Phoenix Pro · pondération A"
            },
        )
        live.error?.let {
            Panel { Text(it, color = Red) }
        }
        Panel {
            Gauge(live.level, threshold, live.running)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Min", if (live.running) "${live.min.roundToInt()}" else "–", Modifier.weight(1f))
                Stat("Moyenne", if (live.running) "${live.leq.roundToInt()}" else "–", Modifier.weight(1f))
                Stat("Max", if (live.running) "${live.max.roundToInt()}" else "–", Modifier.weight(1f))
                Stat("Durée", if (live.running) fmtClock(now - live.startMs) else "–", Modifier.weight(1f))
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2 dernières minutes", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (live.running) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (live.inEvent) Red else Green))
                    Spacer(Modifier.width(6.dp))
                    Text("${live.events} événement${if (live.events > 1) "s" else ""}", fontSize = 13.sp,
                        color = if (live.events > 0) Orange else Muted)
                }
            }
            Spacer(Modifier.height(8.dp))
            LevelChart(live.history, threshold)
            Spacer(Modifier.height(12.dp))
            Text("Seuil de nuisance : $threshold dB(A)", fontSize = 14.sp, color = TextC)
            Slider(
                value = threshold.toFloat(), onValueChange = { threshold = it.roundToInt() },
                onValueChangeFinished = { Store.threshold = threshold },
                valueRange = 35f..90f, enabled = !live.running,
            )
            Text(
                if (live.running) "Le seuil se règle avant de démarrer." else "Au-dessus du seuil pendant ${Store.minDuration} s, l'appli horodate l'événement et garde l'extrait sonore.",
                fontSize = 12.sp, color = Muted,
            )
        }

        Button(
            onClick = {
                if (live.running) { stopping = true; MeterService.stop(ctx) }
                else if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) MeterService.start(ctx)
                else launcher.launch(perms)
            },
            enabled = !stopping,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (live.running) Red else Orange, contentColor = Color(0xFF1A1206)),
        ) {
            Icon(if (live.running) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (stopping) "Enregistrement…" else if (live.running) "Arrêter et enregistrer" else "Démarrer la mesure", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        if (!live.running && Meter.lastSessionId != 0L && !stopping) {
            OutlinedButton(
                onClick = { onOpen(Meter.lastSessionId) },
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp),
            ) { Text("Voir la mesure et le rapport", color = TextC) }
        }
        Text(
            "Posez le téléphone près de la source de bruit, micro dégagé. Pour une preuve solide, mesurez plusieurs fois, à des jours et heures différents.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

fun fmtClock(ms: Long): String {
    val s = max(0L, ms / 1000)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ---------------------------------------------------------------- Historique

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit) {
    val live by Meter.state.collectAsState()
    var sessions by remember { mutableStateOf<List<Session>?>(null) }
    LaunchedEffect(live.running) { sessions = withContext(Dispatchers.IO) { Store.list() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Title("Historique", "Vos mesures et leurs rapports")
        val list = sessions
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
            list.isEmpty() -> Panel { Text("Aucune mesure pour l'instant. Lancez une mesure depuis l'onglet Mesure.", color = Muted) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                            .border(1.dp, LineC, RoundedCornerShape(16.dp)).clickable { onOpen(s.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDate(s.start).dropLast(3), fontWeight = FontWeight.Bold, color = TextC)
                            Text(
                                listOf(fmtDuration(s.durationMs), s.place.ifBlank { null }).filterNotNull().joinToString(" · "),
                                fontSize = 13.sp, color = Muted, maxLines = 1,
                            )
                            Text(
                                "${s.events.size} événement${if (s.events.size > 1) "s" else ""}",
                                fontSize = 13.sp, color = if (s.events.isNotEmpty()) Orange else Muted,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${s.leq().roundToInt()}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextC)
                            Text("dB(A) moy.", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Détail d'une mesure

fun share(ctx: Context, files: List<File>, subject: String) {
    val auth = ctx.packageName + ".files"
    val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, auth, it) })
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uris[0])
    } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.putExtra(Intent.EXTRA_SUBJECT, subject)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "Partager le rapport"))
}

@Composable
fun SessionScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(id) { Store.load(id) }
    if (session == null) {
        Column(Modifier.padding(16.dp)) {
            Text("Mesure introuvable.", color = Muted)
            TextButton(onClick = onBack) { Text("Retour") }
        }
        return
    }
    var place by remember { mutableStateOf(session.place) }
    var nuisance by remember { mutableStateOf(session.nuisance) }
    var playing by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.release(); player[0] = null } }

    LaunchedEffect(place, nuisance) {
        delay(600)
        if (place != session.place || nuisance != session.nuisance) {
            session.place = place; session.nuisance = nuisance
            if (place.isNotBlank()) Store.lastPlace = place
            withContext(Dispatchers.IO) { Store.save(session) }
        }
    }

    fun play(clip: String) {
        player[0]?.release(); player[0] = null
        if (playing == clip) { playing = null; return }
        try {
            val mp = MediaPlayer()
            mp.setDataSource(File(Store.dir(session.id), clip).absolutePath)
            mp.setOnCompletionListener { playing = null }
            mp.prepare(); mp.start()
            player[0] = mp; playing = clip
        } catch (e: Exception) {
            Toast.makeText(ctx, "Lecture impossible", Toast.LENGTH_SHORT).show()
        }
    }

    fun report(withAudio: Boolean) {
        busy = true
        scope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) { Report.build(ctx, session) }
                val files = mutableListOf(pdf)
                if (withAudio) session.events.mapNotNull { it.clip }.forEach { files.add(File(Store.dir(session.id), it)) }
                share(ctx, files, "Rapport de mesure sonore du ${fmtDate(session.start).take(10)}")
            } catch (e: Exception) {
                Toast.makeText(ctx, "Rapport impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour", tint = TextC) }
            Title(fmtDate(session.start).dropLast(3), fmtDuration(session.durationMs))
        }
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Moyenne", fmtDb(session.leq()), Modifier.weight(1f))
                Stat("Fond L90", fmtDb(session.lp(90)), Modifier.weight(1f))
                Stat("Max", fmtDb(session.lmax), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("L10", fmtDb(session.lp(10)), Modifier.weight(1f))
                Stat("Événements", "${session.events.size}", Modifier.weight(1f), if (session.events.isNotEmpty()) Orange else TextC)
                Stat("Au-dessus", fmtDuration(session.secondsAbove() * 1000L), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            LevelChart(session.levels, session.threshold)
            Text("Niveau seconde par seconde · seuil ${session.threshold} dB(A) en pointillés", fontSize = 12.sp, color = Muted,
                modifier = Modifier.padding(top = 6.dp))
        }
        Panel {
            Text("Pour le rapport", fontWeight = FontWeight.Bold, color = TextC)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(place, { place = it }, label = { Text("Lieu de la mesure") },
                placeholder = { Text("Ex. chambre, 2e étage, 12 rue des Lilas") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(nuisance, { nuisance = it }, label = { Text("Nature de la nuisance") },
                placeholder = { Text("Ex. musique forte du logement du dessus") }, modifier = Modifier.fillMaxWidth())
        }
        Panel {
            Text("Événements", fontWeight = FontWeight.Bold, color = TextC)
            if (session.events.isEmpty()) Text("Aucun dépassement du seuil.", color = Muted, modifier = Modifier.padding(top = 6.dp))
            session.events.forEachIndexed { i, e ->
                val em = emergence(session, e)
                val limit = if (isDay(e.start)) 5.0 else 3.0
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${fmtTime(e.start)} · ${fmtDuration(max(1000L, e.durationMs))}", color = TextC, fontWeight = FontWeight.Medium)
                        Text(
                            "moy. ${fmtDb(e.leq)} · max ${fmtDb(e.lmax)} · émergence +${fmtDb(em)} dB (${if (isDay(e.start)) "jour" else "nuit"})",
                            fontSize = 12.sp, color = if (em > limit) Red else Muted,
                        )
                    }
                    val clip = e.clip
                    if (clip != null) {
                        IconButton(onClick = { play(clip) }) {
                            Icon(if (playing == clip) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = "Écouter", tint = Orange)
                        }
                    }
                }
            }
        }
        Button(
            onClick = { report(withAudio = false) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color(0xFF1A1206)),
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null); Spacer(Modifier.width(8.dp))
            Text(if (busy) "Préparation…" else "Partager le rapport PDF", fontWeight = FontWeight.Bold)
        }
        if (session.events.any { it.clip != null }) {
            OutlinedButton(
                onClick = { report(withAudio = true) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = TextC); Spacer(Modifier.width(8.dp))
                Text("Partager le rapport + les extraits audio", color = TextC)
            }
        }
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = Red); Spacer(Modifier.width(6.dp))
            Text("Supprimer cette mesure", color = Red)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer la mesure ?") },
            text = { Text("La mesure, ses extraits audio et ses données seront effacés. Les rapports déjà envoyés ne sont pas touchés.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; Store.delete(session.id); onBack() }) { Text("Supprimer", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
            containerColor = Card2,
        )
    }
}

// ---------------------------------------------------------------- Réglages

@Composable
fun SettingsScreen() {
    val live by Meter.state.collectAsState()
    var minDur by remember { mutableIntStateOf(Store.minDuration) }
    var pre by remember { mutableIntStateOf(Store.preSeconds) }
    var cal by remember { mutableFloatStateOf(Store.calibration) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Title("Réglages")
        Panel {
            Text("Calibrage du micro", fontWeight = FontWeight.Bold, color = TextC)
            Text(
                "Placez le téléphone à côté d'un sonomètre de référence (ou d'une source connue, par exemple 94 dB) et déplacez le curseur jusqu'à lire la même valeur.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (cal > 0) "+" else ""}${fmtDb(cal.toDouble())} dB", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Orange, modifier = Modifier.weight(1f))
                if (live.running) {
                    val shown = live.level + (cal - live.calibration)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${fmtDb(shown)} dB(A)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextC)
                        Text("lecture actuelle", fontSize = 12.sp, color = Muted)
                    }
                }
            }
            Slider(value = cal, onValueChange = { cal = (it * 2).roundToInt() / 2f }, onValueChangeFinished = { Store.calibration = cal }, valueRange = -30f..30f)
            Text(
                if (live.running) "Le nouveau calibrage s'appliquera à la prochaine mesure. La lecture ci-dessus en tient déjà compte."
                else "Lancez une mesure pour voir la lecture en direct pendant le réglage.",
                fontSize = 12.sp, color = Muted,
            )
            TextButton(onClick = { cal = 0f; Store.calibration = 0f }) { Text("Remettre à zéro") }
        }
        Panel {
            Text("Détection des nuisances", fontWeight = FontWeight.Bold, color = TextC)
            Spacer(Modifier.height(8.dp))
            Text("Durée minimale au-dessus du seuil : $minDur s", color = TextC, fontSize = 14.sp)
            Slider(value = minDur.toFloat(), onValueChange = { minDur = it.roundToInt() }, onValueChangeFinished = { Store.minDuration = minDur },
                valueRange = 1f..10f, steps = 8)
            Text("Son gardé avant l'événement : $pre s", color = TextC, fontSize = 14.sp)
            Slider(value = pre.toFloat(), onValueChange = { pre = it.roundToInt() }, onValueChangeFinished = { Store.preSeconds = pre },
                valueRange = 0f..10f, steps = 9)
            Text("Le seuil se règle dans l'onglet Mesure. Chaque extrait est limité à 2 minutes.", fontSize = 12.sp, color = Muted)
        }
        Panel {
            Text("Bon à savoir", fontWeight = FontWeight.Bold, color = TextC)
            Text(
                "• La mesure continue écran éteint ; une notification permet de l'arrêter.\n" +
                    "• Bruit de fond (L90) : niveau dépassé 90 % du temps. L'émergence d'un événement est sa différence avec ce bruit de fond.\n" +
                    "• Repère réglementaire pour les bruits de voisinage : émergence de 5 dB(A) le jour et 3 dB(A) la nuit (Code de la santé publique, art. R1336-7).\n" +
                    "• Le rapport est un relevé personnel : il appuie une démarche amiable mais ne remplace pas un constat de commissaire de justice.\n" +
                    "• Tout reste sur le téléphone : rien n'est envoyé sur Internet.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text("Phoenix Pro Sonomètre 1.0", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}
