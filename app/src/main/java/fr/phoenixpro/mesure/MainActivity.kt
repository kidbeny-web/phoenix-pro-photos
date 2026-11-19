package fr.phoenixpro.mesure

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.flow.MutableStateFlow
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
        Rooms.init(this)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = Color(0xFF1A1206), secondary = Blue,
                    background = Bg, surface = Bg, onBackground = TextC, onSurface = TextC,
                    outline = LineC, surfaceVariant = Card2, onSurfaceVariant = Muted,
                ),
            ) { MesureRoot() }
        }
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
            .border(1.dp, LineC, RoundedCornerShape(18.dp)).padding(16.dp),
        content = content,
    )
}

/** Ouverture du formulaire d'enregistrement : mesure existante (édition) ou valeurs proposées. */
data class Draft(val item: Item?, val w: Double?, val h: Double?)

@Composable
fun MesureRoot() {
    var tab by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var version by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) ArScreen(onSave = { w, h -> draft = Draft(null, w, h) }, onManual = { tab = 1; draft = Draft(null, null, null) })
            else RoomsScreen(version, onEdit = { draft = Draft(it, null, null) }, onAdd = { draft = Draft(null, null, null) })
        }
        NavigationBar(containerColor = Card2, tonalElevation = 0.dp) {
            listOf(Triple("Mesurer", Icons.Filled.Straighten, 0), Triple("Mes pièces", Icons.Filled.Home, 1)).forEach { (label, icon, i) ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Orange, selectedTextColor = Orange, indicatorColor = Color(0x33F08A1C),
                        unselectedIconColor = Muted, unselectedTextColor = Muted,
                    ),
                )
            }
        }
    }

    draft?.let { d ->
        SaveDialog(d, onClose = { saved ->
            draft = null
            if (saved) { version++; tab = 1 }
        })
    }
}

// ---------------------------------------------------------------- Mesure en réalité augmentée

@Composable
fun ArScreen(onSave: (Double?, Double?) -> Unit, onManual: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val support = remember { ArCoreApk.getInstance().checkAvailability(ctx) }

    when {
        support == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
            Message("Ce téléphone n'est pas compatible avec la mesure en réalité augmentée (ARCore).",
                "Vous pouvez quand même noter vos mesures à la main dans « Mes pièces ».", "Saisir une mesure", onManual)
        !granted ->
            Message("La caméra sert à viser les points à mesurer.", "Rien n'est enregistré ni envoyé : l'image reste sur le téléphone.",
                "Autoriser la caméra") { launcher.launch(Manifest.permission.CAMERA) }
        else -> ArMeasure(onSave, onManual)
    }
}

