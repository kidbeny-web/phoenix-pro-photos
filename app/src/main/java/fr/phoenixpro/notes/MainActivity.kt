package fr.phoenixpro.notes

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

/** Couleurs d'encre (claires pour le fond sombre). */
val INKS = listOf(0xFFF1F3F4, 0xFFF08A1C, 0xFF6F9BFF, 0xFF5FD6A8, 0xFFFF5A5F, 0xFFFFD84D, 0xFFB48CF0).map { it.toInt() }
/** Couleurs d'étiquette des notes (0 = aucune). */
val LABELS = listOf(Color.Transparent, Orange, Blue, Green, Red, Color(0xFFFFD84D), Color(0xFFB48CF0))

fun day(ms: Long): String = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.FRANCE).format(Date(ms))

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
            ) { Root() }
        }
    }
}

@Composable
fun Root() {
    var open by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        val id = open
        if (id == null) ListScreen(tick, onOpen = { open = it }, onNew = { handwriting ->
            val n = Note(System.currentTimeMillis(), bg = if (handwriting) "lignes" else "blanc")
            if (!handwriting) n.text = ""
            Store.save(n); open = n.id
        })
        else EditorScreen(id, onClose = { open = null; tick++ })
    }
}

// ---------------------------------------------------------------- liste des notes

