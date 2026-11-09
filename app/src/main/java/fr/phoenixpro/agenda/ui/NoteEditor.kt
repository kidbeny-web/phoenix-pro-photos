package fr.phoenixpro.agenda.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fr.phoenixpro.agenda.Note
import fr.phoenixpro.agenda.Store
import java.time.LocalDate

/** Édition d'une note. Enregistrée automatiquement en revenant en arrière. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(note: Note?, forDay: LocalDate?, onClose: () -> Unit) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var text by remember { mutableStateOf(note?.text ?: "") }
    var day by remember { mutableLongStateOf(note?.day ?: (forDay?.toEpochDay() ?: -1L)) }
    var pickDate by remember { mutableStateOf(false) }

    fun saveAndClose() {
        val empty = title.isBlank() && text.isBlank()
        when {
            empty && note != null -> Store.deleteNote(note.id)
            empty -> {}
            note == null || note.title != title || note.text != text || note.day != day ->
                Store.saveNote(Note(note?.id ?: Store.newId(), title.trim(), text.trimEnd(), day, System.currentTimeMillis()))
        }
        onClose()
    }

    BackHandler { saveAndClose() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { saveAndClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour") }
                Spacer(Modifier.weight(1f))
                if (note != null) IconButton(onClick = { Store.deleteNote(note.id); onClose() }) {
                    Icon(Icons.Outlined.Delete, "Supprimer", tint = HolidayRed)
                }
            }
            val noBorder = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
            )
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                placeholder = { Text("Titre", style = MaterialTheme.typography.headlineMedium) },
                textStyle = MaterialTheme.typography.headlineMedium,
                singleLine = true, colors = noBorder,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { pickDate = true },
                    label = { Text(if (day < 0) "Rattacher à un jour" else LocalDate.ofEpochDay(day).longLabel()) },
                    leadingIcon = { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp)) },
                )
                if (day >= 0) IconButton(onClick = { day = -1 }) { Icon(Icons.Outlined.Close, "Détacher du jour") }
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Écrivez votre note…") },
                colors = noBorder,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp),
            )
        }
    }

    if (pickDate) {
        val initial = if (day >= 0) day else LocalDate.now().toEpochDay()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day = Math.floorDiv(it, 86_400_000L) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Annuler") } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}
