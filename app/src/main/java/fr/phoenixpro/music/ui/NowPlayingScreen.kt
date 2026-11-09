@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import fr.phoenixpro.music.BitPerfect
import fr.phoenixpro.music.Engine
import fr.phoenixpro.music.Eq
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Store
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(ui: UiState) {
    val ps by Engine.state.collectAsState()
    val favs by Store.favorites.collectAsState()
    val bp by BitPerfect.status.collectAsState()
    val eq by Store.eq.collectAsState()
    val t = ps.key?.let { Library.byKey[it] }
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(ps.key) {
        while (true) { if (dragging == null) pos = Engine.player.currentPosition; delay(250) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ui.nowPlaying = false }, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "Fermer")
                }
                Text(
                    "En cours de lecture", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                if (t != null) Box(Modifier.offset(x = 12.dp)) { TrackMenu(listOf(t), ui) } else Spacer(Modifier.width(48.dp))
            }
            if (t == null) {
                EmptyState("Rien en lecture", "Choisis un album ou un titre dans ta bibliothèque.")
                return@Column
            }
            val album = Library.albumOf(t)
            Spacer(Modifier.weight(0.5f))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, 420.dp)
                Box(Modifier.clickable(enabled = album != null) { album?.let { ui.push(Page.AlbumPage(it.id)) } }) {
                    Cover(album, size = side, corner = 20.dp, px = 1000)
                }
            }
            Spacer(Modifier.weight(0.5f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        t.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { ui.push(Page.ArtistPage(album?.artist ?: t.artist)) },
                    )
                    Text(t.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fav = t.key in favs
                IconButton(onClick = { Store.toggleFavorite(t.key) }) {
                    Icon(
                        if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favori",
                        tint = if (fav) PhoenixOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FormatBadges(ps.info)
                if (bp.active) {
                    Spacer(Modifier.width(6.dp))
                    Badge("BIT-PERFECT", Color.White, PhoenixTeal)
                }
            }
            if (bp.dac != null) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Usb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        bp.dac + (if (bp.message.isNotBlank()) " — ${bp.message}" else ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            val d = ps.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (pos.toFloat() / d).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { Engine.player.seekTo((it * d).toLong()); pos = (it * d).toLong() }
                    dragging = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(dur(dragging?.let { (it * d).toLong() } ?: pos), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(dur(ps.durationMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Engine.player.shuffleModeEnabled = !ps.shuffle }) {
                    Icon(Icons.Outlined.Shuffle, "Aléatoire", tint = if (ps.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { Engine.player.seekToPrevious() }, Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Précédent", Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { Engine.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (ps.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture / pause",
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { Engine.player.seekToNext() }, Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, "Suivant", Modifier.size(36.dp))
                }
                IconButton(onClick = { Engine.cycleRepeat() }) {
                    Icon(
                        if (ps.repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, "Répéter",
                        tint = if (ps.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { ui.goTab(Tab.Eq) }) {
                    Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (eq.enabled) "Égaliseur : ${eq.preset}" else "Égaliseur")
                }
                TextButton(onClick = { ui.push(Page.Queue) }) {
                    Icon(Icons.Outlined.QueueMusic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("File d'attente (${ps.index + 1}/${ps.count})")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun QueueScreen(ui: UiState) {
    val ps by Engine.state.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val queue = remember(ps.count, ps.key, version) { Engine.queue() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { if (ps.index > 0) list.scrollToItem((ps.index - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        BackBar(ui, "File d'attente") {
            TextButton(onClick = {
                val p = Engine.player
                val cur = p.currentMediaItemIndex
                if (cur + 1 < p.mediaItemCount) p.removeMediaItems(cur + 1, p.mediaItemCount)
                version++
            }) { Text("Vider la suite") }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(queue, key = { i, t -> "$i/${t.key}" }) { i, t ->
                TrackRow(
                    t, ui, showCover = true, sub = t.artist,
                    onClick = { Engine.player.seekTo(i, 0); Engine.player.play() },
                    extraMenu = { close ->
                        DropdownMenuItem(text = { Text("Retirer de la file") }, onClick = {
                            close(); Engine.player.removeMediaItem(i); version++
                        })
                    },
                )
            }
        }
    }
}
