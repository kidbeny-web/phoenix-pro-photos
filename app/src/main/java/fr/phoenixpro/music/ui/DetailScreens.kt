@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.phoenixpro.music.AudioInfo
import fr.phoenixpro.music.AudioInfos
import fr.phoenixpro.music.Covers
import fr.phoenixpro.music.Engine
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Track
import kotlinx.coroutines.launch

@Composable
fun BackBar(ui: UiState, title: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { ui.pop() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour") }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun PlayButtons(tracks: List<Track>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { Engine.play(tracks) }, modifier = Modifier.weight(1f), enabled = tracks.isNotEmpty()) {
            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Lire")
        }
        FilledTonalButton(onClick = { Engine.play(tracks, shuffle = true) }, modifier = Modifier.weight(1f), enabled = tracks.isNotEmpty()) {
            Icon(Icons.Outlined.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Aléatoire")
        }
    }
}

@Composable
fun FormatBadges(info: AudioInfo?) {
    if (info == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(info.label(), MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
        if (info.hiRes) Badge("HI-RES", androidx.compose.ui.graphics.Color.White, PhoenixOrange)
    }
}

@Composable
fun AlbumScreen(id: Long, ui: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Library.albums.collectAsState().value // se met à jour après un nouveau scan
    val a = Library.album(id)
    if (a == null) {
        Column { BackBar(ui); EmptyState("Album introuvable", "Il a peut-être été supprimé.") }
        return
    }
    val ver by Covers.version.collectAsState()
    val info = remember(a.coverKey, ver) { Covers.albumInfo(ctx, a) }
    val fmt by produceState<AudioInfo?>(null, a.id) { value = AudioInfos.read(ctx, a.tracks.first()) }
    var searching by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackBar(ui) {
                TrackMenu(a.tracks, ui, extra = { close ->
                    DropdownMenuItem(text = { Text("Chercher la pochette sur Internet") }, onClick = {
                        close(); searching = true
                        scope.launch {
                            val ok = Covers.fetchOnline(ctx, a, force = true)
                            searching = false
                            ui.toast(if (ok) "Pochette trouvée" else if (!Covers.canUseNetwork(ctx)) "Pas de connexion (ou Wi-Fi uniquement)" else "Aucune pochette trouvée")
                        }
                    })
                })
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(a, size = 260.dp, corner = 16.dp, px = 800)
                if (searching) LinearProgressIndicator(Modifier.width(260.dp).padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
                Text(a.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    a.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { ui.push(Page.ArtistPage(a.artist)) }.padding(4.dp),
                )
                val bits = listOfNotNull(
                    (info?.date?.take(4)?.takeIf { it.isNotBlank() } ?: a.year.takeIf { it > 0 }?.toString()),
                    plural(a.tracks.size, "titre"),
                    longDur(a.durationMs),
                )
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info != null && info.label.isNotBlank()) {
                    Text("Label : ${info.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                FormatBadges(fmt)
            }
        }
        item { PlayButtons(a.tracks) }
        val discs = a.tracks.map { it.disc }.distinct()
        discs.forEach { d ->
            if (discs.size > 1) item { SectionLabel("Disque $d") }
            val list = a.tracks.filter { it.disc == d }
            items(list, key = { it.key }) { t ->
                TrackRow(
                    t, ui, number = if (t.trackNo > 0) "${t.trackNo}" else "•",
                    sub = t.artist,
                    onClick = { Engine.play(a.tracks, a.tracks.indexOf(t)) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ArtistScreen(name: String, ui: UiState) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    Library.artists.collectAsState().value
    val ar = Library.artist(name)
    if (ar == null) {
        Column { BackBar(ui); EmptyState("Artiste introuvable", "") }
        return
    }
    val ver by Covers.version.collectAsState()
    val info by produceState(Covers.artistInfo(ctx, name), name, ver) {
        if (value == null) value = Covers.fetchArtist(ctx, name)
    }
    var expanded by remember { mutableStateOf(false) }
    val allTracks = remember(ar) { ar.albums.flatMap { it.tracks } }

    LazyColumn(Modifier.fillMaxSize()) {
        item { BackBar(ui) { TrackMenu(allTracks, ui) } }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArtistImage(name, ar.albums.firstOrNull(), 160.dp)
                Spacer(Modifier.height(14.dp))
                Text(name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    "${plural(ar.albums.size, "album")} · ${plural(ar.trackCount, "titre")}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { PlayButtons(allTracks) }
        info?.let { i ->
            if (i.extract.isNotBlank()) item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    SectionLabel("À propos", Modifier.padding(start = 0.dp))
                    Text(
                        i.extract, style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    Row {
                        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Réduire" else "Lire la suite") }
                        Spacer(Modifier.width(16.dp))
                        if (i.url.isNotBlank()) TextButton(onClick = { uri.openUri(i.url) }, contentPadding = PaddingValues(0.dp)) { Text("Wikipédia") }
                    }
                }
            }
        }
        item { SectionLabel("Albums") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(ar.albums, key = { it.id }) { a ->
                    AlbumCard(a, Modifier.width(150.dp), sub = if (a.year > 0) "${a.year}" else plural(a.tracks.size, "titre")) {
                        ui.push(Page.AlbumPage(a.id))
                    }
                }
            }
        }
        val counts = fr.phoenixpro.music.Store.counts.value
        val top = allTracks.filter { (counts[it.key] ?: 0) > 0 }.sortedByDescending { counts[it.key] ?: 0 }.take(5)
        if (top.isNotEmpty()) {
            item { SectionLabel("Les plus écoutés") }
            items(top, key = { "top" + it.key }) { t ->
                TrackRow(t, ui, showCover = true, sub = t.album, onClick = { Engine.play(top, top.indexOf(t)) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
