@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.phoenixpro.music.Album
import fr.phoenixpro.music.Covers
import fr.phoenixpro.music.Engine
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Store
import fr.phoenixpro.music.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

val FR: Locale = Locale.FRANCE

fun dur(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun longDur(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min"
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n > 1) many else one

private val placeholderColors = listOf(PhoenixBlue, PhoenixPurple, PhoenixOrange, PhoenixTeal)

/** Pochette d'album, chargée en arrière-plan, avec un dégradé Phoenix si aucune image. */
@Composable
fun Cover(album: Album?, modifier: Modifier = Modifier, size: Dp = 56.dp, corner: Dp = 10.dp, px: Int = 300) {
    val ctx = LocalContext.current
    val ver by Covers.version.collectAsState()
    val bmp by produceState<Bitmap?>(null, album?.coverKey, ver) {
        value = album?.let { Covers.load(ctx, it, px) }
        if (value == null && album != null && Store.settings.value.onlineCovers) {
            Covers.fetchOnline(ctx, album)
        }
    }
    val shape = RoundedCornerShape(corner)
    val b = bmp
    if (b != null) {
        Image(
            b.asImageBitmap(), null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        val c = placeholderColors[abs((album?.coverKey ?: "").hashCode()) % placeholderColors.size]
        Box(
            modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Album, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize(0.42f))
        }
    }
}

/** Photo d'artiste (Wikipédia) ou pochette d'un de ses albums. */
@Composable
fun ArtistImage(name: String, fallback: Album?, size: Dp) {
    val ctx = LocalContext.current
    val ver by Covers.version.collectAsState()
    val bmp by produceState<Bitmap?>(null, name, ver) {
        value = withContext(Dispatchers.IO) {
            val f = Covers.artistPhoto(ctx, name)
            if (f.exists()) Covers.loadFile(f, 400) else null
        }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 2)))
    else if (fallback != null) Cover(fallback, size = size, corner = size / 2)
    else Box(Modifier.size(size).clip(RoundedCornerShape(size / 2)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(FR), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
fun EmptyState(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun Badge(text: String, color: Color, bg: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing / 2),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Ligne d'un morceau. */
@Composable
fun TrackRow(
    t: Track,
    ui: UiState,
    number: String? = null,
    showCover: Boolean = false,
    sub: String = t.artist,
    onClick: () -> Unit,
    extraMenu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val ps by Engine.state.collectAsState()
    val current = ps.key == t.key
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            Cover(Library.albumOf(t), size = 46.dp, corner = 8.dp, px = 160)
            Spacer(Modifier.width(12.dp))
        } else if (number != null) {
            Text(
                number, style = MaterialTheme.typography.bodyMedium,
                color = if (current) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(30.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) accent else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else null,
            )
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(dur(t.durationMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TrackMenu(listOf(t), ui, extraMenu)
    }
}

/** Menu « ⋮ » : lire ensuite, file d'attente, playlist, favori, album, artiste. */
@Composable
fun TrackMenu(
    tracks: List<Track>,
    ui: UiState,
    extra: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    icon: ImageVector = Icons.Outlined.MoreVert,
) {
    var open by remember { mutableStateOf(false) }
    val favs by Store.favorites.collectAsState()
    Box {
        IconButton(onClick = { open = true }) { Icon(icon, "Plus", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Lire ensuite") }, onClick = { open = false; Engine.playNext(tracks); ui.toast("Sera lu ensuite") })
            DropdownMenuItem(text = { Text("Ajouter à la file d'attente") }, onClick = { open = false; Engine.enqueue(tracks); ui.toast("Ajouté à la file d'attente") })
            DropdownMenuItem(text = { Text("Ajouter à une playlist…") }, onClick = { open = false; ui.addToPlaylist = tracks.map { it.key } })
            if (tracks.size == 1) {
                val t = tracks[0]
                DropdownMenuItem(
                    text = { Text(if (t.key in favs) "Retirer des favoris" else "Ajouter aux favoris") },
                    onClick = { open = false; Store.toggleFavorite(t.key) },
                )
                DropdownMenuItem(text = { Text("Voir l'album") }, onClick = { open = false; ui.push(Page.AlbumPage(t.albumId)) })
                DropdownMenuItem(text = { Text("Voir l'artiste") }, onClick = {
                    open = false; ui.push(Page.ArtistPage(Library.albumOf(t)?.artist ?: t.artist))
                })
            }
            extra?.invoke(this) { open = false }
        }
    }
}

/** Carte d'album pour les grilles. */
@Composable
fun AlbumCard(a: Album, modifier: Modifier = Modifier, sub: String = a.artist, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Cover(a, size = maxWidth, corner = 12.dp, px = 400)
        }
        Spacer(Modifier.height(6.dp))
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Fenêtre « Ajouter à une playlist ». */
@Composable
fun PlaylistPickerDialog(keys: List<String>, ui: UiState, onClose: () -> Unit) {
    val lists by Store.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (creating) "Nouvelle playlist" else "Ajouter à une playlist") },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { creating = true }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("Nouvelle playlist", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    items(lists, key = { it.id }) { p ->
                        Text(
                            "${p.name}  ·  ${plural(p.keys.size, "titre")}",
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                Store.addToPlaylist(p.id, keys); ui.toast("Ajouté à « ${p.name} »"); onClose()
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(onClick = {
                val p = Store.newPlaylist(name, keys); ui.toast("Playlist « ${p.name} » créée"); onClose()
            }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } },
    )
}
