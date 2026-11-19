package fr.phoenixpro.sommeil

import android.Manifest
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// Couleurs Phoenix Pro
val Bg = Color(0xFF0E1012)
val Card = Color(0xE6181B1E)
val Card2 = Color(0xFF202427)
val LineC = Color(0xFF2A2F35)
val TextC = Color(0xFFF1F3F4)
val Muted = Color(0xFF9AA1A8)
val Orange = Color(0xFFF08A1C)
val Blue = Color(0xFF6F9BFF)
val Purple = Color(0xFFB48CF0)
val Red = Color(0xFFFF5A5F)
val Green = Color(0xFF5FD6A8)
val OnOrange = Color(0xFF1A1206)

private val FR = Locale.FRANCE
fun hm(ms: Long): String = SimpleDateFormat("HH:mm", FR).format(Date(ms))
fun day(ms: Long): String = SimpleDateFormat("EEEE d MMMM", FR).format(Date(ms)).replaceFirstChar { it.uppercase() }
fun dur(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "%d h %02d".format(m / 60, m % 60) else "$m min" }
fun kindColor(k: Kind) = when (k) { Kind.SNORE -> Orange; Kind.TALK -> Purple; Kind.NOISE -> Blue }
fun snoreWord(p: Int) = when { p < 5 -> "Nuit calme"; p < 20 -> "Quelques ronflements"; p < 45 -> "Ronflements fréquents"; else -> "Ronflements très présents" }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = OnOrange, secondary = Blue,
                    background = Bg, surface = Bg, onBackground = TextC, onSurface = TextC,
                    outline = LineC, surfaceVariant = Card2, onSurfaceVariant = Muted,
                ),
            ) { SommeilRoot() }
        }
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
fun Header(title: String, sub: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
        Text(sub, fontSize = 14.sp, color = Muted)
    }
}

@Composable
fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Orange else Card2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(text, color = if (on) OnOrange else TextC, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SommeilRoot() {
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Long?>(null) }
    val live by Recorder.state.collectAsState()
    BackHandler(enabled = open != null) { open = null }

    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            val id = open
            when {
                id != null -> NightScreen(id, onBack = { open = null })
                tab == 0 -> TonightScreen(live, onOpen = { open = it })
                tab == 1 -> HistoryScreen(onOpen = { open = it })
                else -> SettingsScreen()
            }
        }
        if (!live.running) NavigationBar(containerColor = Card2, tonalElevation = 0.dp) {
            listOf(Triple("Cette nuit", Icons.Filled.Bedtime, 0), Triple("Historique", Icons.Filled.BarChart, 1), Triple("Réglages", Icons.Filled.Tune, 2))
                .forEach { (label, icon, i) ->
                    NavigationBarItem(
                        selected = tab == i && open == null, onClick = { tab = i; open = null },
                        icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Orange, selectedTextColor = Orange, indicatorColor = Color(0x33F08A1C),
                            unselectedIconColor = Muted, unselectedTextColor = Muted,
                        ),
                    )
                }
        }
    }
}