@Composable
fun Message(title: String, text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Straighten, contentDescription = null, tint = Orange, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextC, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 15.sp, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClick, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color(0xFF1A1206))) {
            Text(button, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ArMeasure(onSave: (Double?, Double?) -> Unit, onManual: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val owner = LocalLifecycleOwner.current
    val state = remember { MutableStateFlow(ArView()) }
    val view by state.collectAsState()
    val renderer = remember { ArRenderer { state.value = it } }
    var error by remember { mutableStateOf<String?>(null) }
    var installRequested by remember { mutableStateOf(false) }
    val glView = remember {
        GLSurfaceView(ctx).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    fun resume() {
        if (error != null || activity == null) return
        try {
            if (renderer.session == null) {
                if (ArCoreApk.getInstance().requestInstall(activity, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                val s = Session(ctx)
                val cfg = Config(s).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                }
                s.configure(cfg)
                renderer.session = s
            }
            renderer.rotation = if (Build.VERSION.SDK_INT >= 30) ctx.display?.rotation ?: 0
            else (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            renderer.session?.resume()
            glView.onResume()
        } catch (e: UnavailableUserDeclinedInstallationException) {
            error = "Les « Services Google Play pour la RA » sont nécessaires. Installez-les depuis le Play Store."
        } catch (e: UnavailableDeviceNotCompatibleException) {
            error = "Ce téléphone n'est pas compatible avec la réalité augmentée."
        } catch (e: Exception) {
            error = "La réalité augmentée ne démarre pas (${e.javaClass.simpleName}). Mettez à jour « Services Google Play pour la RA »."
        }
    }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> { glView.onPause(); renderer.session?.pause() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            glView.onPause()
            renderer.session?.pause()
            renderer.session?.close()
            renderer.session = null
        }
    }

    val err = error
    if (err != null) {
        Message("Mesure en direct indisponible", err, "Saisir une mesure à la main", onManual)
        return
    }

    val labelPaint = remember {
        Paint().apply { isAntiAlias = true; textSize = 44f; typeface = Typeface.DEFAULT_BOLD; color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER }
    }
    val bgPaint = remember { Paint().apply { isAntiAlias = true; color = 0xE60E1012.toInt() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ glView }, Modifier.fillMaxSize())

        Canvas(Modifier.fillMaxSize()) {
            val pts = view.points
            fun label(at: Offset, txt: String, accent: Boolean) {
                drawIntoCanvas { c ->
                    val tw = labelPaint.measureText(txt) + 40f
                    val r = RectF(at.x - tw / 2, at.y - 70f, at.x + tw / 2, at.y - 12f)
                    bgPaint.color = if (accent) 0xF2F08A1C.toInt() else 0xE60E1012.toInt()
                    c.nativeCanvas.drawRoundRect(r, 28f, 28f, bgPaint)
                    labelPaint.color = if (accent) 0xFF1A1206.toInt() else android.graphics.Color.WHITE
                    c.nativeCanvas.drawText(txt, at.x, at.y - 27f, labelPaint)
                }
            }
            // Mesures terminées
            for (i in 0 until pts.size / 2) {
                val a = pts[2 * i]; val b = pts[2 * i + 1]
                if (a != null && b != null) {
                    drawLine(Color(0x99000000), a, b, 14f, cap = StrokeCap.Round)
                    drawLine(Color.White, a, b, 7f, cap = StrokeCap.Round)
                    label(Offset((a.x + b.x) / 2, (a.y + b.y) / 2), cmLabel(view.segments.getOrNull(i)), false)
                }
            }
            // Mesure en cours vers le viseur
            val center = Offset(size.width / 2, size.height / 2)
            if (pts.size % 2 == 1) {
                pts.last()?.let { a ->
                    drawLine(Orange, a, center, 7f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(28f, 18f)))
                    view.live?.let { label(Offset((a.x + center.x) / 2, (a.y + center.y) / 2), cmLabel(it), true) }
                }
            }
            pts.forEach { p -> p?.let { drawCircle(Color.White, 16f, it); drawCircle(Orange, 10f, it) } }
            // Viseur
            val ok = view.reticleOnSurface
            drawCircle(if (ok) Green else Color.White.copy(alpha = 0.6f), 44f, center, style = Stroke(5f))
            drawCircle(if (ok) Green else Color.White.copy(alpha = 0.6f), 6f, center)
        }

        // Consigne
        Column(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                view.hint, color = TextC, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC0E1012)).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // Commandes
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 20.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.Filled.Undo, "Annuler le dernier point", enabled = view.count > 0) { renderer.post(Action.UNDO) }
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(if (view.reticleOnSurface) Orange else Color(0x66F08A1C))
                    .border(4.dp, Color.White, CircleShape).clickable(enabled = view.reticleOnSurface) { renderer.post(Action.ADD) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, contentDescription = "Poser un point", tint = Color(0xFF1A1206), modifier = Modifier.size(44.dp)) }
            RoundButton(Icons.Filled.Save, "Enregistrer", enabled = view.segments.isNotEmpty()) {
                val segs = view.segments
                val last = segs.lastOrNull()?.let { it * 100.0 }
                val prev = if (segs.size >= 2) segs[segs.size - 2] * 100.0 else null
                // Deux mesures : l'avant-dernière devient la largeur, la dernière la hauteur (modifiables ensuite).
                if (prev != null && last != null) onSave(roundCm(prev), roundCm(last)) else onSave(last?.let { roundCm(it) }, null)
            }
        }
        if (view.count > 1) {
            TextButton(
                onClick = { renderer.post(Action.CLEAR) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xAA0E1012)),
            ) { Text("Tout effacer", color = TextC) }
        }
    }
}

