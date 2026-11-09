package fr.phoenixpro.agenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.phoenixpro.agenda.Event
import fr.phoenixpro.agenda.Note
import fr.phoenixpro.agenda.Repeat
import fr.phoenixpro.agenda.categoryColor
import fr.phoenixpro.agenda.timeLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val FR: Locale = Locale.FRANCE

fun String.cap() = replaceFirstChar { it.titlecase(FR) }

fun LocalDate.fmt(pattern: String): String = DateTimeFormatter.ofPattern(pattern, FR).format(this).cap()

/** « Lundi 9 novembre » (+ l'année si ce n'est pas l'année en cours). */
fun LocalDate.longLabel(): String =
    if (year == LocalDate.now().year) fmt("EEEE d MMMM") else fmt("EEEE d MMMM yyyy")

fun LocalDate.shortLabel(): String =
    if (year == LocalDate.now().year) fmt("EEE d MMM") else fmt("EEE d MMM yyyy")

fun catColor(i: Int) = Color(categoryColor(i))

@Composable
fun ScreenTitle(title: String, sub: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(FR), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
fun EmptyState(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Petite étiquette arrondie (jour férié, vacances…). */
@Composable
fun Tag(text: String, color: Color, bg: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Ligne d'un rendez-vous dans une liste. */
@Composable
fun EventRow(e: Event, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(2.dp)).background(catColor(e.category)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(92.dp)) {
            Text(e.timeLabel(), style = MaterialTheme.typography.titleSmall)
        }
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { "Rendez-vous" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.place.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(3.dp))
                Text(e.place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (e.repeat != Repeat.NONE) SmallIcon(Icons.Outlined.Repeat)
        if (e.reminder >= 0) SmallIcon(Icons.Outlined.Notifications)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Aperçu d'une note. */
@Composable
fun NoteCard(n: Note, showDay: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (n.text.isNotBlank()) Text(
            n.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        if (showDay && n.day >= 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Pour le " + LocalDate.ofEpochDay(n.day).shortLabel().lowercase(FR),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Ligne de réglage avec un menu de choix. */
@Composable
fun <T> ChoiceRow(
    icon: ImageVector,
    current: T,
    choices: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldRow(icon, label(current)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(label(c)) }, onClick = { open = false; onPick(c) })
            }
        }
    }
}

@Composable
fun FieldRow(icon: ImageVector, text: String, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}
