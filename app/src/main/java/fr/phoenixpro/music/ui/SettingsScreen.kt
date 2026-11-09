@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.phoenixpro.music.BitPerfect
import fr.phoenixpro.music.Covers
import fr.phoenixpro.music.Engine
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Store
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@Composable
private fun SwitchRow(icon: ImageVector, title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsScreen(ui: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Store.settings.collectAsState()
    val bp by BitPerfect.status.collectAsState()
    val albums by Library.albums.collectAsState()
    val tracks by Library.tracks.collectAsState()
    val scanning by Library.scanning.collectAsState()
    val startHiRes = remember { s.hiRes }
    var coverJob by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var clearHist by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Réglages")

        SectionLabel("Son")
        SwitchRow(
            Icons.Outlined.HighQuality, "Sortie haute résolution",
            "Décode en 32 bits flottant pour garder la pleine qualité des FLAC 24 bits", s.hiRes,
        ) { v -> Store.updateSettings { it.copy(hiRes = v) } }
        if (s.hiRes != startHiRes) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Prise en compte au redémarrage de l'appli.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    Engine.player.pause()
                    val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ctx.startActivity(i)
                    exitProcess(0)
                }) { Text("Redémarrer") }
            }
        }
        SwitchRow(
            Icons.Outlined.Usb, "Bit-perfect vers un DAC USB",
            when {
                !BitPerfect.supported -> "Nécessite Android 14 ou plus récent"
                bp.dac == null -> "Branche un DAC USB pour l'activer"
                else -> "${bp.dac} — ${bp.message.ifBlank { "prêt" }}"
            },
            s.bitPerfect, enabled = BitPerfect.supported,
        ) { v -> Store.updateSettings { it.copy(bitPerfect = v) }; BitPerfect.refresh() }

        SectionLabel("Pochettes et infos")
        SwitchRow(
            Icons.Outlined.Image, "Chercher sur Internet",
            "Pochettes manquantes (MusicBrainz) et biographies des artistes (Wikipédia)", s.onlineCovers,
        ) { v -> Store.updateSettings { it.copy(onlineCovers = v) } }
        SwitchRow(Icons.Outlined.Wifi, "Seulement en Wi-Fi", "N'utilise pas les données mobiles", s.wifiOnly, enabled = s.onlineCovers) { v ->
            Store.updateSettings { it.copy(wifiOnly = v) }
        }
        val job = coverJob
        ActionRow(
            Icons.Outlined.ImageSearch, "Compléter les pochettes manquantes",
            if (job != null) "Recherche… ${job.first} / ${job.second}" else "Cherche une pochette pour chaque album qui n'en a pas",
        ) {
            if (coverJob != null) return@ActionRow
            if (!Covers.canUseNetwork(ctx)) { ui.toast("Pas de connexion disponible (ou Wi-Fi uniquement)"); return@ActionRow }
            coverJob = 0 to 0
            scope.launch {
                val n = Covers.fetchAllMissing(ctx) { a, b -> coverJob = a to b }
                coverJob = null
                ui.toast(if (n == 0) "Aucune nouvelle pochette trouvée" else "${plural(n, "pochette ajoutée", "pochettes ajoutées")}")
            }
        }
        if (job != null && job.second > 0) LinearProgressIndicator(
            progress = { job.first.toFloat() / job.second }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        SectionLabel("Bibliothèque")
        ActionRow(
            Icons.Outlined.Refresh, "Actualiser la bibliothèque",
            if (scanning) "Recherche en cours…" else "${plural(albums.size, "album")} · ${plural(tracks.size, "titre")} sur le téléphone et la carte microSD",
        ) { scope.launch { Library.scan(ctx.applicationContext); ui.toast("Bibliothèque à jour") } }
        ActionRow(Icons.Outlined.History, "Effacer l'historique d'écoute", "Vide « Écoutés récemment » et « Les plus écoutés »") { clearHist = true }

        SectionLabel("À propos")
        val version = remember {
            try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" }
        }
        ActionRow(Icons.Outlined.Info, "Phoenix Pro Music", "Version $version · tout reste sur le téléphone") {}
        Spacer(Modifier.height(24.dp))
    }

    if (clearHist) AlertDialog(
        onDismissRequest = { clearHist = false },
        title = { Text("Effacer l'historique ?") },
        text = { Text("Les playlists et les favoris ne sont pas touchés.") },
        confirmButton = { TextButton(onClick = { Store.clearHistory(); clearHist = false; ui.toast("Historique effacé") }) { Text("Effacer") } },
        dismissButton = { TextButton(onClick = { clearHist = false }) { Text("Annuler") } },
    )
}
