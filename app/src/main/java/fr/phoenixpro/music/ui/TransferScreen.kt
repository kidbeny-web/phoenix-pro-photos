@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fr.phoenixpro.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.phoenixpro.music.Library
import fr.phoenixpro.music.Store
import fr.phoenixpro.music.Transfer

@Composable
fun TransferScreen(ui: UiState) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val st by Transfer.state.collectAsState()
    val settings by Store.settings.collectAsState()
    val storages = remember { Transfer.storages(ctx) }

    DisposableEffect(st.running) {
        view.keepScreenOn = st.running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(st.lastDone) {
        if (st.lastDone > 0) {
            Library.scan(ctx.applicationContext)
            ui.toast("Bibliothèque mise à jour")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Depuis le PC", "Envoie tes albums FLAC par le Wi-Fi")

        SectionLabel("Où ranger la musique")
        storages.forEach { s ->
            val sel = settings.volume == s.volume
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (s.volume == "external_primary") Icons.Outlined.Smartphone else Icons.Outlined.SdCard, null)
                Spacer(Modifier.width(12.dp))
                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                RadioButton(sel, onClick = { if (!st.running) Store.updateSettings { it.copy(volume = s.volume) } }, enabled = !st.running || sel)
            }
        }
        Text(
            "Les albums sont rangés dans le dossier Music, avec la même arborescence que sur le PC.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Spacer(Modifier.height(8.dp))
        if (!st.running) {
            Button(onClick = { Transfer.start(ctx) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp)) {
                Text("Démarrer la réception")
            }
            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            }
            SectionLabel("Comment ça marche")
            listOf(
                "Le PC et le téléphone doivent être sur le même Wi-Fi.",
                "Touche « Démarrer la réception » : une adresse et un code s'affichent.",
                "Sur le PC, ouvre l'adresse dans Chrome, Edge ou Firefox.",
                "Glisse tes dossiers d'albums dans la page (ou choisis-les). Les albums déjà présents sur le téléphone sont sautés.",
            ).forEachIndexed { i, s ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sur le PC, ouvre :", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    st.address ?: "", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text("Code", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(st.code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp, color = PhoenixOrange)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    if (st.received == 0) "En attente du PC…" else "${plural(st.received, "fichier reçu", "fichiers reçus")} · ${mb(st.bytes)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (st.current.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(st.current, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    LinearProgressIndicator(progress = { st.currentPct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Garde l'appli ouverte pendant l'envoi (l'écran reste allumé).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { Transfer.stop() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Arrêter la réception")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun mb(b: Long): String =
    if (b >= 1L shl 30) String.format(FR, "%.2f Go", b / 1073741824.0) else String.format(FR, "%.0f Mo", b / 1048576.0)
