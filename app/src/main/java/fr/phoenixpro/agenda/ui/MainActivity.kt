package fr.phoenixpro.agenda.ui

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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import fr.phoenixpro.agenda.AgendaWidget
import fr.phoenixpro.agenda.Event
import fr.phoenixpro.agenda.Note
import fr.phoenixpro.agenda.Reminders
import fr.phoenixpro.agenda.School
import fr.phoenixpro.agenda.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Tab(val label: String, val icon: ImageVector) {
    Month("Agenda", Icons.Outlined.CalendarMonth),
    Planning("Planning", Icons.Outlined.ViewAgenda),
    Notes("Notes", Icons.Outlined.EditNote),
    Settings("Réglages", Icons.Outlined.Settings),
}

/** Ce qui est ouvert par-dessus les onglets. */
sealed class Overlay {
    /** [event] = null pour un nouveau rendez-vous ; [day] = jour de l'occurrence touchée. */
    data class EditEvent(val event: Event?, val day: LocalDate) : Overlay()
    data class EditNote(val note: Note?, val day: LocalDate?) : Overlay()
}

class UiState {
    var tab by mutableStateOf(Tab.Month)
    var selected by mutableStateOf(LocalDate.now())
    var overlay by mutableStateOf<Overlay?>(null)
    /** Incrémenté pour demander à l'écran Mois de revenir au mois du jour sélectionné. */
    var jump by mutableIntStateOf(0)

    fun openEvent(e: Event?, day: LocalDate) { overlay = Overlay.EditEvent(e, day) }
    fun openNote(n: Note?, day: LocalDate?) { overlay = Overlay.EditNote(n, day) }
    fun goTo(day: LocalDate) { selected = day; tab = Tab.Month; jump++ }
}

class MainActivity : ComponentActivity() {
    private val ui = UiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        handle(intent)
        setContent { AgendaTheme { Root(ui) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val d = i?.getLongExtra("day", Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (d != Long.MIN_VALUE) ui.goTo(LocalDate.ofEpochDay(d))
    }

    override fun onResume() {
        super.onResume()
        val ctx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            Reminders.rescheduleAll(ctx)
            AgendaWidget.updateAll(ctx)
            val s = Store.settings.value
            if (s.showSchool) School.refresh(ctx, s.zone)
        }
    }
}

@Composable
fun Root(ui: UiState) {
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = ui.overlay == null && ui.tab != Tab.Month) { ui.tab = Tab.Month }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = { BottomBar(ui) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (ui.tab) {
                    Tab.Month -> MonthScreen(ui)
                    Tab.Planning -> PlanningScreen(ui)
                    Tab.Notes -> NotesScreen(ui)
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
        when (val o = ui.overlay) {
            is Overlay.EditEvent -> EventEditor(o.event, o.day, onClose = { ui.overlay = null })
            is Overlay.EditNote -> NoteEditor(o.note, o.day, onClose = { ui.overlay = null })
            null -> {}
        }
    }
}

@Composable
private fun BottomBar(ui: UiState) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = ui.tab == t,
                onClick = {
                    if (t == Tab.Month && ui.tab == Tab.Month) ui.goTo(LocalDate.now()) else ui.tab = t
                },
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
