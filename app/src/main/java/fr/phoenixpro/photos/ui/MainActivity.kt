package fr.phoenixpro.photos.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.phoenixpro.photos.Indexer
import fr.phoenixpro.photos.R
import fr.phoenixpro.photos.Scanner

sealed class Screen {
    data object Photos : Screen()
    data object People : Screen()
    data object Map : Screen()
    data object Favs : Screen()
    data object Pc : Screen()
    data object Settings : Screen()
    data class Person(val id: Long) : Screen()
    data class Place(val name: String) : Screen()
    data class Search(val query: String) : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Photos)
    val current: Screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun tab(s: Screen) { stack.clear(); stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

/** Visionneuse ouverte sur une liste de photos. */
class ViewerState {
    var ids by mutableStateOf<List<Long>?>(null)
    var index by mutableIntStateOf(0)
    fun open(list: List<Long>, i: Int) { ids = list; index = i }
    fun close() { ids = null }
}

val LocalNav = staticCompositionLocalOf { Nav() }
val LocalViewer = staticCompositionLocalOf { ViewerState() }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PhoenixTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        if (Scanner.hasMediaPermission(this)) Indexer.start(this)
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val nav = remember { Nav() }
    val viewer = remember { ViewerState() }
    var granted by remember { mutableStateOf(Scanner.hasMediaPermission(ctx)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Scanner.hasMediaPermission(ctx)
        if (granted) Indexer.start(ctx)
    }
    val perms = remember {
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    CompositionLocalProvider(LocalNav provides nav, LocalViewer provides viewer) {
        if (!granted) {
            Welcome { launcher.launch(perms) }
        } else {
        BackHandler(enabled = viewer.ids == null && nav.stack.size > 1) { nav.back() }
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = { BottomBar(nav) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (val s = nav.current) {
                        Screen.Photos -> PhotosScreen()
                        Screen.People -> PeopleScreen()
                        Screen.Map -> MapScreen()
                        Screen.Favs -> FavsScreen()
                        Screen.Pc -> PcScreen()
                        Screen.Settings -> SettingsScreen()
                        is Screen.Person -> PersonScreen(s.id)
                        is Screen.Place -> PlaceScreen(s.name)
                        is Screen.Search -> SearchScreen(s.query)
                    }
                }
            }
            viewer.ids?.let { ids -> Viewer(ids, viewer.index, onClose = { viewer.close() }) }
        }
        }
    }
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

@Composable
private fun BottomBar(nav: Nav) {
    val tabs = listOf(
        Tab(Screen.Photos, "Photos", Icons.Outlined.PhotoLibrary),
        Tab(Screen.People, "Personnes", Icons.Outlined.People),
        Tab(Screen.Map, "Lieux", Icons.Outlined.Place),
        Tab(Screen.Favs, "Favoris", Icons.Outlined.FavoriteBorder),
        Tab(Screen.Pc, "PC", Icons.Outlined.Computer),
    )
    val root = nav.stack.first()
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        tabs.forEach { t ->
            NavigationBarItem(
                selected = root == t.screen,
                onClick = { nav.tab(t.screen) },
                icon = { Icon(t.icon, contentDescription = t.label) },
                label = { Text(t.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun Welcome(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).systemBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.logo_complet), contentDescription = "Phoenix Pro Photos", modifier = Modifier.fillMaxWidth(0.85f))
        Spacer(Modifier.height(36.dp))
        Text("Bienvenue", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Phoenix Pro Photos range vos photos par date, par lieu et par personne. Tout est analysé sur votre téléphone : rien n'est envoyé sur Internet.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAsk, modifier = Modifier.height(50.dp)) { Text("Autoriser l'accès aux photos") }
        Spacer(Modifier.height(10.dp))
        Text(
            "Autorisez aussi « position des photos » pour afficher vos lieux sur la carte.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