@Composable
fun ListScreen(tick: Int, onOpen: (Long) -> Unit, onNew: (Boolean) -> Unit) {
    var q by remember { mutableStateOf("") }
    val notes by produceState<List<Note>?>(null, tick) { value = withContext(Dispatchers.IO) { Store.all() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(top = 8.dp)) {
            Text("Notes", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
            Text("Phoenix Pro · au clavier ou au stylet", fontSize = 14.sp, color = Muted)
        }
        OutlinedTextField(q, { q = it }, placeholder = { Text("Rechercher dans les notes") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
        Box(Modifier.weight(1f)) {
            val list = notes
            when {
                list == null -> CircularProgressIndicator(color = Orange, modifier = Modifier.align(Alignment.Center))
                list.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Draw, null, tint = Orange, modifier = Modifier.size(48.dp))
                    Text("Aucune note", color = TextC, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text("Touchez « Écrire » pour commencer au stylet.", color = Muted, fontSize = 13.sp)
                }
                else -> {
                    val s = q.trim().lowercase()
                    val shown = if (s.isEmpty()) list else list.filter { "${it.title} ${it.text}".lowercase().contains(s) }
                    LazyVerticalGrid(GridCells.Adaptive(160.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 90.dp)) {
                        items(shown, key = { it.id }) { n -> NoteCard(n) { onOpen(n.id) } }
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallFloatingActionButton(onClick = { onNew(false) }, containerColor = Card2, contentColor = TextC) { Icon(Icons.Filled.Keyboard, "Note au clavier") }
                ExtendedFloatingActionButton(onClick = { onNew(true) }, containerColor = Orange, contentColor = OnOrange,
                    icon = { Icon(Icons.Filled.Draw, null) }, text = { Text("Écrire", fontWeight = FontWeight.Bold) })
            }
        }
    }
}

@Composable
fun NoteCard(n: Note, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, LineC, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n.color > 0) { Box(Modifier.size(10.dp).clip(CircleShape).background(LABELS[n.color])); Spacer(Modifier.width(6.dp)) }
            Text(n.display(), color = TextC, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (n.pinned) Icon(Icons.Filled.PushPin, null, tint = Orange, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))) {
            if (n.strokes.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                val top = n.strokes.minOf { it.minY() }.coerceAtLeast(0f) - 20f
                val k = size.width / PAGE_W
                drawAll(n, k, -top * k)
            } else Text(n.text, color = Muted, fontSize = 13.sp, overflow = TextOverflow.Ellipsis)
        }
        Text(day(n.updated), color = Muted, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------- éditeur

@Composable
fun EditorScreen(id: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val note = remember(id) { Store.load(id) ?: Note(id) }
    val st = remember(id) { InkState(note) }
    var tab by remember { mutableIntStateOf(if (note.strokes.isEmpty() && note.text.isNotEmpty() || note.bg == "blanc" && note.strokes.isEmpty()) 1 else 0) }
    var title by remember { mutableStateOf(note.title) }
    var text by remember { mutableStateOf(note.text) }
    var dirty by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    fun save() { note.title = title; note.text = text; Store.save(note) }
    fun close() { save(); if (note.isEmpty) Store.delete(note.id); onClose() }
    BackHandler { close() }
    LaunchedEffect(dirty, title, text) { delay(800); withContext(Dispatchers.IO) { save() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { close() }) { Icon(Icons.Filled.ArrowBack, "Retour", tint = TextC) }
            BasicTextField(
                title, { title = it }, singleLine = true, cursorBrush = SolidColor(Orange),
                textStyle = TextStyle(color = TextC, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                decorationBox = { inner -> if (title.isEmpty()) Text("Titre", color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold); inner() },
                modifier = Modifier.weight(1f),
            )
            if (tab == 0) {
                IconButton(onClick = { st.doUndo(); dirty++ }, enabled = st.undo.isNotEmpty() || st.version < 0) { Icon(Icons.Filled.Undo, "Annuler", tint = TextC) }
                IconButton(onClick = { st.doRedo(); dirty++ }, enabled = st.redo.isNotEmpty() || st.version < 0) { Icon(Icons.Filled.Redo, "Rétablir", tint = TextC) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Plus", tint = TextC) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (note.pinned) "Désépingler" else "Épingler") }, onClick = { note.pinned = !note.pinned; dirty++; menu = false })
                    DropdownMenuItem(text = { Text("Partager en PDF") }, onClick = { menu = false; save(); scope.launch { sharePdf(ctx, note) } })
                    DropdownMenuItem(text = { Text("Copier le texte") }, onClick = {
                        menu = false
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText(title, text)); Toast.makeText(ctx, "Texte copié", Toast.LENGTH_SHORT).show()
                    })
                    Text("Fond de page", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    listOf("blanc" to "Uni", "lignes" to "Lignes", "carreaux" to "Carreaux", "points" to "Points").forEach { (k, l) ->
                        DropdownMenuItem(text = { Text((if (note.bg == k) "✓ " else "") + l) }, onClick = { note.bg = k; st.version++; dirty++; menu = false })
                    }
                    Text("Étiquette", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LABELS.forEachIndexed { i, c ->
                            Box(Modifier.size(24.dp).clip(CircleShape).background(if (i == 0) Card2 else c).border(2.dp, if (note.color == i) TextC else Color.Transparent, CircleShape)
                                .clickable { note.color = i; dirty++; menu = false })
                        }
                    }
                    DropdownMenuItem(text = { Text("Supprimer la note", color = Red) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Bg, contentColor = Orange) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Écriture") }, icon = { Icon(Icons.Filled.Draw, null) })
            Tab(tab == 1, { tab = 1 }, text = { Text("Texte") }, icon = { Icon(Icons.Filled.Notes, null) })
        }
        if (tab == 0) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll, enabled = !st.fingerDraws)) {
                InkCanvas(st, scroll) { dirty++ }
            }
            Toolbar(st, converting, onConvert = {
                if (note.strokes.none { it.tool == Tool.PEN }) { Toast.makeText(ctx, "Écrivez d'abord quelques mots au stylet", Toast.LENGTH_SHORT).show(); return@Toolbar }
                converting = true
                scope.launch {
                    if (!Writing.ready()) {
                        Toast.makeText(ctx, "Téléchargement du module d'écriture française (une seule fois)…", Toast.LENGTH_LONG).show()
                        if (!Writing.download()) { converting = false; Toast.makeText(ctx, "Téléchargement impossible : vérifiez la connexion", Toast.LENGTH_LONG).show(); return@launch }
                    }
                    val r = try { Writing.recognize(note.strokes.toList()) } catch (e: Exception) { "" }
                    converting = false
                    if (r.isBlank()) Toast.makeText(ctx, "Écriture non reconnue", Toast.LENGTH_SHORT).show()
                    else { text = if (text.isBlank()) r else text.trimEnd() + "\n\n" + r; tab = 1; Toast.makeText(ctx, "Texte ajouté dans l'onglet Texte", Toast.LENGTH_SHORT).show() }
                }
            })
        } else {
            BasicTextField(
                text, { text = it }, cursorBrush = SolidColor(Orange),
                textStyle = TextStyle(color = TextC, fontSize = 17.sp, lineHeight = 26.sp),
                decorationBox = { inner -> if (text.isEmpty()) Text("Écrivez ici…", color = Muted, fontSize = 17.sp); inner() },
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = Card2,
        title = { Text("Supprimer cette note ?") }, text = { Text("Elle sera effacée définitivement.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Store.delete(note.id); onClose() }) { Text("Supprimer", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Toolbar(st: InkState, converting: Boolean, onConvert: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Card2).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.Filled.Create, "Stylo", st.tool == Tool.PEN) { st.tool = Tool.PEN }
            ToolButton(Icons.Filled.BorderColor, "Surligneur", st.tool == Tool.HIGHLIGHT) { st.tool = Tool.HIGHLIGHT }
            ToolButton(Icons.Filled.CleaningServices, "Gomme", st.tool == Tool.ERASER) { st.tool = Tool.ERASER }
            Spacer(Modifier.width(6.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                INKS.forEach { c ->
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).border(2.5.dp, if (st.color == c) Orange else Color.Transparent, CircleShape)
                        .clickable { st.color = c; if (st.tool == Tool.ERASER) st.tool = Tool.PEN })
                }
            }
            TextButton(onClick = onConvert, enabled = !converting) {
                if (converting) CircularProgressIndicator(Modifier.size(16.dp), color = Orange, strokeWidth = 2.dp)
                else Icon(Icons.Filled.TextFields, null, tint = Orange)
                Spacer(Modifier.width(4.dp)); Text("En texte", color = Orange)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(2f to "Fin", 4f to "Moyen", 7f to "Épais").forEach { (w, l) ->
                FilterChip(selected = st.width == w, onClick = { st.width = w }, label = { Text(l, fontSize = 12.sp) }, modifier = Modifier.padding(end = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(if (st.stylusSeen) "Stylet détecté" else "Doigt", color = Muted, fontSize = 12.sp)
            Switch(checked = st.fingerDraws, onCheckedChange = { st.fingerDraws = it }, modifier = Modifier.padding(start = 6.dp))
            Text("écrire au doigt", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
fun ToolButton(icon: ImageVector, desc: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Orange else Color.Transparent).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (on) OnOrange else TextC)
    }
}

// ---------------------------------------------------------------- export PDF

suspend fun sharePdf(ctx: Context, n: Note) {
    val f = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val w = 595; val h = 842; val m = 40f
        var no = 0
        fun page(): PdfDocument.Page { no++; return doc.startPage(PdfDocument.PageInfo.Builder(w, h, no).create()) }
        val titleP = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; color = android.graphics.Color.rgb(20, 22, 25) }
        val bodyP = Paint().apply { textSize = 11f; isAntiAlias = true; color = android.graphics.Color.rgb(30, 32, 36) }
        // Texte
        if (n.text.isNotBlank() || n.title.isNotBlank()) {
            var p = page(); var y = m + 18
            p.canvas.drawText(n.display(), m, y, titleP); y += 26
            for (para in n.text.split("\n")) {
                var line = ""
                for (word in para.split(" ")) {
                    val t = if (line.isEmpty()) word else "$line $word"
                    if (bodyP.measureText(t) > w - 2 * m && line.isNotEmpty()) {
                        p.canvas.drawText(line, m, y, bodyP); y += 16; line = word
                        if (y > h - m) { doc.finishPage(p); p = page(); y = m + 12 }
                    } else line = t
                }
                p.canvas.drawText(line, m, y, bodyP); y += 16
                if (y > h - m) { doc.finishPage(p); p = page(); y = m + 12 }
            }
            doc.finishPage(p)
        }
        // Pages manuscrites (encre sombre sur fond blanc pour l'impression)
        if (n.strokes.isNotEmpty()) {
            val lastY = n.strokes.maxOf { it.maxY() }
            val pages = (lastY / PAGE_H).toInt() + 1
            val k = w / PAGE_W
            for (pg in 0 until pages) {
                val p = page(); val c = p.canvas
                val paint = Paint().apply { isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
                n.strokes.forEach { s ->
                    if (s.maxY() < pg * PAGE_H || s.minY() > (pg + 1) * PAGE_H) return@forEach
                    val light = s.color == 0xFFF1F3F4.toInt()
                    paint.color = if (light) android.graphics.Color.rgb(25, 27, 30) else s.color
                    paint.alpha = if (s.tool == Tool.HIGHLIGHT) 90 else 255
                    for (i in 1 until s.count) {
                        paint.strokeWidth = if (s.tool == Tool.HIGHLIGHT) s.width * k else s.width * k * (0.35f + 0.9f * s.p(i))
                        c.drawLine(s.x(i - 1) * k, (s.y(i - 1) - pg * PAGE_H) * k, s.x(i) * k, (s.y(i) - pg * PAGE_H) * k, paint)
                    }
                }
                doc.finishPage(p)
            }
        }
        if (no == 0) doc.finishPage(page())
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val out = File(dir, (n.display().replace(Regex("[^A-Za-z0-9À-ÿ -]"), "").trim().ifBlank { "note" }) + ".pdf")
        out.outputStream().use { doc.writeTo(it) }; doc.close(); out
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Partager la note"))
}
