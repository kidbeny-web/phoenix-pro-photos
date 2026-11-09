package fr.phoenixpro.photos.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import fr.phoenixpro.photos.Db
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Viewer(ids: List<Long>, start: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val trash = LocalTrash.current
    val scope = rememberCoroutineScope()
    val viewerState = LocalViewer.current
    val idsNow by rememberUpdatedState(ids)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (ids.size - 1).coerceAtLeast(0))) { idsNow.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var nameFace by remember { mutableStateOf<Long?>(null) }
    val currentId = ids.getOrNull(pager.currentPage) ?: return
    val photo = rememberData(currentId) { Db.photo(currentId) }
    val faces = rememberData(currentId) { Db.facesOf(currentId) } ?: emptyList()

    BackHandler { onClose() }
    LaunchedEffect(pager.currentPage) { zoomed = false; viewerState.index = pager.currentPage }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, key = { ids[it] }, beyondBoundsPageCount = 1) { page ->
            ZoomableImage(ids[page], isCurrent = page == pager.currentPage, zoomed = zoomed && page == pager.currentPage,
                onZoom = { zoomed = it }, onTap = { chrome = !chrome })
        }
        if (chrome) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Fermer", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    photo?.let {
                        Text(dayLabel(it.taken), color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(timeLabel(it.taken) + (it.place?.let { p -> " · $p" } ?: ""), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                    .navigationBarsPadding().padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            StarsRow(photo?.rating ?: 0, size = 26.dp, idle = Color(0x99FFFFFF)) { r ->
                scope.launch { withContext(Dispatchers.IO) { Db.setRating(listOf(currentId), r) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ViewerAction(Icons.Outlined.Share, "Partager") {
                    photo?.let {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"; putExtra(Intent.EXTRA_STREAM, it.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Partager la photo"))
                    }
                }
                ViewerAction(if (photo?.fav == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favori",
                    tint = if (photo?.fav == true) Color(0xFFFF8A73) else Color.White) {
                    scope.launch { withContext(Dispatchers.IO) { Db.toggleFav(currentId) } }
                }
                ViewerAction(Icons.Outlined.Info, "Infos") { info = true }
                ViewerAction(Icons.Outlined.Delete, "Corbeille") { trash.trash(listOf(currentId)) }
            }
            }
        }
    }

    if (info && photo != null) {
        ModalBottomSheet(onDismissRequest = { info = false }, containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
                if (faces.isNotEmpty()) {
                    Text("PERSONNES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(faces, key = { it.id }) { f ->
                            Column(
                                Modifier.width(68.dp).clickable {
                                    if (f.name != null && f.personId != null) { info = false; onClose(); nav.go(Screen.Person(f.personId)) }
                                    else nameFace = f.id
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                FaceAvatar(f.id, 60.dp)
                                Text(f.name ?: "Qui ?", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (f.name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
                InfoLine(Icons.Outlined.CalendarToday, fullDate(photo.taken), timeLabel(photo.taken))
                if (photo.place != null) InfoLine(Icons.Outlined.Place, photo.place, listOfNotNull(photo.region, photo.country).joinToString(", ")) {
                    info = false; onClose(); nav.go(Screen.Place(photo.place))
                }
                val mp = if (photo.width > 0) "%.1f Mpx".format(FR, photo.width * photo.height / 1e6) else ""
                InfoLine(Icons.Outlined.Image, photo.name, listOf(if (photo.width > 0) "${photo.width} × ${photo.height}" else "", mp, "%.1f Mo".format(FR, photo.size / 1048576.0)).filter { it.isNotBlank() }.joinToString(" · "))
                photo.folder?.let { InfoLine(Icons.Outlined.Folder, it, null) }
                if (photo.lat != null && photo.lon != null) {
                    Spacer(Modifier.height(12.dp))
                    MiniMap(photo.lat, photo.lon, Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)))
                }
            }
        }
    }
    nameFace?.let { fid -> NameFaceDialog(fid) { nameFace = null } }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint)
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, title: String, sub: String?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ZoomableImage(id: Long, isCurrent: Boolean, zoomed: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(zoomed) { if (!zoomed) offset = Offset.Zero }
    val scale = if (zoomed) 2.6f else 1f
    val uri = remember(id) { android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(isCurrent, zoomed) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { onZoom(!zoomed) })
            }
            .then(if (zoomed) Modifier.pointerInput(Unit) {
                detectDragGestures { change, drag -> change.consume(); offset += drag }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@Composable
fun MiniMap(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c ->
            MapView(c).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(false)
                isClickable = false
                controller.setZoom(13.0)
                controller.setCenter(GeoPoint(lat, lon))
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                val m = Marker(this); m.position = GeoPoint(lat, lon); m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                overlays.add(m)
            }
        },
        modifier = modifier,
        onRelease = { it.onDetach() },
    )
}
