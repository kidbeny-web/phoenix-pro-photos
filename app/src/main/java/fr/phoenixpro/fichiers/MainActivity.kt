package fr.phoenixpro.fichiers

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
val Purple = Color(0xFFB48CF0)
val OnOrange = Color(0xFF1A1206)

fun catColor(c: Cat?) = when (c) {
    Cat.IMAGES -> Blue; Cat.VIDEOS -> Purple; Cat.AUDIO -> Green; Cat.DOCS -> Orange; Cat.APK -> Color(0xFF8BD3E6); Cat.ARCHIVES -> Color(0xFFE6C36F); null -> Muted
}

fun catIcon(c: Cat?): ImageVector = when (c) {
    Cat.IMAGES -> Icons.Filled.Image; Cat.VIDEOS -> Icons.Filled.Movie; Cat.AUDIO -> Icons.Filled.MusicNote; Cat.DOCS -> Icons.Filled.Description
    Cat.APK -> Icons.Filled.Android; Cat.ARCHIVES -> Icons.Filled.FolderZip; null -> Icons.Filled.InsertDriveFile
}

sealed class Screen {
    data object Home : Screen()
    data class Browse(val dir: File) : Screen()
    data class Category(val cat: Cat) : Screen()
    data object Recent : Screen()
    data object Big : Screen()
    data object Trash : Screen()
    data object Search : Screen()
    data class Edit(val file: File) : Screen()
}

/** Ouvre un texte dans l'éditeur intégré, le reste dans l'appli adaptée. */
fun openAny(ctx: Context, f: File, go: (Screen) -> Unit) { if (Fs.isText(f)) go(Screen.Edit(f)) else openFile(ctx, f) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

fun hasAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun openFile(ctx: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Aucune appli ne sait ouvrir ce fichier", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Ouverture impossible", Toast.LENGTH_SHORT).show()
    }
}

fun shareFiles(ctx: Context, files: List<File>) {
    val list = files.filter { it.isFile }
    if (list.isEmpty()) { Toast.makeText(ctx, "Seuls les fichiers peuvent être partagés", Toast.LENGTH_SHORT).show(); return }
    val uris = ArrayList(list.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { type = mimeOf(list[0]); putExtra(Intent.EXTRA_STREAM, uris[0]) }
    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Partager"))
}

// ---------------------------------------------------------------- vignettes

object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)
    fun get(f: File): Bitmap? {
        cache.get(f.path)?.let { return it }
        val c = catOf(f)
        val b: Bitmap? = try {
            when {
                c == Cat.IMAGES && Build.VERSION.SDK_INT >= 29 -> ThumbnailUtils.createImageThumbnail(f, Size(144, 144), null)
                c == Cat.IMAGES -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
                    var s = 1; while (o.outWidth / (s * 2) >= 144 && o.outHeight / (s * 2) >= 144) s *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                }
                c == Cat.VIDEOS && Build.VERSION.SDK_INT >= 29 -> ThumbnailUtils.createVideoThumbnail(f, Size(144, 144), null)
                else -> null
            }
        } catch (e: Exception) { null }
        b?.let { cache.put(f.path, it) }
        return b
    }
}

// ---------------------------------------------------------------- éléments communs

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, LineC, RoundedCornerShape(18.dp)).padding(16.dp), content = content)
}

@Composable
fun TopBar(title: String, sub: String? = null, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Retour", tint = TextC) } else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = if (onBack == null) 28.sp else 20.sp, fontWeight = FontWeight.Bold, color = TextC, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(f: File, selected: Boolean, showPath: Boolean = false, onClick: () -> Unit, onLong: () -> Unit) {
    val c = if (f.isDirectory) null else catOf(f)
    val thumb by produceState<Bitmap?>(null, f.path) { value = if (c == Cat.IMAGES || c == Cat.VIDEOS) withContext(Dispatchers.IO) { Thumbs.get(f) } else null }
    val info by produceState(if (f.isDirectory) "" else "${size(f.length())} · ${date(f.lastModified())}", f.path) {
        if (f.isDirectory) value = withContext(Dispatchers.IO) { val n = Fs.childCount(f); "$n élément${if (n > 1) "s" else ""}" }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Color(0x33F08A1C) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (f.isDirectory) Color(0x22F08A1C) else catColor(c).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            val t = thumb
            if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (f.isDirectory) Icons.Filled.Folder else catIcon(c), null, tint = if (f.isDirectory) Orange else catColor(c))
            if (selected) Box(Modifier.fillMaxSize().background(Color(0x99F08A1C)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, null, tint = OnOrange) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = TextC, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (showPath) (f.parentFile?.path?.removePrefix(Fs.primary.path)?.ifBlank { "/" } ?: "") + " · " + info else info, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AskText(title: String, initial: String, ok: String, onDone: (String?) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) }, containerColor = Card2, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onDone(v.trim()) }) { Text(ok, color = Orange, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Annuler", color = Muted) } },
    )
}