// ---------------------------------------------------------------- cette nuit

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(live: Live, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    var delayMin by remember { mutableIntStateOf(Store.delayMin) }
    var factors by remember { mutableStateOf(Store.lastFactors) }
    var stopping by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val perms = remember { buildList { add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) NightService.start(ctx)
        else Toast.makeText(ctx, "Sans le micro, l'appli ne peut rien enregistrer.", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(live.running) {
        if (!live.running && stopping) { stopping = false; if (Recorder.lastNightId != 0L) onOpen(Recorder.lastNightId) }
    }

    if (live.running || stopping) {
        // Écran de nuit, très sombre.
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(hm(now), fontSize = 72.sp, fontWeight = FontWeight.Light, color = Color(0xFF6B7178))
            Spacer(Modifier.height(8.dp))
            val waiting = now < live.listenFrom
            Text(
                when {
                    stopping -> "Préparation du résumé…"
                    waiting -> "L'écoute commence dans ${dur(live.listenFrom - now + 59_000)}"
                    else -> "Écoute depuis ${dur(now - live.listenFrom)} · ${live.events} son${if (live.events > 1) "s" else ""} enregistré${if (live.events > 1) "s" else ""}"
                },
                color = Color(0xFF6B7178), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { stopping = true; NightService.stop(ctx) }, enabled = !stopping,
                modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2116), contentColor = Orange),
            ) { Icon(Icons.Filled.WbSunny, null); Spacer(Modifier.width(8.dp)); Text("Réveil : voir ma nuit", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
            Spacer(Modifier.height(16.dp))
            Text("Vous pouvez éteindre l'écran. Laissez le téléphone branché, près de la tête de lit.", fontSize = 13.sp, color = Color(0xFF4A4F55), textAlign = TextAlign.Center)
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Cette nuit", "Phoenix Pro Sommeil · ronflements et bruits de la nuit")
        live.error?.let { Panel { Text(it, color = Red) } }
        Panel {
            Text("Temps pour s'endormir", fontWeight = FontWeight.SemiBold, color = TextC)
            Text("L'écoute commence après ce délai.", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 45).forEach { m -> Pill(if (m == 0) "Tout de suite" else "$m min", delayMin == m) { delayMin = m; Store.delayMin = m } }
            }
        }
        Panel {
            Text("Ce soir…", fontWeight = FontWeight.SemiBold, color = TextC)
            Text("Notez ce qui peut jouer : l'historique comparera vos nuits.", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FACTORS.forEach { f ->
                    Pill(f, f in factors) { factors = if (f in factors) factors - f else factors + f; Store.lastFactors = factors }
                }
            }
        }
        Button(
            onClick = {
                if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) NightService.start(ctx)
                else launcher.launch(perms)
            },
            modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange),
        ) { Icon(Icons.Filled.Bedtime, null); Spacer(Modifier.width(8.dp)); Text("Bonne nuit", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        if (Recorder.lastNightId != 0L) OutlinedButton(onClick = { onOpen(Recorder.lastNightId) }, modifier = Modifier.fillMaxWidth()) { Text("Voir la dernière nuit", color = TextC) }
        Text(
            "Posez le téléphone à moins d'un mètre de la tête, micro dégagé, et branchez-le. Les sons restent sur le téléphone.\n" +
                "Ce n'est pas un appareil médical : en cas de ronflements forts avec des pauses de respiration ou de grande fatigue la journée, parlez-en à un médecin.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

// ---------------------------------------------------------------- résumé d'une nuit

@Composable
fun NightChart(n: Night, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Card2)) {
        val total = max(1, n.minutes.size)
        if (n.minutes.size > 1) {
            val lo = (n.minutes.minOrNull() ?: 0f) - 3f; val hi = (n.minutes.maxOrNull() ?: 60f) + 10f
            val p = Path()
            n.minutes.forEachIndexed { i, v ->
                val x = i.toFloat() / (total - 1).coerceAtLeast(1) * size.width
                val y = size.height * 0.85f - (v - lo) / (hi - lo) * size.height * 0.7f
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, Muted.copy(alpha = 0.6f), style = Stroke(2.5f))
        }
        val span = (n.end - n.listenFrom).coerceAtLeast(1)
        n.events.forEach { e ->
            val x = ((e.start - n.listenFrom).toFloat() / span).coerceIn(0f, 1f) * size.width
            val h = if (e.kind == Kind.SNORE) 0.55f else 0.35f
            drawRect(kindColor(e.kind), Offset(x, size.height * (1 - h)), Size(3f, size.height * h))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NightScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val n = remember(id) { Store.load(id) }
    if (n == null) { Column(Modifier.padding(16.dp)) { Text("Nuit introuvable.", color = Muted); TextButton(onClick = onBack) { Text("Retour") } }; return }
    var factors by remember { mutableStateOf(n.factors.toSet()) }
    var note by remember { mutableStateOf(n.note) }
    var filter by remember { mutableStateOf<Kind?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var keepTick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.release() } }
    LaunchedEffect(factors, note, keepTick) {
        delay(500)
        n.factors.clear(); n.factors.addAll(factors); n.note = note
        withContext(Dispatchers.IO) { Store.save(n) }
    }
    fun play(clip: String) {
        player[0]?.release(); player[0] = null
        if (playing == clip) { playing = null; return }
        try {
            val mp = MediaPlayer(); mp.setDataSource(File(Store.dir(n.id), clip).absolutePath)
            mp.setOnCompletionListener { playing = null }; mp.prepare(); mp.start(); player[0] = mp; playing = clip
        } catch (e: Exception) { Toast.makeText(ctx, "Lecture impossible", Toast.LENGTH_SHORT).show() }
    }
    val pct = n.snorePct()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour", tint = TextC) }
            Column {
                Text("Nuit du ${day(n.start)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextC)
                Text("${hm(n.listenFrom)} → ${hm(n.end)} · ${dur(n.listenMs)} d'écoute", fontSize = 13.sp, color = Muted)
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).clip(CircleShape).border(8.dp, if (pct < 20) Green else Orange, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$pct %", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextC)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(snoreWord(pct), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextC)
                    Text("de la nuit avec ronflements (${n.snoreMinutes()} min)", fontSize = 13.sp, color = Muted)
                }
            }
            Spacer(Modifier.height(14.dp))
            NightChart(n)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Kind.entries.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor(k))); Spacer(Modifier.width(5.dp))
                        Text("${k.label} ${n.events.count { it.kind == k }}", fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
        Panel {
            Text("Les sons de la nuit", fontWeight = FontWeight.Bold, color = TextC)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Tous", filter == null) { filter = null }
                Kind.entries.forEach { k -> Pill(k.label.substringBefore(" "), filter == k) { filter = if (filter == k) null else k } }
            }
            val shown = n.events.filter { filter == null || it.kind == filter }
            if (shown.isEmpty()) Text("Aucun son de ce type.", color = Muted)
            key(keepTick) {
                shown.take(120).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor(e.kind)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${hm(e.start)} · ${e.kind.label}", color = TextC, fontSize = 14.sp)
                            Text("${((e.end - e.start) / 1000).coerceAtLeast(1)} s", fontSize = 12.sp, color = Muted)
                        }
                        val c = e.clip
                        if (c != null) {
                            IconButton(onClick = { e.keep = !e.keep; keepTick++ }) {
                                Icon(if (e.keep) Icons.Filled.Star else Icons.Filled.StarBorder, "Garder", tint = if (e.keep) Orange else Muted)
                            }
                            IconButton(onClick = { play(c) }) { Icon(if (playing == c) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Écouter", tint = Orange) }
                        } else Text("extrait effacé", fontSize = 11.sp, color = Muted)
                    }
                }
            }
            Text("L'étoile garde un extrait au-delà de ${Store.keepDays} jours. Le tri ronflement / bruit est automatique et peut se tromper.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        Panel {
            Text("Ce soir-là…", fontWeight = FontWeight.Bold, color = TextC)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FACTORS.forEach { f -> Pill(f, f in factors) { factors = if (f in factors) factors - f else factors + f } }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = { confirm = true }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)) {
            Icon(Icons.Filled.Delete, null, tint = Red); Spacer(Modifier.width(6.dp)); Text("Supprimer cette nuit", color = Red)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Card2,
        title = { Text("Supprimer cette nuit ?") }, text = { Text("Le résumé et tous les extraits seront effacés.") },
        confirmButton = { TextButton(onClick = { confirm = false; Store.delete(n.id); onBack() }) { Text("Supprimer", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- historique

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit) {
    var nights by remember { mutableStateOf<List<Night>?>(null) }
    LaunchedEffect(Unit) { nights = withContext(Dispatchers.IO) { Store.list() } }
    val list = nights
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("Historique", "Vos nuits et ce qui les influence")
        Spacer(Modifier.height(10.dp))
        when {
            list == null -> CircularProgressIndicator(color = Orange)
            list.isEmpty() -> Panel { Text("Aucune nuit enregistrée pour l'instant.", color = Muted) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Panel {
                        Text("Ronflements, ${minOf(14, list.size)} dernières nuits", fontWeight = FontWeight.Bold, color = TextC)
                        Spacer(Modifier.height(10.dp))
                        val last = list.take(14).reversed()
                        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                            last.forEach { n ->
                                val p = n.snorePct()
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().height((6 + p * 0.9f).dp).clip(RoundedCornerShape(4.dp)).background(if (p < 20) Green else Orange))
                                    Text(SimpleDateFormat("d", FR).format(Date(n.start)), fontSize = 10.sp, color = Muted)
                                }
                            }
                        }
                    }
                }
                item { FactorsPanel(list) }
                items(list, key = { it.id }) { n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).clickable { onOpen(n.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(day(n.start), color = TextC, fontWeight = FontWeight.SemiBold)
                            Text("${dur(n.listenMs)} · ${n.events.size} sons" + if (n.factors.isNotEmpty()) " · ${n.factors.joinToString(", ").lowercase()}" else "",
                                fontSize = 12.sp, color = Muted, maxLines = 1)
                        }
                        Text("${n.snorePct()} %", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (n.snorePct() < 20) Green else Orange)
                    }
                }
            }
        }
    }
}

