package fr.phoenixpro.agenda.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.phoenixpro.agenda.Event
import fr.phoenixpro.agenda.Holidays
import fr.phoenixpro.agenda.Note
import fr.phoenixpro.agenda.School
import fr.phoenixpro.agenda.Settings
import fr.phoenixpro.agenda.Store
import fr.phoenixpro.agenda.eventsOn
import fr.phoenixpro.agenda.notesOn
import fr.phoenixpro.agenda.weekNumber
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private val BASE: YearMonth = YearMonth.of(1970, 1)
private const val PAGES = 12 * 200

private fun pageOf(ym: YearMonth) = ChronoUnit.MONTHS.between(BASE, ym).toInt()
private fun monthOf(page: Int): YearMonth = BASE.plusMonths(page.toLong())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonthScreen(ui: UiState) {
    val events by Store.events.collectAsState()
    val notes by Store.notes.collectAsState()
    val settings by Store.settings.collectAsState()
    val schoolVersion by School.version.collectAsState()
    val pager = rememberPagerState(initialPage = pageOf(YearMonth.from(ui.selected))) { PAGES }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ui.jump) {
        val p = pageOf(YearMonth.from(ui.selected))
        if (pager.currentPage != p) pager.scrollToPage(p)
    }
    val shown = monthOf(pager.currentPage)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // en-tête : mois + navigation
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(shown.atDay(1).fmt("MMMM yyyy"), style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = { ui.goTo(LocalDate.now()) }) { Icon(Icons.Outlined.Today, "Aujourd'hui") }
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                    Icon(Icons.Outlined.ChevronLeft, "Mois précédent")
                }
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Icon(Icons.Outlined.ChevronRight, "Mois suivant")
                }
            }
            // jours de la semaine
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
                Text("Sem.", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                listOf("L", "M", "M", "J", "V", "S", "D").forEachIndexed { i, d ->
                    Text(
                        d, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                        color = if (i == 6) HolidayRed.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), beyondBoundsPageCount = 1) { page ->
                key(schoolVersion) {
                    MonthGrid(monthOf(page), ui.selected, events, notes, settings) { ui.selected = it }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            key(schoolVersion) {
                DayDetails(ui, ui.selected, events, notes, settings, Modifier.weight(1f))
            }
        }
        FloatingActionButton(
            onClick = { ui.openEvent(null, ui.selected) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Nouveau rendez-vous") }
    }
}

@Composable
private fun MonthGrid(
    ym: YearMonth,
    selected: LocalDate,
    events: List<Event>,
    notes: List<Note>,
    settings: Settings,
    onSelect: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val first = ym.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        for (w in 0 until 6) {
            val monday = first.plusDays(w * 7L)
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "${monday.weekNumber()}", Modifier.width(30.dp).padding(top = 9.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                )
                for (i in 0 until 7) {
                    val d = monday.plusDays(i.toLong())
                    DayCell(
                        d, inMonth = YearMonth.from(d) == ym, isToday = d == today, isSelected = d == selected,
                        holiday = Holidays.name(d) != null,
                        school = settings.showSchool && School.on(d, settings.zone) != null,
                        cats = eventsOn(d, events).map { it.category }.take(3),
                        hasNote = notesOn(d, notes).isNotEmpty(),
                        sunday = i == 6,
                        modifier = Modifier.weight(1f),
                    ) { onSelect(d) }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    d: LocalDate, inMonth: Boolean, isToday: Boolean, isSelected: Boolean,
    holiday: Boolean, school: Boolean, cats: List<Int>, hasNote: Boolean, sunday: Boolean,
    modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (school) SchoolTint else Color.Transparent)
            .clickable(onClick = onClick)
            .alpha(if (inMonth) 1f else 0.35f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        val numColor = when {
            isToday -> cs.onPrimary
            holiday -> HolidayRed
            sunday -> cs.onSurface.copy(alpha = 0.6f)
            else -> cs.onSurface
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(if (isToday) cs.primary else if (isSelected) cs.primaryContainer else Color.Transparent)
                .then(if (isSelected && !isToday) Modifier.border(1.5.dp, cs.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${d.dayOfMonth}", color = numColor, fontSize = 14.sp,
                fontWeight = if (isToday || isSelected || holiday) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            cats.forEach { Dot(catColor(it), 6.dp) }
            if (hasNote) Box(Modifier.size(width = 8.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(cs.onSurfaceVariant))
        }
    }
}

@Composable
private fun DayDetails(
    ui: UiState, day: LocalDate, events: List<Event>, notes: List<Note>, settings: Settings, modifier: Modifier,
) {
    val dayEvents = eventsOn(day, events)
    val dayNotes = notesOn(day, notes)
    val holiday = Holidays.name(day)
    val school = if (settings.showSchool) School.on(day, settings.zone) else null
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 88.dp)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
                Text(day.longLabel(), style = MaterialTheme.typography.titleLarge)
                Text(
                    "Semaine ${day.weekNumber()}" + if (day == LocalDate.now()) " · Aujourd'hui" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (holiday != null || school != null) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (holiday != null) Tag("Férié · $holiday", HolidayRed, HolidayRed.copy(alpha = 0.12f))
                        if (school != null) Tag("${school.name} (zone ${settings.zone})", Color(0xFFB4610A), SchoolTint)
                    }
                }
            }
        }
        if (dayEvents.isEmpty() && dayNotes.isEmpty()) item {
            Text(
                "Rien de prévu ce jour-là.", Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(dayEvents, key = { "e" + it.id }) { e -> EventRow(e) { ui.openEvent(e, day) } }
        if (dayNotes.isNotEmpty()) item { SectionLabel("Notes du jour") }
        items(dayNotes, key = { "n" + it.id }) { n -> NoteCard(n, showDay = false) { ui.openNote(n, day) } }
        item {
            TextButton(onClick = { ui.openNote(null, day) }, modifier = Modifier.padding(start = 8.dp, top = 6.dp)) {
                Icon(Icons.Outlined.NoteAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ajouter une note pour ce jour")
            }
        }
    }
}