// ---------------------------------------------------------------- racine et navigation

class Clip(val files: List<File>, val cut: Boolean)

@Composable
fun Root() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(hasAccess(ctx)) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = hasAccess(ctx) }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access = hasAccess(ctx) }
    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        if (!access) Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x22F08A1C)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Folder, null, tint = Orange, modifier = Modifier.size(46.dp)) }
            Spacer(Modifier.height(18.dp))
            Text("Accès aux fichiers", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextC)
            Text("Pour ranger, copier et nettoyer vos fichiers, Android demande d'autoriser « l'accès à tous les fichiers ». Rien ne quitte votre téléphone.",
                color = Muted, modifier = Modifier.padding(vertical = 10.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
                    catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Autoriser", fontWeight = FontWeight.Bold) }
        } else App()
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Fs.purgeOld() } }
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    BackHandler(enabled = stack.size > 1) { back() }
    val ops = remember { Ops(ctx) { tick++ } }
    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(tick, ::go)
        is Screen.Browse -> BrowseScreen(s.dir, tick, clip, ops, onClip = { clip = it }, go = ::go, back = ::back)
        is Screen.Category -> ListScreen(s.cat.label, tick, ops, onClip = { clip = it }, go = ::go, back = ::back) { Fs.category(ctx, s.cat) }
        Screen.Recent -> ListScreen("Récents", tick, ops, onClip = { clip = it }, go = ::go, back = ::back) { Fs.recent(ctx) }
        Screen.Big -> ListScreen("Gros fichiers", tick, ops, onClip = { clip = it }, go = ::go, back = ::back, sub = "Plus de 50 Mo, du plus lourd au plus léger") { Fs.big(ctx) }
        Screen.Trash -> TrashScreen(tick, ops, ::back)
        Screen.Search -> SearchScreen(ops, onClip = { clip = it }, go = ::go, back = ::back)
        is Screen.Edit -> EditorScreen(s.file, ops, ::back)
    }
}

/** Opérations lancées en arrière-plan, avec message de fin. */
class Ops(private val ctx: Context, private val refresh: () -> Unit) {
    var busy by mutableStateOf<String?>(null)
    suspend fun run(label: String, done: String, block: () -> List<File>) {
        busy = label
        val r = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { busy = null; Toast.makeText(ctx, "Échec : ${e.message ?: "erreur"}", Toast.LENGTH_LONG).show(); refresh(); return }
        withContext(Dispatchers.IO) { Fs.rescan(ctx, r) }
        busy = null
        Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
        refresh()
    }
}

