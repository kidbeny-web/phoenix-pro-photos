package fr.phoenixpro.detecteur

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

@Composable
fun CamsScreen() {
    var tool by remember { mutableStateOf<String?>(null) }
    when (tool) {
        "reflets" -> LensTool(front = false, onBack = { tool = null })
        "ir" -> LensTool(front = true, onBack = { tool = null })
        "reseau" -> NetScanTool(onBack = { tool = null })
        "liste" -> ChecklistTool(onBack = { tool = null })
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header("Caméras cachées", "Chambre d'hôtel, location, vestiaire : quatre vérifications complémentaires")
            ToolCard(Icons.Filled.FlashOn, "Reflets d'objectif", "Flash allumé, pièce sombre : un objectif renvoie un petit point brillant.") { tool = "reflets" }
            ToolCard(Icons.Filled.NightsStay, "Lumière infrarouge", "Dans le noir, la caméra avant voit les LED des caméras à vision nocturne.") { tool = "ir" }
            ToolCard(Icons.Filled.Wifi, "Appareils sur le Wi-Fi", "Liste les appareils du réseau et signale ceux qui ressemblent à des caméras.") { tool = "reseau" }
            ToolCard(Icons.Filled.Checklist, "Cachettes à vérifier", "Les endroits où l'on trouve le plus souvent des caméras.") { tool = "liste" }
            Text(
                "Aucune appli ne peut garantir qu'il n'y a pas de caméra. Ces outils aident à repérer les plus courantes. " +
                    "Si vous en trouvez une dans un logement loué, ne la touchez pas : prenez des photos, signalez-la à la plateforme et à la police.",
                fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
fun ToolCard(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22F08A1C)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Orange)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextC, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text, color = Muted, fontSize = 13.sp)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Muted)
    }
}

@Composable
fun BackRow(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Retour", tint = TextC) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextC)
    }
}

// ---------------------------------------------------------------- reflets et infrarouge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LensTool(front: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(!front) }
    var dark by remember { mutableStateOf(true) }

    CameraPermission {
        DisposableEffect(front) {
            val fut = ProcessCameraProvider.getInstance(ctx)
            fut.addListener({
                try {
                    val prov = fut.get()
                    val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    prov.unbindAll()
                    val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                    camera = prov.bindToLifecycle(owner, sel, p)
                } catch (e: Exception) { error = "La caméra ne démarre pas" }
            }, ContextCompat.getMainExecutor(ctx))
            onDispose { try { fut.get().unbindAll() } catch (_: Exception) {} }
        }
        LaunchedEffect(camera, torch, dark) {
            val c = camera ?: return@LaunchedEffect
            if (c.cameraInfo.hasFlashUnit()) c.cameraControl.enableTorch(torch && !front)
            val range = c.cameraInfo.exposureState.exposureCompensationRange
            if (c.cameraInfo.exposureState.isExposureCompensationSupported) c.cameraControl.setExposureCompensationIndex(if (dark) range.lower else 0)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BackRow(if (front) "Lumière infrarouge" else "Reflets d'objectif", onBack)
            Text(
                if (front) "Éteignez la lumière et fermez les rideaux. Balayez lentement la pièce avec l'écran tourné vers elle : une LED infrarouge apparaît comme un point violet ou blanc qui ne se voit pas à l'œil nu. Test : visez une télécommande et appuyez sur un bouton."
                else "Éteignez la lumière. Balayez lentement la pièce à 1 ou 2 mètres des objets : un objectif renvoie un petit point brillant bleuté ou violet qui reste fixe quand vous bougez.",
                fontSize = 13.sp, color = Muted,
            )
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
                AndroidView({ preview }, Modifier.fillMaxSize())
                error?.let { Text(it, color = Red, modifier = Modifier.align(Alignment.Center)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (!front) FilterChip(selected = torch, onClick = { torch = !torch }, label = { Text("Flash") }, leadingIcon = { Icon(Icons.Filled.FlashOn, null) })
                FilterChip(selected = dark, onClick = { dark = !dark }, label = { Text("Image assombrie") }, leadingIcon = { Icon(Icons.Filled.Contrast, null) })
            }
        }
    }
}

// ---------------------------------------------------------------- scan du réseau

data class Host(val ip: String, val ports: List<Int>, val name: String?, val self: Boolean, val gateway: Boolean) {
    /** Ports typiques des caméras IP et enregistreurs vidéo (RTSP, ONVIF, DVR…). */
    val suspicious get() = ports.any { it in listOf(554, 8554, 8000, 34567, 37777, 9527) }
}

val PORTS = listOf(554, 8554, 80, 8080, 8000, 443, 34567, 37777, 9527)

