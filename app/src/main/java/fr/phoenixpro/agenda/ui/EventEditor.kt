package fr.phoenixpro.agenda.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fr.phoenixpro.agenda.Event
import fr.phoenixpro.agenda.REMINDER_CHOICES
import fr.phoenixpro.agenda.Repeat
import fr.phoenixpro.agenda.Store
import fr.phoenixpro.agenda.minutesLabel
import fr.phoenixpro.agenda.reminderLabel
import fr.phoenixpro.agenda.weekNumber
import java.time.LocalDate
import java.time.LocalTime

/** Création / modification d'un rendez-vous (plein écran). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditor(event: Event?, occurrence: LocalDate, onClose: () -> Unit) {
    val settings by Store.settings.collectAsState()
    val isNew = event == null
    val nowRounded = remember {
        val t = LocalTime.now()
        ((t.hour + 1).coerceAtMost(23)) * 60
    }

    var title by remember { mutableStateOf(event?.title ?: "") }
    var category by remember { mutableIntStateOf(event?.category ?: 0) }
    var day by remember { mutableStateOf(if (event != null) LocalDate.ofEpochDay(event.day) else occurrence) }
    var allDay by remember { mutableStateOf(event?.allDay ?: false) }
    var start by remember { mutableIntStateOf(event?.start?.takeIf { it >= 0 } ?: nowRounded) }
    var end by remember { mutableIntStateOf(event?.end?.takeIf { it >= 0 } ?: (start + 60).coerceAtMost(23 * 60 + 59)) }
    var place by remember { mutableStateOf(event?.place ?: "") }
    var note by remember { mutableStateOf(event?.note ?: "") }
    var reminder by remember { mutableIntStateOf(event?.reminder ?: settings.defaultReminder) }
    var repeat by remember { mutableStateOf(event?.repeat ?: Repeat.NONE) }
    var until by remember { mutableLongStateOf(event?.until ?: -1L) }

    var pickDate by remember { mutableStateOf(false) }
    var pickUntil by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(0) } // 1 = début, 2 = fin
    var askDelete by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    fun save() {
        val e = Event(
            id = event?.id ?: Store.newId(),
            title = title.trim(),
            day = day.toEpochDay(),
            start = if (allDay) -1 else start,
            end = if (allDay) -1 else maxOf(end, start),
            place = place.trim(),
            note = note.trim(),
            category = category,
            repeat = repeat,
            until = if (repeat == Repeat.NONE) -1 else until,
            reminder = reminder,
            skips = event?.skips ?: emptySet(),
        )
        Store.saveEvent(e)
        onClose()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Fermer") }
                Text(
                    if (isNew) "Nouveau rendez-vous" else "Rendez-vous",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                )
                if (!isNew) IconButton(onClick = { askDelete = true }) {
                    Icon(Icons.Outlined.Delete, "Supprimer", tint = HolidayRed)
                }
                Button(onClick = { save() }, modifier = Modifier.padding(end = 8.dp)) { Text("Enregistrer") }
            }

            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Titre") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // catégories
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    settings.categories.forEachIndexed { i, name ->
                        FilterChip(
                            selected = category == i,
                            onClick = { category = i },
                            label = { Text(name.ifBlank { fr.phoenixpro.agenda.DEFAULT_CATEGORIES[i] }) },
                            leadingIcon = { Dot(catColor(i)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = catColor(i).copy(alpha = 0.18f),
                            ),
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

                FieldRow(Icons.Outlined.CalendarToday, day.fmt("EEEE d MMMM yyyy"), trailing = {
                    Text("S${day.weekNumber()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }) { pickDate = true }

                FieldRow(Icons.Outlined.WbSunny, "Journée entière", trailing = {
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }) { allDay = !allDay }

                if (!allDay) {
                    FieldRow(Icons.Outlined.Schedule, "Début", trailing = {
                        Text(minutesLabel(start), style = MaterialTheme.typography.titleMedium)
                    }) { pickTime = 1 }
                    FieldRow(Icons.Outlined.Schedule, "Fin", trailing = {
                        Text(minutesLabel(end), style = MaterialTheme.typography.titleMedium)
                    }) { pickTime = 2 }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ChoiceRow(Icons.Outlined.Notifications, reminder, REMINDER_CHOICES, { reminderLabel(it, allDay) }) { reminder = it }
                ChoiceRow(Icons.Outlined.Repeat, repeat, Repeat.entries.toList(), { it.label }) { repeat = it }
                if (repeat != Repeat.NONE) {
                    FieldRow(
                        Icons.Outlined.EventBusy,
                        if (until < 0) "Sans date de fin" else "Jusqu'au " + LocalDate.ofEpochDay(until).fmt("d MMMM yyyy"),
                        trailing = {
                            if (until >= 0) TextButton(onClick = { until = -1 }) { Text("Sans fin") }
                        },
                    ) { pickUntil = true }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                OutlinedTextField(
                    value = place, onValueChange = { place = it },
                    label = { Text("Lieu") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Place, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Notes") }, minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (pickDate || pickUntil) {
        val initial = if (pickUntil && until >= 0) LocalDate.ofEpochDay(until) else day
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { pickDate = false; pickUntil = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L))
                        if (pickUntil) until = d.toEpochDay() else day = d
                    }
                    pickDate = false; pickUntil = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false; pickUntil = false }) { Text("Annuler") } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }

    if (pickTime != 0) {
        val cur = if (pickTime == 1) start else end
        val state = rememberTimePickerState(initialHour = cur / 60, initialMinute = cur % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = 0 },
            title = { Text(if (pickTime == 1) "Heure de début" else "Heure de fin") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    if (pickTime == 1) {
                        val duration = (end - start).coerceAtLeast(0)
                        start = m
                        end = (m + duration).coerceAtMost(23 * 60 + 59)
                    } else {
                        end = m
                        if (end < start) start = end
                    }
                    pickTime = 0
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = 0 }) { Text("Annuler") } },
        )
    }

    if (askDelete && event != null) {
        val series = event.repeat != Repeat.NONE
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Supprimer ce rendez-vous ?") },
            text = { Text(if (series) "Ce rendez-vous se répète. Que voulez-vous supprimer ?" else "« ${event.title.ifBlank { "Rendez-vous" }} » sera supprimé.") },
            confirmButton = {
                if (series) Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { Store.skipDay(event.id, occurrence.toEpochDay()); onClose() }) {
                        Text("Seulement le " + occurrence.fmt("d MMMM").lowercase(FR))
                    }
                    TextButton(onClick = { Store.deleteEvent(event.id); onClose() }) {
                        Text("Toute la série", color = HolidayRed)
                    }
                    TextButton(onClick = { askDelete = false }) { Text("Annuler") }
                } else TextButton(onClick = { Store.deleteEvent(event.id); onClose() }) {
                    Text("Supprimer", color = HolidayRed)
                }
            },
            dismissButton = { if (!series) TextButton(onClick = { askDelete = false }) { Text("Annuler") } },
        )
    }
}
