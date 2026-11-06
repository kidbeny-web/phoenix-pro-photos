package fr.phoenixpro.photos.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import fr.phoenixpro.photos.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SettingsCard(title: String, help: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text("%,d".format(FR, value), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val stats = rememberData("stats") { Db.stats() }
    var threshold by remember { mutableFloatStateOf(prefs.faceThreshold) }
    var autoSync by remember { mutableStateOf(prefs.autoSync) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var paired by remember { mutableStateOf(prefs.paired) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour") }
            Text("Réglages", style = MaterialTheme.typography.headlineMedium)
        }
        ProgressBanner()

        SettingsCard("Bibliothèque") {
            val s = stats
            if (s != null) {
                Row(Modifier.fillMaxWidth()) {
                    Stat(s.photos, "photos", Modifier.weight(1f))
                    Stat(s.faces, "visages", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat(s.people, "personnes nommées", Modifier.weight(1f))
                    Stat(s.geo, "photos localisées", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { Indexer.start(ctx) }) { Text("Rechercher les nouvelles photos") }
        }

        SettingsCard("Autorisations", "Pour afficher les lieux, l'accès à la position des photos doit être autorisé.") {
            Text(
                (if (Scanner.hasMediaPermission(ctx)) "✓ Accès aux photos" else "✗ Accès aux photos") + "\n" +
                    (if (Scanner.hasLocationPermission(ctx)) "✓ Position des photos" else "✗ Position des photos"),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }) { Text("Ouvrir les réglages Android") }
        }

        SettingsCard("Reconnaissance des visages", "Analyse 100 % sur le téléphone : aucune photo n'est envoyée sur Internet.") {
            val label = when {
                threshold < 0.39f -> "large"
                threshold > 0.48f -> "strict"
                else -> "équilibré"
            }
            Text("Regroupement : $label (${"%.2f".format(FR, threshold)})", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = threshold, onValueChange = { threshold = it }, valueRange = 0.32f..0.60f,
                onValueChangeFinished = { prefs.faceThreshold = threshold },
            )
            Row {
                Text("Plus large", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Plus strict", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { withContext(Dispatchers.IO) { Clusters.recluster(ctx) }; busy = false; Toast.makeText(ctx, "Visages regroupés", Toast.LENGTH_SHORT).show() }
            }) { Text(if (busy) "Regroupement…" else "Regrouper à nouveau les visages") }
        }

        SettingsCard("Synchronisation avec le PC", if (paired) "Relié à ${prefs.pcUrl.removePrefix("http://")}" else "Reliez le téléphone à Phoenix Pro Photos sur votre PC.") {
            if (!paired) {
                PairButton { paired = true; Indexer.start(ctx) }
            } else {
                SwitchLine("Envoyer automatiquement les nouvelles photos", autoSync) { autoSync = it; prefs.autoSync = it }
                SwitchLine("Uniquement en Wi-Fi", wifiOnly) { wifiOnly = it; prefs.wifiOnly = it; Indexer.schedulePeriodic(ctx) }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { Sync.resendAll() }; Indexer.start(ctx); Toast.makeText(ctx, "Toutes les photos seront envoyées au PC", Toast.LENGTH_LONG).show() }
                }) { Text("Envoyer aussi toutes les anciennes photos") }
                TextButton(onClick = { prefs.unpair(); paired = false }) { Text("Délier le PC", color = MaterialTheme.colorScheme.error) }
            }
        }

        SettingsCard("À propos") {
            Image(painterResource(R.drawable.logo_complet), contentDescription = null, modifier = Modifier.fillMaxWidth(0.6f))
            Spacer(Modifier.height(8.dp))
            Text("Version 1.0.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
