package fr.phoenixpro.testeur

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            ) { TesteurRoot() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (Keys.capture && (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Keys.flow.tryEmit(k)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

// ---------------------------------------------------------------- éléments communs

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

/** Autorisations nécessaires avant de lancer un test. */
fun permsFor(id: String): Array<String> = when (id) {
    "cam_arriere", "cam_avant" -> arrayOf(Manifest.permission.CAMERA)
    "micro" -> arrayOf(Manifest.permission.RECORD_AUDIO)
    "gps" -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    else -> emptyArray()
}

@Composable
fun TestHost(id: String, done: (Result?) -> Unit) {
    when (id) {
        "ecran" -> ScreenTest(done)
        "tactile" -> TouchTest(done)
        "multitouch" -> MultiTouchTest(done)
        "hp" -> BeepTest(earpiece = false, done)
        "ecouteur" -> BeepTest(earpiece = true, done)
        "micro" -> MicTest(done)
        "vibreur" -> VibrationTest(done)
        "volume" -> VolumeKeysTest(done)
        "cam_arriere" -> CameraTest(front = false, done)
        "cam_avant" -> CameraTest(front = true, done)
        "flash" -> FlashTest(done)
        "capteurs" -> SensorsTest(done)
        "gps" -> GpsTest(done)
        "charge" -> ChargeTest(done)
        "empreinte" -> BiometricTest(done)
        else -> LaunchedEffect(id) { done(null) }
    }
}

fun share(ctx: Context, f: java.io.File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Rapport de test du téléphone")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Envoyer le rapport"))
}

// ---------------------------------------------------------------- écran principal

@Composable
fun TesteurRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<String?>(null) }
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) } // force le rafraîchissement après chaque test
    var confirmReset by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (Store.results.isEmpty()) {
            AutoTests.runAll(ctx)
            version++
        }
    }

    var pendingId by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val id = pendingId ?: return@rememberLauncherForActivityResult
        pendingId = null
        if (res.values.any { it }) running = id
        else { Store.put(id, Result(Status.NA, "Autorisation refusée : test non fait")); version++; queue = queue.drop(1) }
    }

    fun start(id: String) {
        val t = TESTS.first { it.id == id }
        if (t.kind == Kind.AUTO) {
            AutoTests.run(ctx, id)?.let { Store.put(id, it) }; version++
            return
        }
        val perms = permsFor(id)
        if (perms.isEmpty() || perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) running = id
        else { pendingId = id; permLauncher.launch(perms) }
    }

    fun finished(id: String, r: Result?) {
        running = null
        if (r != null) Store.put(id, r)
        version++
        if (queue.isNotEmpty() && queue.first() == id) {
            queue = if (r == null) emptyList() else queue.drop(1)
            queue.firstOrNull()?.let { start(it) }
        }
    }

    BackHandler(enabled = running != null) { running?.let { finished(it, null) } }

    val current = running
    if (current != null) {
        TestHost(current) { r -> finished(current, r) }
        return
    }

    val tick = version // relit les résultats après chaque test
    val results = if (tick >= 0) Store.results.toMap() else emptyMap()
    val ok = TESTS.count { results[it.id]?.status == Status.OK }
    val ko = TESTS.count { results[it.id]?.status == Status.KO }
    val done = TESTS.count { results.containsKey(it.id) }

    var model by remember { mutableStateOf(Store.model.ifBlank { deviceName() }) }
    var imei by remember { mutableStateOf(Store.imei) }
    var note by remember { mutableStateOf(Store.note) }

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            Text("Testeur", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
            Text("Phoenix Pro · contrôle avant achat ou vente", fontSize = 14.sp, color = Muted)
        }
        Panel {
            Text("$done test${if (done > 1) "s" else ""} sur ${TESTS.size}", color = Muted, fontSize = 13.sp)
            Text(
                when { done == 0 -> "À tester"; ko > 0 -> "$ko défaut${if (ko > 1) "s" else ""} trouvé${if (ko > 1) "s" else ""}"; else -> "$ok réussi${if (ok > 1) "s" else ""}, aucun défaut" },
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (ko > 0) Red else if (done > 0) Green else TextC,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Card2)) {
                TESTS.forEach { t ->
                    val c = when (results[t.id]?.status) { Status.OK -> Green; Status.KO -> Red; Status.NA -> Muted.copy(alpha = 0.5f); null -> Color.Transparent }
                    Box(Modifier.weight(1f).fillMaxHeight().background(c))
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val todo = TESTS.filter { it.kind == Kind.GUIDED && !results.containsKey(it.id) }.map { it.id }
                    if (todo.isEmpty()) Toast.makeText(ctx, "Tous les tests sont faits.", Toast.LENGTH_SHORT).show()
                    else { queue = todo; start(todo.first()) }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color(0xFF1A1206)),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp))
                Text(if (done > 3) "Continuer les tests" else "Lancer tous les tests", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Panel {
            Text("Pour le rapport", fontWeight = FontWeight.Bold, color = TextC)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(model, { model = it; Store.model = it }, label = { Text("Modèle (avec la capacité)") },
                placeholder = { Text("Ex. Pixel 9 Pro 256 Go") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(imei, { imei = it; Store.imei = it }, label = { Text("IMEI (composez *#06#)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(note, { note = it; Store.note = it }, label = { Text("État visible") },
                placeholder = { Text("Ex. micro-rayures au dos, écran impeccable, avec boîte") }, modifier = Modifier.fillMaxWidth())
        }

        TESTS.groupBy { it.group }.forEach { (group, tests) ->
            Panel {
                Text(group, fontWeight = FontWeight.Bold, color = TextC)
                tests.forEach { t ->
                    val r = results[t.id]
                    Row(
                        Modifier.fillMaxWidth().clickable { start(t.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(
                                when (r?.status) { Status.OK -> Green; Status.KO -> Red; Status.NA -> LineC; null -> Card2 }
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when (r?.status) { Status.OK -> "✓"; Status.KO -> "✕"; Status.NA -> "–"; null -> "" },
                                color = if (r?.status == Status.NA) Muted else Color(0xFF0E1012), fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = TextC, fontWeight = FontWeight.Medium)
                            Text(r?.detail ?: t.desc, fontSize = 12.sp, color = if (r?.status == Status.KO) Red else Muted, maxLines = 3)
                        }
                        Text(if (r == null) "Tester" else "Refaire", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val f = withContext(Dispatchers.IO) { Report.build(ctx) }
                        share(ctx, f)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Rapport impossible : ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            },
            enabled = !busy && done > 0,
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color(0xFF1A1206)),
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null); Spacer(Modifier.width(8.dp))
            Text(if (busy) "Préparation…" else "Envoyer le rapport PDF", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { confirmReset = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = Muted); Spacer(Modifier.width(6.dp))
            Text("Nouveau test (tout effacer)", color = Muted)
        }
        Text(
            "Faites les tests devant l'acheteur ou le vendeur. Pour le son, le vibreur et le flash, il faut compter un nombre tiré au hasard : impossible de répondre au hasard.",
            fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Tout effacer ?") },
            text = { Text("Les résultats, l'IMEI et l'état visible seront effacés pour tester un autre téléphone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    Store.reset(); imei = ""; note = ""
                    AutoTests.runAll(ctx)
                    version++
                }) { Text("Effacer", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuler") } },
            containerColor = Card2,
        )
    }
}
