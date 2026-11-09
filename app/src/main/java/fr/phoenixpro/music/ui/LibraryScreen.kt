@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.phoenixpro.music.Album
import fr.phoenixpro.music.Engine
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Store

enum class LibView(val label: String) { Albums("Albums"), Artists("Artistes"), Tracks("Titres") }

private val sorts = linkedMapOf(
    "artist" to "Artiste",
    "title" to "Titre de l'album",
    "recent" to "Ajout récent",
    "year" to "Année",
)

fun sortAlbums(list: List<Album>, sort: String): List<Album> = when (sort) {
    "title" -> list.sortedBy { Library.norm(it.title) }
    "recent" -> list.sortedByDescending { it.dateAdded }
    "year" -> list.sortedWith(compareByDescending<Album> { it.year }.thenBy { Library.norm(it.artist) })
    else -> list.sortedWith(compareBy<Album> { Library.norm(it.artist) }.thenBy { it.year }.thenBy { Library.norm(it.title) })
}

private var savedView by mutableStateOf(LibView.Albums)

@Composable
fun LibraryScreen(ui: UiState) {
    val albums by Library.albums.collectAsState()
    val artists by Library.artists.collectAsState()
    val tracks by Library.tracks.collectAsState()
    val scanning by Library.scanning.collectAsState()
    val settings by Store.settings.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Album, artiste, titre…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Outlined.Close, "Fermer") } },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
        } else {
            ScreenTitle(
                "Musique",
                when {
                    scanning && tracks.isEmpty() -> "Recherche de la musique…"
                    else -> "${plural(albums.size, "album")} · ${plural(tracks.size, "titre")}"
                },
            ) {
                IconButton(onClick = { Engine.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Outlined.Shuffle, "Tout lire en aléatoire")
                }
                IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Rechercher") }
            }
        }

        if (query.isNotBlank()) {
            SearchResults(query, ui)
            return@Column
        }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LibView.entries.forEach { v ->
                FilterChip(
                    selected = savedView == v, onClick = { savedView = v }, label = { Text(v.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (savedView == LibView.Albums) Box {
                IconButton(onClick = { sortOpen = true }) { Icon(Icons.Outlined.Sort, "Trier") }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    sorts.forEach { (k, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = { if (settings.albumSort == k) RadioButton(true, null) },
                            onClick = { sortOpen = false; Store.updateSettings { it.copy(albumSort = k) } },
                        )
                    }
                }
            }
        }

        if (tracks.isEmpty() && !scanning) {
            EmptyState(
                "Aucune musique trouvée",
                "Copie tes albums sur le téléphone ou la carte microSD, ou envoie-les depuis le PC par le Wi-Fi.",
            ) { Button(onClick = { ui.goTab(Tab.Transfer) }) { Text("Envoyer depuis le PC") } }
            return@Column
        }

        when (savedView) {
            LibView.Albums -> {
                val sorted = remember(albums, settings.albumSort) { sortAlbums(albums, settings.albumSort) }
                val recent = remember(albums) { albums.sortedByDescending { it.dateAdded }.take(12) }
                LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (albums.size > 12 && settings.albumSort != "recent") {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                SectionLabel("Ajoutés récemment", Modifier.padding(start = 0.dp))
                                LazyRow(contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    items(recent, key = { it.id }) { a ->
                                        AlbumCard(a, Modifier.width(130.dp)) { ui.push(Page.AlbumPage(a.id)) }
                                    }
                                }
                                SectionLabel("Tous les albums", Modifier.padding(start = 0.dp))
                            }
                        }
                    }
                    items(sorted, key = { it.id }) { a ->
                        AlbumCard(a) { ui.push(Page.AlbumPage(a.id)) }
                    }
                }
            }
            LibView.Artists -> LazyColumn(Modifier.fillMaxSize()) {
                items(artists, key = { it.name }) { ar ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ui.push(Page.ArtistPage(ar.name)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtistImage(ar.name, ar.albums.firstOrNull(), 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ar.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${plural(ar.albums.size, "album")} · ${plural(ar.trackCount, "titre")}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            LibView.Tracks -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks, key = { _, t -> t.key }) { i, t ->
                    TrackRow(t, ui, showCover = true, sub = "${t.artist} · ${t.album}", onClick = { Engine.play(tracks, i) })
                }
            }
        }
    }
}

@Composable
private fun SearchResults(query: String, ui: UiState) {
    val albums by Library.albums.collectAsState()
    val artists by Library.artists.collectAsState()
    val tracks by Library.tracks.collectAsState()
    val q = Library.norm(query)
    val al = remember(q, albums) { albums.filter { Library.norm(it.title).contains(q) || Library.norm(it.artist).contains(q) }.take(30) }
    val ar = remember(q, artists) { artists.filter { Library.norm(it.name).contains(q) }.take(20) }
    val tr = remember(q, tracks) { tracks.filter { Library.norm(it.title).contains(q) || Library.norm(it.artist).contains(q) }.take(100) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (ar.isNotEmpty()) {
            item { SectionLabel("Artistes") }
            items(ar, key = { "ar" + it.name }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { ui.push(Page.ArtistPage(a.name)) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtistImage(a.name, a.albums.firstOrNull(), 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (al.isNotEmpty()) {
            item { SectionLabel("Albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    items(al, key = { it.id }) { a -> AlbumCard(a, Modifier.width(130.dp)) { ui.push(Page.AlbumPage(a.id)) } }
                }
            }
        }
        if (tr.isNotEmpty()) {
            item { SectionLabel("Titres") }
            itemsIndexed(tr, key = { _, t -> "t" + t.key }) { i, t ->
                TrackRow(t, ui, showCover = true, sub = "${t.artist} · ${t.album}", onClick = { Engine.play(tr, i) })
            }
        }
        if (ar.isEmpty() && al.isEmpty() && tr.isEmpty()) item { EmptyState("Aucun résultat", "Essaie un autre mot.") }
    }
}
