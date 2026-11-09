@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import fr.phoenixpro.music.Engine
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    Library("Musique", Icons.Outlined.LibraryMusic),
    Playlists("Playlists", Icons.Outlined.QueueMusic),
    Eq("Égaliseur", Icons.Outlined.GraphicEq),
    Transfer("Depuis le PC", Icons.Outlined.Wifi),
    Settings("Réglages", Icons.Outlined.Settings),
}

/** Écrans ouverts par-dessus les onglets. */
sealed class Page {
    data class AlbumPage(val id: Long) : Page()
    data class ArtistPage(val name: String) : Page()
    /** id > 0 : playlist perso ; -1 favoris ; -2 récents ; -3 les plus écoutés. */
    data class PlaylistPage(val id: Long) : Page()
    object Queue : Page()
}

class UiState {
    var tab by mutableStateOf(Tab.Library)
    val stack = mutableStateListOf<Page>()
    var nowPlaying by mutableStateOf(false)
    var addToPlaylist by mutableStateOf<List<String>?>(null)
    var message by mutableStateOf<String?>(null)
    var messageId by mutableIntStateOf(0)

    fun push(p: Page) { nowPlaying = false; stack.add(p) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun toast(m: String) { message = m; messageId++ }
    fun goTab(t: Tab) { stack.clear(); nowPlaying = false; tab = t }
}

class MainActivity : ComponentActivity() {
    private val ui = UiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Engine.init(this)
        handle(intent)
        setContent { MusicTheme { Root(ui) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("nowPlaying", false) == true) ui.nowPlaying = true
    }

    override fun onResume() {
        super.onResume()
        if (hasAudioPermission()) lifecycleScope.launch { Library.scan(applicationContext) }
    }

    fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun Root(ui: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf((ctx as MainActivity).hasAudioPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = (ctx as MainActivity).hasAudioPermission()
        if (granted) scope.launch { Library.scan(ctx.applicationContext) }
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            val perms = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            launcher.launch(perms)
        }
    }

    BackHandler(enabled = ui.nowPlaying) { ui.nowPlaying = false }
    BackHandler(enabled = !ui.nowPlaying && ui.stack.isNotEmpty()) { ui.pop() }
    BackHandler(enabled = !ui.nowPlaying && ui.stack.isEmpty() && ui.tab != Tab.Library) { ui.tab = Tab.Library }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(ui.messageId) {
        ui.message?.let { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it, duration = SnackbarDuration.Short) }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    MiniPlayer(ui)
                    BottomBar(ui)
                }
            },
            snackbarHost = { SnackbarHost(snack) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (!granted && (ui.tab == Tab.Library || ui.tab == Tab.Playlists)) {
                    Column {
                        EmptyState(
                            "Accès à la musique",
                            "Phoenix Pro Music a besoin de l'autorisation de lire les fichiers audio du téléphone et de la carte microSD.",
                        ) {
                            Button(onClick = {
                                val perms = if (Build.VERSION.SDK_INT >= 33)
                                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                launcher.launch(perms)
                            }) { Text("Autoriser") }
                        }
                    }
                } else {
                    val top = ui.stack.lastOrNull()
                    when (top) {
                        is Page.AlbumPage -> AlbumScreen(top.id, ui)
                        is Page.ArtistPage -> ArtistScreen(top.name, ui)
                        is Page.PlaylistPage -> PlaylistScreen(top.id, ui)
                        Page.Queue -> QueueScreen(ui)
                        null -> when (ui.tab) {
                            Tab.Library -> LibraryScreen(ui)
                            Tab.Playlists -> PlaylistsScreen(ui)
                            Tab.Eq -> EqScreen()
                            Tab.Transfer -> TransferScreen(ui)
                            Tab.Settings -> SettingsScreen(ui)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            ui.nowPlaying,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(ui)
        }
        ui.addToPlaylist?.let { keys -> PlaylistPickerDialog(keys, ui) { ui.addToPlaylist = null } }
    }
}

@Composable
private fun MiniPlayer(ui: UiState) {
    val ps by Engine.state.collectAsState()
    val t = ps.key?.let { Library.byKey[it] } ?: return
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ps.playing, ps.key) {
        while (true) { pos = Engine.player.currentPosition; delay(500) }
    }
    val frac = if (ps.durationMs > 0) (pos.toFloat() / ps.durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { ui.nowPlaying = true }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(Library.albumOf(t), size = 44.dp, corner = 8.dp, px = 160)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { Engine.toggle() }) {
                Icon(if (ps.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture / pause")
            }
            IconButton(onClick = { Engine.player.seekToNext() }) { Icon(Icons.Filled.SkipNext, "Suivant") }
        }
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(2.dp),
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun BottomBar(ui: UiState) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = ui.tab == t && ui.stack.isEmpty(),
                onClick = { ui.goTab(t) },
                icon = { Icon(t.icon, contentDescription = t.label) },
                label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}