// ---------------------------------------------------------------- accueil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(tick: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val vols by produceState(emptyList<Volume>(), tick) { value = withContext(Dispatchers.IO) { Fs.volumes(ctx) } }
    val usage by produceState(emptyMap<Cat, Long>(), tick) { value = withContext(Dispatchers.IO) { Fs.usage(ctx) } }
    val recent by produceState(emptyList<File>(), tick) { value = withContext(Dispatchers.IO) { Fs.recent(ctx).take(6) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TopBar("Fichiers", "Phoenix Pro · gestionnaire de fichiers", null) {
            IconButton(onClick = { go(Screen.Search) }) { Icon(Icons.Filled.Search, "Rechercher", tint = TextC) }
        }
        vols.forEach { v ->
            val used = v.total - v.free
            Panel(Modifier.clickable { go(Screen.Browse(v.root)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (v.root == Fs.primary) Icons.Filled.PhoneAndroid else Icons.Filled.SdCard, null, tint = Orange)
                    Spacer(Modifier.width(10.dp))
                    Text(v.name, color = TextC, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${size(used)} / ${size(v.total)}", color = Muted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card2)) {
                    if (v.root == Fs.primary) {
                        Cat.entries.forEach { c -> val b = usage[c] ?: 0L; if (b > 0) Box(Modifier.fillMaxHeight().weight(b.toFloat().coerceAtLeast(1f)).background(catColor(c))) }
                        val other = (used - usage.values.sum()).coerceAtLeast(0)
                        if (other > 0) Box(Modifier.fillMaxHeight().weight(other.toFloat()).background(Muted.copy(alpha = 0.5f)))
                        if (v.free > 0) Box(Modifier.fillMaxHeight().weight(v.free.toFloat()))
                    } else {
                        if (used > 0) Box(Modifier.fillMaxHeight().weight(used.toFloat()).background(Orange))
                        if (v.free > 0) Box(Modifier.fillMaxHeight().weight(v.free.toFloat()))
                    }
                }
                Text("${size(v.free)} libres · touchez pour parcourir", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        // Catégories
        Cat.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Card).clickable { go(Screen.Category(c)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(catColor(c).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon(catIcon(c), null, tint = catColor(c)) }
                        Spacer(Modifier.width(10.dp))
                        Column { Text(c.label, color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(size(usage[c] ?: 0L), color = Muted, fontSize = 12.sp) }
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Environment.DIRECTORY_DOWNLOADS to "Téléchargements", Environment.DIRECTORY_DCIM to "Appareil photo", Environment.DIRECTORY_DOCUMENTS to "Documents", Environment.DIRECTORY_PICTURES to "Images")
                .forEach { (d, label) ->
                    val dir = File(Fs.primary, d)
                    AssistChip(onClick = { go(Screen.Browse(dir)) }, label = { Text(label) }, leadingIcon = { Icon(Icons.Filled.Folder, null, tint = Orange) })
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcut(Icons.Filled.Schedule, "Récents", Modifier.weight(1f)) { go(Screen.Recent) }
            Shortcut(Icons.Filled.CleaningServices, "Gros fichiers", Modifier.weight(1f)) { go(Screen.Big) }
            Shortcut(Icons.Filled.Delete, "Corbeille", Modifier.weight(1f)) { go(Screen.Trash) }
        }
        if (recent.isNotEmpty()) Panel {
            Text("Derniers fichiers", color = TextC, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            recent.forEach { f -> FileRow(f, false, true, onClick = { openAny(ctx, f, go) }, onLong = {}) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun Shortcut(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Card).clickable(onClick = onClick).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Orange); Spacer(Modifier.height(4.dp)); Text(label, color = TextC, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- sélection : barre d'actions commune

@Composable
fun SelectionBar(sel: List<File>, ops: Ops, onClip: (Clip) -> Unit, clear: () -> Unit, selectAll: (() -> Unit)?, extraDir: File?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var zipName by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var pdfName by remember { mutableStateOf(false) }
    val allImages = sel.isNotEmpty() && sel.all { it.isFile && catOf(it) == Cat.IMAGES }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Card2).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = clear) { Icon(Icons.Filled.Close, "Annuler la sélection", tint = TextC) }
        Text("${sel.size}", color = TextC, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (selectAll != null) IconButton(onClick = selectAll) { Icon(Icons.Filled.SelectAll, "Tout sélectionner", tint = TextC) }
        IconButton(onClick = { onClip(Clip(sel, false)); clear(); Toast.makeText(ctx, "Copié : choisissez le dossier puis « Coller »", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Filled.ContentCopy, "Copier", tint = TextC) }
        IconButton(onClick = { onClip(Clip(sel, true)); clear(); Toast.makeText(ctx, "Coupé : choisissez le dossier puis « Déplacer ici »", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Filled.ContentCut, "Couper", tint = TextC) }
        if (sel.size == 1) IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Renommer", tint = TextC) }
        IconButton(onClick = { shareFiles(ctx, sel) }) { Icon(Icons.Filled.Share, "Partager", tint = TextC) }
        if (allImages) IconButton(onClick = { pdfName = true }) { Icon(Icons.Filled.PictureAsPdf, "Créer un PDF", tint = Orange) }
        if (extraDir != null) IconButton(onClick = { zipName = true }) { Icon(Icons.Filled.FolderZip, "Compresser", tint = TextC) }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Supprimer", tint = Red) }
    }
    if (rename) AskText("Renommer", sel[0].name, "Renommer") { n ->
        rename = false
        if (n != null) { if (Fs.rename(sel[0], n) == null) Toast.makeText(ctx, "Nom impossible ou déjà pris", Toast.LENGTH_SHORT).show() else { clear(); scope.launch { ops.run("Renommage…", "Renommé") { emptyList() } } } }
    }
    if (zipName && extraDir != null) AskText("Nom de l'archive", if (sel.size == 1) sel[0].nameWithoutExtension else "archive", "Compresser") { n ->
        zipName = false
        if (!n.isNullOrBlank()) { val files = sel.toList(); clear(); scope.launch { ops.run("Compression…", "Archive créée") { val z = Fs.freeName(extraDir, "$n.zip"); Fs.zip(files, z); listOf(z) } } }
    }
    if (pdfName) AskText("Créer un PDF (${sel.size} page${if (sel.size > 1) "s" else ""})", if (sel.size == 1) sel[0].nameWithoutExtension else "Document", "Créer") { n ->
        pdfName = false
        if (!n.isNullOrBlank()) {
            val files = sel.toList(); val target = extraDir ?: Fs.documents; clear()
            scope.launch { ops.run("Création du PDF…", "PDF créé dans ${target.name}") { listOf(Fs.imagesToPdf(files, Fs.freeName(target, "$n.pdf"))) } }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Card2,
        title = { Text("Mettre à la corbeille ?") },
        text = { Text("${sel.size} élément${if (sel.size > 1) "s" else ""}. Vous pourrez ${if (sel.size > 1) "les" else "le"} restaurer pendant 30 jours.") },
        confirmButton = { TextButton(onClick = { confirm = false; val files = sel.toList(); clear(); scope.launch { ops.run("Suppression…", "Mis à la corbeille") { files.forEach { Fs.toTrash(it) }; files } } }) { Text("Supprimer", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
    )
}

@Composable
fun BusyBar(ops: Ops) {
    ops.busy?.let { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(it, color = Muted, fontSize = 13.sp); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Orange) } }
}

// ---------------------------------------------------------------- navigateur

@Composable
fun BrowseScreen(dir: File, tick: Int, clip: Clip?, ops: Ops, onClip: (Clip?) -> Unit, go: (Screen) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hidden by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("nom") }
    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var newText by remember { mutableStateOf(false) }
    val sel = remember(dir) { mutableStateListOf<File>() }
    val items by produceState(emptyList<File>(), dir, tick, hidden, sort) { value = withContext(Dispatchers.IO) { Fs.list(dir, hidden, sort) } }
    BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }
    val title = if (dir == Fs.primary) "Mémoire interne" else dir.name
    Column(Modifier.fillMaxSize()) {
        if (sel.isEmpty()) TopBar(title, dir.path.removePrefix(Fs.primary.path).ifBlank { "/" }, back) {
            Box {
                IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.AddCircle, "Créer", tint = Orange) }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null, tint = Orange) }, text = { Text("Nouveau dossier") }, onClick = { addMenu = false; newFolder = true })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.NoteAdd, null, tint = Orange) }, text = { Text("Nouveau fichier texte") }, onClick = { addMenu = false; newText = true })
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Sort, "Trier", tint = TextC) }
                DropdownMenu(menu, { menu = false }) {
                    listOf("nom" to "Nom", "date" to "Date", "taille" to "Taille", "type" to "Type").forEach { (k, l) ->
                        DropdownMenuItem(text = { Text((if (sort == k) "✓ " else "") + "Trier par $l") }, onClick = { sort = k; menu = false })
                    }
                    DropdownMenuItem(text = { Text((if (hidden) "✓ " else "") + "Fichiers cachés") }, onClick = { hidden = !hidden; menu = false })
                }
            }
        } else SelectionBar(sel.toList(), ops, onClip, { sel.clear() }, { sel.clear(); sel.addAll(items) }, dir)
        BusyBar(ops)
        Box(Modifier.weight(1f)) {
            if (items.isEmpty()) Text("Dossier vide", color = Muted, modifier = Modifier.align(Alignment.Center))
            LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(items, key = { it.path }) { f ->
                    FileRow(f, f in sel, onClick = {
                        when {
                            sel.isNotEmpty() -> if (f in sel) sel.remove(f) else sel.add(f)
                            f.isDirectory -> go(Screen.Browse(f))
                            ext(f) == "zip" -> scope.launch { ops.run("Extraction…", "Archive extraite") { listOf(Fs.unzip(f, dir)) } }
                            else -> openAny(ctx, f, go)
                        }
                    }, onLong = { if (f in sel) sel.remove(f) else sel.add(f) })
                }
            }
        }
        val c = clip
        if (c != null && sel.isEmpty()) Row(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(16.dp)).background(Card2).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${c.files.size} élément${if (c.files.size > 1) "s" else ""} ${if (c.cut) "à déplacer" else "à copier"}", color = TextC, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { onClip(null) }) { Text("Annuler", color = Muted) }
            Button(onClick = {
                if (c.files.any { dir.path == it.path || dir.path.startsWith(it.path + "/") }) { Toast.makeText(ctx, "Impossible de copier un dossier dans lui-même", Toast.LENGTH_SHORT).show(); return@Button }
                onClip(null)
                scope.launch { ops.run(if (c.cut) "Déplacement…" else "Copie…", if (c.cut) "Déplacé" else "Copié") { c.files.map { if (c.cut) Fs.move(it, dir) else Fs.copy(it, dir) } } }
            }, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange)) { Text(if (c.cut) "Déplacer ici" else "Coller ici", fontWeight = FontWeight.Bold) }
        }
    }
    if (newFolder) AskText("Nouveau dossier", "Nouveau dossier", "Créer") { n ->
        newFolder = false
        if (!n.isNullOrBlank()) { val d = Fs.freeName(dir, n); if (d.mkdirs()) scope.launch { ops.run("", "Dossier créé") { emptyList() } } else Toast.makeText(ctx, "Création impossible", Toast.LENGTH_SHORT).show() }
    }
    if (newText) AskText("Nouveau fichier texte", "Note.txt", "Créer") { n ->
        newText = false
        if (!n.isNullOrBlank()) {
            val f = Fs.freeName(dir, if (n.contains('.')) n else "$n.txt")
            try { f.writeText(""); go(Screen.Edit(f)) } catch (e: Exception) { Toast.makeText(ctx, "Création impossible", Toast.LENGTH_SHORT).show() }
        }
    }
}

