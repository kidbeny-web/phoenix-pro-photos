package fr.phoenixpro.cam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
val OnOrange = Color(0xFF1A1206)

private val FR = Locale.FRANCE
fun when_(ms: Long): String = SimpleDateFormat("EEE d MMM · HH:mm:ss", FR).format(Date(ms))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = OnOrange, secondary = Blue,
                    background = Bg, surface = Bg, onBackground = TextC, onSurface = TextC,
                    outline = LineC, surfaceVariant = Card2, onSurfaceVariant = Muted,
                ),
            ) { CamRoot() }
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
fun Header(title: String, sub: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
            Text(sub, fontSize = 14.sp, color = Muted)
        }
        action?.invoke()
    }
}

@Composable
fun BigButton(text: String, color: Color = Orange, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = OnOrange)) { Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
fun Frame(bmp: Bitmap?, modifier: Modifier = Modifier, placeholder: String = "Connexion…") {
    Box(modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text(placeholder, color = Muted)
    }
}

@Composable
fun CamRoot() {
    var role by remember { mutableStateOf(Prefs.role) }
    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        when (role) {
            "camera" -> CameraMode(onSwitch = { role = ""; Prefs.role = "" })
            "viewer" -> ViewerMode(onSwitch = { role = ""; Prefs.role = "" })
            else -> RoleChoice { role = it; Prefs.role = it }
        }
    }
}

@Composable
fun RoleChoice(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Phoenix Pro Cam", "Votre vieux téléphone devient une caméra de surveillance")
        RoleCard(Icons.Filled.Videocam, "Ce téléphone est la caméra", "À poser dans la pièce à surveiller, branché sur secteur.") { onPick("camera") }
        RoleCard(Icons.Filled.Smartphone, "Ce téléphone regarde les caméras", "Votre téléphone de tous les jours : direct, passages enregistrés, alertes.") { onPick("viewer") }
        Text("Les deux téléphones doivent être sur le même Wi-Fi. Aucune image ne sort de chez vous.", fontSize = 13.sp, color = Muted)
    }
}

@Composable
fun RoleCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).clickable(onClick = onClick).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x22F08A1C)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Orange, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = TextC, fontSize = 17.sp)
            Text(text, color = Muted, fontSize = 13.sp)
        }
    }
}