fun roundCm(v: Double) = (v * 2).roundToInt() / 2.0
fun cmLabel(m: Float?): String = if (m == null) "…" else if (m >= 1f) "${cm(roundCm(m * 100.0))} cm" else "${cm(roundCm(m * 100.0))} cm"

@Composable
fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(58.dp).clip(CircleShape).background(Color(0xCC0E1012)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = if (enabled) TextC else Muted.copy(alpha = 0.5f)) }
}

// ---------------------------------------------------------------- Mes pièces

@Composable
fun RoomsScreen(version: Int, onEdit: (Item) -> Unit, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val rooms = remember(version) { Rooms.list }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mes pièces", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
                Text("Vos mesures rangées pièce par pièce", fontSize = 14.sp, color = Muted)
            }
            FilledIconButton(onClick = onAdd, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Orange, contentColor = Color(0xFF1A1206))) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter une mesure à la main")
            }
        }
        if (rooms.isEmpty()) {
            Panel { Text("Aucune mesure enregistrée. Mesurez avec la caméra puis touchez le bouton Enregistrer, ou ajoutez une mesure à la main avec +.", color = Muted) }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(rooms, key = { it.name }) { r ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextC, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Mesures ${r.name}", Rooms.text(r)))
                            Toast.makeText(ctx, "Liste copiée", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = Orange, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copier", color = Orange) }
                    }
                    r.items.forEach { i ->
                        Row(Modifier.fillMaxWidth().clickable { onEdit(i) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(i.name, color = TextC, fontWeight = FontWeight.Medium)
                                Text(i.type, fontSize = 12.sp, color = Muted)
                                if (i.type == "Fenêtre") Text(curtains(i), fontSize = 12.sp, color = Orange)
                            }
                            Text(dims(i), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextC)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Formulaire

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SaveDialog(d: Draft, onClose: (Boolean) -> Unit) {
    val existing = d.item
    val roomNames = Rooms.list.map { it.name }
    var room by remember { mutableStateOf(existing?.let { Rooms.roomOf(it.id) } ?: roomNames.lastOrNull() ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: "Fenêtre") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var w by remember { mutableStateOf((existing?.w ?: d.w)?.let { cm(it) } ?: "") }
    var h by remember { mutableStateOf((existing?.h ?: d.h)?.let { cm(it) } ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    fun num(s: String) = s.replace(',', '.').replace(" ", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = { onClose(false) },
        containerColor = Card2,
        title = { Text(if (existing != null) "Modifier la mesure" else "Enregistrer la mesure") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(room, { room = it }, label = { Text("Pièce") }, placeholder = { Text("Ex. Salon") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (roomNames.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    roomNames.forEach { n -> FilterChip(selected = room == n, onClick = { room = n }, label = { Text(n) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, placeholder = { Text("Ex. Fenêtre côté jardin") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it }, label = { Text("Largeur (cm)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(h, { h = it }, label = { Text("Hauteur (cm)") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text("Hauteur facultative. Avec deux mesures à l'écran, la première est proposée en largeur et la seconde en hauteur.", fontSize = 12.sp, color = Muted)
                if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Supprimer cette mesure", color = Red) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val wv = num(w)
                if (room.isBlank() || wv == null || wv <= 0) return@TextButton
                Rooms.put(room.trim(), Item(existing?.id ?: System.currentTimeMillis(), type, name.trim().ifBlank { type }, wv, num(h)?.takeIf { it > 0 }, existing?.at ?: System.currentTimeMillis()))
                onClose(true)
            }) { Text("Enregistrer", color = Orange, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { onClose(false) }) { Text("Annuler", color = Muted) } },
    )

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Card2,
            title = { Text("Supprimer ?") },
            text = { Text("« ${existing.name} » sera effacée de ${Rooms.roomOf(existing.id) ?: "la pièce"}.") },
            confirmButton = { TextButton(onClick = { Rooms.delete(existing.id); confirmDelete = false; onClose(true) }) { Text("Supprimer", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}
