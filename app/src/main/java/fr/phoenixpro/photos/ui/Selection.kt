package fr.phoenixpro.photos.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.phoenixpro.photos.Db
import fr.phoenixpro.photos.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val StarGold = Color(0xFFFFC23D)

/** Photos sélectionnées (appui long sur une vignette). */
class SelectionState {
    val ids = mutableStateListOf<Long>()
    /** Mode sélection ouvert depuis le menu, même sans photo cochée. */
    var mode by mutableStateOf(false)
    /** Toutes les photos de l'écran affiché, pour « Tout sélectionner ». */
    var visible: List<Long> = emptyList()
    val active: Boolean get() = mode || ids.isNotEmpty()
    fun toggle(id: Long) { if (id in ids) ids.remove(id) else ids.add(id) }
    fun clear() { ids.clear(); mode = false }
    fun selectAll() { val s = ids.toHashSet(); ids.addAll(visible.filter { it !in s }) }
}

/** Ordre d'affichage choisi, mémorisé. */
class SortState(private val prefs: Prefs) {
    var key by mutableStateOf(prefs.sort)
        private set
    val byDate: Boolean get() = key.startsWith("date")
    fun set(k: String) { key = k; prefs.sort = k }

    /** Nombre de photos par ligne (pincer la galerie pour changer). */
    var cols by mutableIntStateOf(prefs.gridColumns.coerceIn(MIN_COLS, MAX_COLS))
        private set
    fun setColumns(n: Int) { cols = n.coerceIn(MIN_COLS, MAX_COLS); prefs.gridColumns = cols }

    companion object { const val MIN_COLS = 1; const val MAX_COLS = 7 }
}

/** Corbeille : demande la confirmation d'Android puis retire les photos. */
fun interface TrashAction { fun trash(ids: List<Long>) }

val LocalSelection = staticCompositionLocalOf { SelectionState() }
val LocalSort = staticCompositionLocalOf<SortState> { error("SortState") }
val LocalTrash = staticCompositionLocalOf { TrashAction { } }

@Composable
fun rememberTrashAction(onDone: (List<Long>) -> Unit): TrashAction {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val ids = pending
        pending = emptyList()
        if (r.resultCode == Activity.RESULT_OK && ids.isNotEmpty()) {
            scope.launch {
                withContext(Dispatchers.IO) { Db.removePhotos(ctx, ids) }
                Toast.makeText(ctx, plural(ids.size, "photo mise", "photos mises") + " à la corbeille", Toast.LENGTH_SHORT).show()
                onDone(ids)
            }
        }
    }
    return remember {
        TrashAction { ids ->
            if (ids.isEmpty()) return@TrashAction
            val uris = ids.map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
            if (Build.VERSION.SDK_INT >= 30) {
                pending = ids
                val pi = MediaStore.createTrashRequest(ctx.contentResolver, uris, true)
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { deleteLegacy(ctx, ids) }
                    if (ok.isNotEmpty()) { Db.removePhotos(ctx, ok); onDone(ok) }
                }
            }
        }
    }
}

private fun deleteLegacy(ctx: Context, ids: List<Long>): List<Long> = ids.filter { id ->
    try {
        ctx.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null) > 0
    } catch (e: Exception) {
        false
    }
}

@Composable
fun SortButton() {
    val sort = LocalSort.current
    val sel = LocalSelection.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Trier et afficher") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Sélectionner des photos") },
                leadingIcon = { Icon(Icons.Outlined.CheckCircle, null) },
                onClick = { sel.mode = true; open = false },
            )
            HorizontalDivider()
            Text("Taille des photos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            listOf(1 to "Très grande", 2 to "Grande", 3 to "Moyenne", 4 to "Petite", 6 to "Très petite").forEach { (n, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (n == sort.cols) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { sort.setColumns(n); open = false },
                )
            }
            HorizontalDivider()
            Text("Trier par", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Db.SORTS.forEach { (k, v) ->
                DropdownMenuItem(
                    text = { Text(v.first, color = if (k == sort.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { sort.set(k); open = false },
                )
            }
        }
    }
}

@Composable
fun StarsRow(rating: Int, size: Dp = 28.dp, idle: Color = MaterialTheme.colorScheme.outline, onRate: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (k in 1..5) {
            Icon(
                if (k <= rating) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = "$k étoile${if (k > 1) "s" else ""}",
                tint = if (k <= rating) StarGold else idle,
                modifier = Modifier.size(size + 8.dp).clip(RoundedCornerShape(50)).clickable { onRate(if (k == rating) 0 else k) }.padding(4.dp),
            )
        }
    }
}

/** Barre affichée en haut pendant une sélection : noter, corbeille. */
@Composable
fun SelectionBar() {
    val sel = LocalSelection.current
    val trash = LocalTrash.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var rateMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { sel.clear() }) { Icon(Icons.Outlined.Close, "Annuler", tint = Color.White) }
        Text(if (sel.ids.isEmpty()) "Touchez des photos" else "${sel.ids.size}", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { sel.selectAll() }) { Icon(Icons.Outlined.SelectAll, "Tout sélectionner", tint = Color.White) }
        Box {
            IconButton(onClick = { rateMenu = true }) { Icon(Icons.Filled.Star, "Noter", tint = Color.White) }
            DropdownMenu(expanded = rateMenu, onDismissRequest = { rateMenu = false }) {
                Box(Modifier.padding(horizontal = 8.dp)) {
                    StarsRow(0) { r ->
                        val ids = sel.ids.toList()
                        rateMenu = false
                        scope.launch {
                            withContext(Dispatchers.IO) { Db.setRating(ids, r) }
                            Toast.makeText(ctx, plural(ids.size, "photo notée", "photos notées") + " " + "★".repeat(r), Toast.LENGTH_SHORT).show()
                            sel.clear()
                        }
                    }
                }
                DropdownMenuItem(text = { Text("Retirer la note") }, onClick = {
                    val ids = sel.ids.toList(); rateMenu = false
                    scope.launch { withContext(Dispatchers.IO) { Db.setRating(ids, 0) }; sel.clear() }
                })
            }
        }
        IconButton(onClick = { trash.trash(sel.ids.toList()) }) { Icon(Icons.Outlined.Delete, "Corbeille", tint = Color.White) }
    }
}