// ================================================================ MODE CAMÉRA

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraMode(onSwitch: () -> Unit) {
    val ctx = LocalContext.current
    val status by CamState.status.collectAsState()
    val frame by CamState.frame.collectAsState()
    var name by remember { mutableStateOf(Prefs.camName) }
    var front by remember { mutableStateOf(Prefs.front) }
    var sens by remember { mutableIntStateOf(Prefs.sensitivity) }
    var code by remember { mutableStateOf(Prefs.code) }
    var dark by remember { mutableStateOf(false) }
    val perms = remember { buildList { add(Manifest.permission.CAMERA); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) CamService.start(ctx) else Toast.makeText(ctx, "La caméra est indispensable.", Toast.LENGTH_LONG).show()
    }
    val activity = ctx as? ComponentActivity
    DisposableEffect(status.running) {
        if (status.running) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    if (dark && status.running) {
        // Écran noir pour économiser l'écran et ne pas éclairer la pièce ; la surveillance continue.
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { dark = false }, contentAlignment = Alignment.BottomCenter) {
            Text("Surveillance active · touchez pour rallumer", color = Color(0xFF2E3237), fontSize = 12.sp, modifier = Modifier.padding(24.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header("Caméra", if (status.running) "Surveillance de « ${Prefs.camName} »" else "Réglez puis démarrez la surveillance") {
            if (!status.running) TextButton(onClick = onSwitch) { Text("Changer de mode", color = Muted) }
        }
        status.error?.let { Panel { Text(it, color = Red) } }
        if (status.running) {
            Box {
                Frame(frame, placeholder = "Démarrage de la caméra…")
                Row(Modifier.padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(when { !status.armed -> Muted; status.motion -> Red; else -> Green }))
                    Spacer(Modifier.width(6.dp))
                    Text(when { !status.armed -> "Détection coupée"; status.motion -> "Mouvement : enregistrement"; else -> "Surveillance" }, color = Color.White, fontSize = 13.sp)
                }
            }
            Panel {
                Text("Pour connecter votre téléphone", fontWeight = FontWeight.Bold, color = TextC)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Text("Code", fontSize = 12.sp, color = Muted); Text(code.chunked(3).joinToString(" "), fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Orange) }
                    Column(Modifier.weight(1f)) { Text("Adresse", fontSize = 12.sp, color = Muted); Text(status.ip ?: "Pas de Wi-Fi", fontSize = 16.sp, color = TextC) }
                }
                Text("${status.events} passage${if (status.events > 1) "s" else ""} enregistré${if (status.events > 1) "s" else ""}", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { dark = true }, modifier = Modifier.weight(1f).height(52.dp)) { Icon(Icons.Filled.DarkMode, null); Spacer(Modifier.width(6.dp)); Text("Écran noir", color = TextC) }
                Button(onClick = { CamService.stop(ctx) }, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Arrêter") }
            }
            Text("Vous pouvez aussi éteindre l'écran avec le bouton du téléphone : la surveillance continue. Laissez le téléphone branché.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp))
        } else {
            Panel {
                OutlinedTextField(name, { name = it; Prefs.camName = it.ifBlank { "Caméra" } }, label = { Text("Nom de la caméra") }, placeholder = { Text("Ex. Salon, Entrée, Garage") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Objectif", color = TextC)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    FilterChip(selected = !front, onClick = { front = false; Prefs.front = false }, label = { Text("Arrière (meilleure image)") })
                    FilterChip(selected = front, onClick = { front = true; Prefs.front = true }, label = { Text("Avant") })
                }
                Spacer(Modifier.height(12.dp))
                Text("Sensibilité : ${when { sens <= 2 -> "très sensible"; sens <= 5 -> "normale"; else -> "faible (gros mouvements)" }}", color = TextC)
                Slider(value = (11 - sens).toFloat(), onValueChange = { sens = 11 - it.toInt() }, onValueChangeFinished = { Prefs.sensitivity = sens }, valueRange = 1f..10f, steps = 8)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Code de la caméra", fontSize = 12.sp, color = Muted); Text(code.chunked(3).joinToString(" "), fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Orange) }
                    TextButton(onClick = { Prefs.newCode(); code = Prefs.code }) { Text("Nouveau code") }
                }
            }
            BigButton("Démarrer la surveillance") {
                if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) CamService.start(ctx) else launcher.launch(perms)
            }
            Text(
                "Posez le téléphone en hauteur, branché sur secteur, face à l'entrée de la pièce. " +
                    "Prévenez les personnes filmées chez vous (aide à domicile, invités) et ne filmez ni la voie publique ni chez les voisins.",
                fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

// ================================================================ MODE VISIONNEUSE

@Composable
fun ViewerMode(onSwitch: () -> Unit) {
    var cams by remember { mutableStateOf(Prefs.cams) }
    var open by remember { mutableStateOf<Cam?>(null) }
    var adding by remember { mutableStateOf(false) }
    BackHandler(enabled = open != null) { open = null }
    val o = open
    when {
        o != null -> CamDetail(o, onBack = { open = null }, onRemove = { Prefs.removeCam(o); cams = Prefs.cams; open = null })
        else -> CamList(cams, onOpen = { open = it }, onAdd = { adding = true }, onSwitch = onSwitch)
    }
    if (adding) AddCamDialog(onClose = { adding = false; cams = Prefs.cams })
}

@Composable
fun CamList(cams: List<Cam>, onOpen: (Cam) -> Unit, onAdd: () -> Unit, onSwitch: () -> Unit) {
    val ctx = LocalContext.current
    var alerts by remember { mutableStateOf(Prefs.alerts) }
    val thumbs = remember { mutableStateMapOf<String, Bitmap?>() }
    val online = remember { mutableStateMapOf<String, Boolean>() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(cams) {
        while (true) {
            cams.forEach { c -> val b = Net.snap(c); online[c.host] = b != null; if (b != null) thumbs[c.host] = b }
            delay(4000)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("Mes caméras", "Sur le Wi-Fi de la maison") {
            IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Ajouter une caméra", tint = Orange) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Alertes de mouvement", color = TextC)
                Text("Notification à chaque passage, même appli fermée", fontSize = 12.sp, color = Muted)
            }
            Switch(checked = alerts, onCheckedChange = { on ->
                alerts = on; Prefs.alerts = on
                if (on) {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    AlertService.start(ctx)
                } else AlertService.stop(ctx)
            })
        }
        if (cams.isEmpty()) Panel {
            Text("Aucune caméra pour l'instant.", color = TextC, fontWeight = FontWeight.SemiBold)
            Text("Sur le vieux téléphone, installez Phoenix Pro Cam, choisissez « caméra » et démarrez la surveillance. Puis touchez + ici.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            BigButton("Ajouter une caméra", onClick = onAdd)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f)) {
            items(cams, key = { it.host + it.name }) { c ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).clickable { onOpen(c) }.padding(10.dp)) {
                    Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                        val t = thumbs[c.host]
                        if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(if (online[c.host] == false) "Hors ligne" else "Connexion…", color = Muted)
                    }
                    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (online[c.host] == true) Green else Muted))
                        Spacer(Modifier.width(8.dp))
                        Text(c.name, fontWeight = FontWeight.Bold, color = TextC, modifier = Modifier.weight(1f))
                        Text(c.host, fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
        TextButton(onClick = onSwitch, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Changer de mode", color = Muted) }
    }
}

@Composable
fun AddCamDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<Triple<String, String, Int>>() }
    var host by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var port by remember { mutableIntStateOf(PORT) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val d = Discovery(ctx) { n, h, p -> if (found.none { it.second == h }) found.add(Triple(n, h, p)) }
        d.start()
        onDispose { d.stop() }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Card2,
        title = { Text("Ajouter une caméra") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (found.isEmpty()) "Recherche des caméras sur le Wi-Fi…" else "Caméras trouvées :", fontSize = 13.sp, color = Muted)
                found.forEach { (n, h, p) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (host == h) Color(0x33F08A1C) else Card).clickable { host = h; name = n; port = p }.padding(12.dp)) {
                        Icon(Icons.Filled.Videocam, null, tint = Orange); Spacer(Modifier.width(10.dp))
                        Column { Text(n, color = TextC, fontWeight = FontWeight.SemiBold); Text(h, fontSize = 12.sp, color = Muted) }
                    }
                }
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Adresse (si non trouvée)") }, placeholder = { Text("Ex. 192.168.1.42") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { if (it.length <= 7) code = it.filter(Char::isDigit) }, label = { Text("Code affiché sur la caméra") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                msg?.let { Text(it, color = Red, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (host.isBlank() || code.length != 6) { msg = "Choisissez une caméra et tapez son code à 6 chiffres"; return@TextButton }
                val cam = Cam(name.ifBlank { "Caméra" }, host, port, code)
                scope.launch {
                    val st = Net.status(cam)
                    if (st == null) msg = "Connexion impossible : vérifiez le code et que les deux téléphones sont sur le même Wi-Fi"
                    else { Prefs.saveCam(cam.copy(name = st.optString("name", cam.name))); onClose() }
                }
            }) { Text("Ajouter", color = Orange, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler", color = Muted) } },
    )
}

@Composable
fun CamDetail(cam: Cam, onBack: () -> Unit, onRemove: () -> Unit) {
    val scope = rememberCoroutineScope()
    var live by remember { mutableStateOf<Bitmap?>(null) }
    var online by remember { mutableStateOf(true) }
    var armed by remember { mutableStateOf(true) }
    var battery by remember { mutableIntStateOf(-1) }
    var events by remember { mutableStateOf<List<RemoteEvent>>(emptyList()) }
    var playing by remember { mutableStateOf<RemoteEvent?>(null) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(cam) { while (true) { val b = Net.snap(cam); online = b != null; if (b != null) live = b; delay(250) } }
    LaunchedEffect(cam) {
        while (true) {
            Net.status(cam)?.let { armed = it.optBoolean("armed", true); battery = it.optInt("battery", -1) }
            Net.events(cam)?.let { events = it; if (it.isNotEmpty()) Prefs.setLastSeen(cam, it.maxOf { e -> e.id }) }
            delay(5000)
        }
    }
    val p = playing
    if (p != null) { EventPlayer(cam, p, onBack = { playing = null }, onDelete = { scope.launch { Net.delete(cam, p.id); events = events.filter { it != p }; playing = null } }); return }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour", tint = TextC) }
            Column(Modifier.weight(1f)) {
                Text(cam.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextC)
                Text(if (!online) "Hors ligne" else "En direct" + if (battery >= 0) " · batterie $battery %" else "", fontSize = 13.sp, color = if (online) Green else Red)
            }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Retirer", tint = Muted) }
        }
        Frame(live, placeholder = if (online) "Connexion…" else "Caméra injoignable")
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Détection de mouvement", color = TextC, modifier = Modifier.weight(1f))
            Switch(checked = armed, onCheckedChange = { on -> armed = on; scope.launch { Net.arm(cam, on) } })
        }
        Text("Passages enregistrés (${events.size})", fontWeight = FontWeight.Bold, color = TextC)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (events.isEmpty()) item { Text("Aucun passage pour l'instant.", color = Muted, fontSize = 13.sp) }
            items(events, key = { it.id }) { e ->
                var thumb by remember(e.id) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(e.id) { thumb = Net.frame(cam, e.id, minOf(4, e.frames - 1).coerceAtLeast(0)) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).clickable { playing = e }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                        thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when_(e.id), color = TextC, fontWeight = FontWeight.SemiBold)
                        Text("${e.frames / 2} s environ · ${e.frames} images", fontSize = 12.sp, color = Muted)
                    }
                    Icon(Icons.Filled.PlayArrow, null, tint = Orange)
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Card2,
        title = { Text("Retirer cette caméra ?") }, text = { Text("Elle disparaît de cette liste. Les enregistrements restent sur le téléphone-caméra.") },
        confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Retirer", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
    )
}

