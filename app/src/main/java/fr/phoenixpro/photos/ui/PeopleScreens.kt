package fr.phoenixpro.photos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import fr.phoenixpro.photos.Clusters
import fr.phoenixpro.photos.Db
import fr.phoenixpro.photos.Face
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PeopleScreen() {
    val nav = LocalNav.current
    var showAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberData(showAll) { Db.persons(all = showAll) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp),
    ) {
        fullWidth("titre") {
            ScreenTitle("Personnes", list?.let { if (it.isEmpty()) null else plural(it.size, "personne", "personnes") }) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Essentiel" else "Tout") }
            }
        }
        fullWidth("progression") { ProgressBanner() }
        val named = list?.filter { it.name != null } ?: emptyList()
        val unnamed = list?.filter { it.name == null } ?: emptyList()
        if (list != null && list.isEmpty()) {
            fullWidth("vide") {
                EmptyState("Aucun visage pour l'instant", "Phoenix Pro Photos regroupe automatiquement les visages qui se ressemblent. Donnez-leur un nom pour retrouver toutes les photos d'une personne.")
            }
        }
        items(named.size, key = { "n" + named[it].id }) { i ->
            val p = named[i]
            PersonCell(p.name, p.coverFaceId, p.photos) { nav.go(Screen.Person(p.id)) }
        }
        if (unnamed.isNotEmpty()) {
            item(key = "label", span = { GridItemSpan(maxLineSpan) }) { SectionLabel(if (named.isEmpty()) "Visages regroupés automatiquement" else "À identifier") }
        }
        items(unnamed.size, key = { "u" + unnamed[it].id }) { i ->
            val p = unnamed[i]
            PersonCell(null, p.coverFaceId, p.photos) { nav.go(Screen.Person(p.id)) }
        }
        if (!list.isNullOrEmpty()) {
            fullWidth("regrouper") {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch { withContext(Dispatchers.IO) { Clusters.recluster(ctx) }; busy = false }
                    }) { Text(if (busy) "Regroupement…" else "Regrouper à nouveau les visages") }
                }
            }
        }
    }
}

@Composable
fun PersonScreen(id: Long) {
    val nav = LocalNav.current
    val viewer = LocalViewer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val person = rememberData(id) { Db.person(id) }
    val faces = rememberData(id) { Db.personFaces(id) }
    val sort = LocalSort.current.key
    val photos = rememberData(id, sort) { Db.photos(personId = id, sort = sort) }
    val sugg = rememberData(id) { Clusters.suggestions(id) }
    var name by remember(person?.name) { mutableStateOf(person?.name ?: "") }
    var menuFace by remember { mutableStateOf<Face?>(null) }
    var nameFace by remember { mutableStateOf<Long?>(null) }

    if (person == null) return

    fun saveName() {
        if (name.trim() == (person.name ?: "")) return
        scope.launch {
            val newId = withContext(Dispatchers.IO) { Clusters.rename(id, name) }
            if (newId != id) { nav.back(); nav.go(Screen.Person(newId)) }
        }
    }

    PhotoGrid(photos ?: emptyList(), header = {
        fullWidth("tete") {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FaceAvatar(person.coverFaceId, 84.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            placeholder = { Text("Ajouter un nom") }, singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); saveName() }),
                            trailingIcon = {
                                if (name.trim() != (person.name ?: "")) TextButton(onClick = { focus.clearFocus(); saveName() }) { Text("OK") }
                            },
                        )
                        Text(plural(person.photos, "photo", "photos"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { Db.setHidden(id, !person.hidden) }; nav.back() }
                    }) { Text(if (person.hidden) "Ne plus masquer" else "Masquer cette personne") }
                }
            }
        }
        if (!sugg.isNullOrEmpty()) {
            fullWidth("sugg") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Est-ce aussi ${person.name ?: "cette personne"} ?", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(sugg, key = { it.id }) { s ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        FaceAvatar(s.coverFaceId, 60.dp)
                                        Text(s.name ?: plural(s.photos, "visage", "visages"), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        FilledTonalButton(
                                            onClick = { scope.launch { withContext(Dispatchers.IO) { Clusters.mergeInto(s.id, id) } } },
                                            contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(32.dp),
                                        ) { Text("Oui") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        fullWidth("visages") {
            Column {
                SectionLabel("Visages reconnus — touchez pour corriger")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(faces ?: emptyList(), key = { it.id }) { f ->
                        FaceAvatar(f.id, 64.dp, Modifier.clickable { menuFace = f })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionLabel("Photos") }
                    SortButton()
                }
            }
        }
    })

    menuFace?.let { f ->
        AlertDialog(
            onDismissRequest = { menuFace = null },
            icon = { FaceAvatar(f.id, 72.dp) },
            title = { Text("Ce visage") },
            text = {
                Column {
                    MenuLine("C'est quelqu'un d'autre…") { menuFace = null; nameFace = f.id }
                    MenuLine("Utiliser comme photo de profil") {
                        menuFace = null; scope.launch { withContext(Dispatchers.IO) { Db.setCover(id, f.id) } }
                    }
                    MenuLine("Voir la photo") {
                        menuFace = null
                        val list = photos ?: emptyList()
                        val idx = list.indexOfFirst { it.id == f.photoId }.coerceAtLeast(0)
                        viewer.open(list.map { it.id }, idx)
                    }
                    MenuLine("Ce n'est pas un visage", color = MaterialTheme.colorScheme.error) {
                        menuFace = null; scope.launch { withContext(Dispatchers.IO) { Clusters.deleteFace(ctx, f.id) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFace = null }) { Text("Fermer") } },
        )
    }
    nameFace?.let { fid -> NameFaceDialog(fid) { nameFace = null } }
}

@Composable
fun MenuLine(text: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Text(
        text, color = color, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

/** « Qui est-ce ? » : attribue un visage à une personne nommée. */
@Composable
fun NameFaceDialog(faceId: Long, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val people = rememberData("noms") { Db.namedPersons() } ?: emptyList()
    val filtered = people.filter { fr.phoenixpro.photos.norm(it.name).contains(fr.phoenixpro.photos.norm(name)) }.take(6)
    fun save(n: String?) {
        scope.launch { withContext(Dispatchers.IO) { Clusters.assignFace(faceId, n) }; onDone() }
    }
    AlertDialog(
        onDismissRequest = onDone,
        icon = { FaceAvatar(faceId, 72.dp) },
        title = { Text("Qui est-ce ?") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("Prénom et nom") }, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) save(name) }),
                    textStyle = TextStyle.Default,
                )
                LazyColumn(Modifier.heightIn(max = 240.dp).padding(top = 8.dp)) {
                    items(filtered, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { save(p.name) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FaceAvatar(p.coverFaceId, 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(p.name ?: "")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name) }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = { save(null) }) { Text("Inconnu") } },
    )
}
