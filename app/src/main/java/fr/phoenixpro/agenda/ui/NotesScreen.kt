package fr.phoenixpro.agenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.phoenixpro.agenda.Store

@Composable
fun NotesScreen(ui: UiState) {
    val notes by Store.notes.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(notes, query) {
        notes.filter {
            query.isBlank() || it.title.contains(query, true) || it.text.contains(query, true)
        }.sortedByDescending { it.updated }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item { ScreenTitle("Notes", if (notes.isEmpty()) null else "${notes.size} note" + if (notes.size > 1) "s" else "") }
            if (notes.isNotEmpty()) item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Rechercher dans les notes") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (notes.isEmpty()) item {
                EmptyState("Aucune note", "Touchez + pour écrire une note. Vous pouvez aussi rattacher une note à un jour depuis l'agenda.")
            }
            items(shown, key = { it.id }) { n -> NoteCard(n, showDay = true) { ui.openNote(n, null) } }
        }
        FloatingActionButton(
            onClick = { ui.openNote(null, null) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Nouvelle note") }
    }
}