@Composable
fun EventPlayer(cam: Cam, e: RemoteEvent, onBack: () -> Unit, onDelete: () -> Unit) {
    val frames = remember(e.id) { mutableStateMapOf<Int, Bitmap>() }
    var index by remember { mutableIntStateOf(0) }
    var play by remember { mutableStateOf(true) }
    BackHandler { onBack() }
    LaunchedEffect(e.id) { for (i in 0 until e.frames) { Net.frame(cam, e.id, i)?.let { frames[i] = it } } }
    LaunchedEffect(play, e.id) { while (play && e.frames > 0) { delay(500); index = (index + 1) % e.frames } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour", tint = TextC) }
            Column(Modifier.weight(1f)) {
                Text(cam.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextC)
                Text(when_(e.id), fontSize = 13.sp, color = Muted)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Supprimer", tint = Red) }
        }
        Frame(frames[index], placeholder = "Chargement… ${frames.size}/${e.frames}")
        Slider(value = index.toFloat(), onValueChange = { play = false; index = it.toInt() }, valueRange = 0f..(e.frames - 1).coerceAtLeast(1).toFloat())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { play = !play }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange)) {
                Icon(if (play) Icons.Filled.Pause else Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (play) "Pause" else "Lecture")
            }
            Text("${index + 1} / ${e.frames}", color = Muted, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Text("Les 2 premières secondes montrent la pièce juste avant le mouvement.", fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
