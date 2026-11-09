package fr.phoenixpro.agenda.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fr.phoenixpro.agenda.DEFAULT_CATEGORIES
import fr.phoenixpro.agenda.REMINDER_CHOICES
import fr.phoenixpro.agenda.School
import fr.phoenixpro.agenda.Store
import fr.phoenixpro.agenda.reminderLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.exportJson().toByteArray()) } }.isSuccess
            }
            toast(if (ok) "Sauvegarde enregistrée" else "Impossible d'enregistrer la sauvegarde")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val txt = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
                txt != null && Store.importJson(txt)
            }
            toast(if (ok) "Sauvegarde restaurée" else "Fichier de sauvegarde non reconnu")
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val notifOk = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Réglages")

        SectionLabel("Vacances scolaires")
        FieldRow(Icons.Outlined.BeachAccess, "Afficher les vacances", trailing = {
            Switch(checked = s.showSchool, onCheckedChange = { Store.updateSettings(s.copy(showSchool = it)) })
        }) { Store.updateSettings(s.copy(showSchool = !s.showSchool)) }
        if (s.showSchool) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Zone", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
                listOf("A", "B", "C").forEach { z ->
                    FilterChip(
                        selected = s.zone == z,
                        onClick = {
                            Store.updateSettings(s.copy(zone = z))
                            scope.launch(Dispatchers.IO) { School.refresh(ctx.applicationContext, z) }
                        },
                        label = { Text("Zone $z") },
                    )
                }
            }
            Text(
                "Le Cher (académie d'Orléans-Tours) est en zone B. Les dates viennent du ministère de l'Éducation nationale.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            TextButton(
                enabled = !updating,
                onClick = {
                    updating = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { School.refresh(ctx.applicationContext, s.zone, force = true) }
                        updating = false
                        toast(if (ok) "Dates des vacances mises à jour" else "Pas de connexion : dates intégrées utilisées")
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (updating) "Mise à jour…" else "Mettre à jour les dates")
            }
            val sv by School.version.collectAsState()
            val next = remember(s.zone, sv) {
                School.breaks(s.zone).firstOrNull { !it.to.isBefore(LocalDate.now()) }
            }
            if (next != null) Text(
                "Prochaines : ${next.name}, du ${next.from.fmt("d MMMM").lowercase(FR)} au ${next.to.fmt("d MMMM yyyy").lowercase(FR)}",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        SectionLabel("Rappels")
        ChoiceRow(Icons.Outlined.Notifications, s.defaultReminder, REMINDER_CHOICES, {
            "Par défaut : " + reminderLabel(it, false).replaceFirstChar { c -> c.lowercase(FR) }
        }) { Store.updateSettings(s.copy(defaultReminder = it)) }
        if (!notifOk) FieldRow(Icons.Outlined.NotificationsOff, "Autoriser les notifications") {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        FieldRow(Icons.Outlined.Tune, "Son et vibration des rappels") {
            val i = Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, fr.phoenixpro.agenda.Reminders.CHANNEL)
            runCatching { ctx.startActivity(i) }
        }

        SectionLabel("Catégories")
        s.categories.forEachIndexed { i, name ->
            var text by remember(i) { mutableStateOf(name) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(catColor(i), 16.dp)
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        val list = s.categories.toMutableList()
                        list[i] = it
                        Store.updateSettings(s.copy(categories = list))
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionLabel("Sauvegarde")
        FieldRow(Icons.Outlined.Upload, "Exporter mes rendez-vous et notes") {
            exportLauncher.launch("PhoenixProAgenda-${LocalDate.now()}.json")
        }
        FieldRow(Icons.Outlined.Download, "Restaurer une sauvegarde") {
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
        }
        Text(
            "Vos données restent sur le téléphone. Exportez une sauvegarde de temps en temps (par exemple vers Google Drive).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SectionLabel("À propos")
        val version = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        }
        Text(
            "Phoenix Pro Agenda $version",
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}
