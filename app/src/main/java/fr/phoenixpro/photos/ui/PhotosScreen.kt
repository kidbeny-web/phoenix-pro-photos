package fr.phoenixpro.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import fr.phoenixpro.photos.Db

@Composable
fun SearchBox(initial: String = "", onSettings: (() -> Unit)? = null) {
    val nav = LocalNav.current
    val focus = LocalFocusManager.current
    var q by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    val sugg = rememberData(q) { if (q.isBlank()) null else Db.suggestions(q) }
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = q, onValueChange = { q = it },
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                placeholder = { Text("Personne, lieu, « juillet 2024 »…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    if (q.isNotBlank()) nav.go(Screen.Search(q.trim()))
                }),
            )
            SortButton()
            if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Réglages") }
        }
        val s = sugg
        if (focused && s != null && (s.first.isNotEmpty() || s.second.isNotEmpty())) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)
            ) {
                s.first.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { focus.clearFocus(); nav.go(Screen.Person(p.id)) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaceAvatar(p.coverFaceId, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name ?: "", Modifier.weight(1f))
                        Text("Personne", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                s.second.forEach { pl ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { focus.clearFocus(); nav.go(Screen.Place(pl.name)) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(pl.name, Modifier.weight(1f))
                        Text(plural(pl.count, "photo", "photos"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun PhotosScreen() {
    val nav = LocalNav.current
    val sort = LocalSort.current.key
    val photos = rememberData("all", sort) { Db.photos(sort = sort) }
    Column {
        SearchBox(onSettings = { nav.go(Screen.Settings) })
        ProgressBanner()
        when {
            photos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            photos.isEmpty() -> EmptyState("Aucune photo pour l'instant", "Vos photos apparaîtront ici dès la fin de l'analyse.")
            else -> PhotoGrid(photos)
        }
    }
}

@Composable
fun FavsScreen() {
    val sort = LocalSort.current.key
    val photos = rememberData("fav", sort) { Db.photos(favOnly = true, sort = sort) }
    Column(Modifier.statusBarsPadding()) {
        ScreenTitle("Favoris", photos?.let { if (it.isEmpty()) null else plural(it.size, "photo", "photos") }) { SortButton() }
        if (photos != null && photos.isEmpty()) EmptyState("Pas encore de favoris", "Ouvrez une photo et touchez le cœur pour la retrouver ici.")
        else if (photos != null) PhotoGrid(photos)
    }
}

@Composable
fun SearchScreen(query: String) {
    val sort = LocalSort.current.key
    val photos = rememberData(query, sort) { Db.photos(query = query, sort = sort) }
    Column {
        SearchBox(initial = query)
        ScreenTitle("« $query »", photos?.let { plural(it.size, "résultat", "résultats") })
        if (photos != null && photos.isEmpty()) EmptyState("Aucun résultat", "Essayez un prénom, une ville, une année ou un mois.")
        else if (photos != null) PhotoGrid(photos)
    }
}

@Composable
fun PlaceScreen(name: String) {
    val sort = LocalSort.current.key
    val photos = rememberData(name, sort) { Db.photos(place = name, sort = sort) }
    Column(Modifier.statusBarsPadding()) {
        ScreenTitle(name, photos?.let { plural(it.size, "photo", "photos") }) { SortButton() }
        if (photos != null) PhotoGrid(photos)
    }
}
