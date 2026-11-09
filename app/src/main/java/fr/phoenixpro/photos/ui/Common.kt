package fr.phoenixpro.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.phoenixpro.photos.FaceEngine
import fr.phoenixpro.photos.Indexer
import fr.phoenixpro.photos.PhoenixApp
import fr.phoenixpro.photos.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val FR: Locale = Locale.FRANCE

fun String.cap() = replaceFirstChar { it.titlecase(FR) }

fun dayLabel(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val pattern = if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "EEE d MMM" else "EEE d MMM yyyy"
    return SimpleDateFormat(pattern, FR).format(Date(t)).cap()
}

fun monthLabel(t: Long) = SimpleDateFormat("MMMM yyyy", FR).format(Date(t)).cap()
fun fullDate(t: Long) = SimpleDateFormat("EEEE d MMMM yyyy", FR).format(Date(t)).cap()
fun timeLabel(t: Long) = SimpleDateFormat("HH:mm", FR).format(Date(t))

fun plural(n: Int, one: String, many: String) = "${"%,d".format(FR, n)} ${if (n > 1) many else one}"

/** Charge des données hors du fil principal et les recharge quand la base change. */
@Composable
fun <T> rememberData(vararg keys: Any?, load: () -> T): T? {
    val version by PhoenixApp.dataVersion.collectAsState()
    var value by remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(version, *keys) {
        value = withContext(Dispatchers.IO) { load() }
    }
    return value
}

@Composable
fun ScreenTitle(title: String, sub: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
fun ProgressBanner() {
    val p by Indexer.progress.collectAsState()
    if (!p.running) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            p.phase + if (p.total > 0) " · ${p.done}/${p.total}" else "",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun EmptyState(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FaceAvatar(faceId: Long, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    AsyncImage(
        model = FaceEngine.faceFile(ctx, faceId),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun Thumb(photo: Photo, modifier: Modifier = Modifier, selecting: Boolean = false, selected: Boolean = false) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = photo.uri, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (selected) Modifier.padding(10.dp).clip(RoundedCornerShape(8.dp)) else Modifier),
        )
        if (photo.fav) Icon(
            Icons.Filled.Favorite, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(14.dp),
        )
        if (photo.rating > 0) Text(
            "★ ${photo.rating}", color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0x73000000)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        if (selecting) Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000))
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

/** Grille de photos groupées par mois et par jour. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyGridScope.photoSections(photos: List<Photo>, byDate: Boolean, sel: SelectionState, onOpen: (Int) -> Unit) {
    if (!byDate) {
        photos.forEachIndexed { i, p ->
            item(key = p.id) { PhotoCell(p, i, sel, onOpen) }
        }
        return
    }
    var lastMonth = ""
    var lastDay = ""
    val cal = Calendar.getInstance()
    fun keys(t: Long): Pair<String, String> {
        cal.timeInMillis = t
        val m = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        return m to "$m-${cal.get(Calendar.DAY_OF_MONTH)}"
    }
    val placesByDay = HashMap<String, LinkedHashSet<String>>()
    for (p in photos) p.place?.let { placesByDay.getOrPut(keys(p.taken).second) { LinkedHashSet() }.add(it) }
    photos.forEachIndexed { i, p ->
        val (month, day) = keys(p.taken)
        if (month != lastMonth) {
            lastMonth = month
            item(key = "m$month", span = { GridItemSpan(maxLineSpan) }) {
                Text(monthLabel(p.taken), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 4.dp))
            }
        }
        if (day != lastDay) {
            lastDay = day
            val places = placesByDay[day]?.take(2)?.joinToString(", ") ?: ""
            item(key = "d$day", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(dayLabel(p.taken), style = MaterialTheme.typography.titleSmall)
                    if (places.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(places, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item(key = p.id) { PhotoCell(p, i, sel, onOpen) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(p: Photo, i: Int, sel: SelectionState, onOpen: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val selected = p.id in sel.ids
    Thumb(
        p,
        Modifier.aspectRatio(1f).combinedClickable(
            onClick = { if (sel.active) sel.toggle(p.id) else onOpen(i) },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); sel.toggle(p.id) },
        ),
        selecting = sel.active, selected = selected,
    )
}

@Composable
fun PhotoGrid(
    photos: List<Photo>,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val viewer = LocalViewer.current
    val sel = LocalSelection.current
    val sort = LocalSort.current
    val ids = remember(photos) { photos.map { it.id } }
    LaunchedEffect(ids) { sel.visible = ids }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        header?.invoke(this)
        photoSections(photos, sort.byDate, sel) { i -> viewer.open(ids, i) }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(FR), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
fun PersonCell(name: String?, faceId: Long, count: Int, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FaceAvatar(faceId, 92.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            name ?: "Ajouter un nom", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Text(plural(count, "photo", "photos"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun LazyGridScope.fullWidth(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