@Composable
fun FactorsPanel(list: List<Night>) {
    val rows = FACTORS.mapNotNull { f ->
        val with = list.filter { f in it.factors }; val without = list.filter { f !in it.factors }
        if (with.size >= 2 && without.size >= 2) Triple(f, with.map { it.snorePct() }.average().toInt(), without.map { it.snorePct() }.average().toInt()) else null
    }
    Panel {
        Text("Ce qui change vos nuits", fontWeight = FontWeight.Bold, color = TextC)
        if (rows.isEmpty()) Text("Notez les facteurs du soir : la comparaison apparaît dès 2 nuits avec et 2 nuits sans.", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        rows.sortedByDescending { it.second - it.third }.forEach { (f, w, wo) ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f, color = TextC, modifier = Modifier.weight(1f))
                Text("$w % avec · $wo % sans", fontSize = 13.sp, color = if (w - wo >= 10) Orange else Muted)
            }
        }
    }
}

// ---------------------------------------------------------------- réglages

@Composable
fun SettingsScreen() {
    var margin by remember { mutableIntStateOf(Store.margin) }
    var keep by remember { mutableIntStateOf(Store.keepDays) }
    var size by remember { mutableStateOf("…") }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { size = withContext(Dispatchers.IO) { "%.0f Mo".format(Store.root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1e6) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Réglages", "Sensibilité et stockage")
        Panel {
            Text("Sensibilité", fontWeight = FontWeight.SemiBold, color = TextC)
            Text(
                when { margin <= 6 -> "Très sensible : enregistre aussi les petits bruits"; margin <= 10 -> "Normale"; else -> "Peu sensible : seulement les bruits forts" },
                fontSize = 13.sp, color = Muted,
            )
            Slider(value = (16 - margin).toFloat(), onValueChange = { margin = 16 - it.toInt() }, onValueChangeFinished = { Store.margin = margin }, valueRange = 1f..11f, steps = 9)
        }
        Panel {
            Text("Garder les extraits", fontWeight = FontWeight.SemiBold, color = TextC)
            Text("Au-delà, les sons sont effacés mais les résumés restent. Les extraits étoilés sont toujours gardés.", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30, 90).forEach { d -> Pill("$d j", keep == d) { keep = d; Store.keepDays = d } }
            }
            Spacer(Modifier.height(10.dp))
            Text("Espace utilisé : $size", fontSize = 13.sp, color = Muted)
            TextButton(onClick = { confirm = true }) { Text("Effacer tous les extraits non étoilés", color = Red) }
        }
        Panel {
            Text("Comment ça marche", fontWeight = FontWeight.SemiBold, color = TextC)
            Text(
                "L'appli mesure le bruit de fond de la chambre et n'enregistre que les sons qui le dépassent nettement. " +
                    "Un ronflement est reconnu à son son grave qui se répète toutes les quelques secondes ; " +
                    "les autres sons sont classés en paroles / sons aigus ou bruits. Ce tri automatique peut se tromper : réécoutez les extraits.\n\n" +
                    "Phoenix Pro Sommeil n'est pas un appareil médical et ne détecte pas l'apnée du sommeil.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Card2,
        title = { Text("Effacer les extraits ?") }, text = { Text("Les résumés des nuits sont conservés.") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                Store.list().forEach { n ->
                    n.events.forEach { e -> val c = e.clip; if (c != null && !e.keep) { File(Store.dir(n.id), c).delete(); e.clip = null } }
                    Store.save(n)
                }
                size = "0 Mo"
            }) { Text("Effacer", color = Red) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
    )
}
