package fr.phoenixpro.photos.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import fr.phoenixpro.photos.Db
import fr.phoenixpro.photos.Indexer
import fr.phoenixpro.photos.Prefs
import fr.phoenixpro.photos.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bouton « Scanner le QR code du PC » + saisie manuelle. */
@Composable
fun PairButton(onPaired: () -> Unit) {
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val txt = r.contents ?: return@rememberLauncherForActivityResult
        if (Prefs(ctx).pairFromLink(txt)) { Toast.makeText(ctx, "Relié au PC", Toast.LENGTH_SHORT).show(); onPaired() }
        else Toast.makeText(ctx, "Ce QR code ne vient pas de Phoenix Pro Photos", Toast.LENGTH_LONG).show()
    }
    Column {
        Button(onClick = {
            scan.launch(ScanOptions().setPrompt("Scannez le QR code affiché dans Réglages → Téléphone Android sur le PC").setBeepEnabled(false).setOrientationLocked(false))
        }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scanner le QR code du PC")
        }
        TextButton(onClick = { manual = !manual }) { Text("Saisir le lien à la main") }
        if (manual) {
            OutlinedTextField(link, { link = it }, singleLine = true, shape = RoundedCornerShape(12.dp),
                placeholder = { Text("http://192.168.1.20:8765/?cle=…") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                if (Prefs(ctx).pairFromLink(link)) onPaired() else Toast.makeText(ctx, "Lien invalide", Toast.LENGTH_SHORT).show()
            }) { Text("Valider") }
        }
    }
}

@Composable
fun PcScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var paired by remember { mutableStateOf(prefs.paired) }
    var status by remember { mutableStateOf<Sync.PingResult?>(null) }
    var browse by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val stats = rememberData("stats") { Db.stats() }
    val progress by Indexer.progress.collectAsState()

    LaunchedEffect(paired, refresh) {
        if (paired) { status = null; status = withContext(Dispatchers.IO) { Sync.ping(ctx) } }
    }

    if (browse && paired && status is Sync.PingResult.Ok) {
        PcWebView("${prefs.pcUrl}/?cle=${prefs.pcKey}") { browse = false }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        ScreenTitle("Mon PC", if (paired) prefs.pcUrl.removePrefix("http://") else null) {
            if (paired) IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "Actualiser") }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (!paired) {
                Text(
                    "Reliez le téléphone à Phoenix Pro Photos sur votre PC pour y envoyer automatiquement vos nouvelles photos (avec leur position) et parcourir toute la bibliothèque du PC.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("Sur le PC : Réglages → Téléphone Android → activez l'accès. Un QR code apparaît.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                PairButton { paired = true; Indexer.start(ctx) }
                return@Column
            }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(18.dp)) {
                    when (val s = status) {
                        null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Recherche du PC sur le Wi-Fi…")
                        }
                        is Sync.PingResult.Ok -> Text("● PC connecté", color = PhoenixTeal, style = MaterialTheme.typography.titleMedium)
                        is Sync.PingResult.Error -> {
                            Text("● PC non disponible", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if ((stats?.toSync ?: 0) == 0) "Toutes les photos du téléphone sont sur le PC."
                        else plural(stats?.toSync ?: 0, "photo à envoyer", "photos à envoyer"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (progress.running && progress.phase.startsWith("Envoi")) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { browse = true }, enabled = status is Sync.PingResult.Ok,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Parcourir la bibliothèque du PC") }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { Sync.uploadPending(ctx) { _, _ -> } }
                        Toast.makeText(ctx, if (n < 0) "PC injoignable" else plural(n, "photo envoyée", "photos envoyées"), Toast.LENGTH_SHORT).show()
                        fr.phoenixpro.photos.PhoenixApp.changed()
                    }
                },
                enabled = status is Sync.PingResult.Ok && !progress.running,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(8.dp)); Text("Envoyer maintenant") }
            Spacer(Modifier.height(18.dp))
            Text(
                "Les nouvelles photos partent toutes seules vers le PC dès que le téléphone est sur le même Wi-Fi. Les réglages d'envoi sont dans Photos → ⚙.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PcWebView(url: String, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = onClose) { Text("‹ Retour") }
            Spacer(Modifier.weight(1f))
            Text("Bibliothèque du PC", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f)); Spacer(Modifier.width(80.dp))
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
