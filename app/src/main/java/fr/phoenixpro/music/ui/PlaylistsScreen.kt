@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.phoenixpro.music.Engine
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Store
import fr.phoenixpro.music.Track

/** Morceaux d'une playlist (perso ou automatique). */
fun playlistTracks(id: Long): List<Track> {
    val by = Library.byKey
    return when (id) {
        -1L -> Store.favorites.value.mapNotNull { by[it] }.sortedBy { Library.norm(it.artist) + Library.norm(it.album) }
        -2L -> Store.history.value.mapNotNull { by[it.key] }.take(100)
        -3L -> Store.counts.value.entries.sortedByDescending { it.value }.mapNotNull { by[it.key] }.take(100)
        else -> Store.playlists.value.firstOrNull { it.id == id }?.keys?.mapNotNull { by[it] } ?: emptyList()
    }
}

fun playlistName(id: Long): String = when (id) {
    -1L -> "Favoris"
    -2L -> "Écoutés récemment"
    -3L -> "Les plus écoutés"
    else -> Store.playlists.value.firstOrNull { it.id == id }?.name ?: "Playlist"
}

@Composable
private fun SmartTile(icon: ImageVector, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PlaylistsScreen(ui: UiState) {
    val lists by Store.playlists.collectAsState()
    val favs by Store.favorites.collectAsState()
    val hist by Store.history.collectAsState()
    Library.tracks.collectAsState().value
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ScreenTitle("Playlists") {
                IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "Nouvelle playlist") }
            }
        }
        item { SmartTile(Icons.Outlined.Favorite, "Favoris", plural(favs.size, "titre"), PhoenixOrange) { ui.push(Page.PlaylistPage(-1)) } }
        item { SmartTile(Icons.Outlined.History, "Écoutés récemment", plural(hist.size.coerceAtMost(100), "titre"), PhoenixBlue) { ui.push(Page.PlaylistPage(-2)) } }
        item { SmartTile(Icons.Outlined.TrendingUp, "Les plus écoutés", "Tes 100 titres préférés", PhoenixPurple) { ui.push(Page.PlaylistPage(-3)) } }
        item { SectionLabel("Mes playlists") }
        if (lists.isEmpty()) item {
            EmptyState("Aucune playlist", "Crée ta première playlist, ou ajoute des titres depuis le menu ⋮ d'un morceau ou d'un album.") {
                Button(onClick = { creating = true }) { Text("Créer une playlist") }
            }
        }
        items(lists, key = { it.id }) { p ->
            val first = p.keys.firstNotNullOfOrNull { Library.byKey[it] }
            Row(
                Modifier.fillMaxWidth().clickable { ui.push(Page.PlaylistPage(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(first?.let { Library.albumOf(it) }, size = 52.dp, corner = 12.dp, px = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(plural(p.keys.size, "titre"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (creating) NameDialog("Nouvelle playlist", "", onClose = { creating = false }) { name ->
        val p = Store.newPlaylist(name); ui.push(Page.PlaylistPage(p.id))
    }
}

@Composable
fun NameDialog(title: String, initial: String, onClose: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(name); onClose() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler") } },
    )
}

@Composable
fun PlaylistScreen(id: Long, ui: UiState) {
    val lists by Store.playlists.collectAsState()
    val favs by Store.favorites.collectAsState()
    val hist by Store.history.collectAsState()
    val counts by Store.counts.collectAsState()
    val lib by Library.tracks.collectAsState()
    val tracks = remember(id, lists, favs, hist, counts, lib) { playlistTracks(id) }
    val custom = id > 0
    val pl = lists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(ui) {
            if (custom && pl != null) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, "Renommer") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "Supprimer") }
            }
            if (id == -2L || id == -3L) TextButton(onClick = { Store.clearHistory() }) { Text("Effacer") }
        }
        ScreenTitle(playlistName(id), "${plural(tracks.size, "titre")} · ${longDur(tracks.sumOf { it.durationMs })}")
        PlayButtons(tracks)
        if (tracks.isEmpty()) {
            EmptyState("Vide pour l'instant", when (id) {
                -1L -> "Touche le cœur pendant la lecture pour ajouter un favori."
                -2L, -3L -> "Écoute de la musique et elle apparaîtra ici."
                else -> "Ajoute des titres depuis le menu ⋮ d'un morceau ou d'un album."
            })
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { i, t -> "$i/${t.key}" }) { i, t ->
                TrackRow(
                    t, ui, showCover = true, sub = "${t.artist} · ${t.album}",
                    onClick = { Engine.play(tracks, i) },
                    extraMenu = { close ->
                        if (custom && pl != null) {
                            val ki = pl.keys.indexOf(t.key)
                            if (ki > 0) DropdownMenuItem(text = { Text("Monter") }, onClick = {
                                close(); val k = pl.keys.toMutableList(); java.util.Collections.swap(k, ki, ki - 1); Store.updatePlaylist(pl.copy(keys = k))
                            })
                            if (ki >= 0 && ki < pl.keys.lastIndex) DropdownMenuItem(text = { Text("Descendre") }, onClick = {
                                close(); val k = pl.keys.toMutableList(); java.util.Collections.swap(k, ki, ki + 1); Store.updatePlaylist(pl.copy(keys = k))
                            })
                            DropdownMenuItem(text = { Text("Retirer de la playlist") }, onClick = {
                                close(); Store.updatePlaylist(pl.copy(keys = pl.keys.filter { it != t.key }))
                            })
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (renaming && pl != null) NameDialog("Renommer", pl.name, onClose = { renaming = false }) { Store.updatePlaylist(pl.copy(name = it.ifBlank { pl.name })) }
    if (deleting && pl != null) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Supprimer « ${pl.name} » ?") },
        text = { Text("Les morceaux restent dans ta bibliothèque.") },
        confirmButton = { TextButton(onClick = { deleting = false; Store.deletePlaylist(pl.id); ui.pop() }) { Text("Supprimer") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annuler") } },
    )
}