// ---------------------------------------------------------------- listes (catégories, récents, gros fichiers)

@Composable
fun ListScreen(title: String, tick: Int, ops: Ops, onClip: (Clip?) -> Unit, go: (Screen) -> Unit, back: () -> Unit, sub: String? = null, load: () -> List<File>) {
    val ctx = LocalContext.current
    val sel = remember(title) { mutableStateListOf<File>() }
    var items by remember(title) { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(title, tick) { items = withContext(Dispatchers.IO) { load() } }
    BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }
    val list = items
    Column(Modifier.fillMaxSize()) {
        if (sel.isEmpty()) TopBar(title, sub ?: list?.let { "${it.size} fichier${if (it.size > 1) "s" else ""} · ${size(it.sumOf { f -> f.length() })}" }, back)
        else SelectionBar(sel.toList(), ops, onClip, { sel.clear() }, { sel.clear(); sel.addAll(list ?: emptyList()) }, null)
        BusyBar(ops)
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Rien ici", color = Muted) }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(list, key = { it.path }) { f ->
                    FileRow(f, f in sel, showPath = true,
                        onClick = { if (sel.isNotEmpty()) { if (f in sel) sel.remove(f) else sel.add(f) } else openAny(ctx, f, go) },
                        onLong = { if (f in sel) sel.remove(f) else sel.add(f) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- recherche

@Composable
fun SearchScreen(ops: Ops, onClip: (Clip?) -> Unit, go: (Screen) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<File>() }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun go2() {
        if (q.length < 2) return
        job?.cancel(); results.clear(); running = true
        job = scope.launch {
            val roots = withContext(Dispatchers.IO) { Fs.volumes(ctx).map { it.root } }
            val found = mutableListOf<File>()
            withContext(Dispatchers.IO) { Fs.search(roots, q) { found.add(it) } }
            results.addAll(found); running = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Rechercher", null, back)
        OutlinedTextField(
            q, { q = it }, placeholder = { Text("Nom du fichier ou du dossier") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { TextButton(onClick = { go2() }) { Text("Chercher", color = Orange) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(14.dp),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go2() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        )
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = Orange)
        else if (results.isNotEmpty()) Text("${results.size} résultat${if (results.size > 1) "s" else ""}${if (results.size >= 400) " (les 400 premiers)" else ""}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 10.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(results, key = { it.path }) { f ->
                FileRow(f, false, showPath = true, onClick = { if (f.isDirectory) go(Screen.Browse(f)) else openAny(ctx, f, go) }, onLong = { f.parentFile?.let { go(Screen.Browse(it)) } })
            }
        }
    }
}

// ---------------------------------------------------------------- corbeille

@Composable
fun TrashScreen(tick: Int, ops: Ops, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Fs.Trashed>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { list = withContext(Dispatchers.IO) { Fs.trashed() } }
    val l = list
    Column(Modifier.fillMaxSize()) {
        TopBar("Corbeille", "Effacé définitivement après 30 jours", back) {
            if (!l.isNullOrEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Vider", color = Red) }
        }
        BusyBar(ops)
        when {
            l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
            l.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("La corbeille est vide", color = Muted) }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(l, key = { it.file.path }) { t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (t.file.isDirectory) Icons.Filled.Folder else catIcon(catOf(t.file)), null, tint = Muted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(File(t.from).name, color = TextC, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Supprimé le ${date(t.at)}", color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { scope.launch { ops.run("Restauration…", "Restauré") { listOf(Fs.restore(t)) } } }) { Icon(Icons.Filled.Restore, "Restaurer", tint = Green) }
                        IconButton(onClick = { scope.launch { ops.run("Suppression…", "Supprimé définitivement") { Fs.purge(t); emptyList() } } }) { Icon(Icons.Filled.DeleteForever, "Supprimer définitivement", tint = Red) }
                    }
                }
            }
        }
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false }, containerColor = Card2,
        title = { Text("Vider la corbeille ?") }, text = { Text("Tous ces fichiers seront supprimés définitivement.") },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; val all = l ?: emptyList(); scope.launch { ops.run("Suppression…", "Corbeille vidée") { all.forEach { Fs.purge(it) }; emptyList() } } }) { Text("Vider", color = Red) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Annuler") } },
    )
}


// ---------------------------------------------------------------- éditeur de texte intégré

@Composable
fun EditorScreen(file: File, ops: Ops, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(file) { mutableStateOf<String?>(null) }
    var saved by remember(file) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    LaunchedEffect(file) { val t = withContext(Dispatchers.IO) { try { file.readText() } catch (e: Exception) { "" } }; text = t; saved = t }
    val changed = text != null && text != saved
    fun save(then: () -> Unit = {}) {
        val t = text ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { try { file.writeText(t); true } catch (e: Exception) { false } }
            if (ok) { saved = t; withContext(Dispatchers.IO) { Fs.rescan(ctx, listOf(file)) }; Toast.makeText(ctx, "Enregistré", Toast.LENGTH_SHORT).show(); then() }
            else Toast.makeText(ctx, "Enregistrement impossible", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler { if (changed) askLeave = true else back() }
    Column(Modifier.fillMaxSize()) {
        TopBar(file.name, if (changed) "Modifié · non enregistré" else file.parentFile?.name ?: "", { if (changed) askLeave = true else back() }) {
            IconButton(onClick = { save() }, enabled = changed) { Icon(Icons.Filled.Save, "Enregistrer", tint = if (changed) Orange else Muted) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Plus", tint = TextC) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null, tint = Orange) }, text = { Text("Exporter en PDF") }, onClick = {
                        menu = false
                        val t = text ?: ""
                        scope.launch { ops.run("Création du PDF…", "PDF créé à côté du fichier") { listOf(Fs.textToPdf(file.nameWithoutExtension, t, Fs.freeName(file.parentFile!!, file.nameWithoutExtension + ".pdf"))) } }
                    })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Share, null, tint = TextC) }, text = { Text("Partager") }, onClick = { menu = false; shareFiles(ctx, listOf(file)) })
                    DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.OpenInNew, null, tint = TextC) }, text = { Text("Ouvrir avec…") }, onClick = { menu = false; openFile(ctx, file) })
                }
            }
        }
        BusyBar(ops)
        val t = text
        if (t == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
        else androidx.compose.foundation.text.BasicTextField(
            t, { text = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = TextC, fontSize = 15.sp, lineHeight = 22.sp,
                fontFamily = if (ext(file) in setOf("txt", "md")) androidx.compose.ui.text.font.FontFamily.Default else androidx.compose.ui.text.font.FontFamily.Monospace),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Orange),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(Card2)
                .verticalScroll(rememberScrollState()).padding(14.dp).imePadding(),
            decorationBox = { inner -> if (t.isEmpty()) Text("Écrivez ici…", color = Muted, fontSize = 15.sp); inner() },
        )
    }
    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false }, containerColor = Card2,
        title = { Text("Enregistrer les modifications ?") },
        confirmButton = { TextButton(onClick = { askLeave = false; save(back) }) { Text("Enregistrer", color = Orange, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { askLeave = false; back() }) { Text("Ne pas enregistrer", color = Red) } },
    )
}