suspend fun scanNetwork(ctx: Context, progress: (Int) -> Unit): Pair<String?, List<Host>> = withContext(Dispatchers.IO) {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork
    val caps = cm.getNetworkCapabilities(net)
    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@withContext "Connectez-vous d'abord au Wi-Fi du logement." to emptyList()
    val lp = cm.getLinkProperties(net) ?: return@withContext "Réseau illisible." to emptyList()
    val me = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address } ?: return@withContext "Adresse du téléphone introuvable." to emptyList()
    val gw = lp.routes.mapNotNull { it.gateway }.firstOrNull { it is Inet4Address }?.hostAddress
    val b = me.address
    val sem = Semaphore(48)
    var done = 0
    val found = coroutineScope {
        (1..254).map { i ->
            async {
                sem.withPermit {
                    val ip = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.$i"
                    val open = PORTS.filter { port ->
                        try { Socket().use { s -> s.connect(InetSocketAddress(ip, port), 220); true } } catch (e: Exception) { false }
                    }
                    val alive = open.isNotEmpty() || try { InetAddress.getByName(ip).isReachable(250) } catch (e: Exception) { false }
                    synchronized(this@withContext) { done++; progress(done * 100 / 254) }
                    if (!alive) null else {
                        val addr = InetAddress.getByName(ip)
                        val name = try { addr.canonicalHostName.takeIf { it != ip } } catch (e: Exception) { null }
                        Host(ip, open, name, ip == me.hostAddress, ip == gw)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
    null to found.sortedWith(compareByDescending<Host> { it.suspicious }.thenBy { it.ip.substringAfterLast('.').toInt() })
}

@Composable
fun NetScanTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var hosts by remember { mutableStateOf<List<Host>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        running = true; progress = 0; error = null
        scope.launch {
            val (err, list) = scanNetwork(ctx) { progress = it }
            error = err; hosts = list; running = false
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackRow("Appareils sur le Wi-Fi", onBack)
        Text("Connecté au Wi-Fi du logement, le téléphone interroge les 254 adresses du réseau. Une caméra cachée doit souvent passer par ce Wi-Fi pour envoyer ses images.", fontSize = 13.sp, color = Muted)
        Button(
            onClick = { if (!running) go() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange),
        ) { Text(if (running) "Analyse… $progress %" else if (hosts == null) "Analyser le réseau" else "Relancer l'analyse", fontWeight = FontWeight.Bold) }
        if (running) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Orange)
        error?.let { Text(it, color = Red) }
        hosts?.let { list ->
            val sus = list.count { it.suspicious && !it.self }
            Panel {
                Text("${list.size} appareil${if (list.size > 1) "s" else ""} trouvé${if (list.size > 1) "s" else ""}", fontWeight = FontWeight.Bold, color = TextC, fontSize = 18.sp)
                Text(
                    if (sus == 0) "Aucun ne ressemble à une caméra (aucun port vidéo ouvert)." else "$sus appareil${if (sus > 1) "s" else ""} avec un port vidéo ouvert : à vérifier.",
                    color = if (sus == 0) Green else Red, fontSize = 14.sp,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(list, key = { it.ip }) { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when { h.self -> Icons.Filled.Smartphone; h.gateway -> Icons.Filled.Router; h.suspicious -> Icons.Filled.Videocam; else -> Icons.Filled.Devices },
                            contentDescription = null, tint = if (h.suspicious && !h.self) Red else Orange,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when { h.self -> "Ce téléphone"; h.gateway -> "Box / routeur"; h.suspicious -> "Possible caméra ou enregistreur"; else -> h.name ?: "Appareil" },
                                color = TextC, fontWeight = FontWeight.SemiBold,
                            )
                            Text(h.ip + (h.name?.let { " · $it" } ?: ""), fontSize = 12.sp, color = Muted)
                            if (h.ports.isNotEmpty()) Text("Ports ouverts : ${h.ports.joinToString(", ")}", fontSize = 12.sp, color = if (h.suspicious) Red else Muted)
                        }
                    }
                }
            }
        }
        if (hosts == null && !running) Text(
            "Ports vidéo recherchés : 554 et 8554 (flux vidéo RTSP), 8000, 34567, 37777 et 9527 (enregistreurs). Une télé ou une enceinte connectée peut aussi apparaître : comparez avec les appareils visibles dans le logement.",
            fontSize = 12.sp, color = Muted,
        )
    }
}

// ---------------------------------------------------------------- liste des cachettes

val SPOTS = listOf(
    "Détecteur de fumée (surtout au-dessus du lit ou de la douche)",
    "Réveil, radio, enceinte connectée",
    "Chargeur USB ou adaptateur branché sans raison",
    "Prises et multiprises",
    "Cadres, tableaux, horloges murales",
    "Miroir : collez le doigt, s'il n'y a pas d'écart entre le doigt et son reflet, c'est peut-être un miroir sans tain",
    "Box internet, décodeur, télévision",
    "Plantes, peluches, objets décoratifs face au lit",
    "Bouches d'aération et grilles",
    "Ampoules ou lampes de chevet inhabituelles",
    "Trous minuscules dans les murs ou le plafond",
)

@Composable
fun ChecklistTool(onBack: () -> Unit) {
    val checked = remember { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BackRow("Cachettes à vérifier", onBack)
        Text("${checked.size} / ${SPOTS.size} vérifiés", color = if (checked.size == SPOTS.size) Green else Muted, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(SPOTS.indices.toList()) { i ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card)
                        .clickable { if (i in checked) checked.remove(i) else checked.add(i) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = i in checked, onCheckedChange = { if (it) checked.add(i) else checked.remove(i) })
                    Text(SPOTS[i], color = TextC, fontSize = 14.sp)
                }
            }
        }
    }
}
