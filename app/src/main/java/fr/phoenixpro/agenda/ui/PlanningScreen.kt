package fr.phoenixpro.agenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.phoenixpro.agenda.Event
import fr.phoenixpro.agenda.Holidays
import fr.phoenixpro.agenda.Note
import fr.phoenixpro.agenda.School
import fr.phoenixpro.agenda.SchoolBreak
import fr.phoenixpro.agenda.Store
import fr.phoenixpro.agenda.eventsOn
import fr.phoenixpro.agenda.notesOn
import fr.phoenixpro.agenda.weekNumber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private sealed class Line {
    data class Week(val monday: LocalDate) : Line()
    data class Day(val d: LocalDate, val holiday: String?, val school: SchoolBreak?) : Line()
    data class Ev(val d: LocalDate, val e: Event) : Line()
    data class Nt(val d: LocalDate, val n: Note) : Line()
}

/** Liste des jours à venir, semaine par semaine. */
@Composable
fun PlanningScreen(ui: UiState) {
    val events by Store.events.collectAsState()
    val notes by Store.notes.collectAsState()
    val settings by Store.settings.collectAsState()
    val schoolVersion by School.version.collectAsState()
    var days by remember { mutableIntStateOf(120) }

    val lines = remember(events, notes, settings, schoolVersion, days) {
        val out = ArrayList<Line>()
        val today = LocalDate.now()
        var weekShown: LocalDate? = null
        for (i in 0 until days) {
            val d = today.plusDays(i.toLong())
            val evs = eventsOn(d, events)
            val nts = notesOn(d, notes)
            val hol = Holidays.name(d)
            val sch = if (settings.showSchool) School.on(d, settings.zone) else null
            val schStart = sch?.takeIf { it.from == d || d == today }
            if (evs.isEmpty() && nts.isEmpty() && hol == null && schStart == null && d != today) continue
            val monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (monday != weekShown) { out.add(Line.Week(monday)); weekShown = monday }
            out.add(Line.Day(d, hol, schStart))
            evs.forEach { out.add(Line.Ev(d, it)) }
            nts.forEach { out.add(Line.Nt(d, it)) }
        }
        out
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Planning", "Les ${days} prochains jours") }
        items(lines) { l ->
            when (l) {
                is Line.Week -> {
                    val sunday = l.monday.plusDays(6)
                    val range = if (l.monday.month == sunday.month) "${l.monday.dayOfMonth} – ${sunday.fmt("d MMMM")}"
                    else "${l.monday.fmt("d MMM")} – ${sunday.fmt("d MMM")}"
                    SectionLabel("Semaine ${l.monday.weekNumber()} · $range")
                }
                is Line.Day -> Column(
                    Modifier.fillMaxWidth().clickable { ui.goTo(l.d) }.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            l.d.longLabel(), style = MaterialTheme.typography.titleSmall,
                            color = if (l.d == LocalDate.now()) MaterialTheme.colorScheme.primary
                            else if (l.holiday != null) HolidayRed else MaterialTheme.colorScheme.onSurface,
                        )
                        if (l.d == LocalDate.now()) Text(
                            "  Aujourd'hui", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (l.holiday != null) Text("Jour férié · ${l.holiday}", style = MaterialTheme.typography.bodyMedium, color = HolidayRed)
                    if (l.school != null) Text(
                        "${l.school.name} jusqu'au ${l.school.to.fmt("d MMMM").lowercase(FR)}",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB4610A),
                        modifier = Modifier.padding(top = 2.dp).background(SchoolTint).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                is Line.Ev -> EventRow(l.e) { ui.openEvent(l.e, l.d) }
                is Line.Nt -> NoteCard(l.n, showDay = false) { ui.openNote(l.n, l.d) }
            }
        }
        item {
            TextButton(onClick = { days += 120 }, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                Text("Afficher plus loin")
            }
        }
    }
}
