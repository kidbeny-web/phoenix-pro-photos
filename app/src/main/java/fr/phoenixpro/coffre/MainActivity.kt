package fr.phoenixpro.coffre

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Couleurs Phoenix Pro
val Bg = Color(0xFF0E1012)
val Card = Color(0xE6181B1E)
val Card2 = Color(0xFF202427)
val LineC = Color(0xFF2A2F35)
val TextC = Color(0xFFF1F3F4)
val Muted = Color(0xFF9AA1A8)
val Orange = Color(0xFFF08A1C)
val Blue = Color(0xFF6F9BFF)
val Red = Color(0xFFFF5A5F)
val Green = Color(0xFF5FD6A8)
val OnOrange = Color(0xFF1A1206)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Vault.init(this)
        // Pas de capture d'écran ni d'aperçu dans les applis récentes.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = OnOrange, secondary = Blue,
                    background = Bg, surface = Bg, onBackground = TextC, onSurface = TextC,
                    outline = LineC, surfaceVariant = Card2, onSurfaceVariant = Muted,
                ),
            ) { CoffreRoot() }
        }
    }
}

fun catIcon(cat: String): ImageVector = when (cat) {
    "Wi-Fi" -> Icons.Filled.Wifi
    "Digicode" -> Icons.Filled.Dialpad
    "Alarme" -> Icons.Filled.Security
    "Cadenas" -> Icons.Filled.Lock
    "N° de série" -> Icons.Filled.Tag
    "Pièces" -> Icons.Filled.Build
    else -> Icons.Filled.Notes
}

fun bioAvailable(ctx: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= 30) {
        ctx.getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    } else if (Build.VERSION.SDK_INT >= 29) {
        ctx.getSystemService(BiometricManager::class.java).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    } else ctx.packageManager.hasSystemFeature("android.hardware.fingerprint")
} catch (e: Exception) { false }

/** Affiche la fenêtre d'empreinte avec un chiffreur à débloquer. */
fun biometric(ctx: Context, title: String, cipher: javax.crypto.Cipher, onOk: (javax.crypto.Cipher) -> Unit, onFail: (String?) -> Unit) {
    val ex = ContextCompat.getMainExecutor(ctx)
    val prompt = BiometricPrompt.Builder(ctx)
        .setTitle(title)
        .setSubtitle("Posez votre doigt ou regardez l'écran")
        .setNegativeButton("Utiliser le code", ex) { _, _ -> onFail(null) }
        .build()
    prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), ex, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            val c = result?.cryptoObject?.cipher
            if (c != null) onOk(c) else onFail("Empreinte non disponible")
        }
        override fun onAuthenticationError(code: Int, msg: CharSequence?) {
            if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) onFail(null)
            else onFail(msg?.toString())
        }
    })
}

/** Copie un code et l'efface du presse-papiers 45 s plus tard. */
fun copySecret(ctx: Context, label: String, value: String) {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cb.setPrimaryClip(clip)
    Toast.makeText(ctx, "Copié · effacé du presse-papiers dans 45 s", Toast.LENGTH_SHORT).show()
    Handler(Looper.getMainLooper()).postDelayed({
        try {
            val cur = cb.primaryClip
            if (cur != null && cur.itemCount > 0 && cur.getItemAt(0).text?.toString() == value) cb.clearPrimaryClip()
        } catch (_: Exception) { }
    }, 45_000)
}

// ---------------------------------------------------------------- racine

@Composable
fun CoffreRoot() {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    fun refresh() { tick++ }

    // Verrouillage automatique après 30 s hors de l'appli.
    DisposableEffect(owner) {
        var leftAt = 0L
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_STOP -> leftAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> if (leftAt > 0 && System.currentTimeMillis() - leftAt > 30_000 && Vault.unlocked) { Vault.lock(); tick++ }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        key(tick) {
            when {
                !Vault.isSetup -> SetupScreen { refresh() }
                !Vault.unlocked -> LockScreen { refresh() }
                else -> MainScreen(onLock = { Vault.lock(); refresh() })
            }
        }
    }
}

// ---------------------------------------------------------------- clavier à code

@Composable
fun PinPad(title: String, sub: String, error: String?, bioButton: (() -> Unit)? = null, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(Card2), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = Orange, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextC, textAlign = TextAlign.Center)
        Text(sub, fontSize = 14.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < pin.length) Orange else Card2).border(1.5.dp, LineC, CircleShape)) }
        }
        Text(error ?: "", color = Red, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp).height(20.dp))
        Spacer(Modifier.height(12.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(74.dp).clip(CircleShape)
                            .background(if (k == "bio" && bioButton == null) Color.Transparent else Card2)
                            .clickable(enabled = k != "bio" || bioButton != null) {
                                when (k) {
                                    "del" -> pin = pin.dropLast(1)
                                    "bio" -> bioButton?.invoke()
                                    else -> if (pin.length < 6) {
                                        pin += k
                                        if (pin.length == 6) { val p = pin; pin = ""; onDone(p) }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k) {
                            "del" -> Icon(Icons.Filled.Backspace, contentDescription = "Effacer", tint = TextC)
                            "bio" -> if (bioButton != null) Icon(Icons.Filled.Fingerprint, contentDescription = "Empreinte", tint = Orange, modifier = Modifier.size(32.dp))
                            else -> Text(k, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = TextC)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- création

@Composable
fun SetupScreen(onReady: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var offerBio by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (offerBio) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = Orange, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("Ouvrir le coffre avec l'empreinte ?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextC, textAlign = TextAlign.Center)
            Text("Plus rapide au quotidien. Le code reste nécessaire si l'empreinte échoue.", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                try {
                    val c = Vault.bioEnrollCipher()
                    biometric(ctx, "Activer l'empreinte", c, onOk = { ok -> Vault.finishBioEnroll(ok); onReady() }, onFail = { m -> m?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } })
                } catch (e: Exception) { Toast.makeText(ctx, "Empreinte indisponible", Toast.LENGTH_LONG).show(); onReady() }
            }, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Activer l'empreinte", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onReady) { Text("Plus tard", color = Muted) }
        }
        return
    }
    if (busy) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }; return }

    PinPad(
        if (first == null) "Créez votre code" else "Confirmez le code",
        if (first == null) "6 chiffres, à retenir : il protège tous vos codes." else "Tapez le même code une seconde fois.",
        error,
    ) { p ->
        val f = first
        when {
            f == null && (p.toSet().size == 1 || "0123456789".contains(p) || "9876543210".contains(p)) -> error = "Code trop simple, choisissez-en un autre"
            f == null -> { first = p; error = null }
            f != p -> { first = null; error = "Les deux codes sont différents, recommencez" }
            else -> {
                busy = true
                scope.launch {
                    withContext(Dispatchers.Default) { Vault.setup(p) }
                    busy = false
                    if (bioAvailable(ctx)) offerBio = true else onReady()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- verrou

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun tryBio() {
        val c = Vault.bioUnlockCipher()
        if (c == null) { error = if (Vault.bioEnabled) null else "Empreinte à réactiver dans les réglages : utilisez le code"; return }
        biometric(ctx, "Ouvrir le coffre", c, onOk = { ok -> if (Vault.finishBioUnlock(ok)) onUnlocked() else error = "Échec, utilisez le code" }, onFail = { m -> if (m != null) error = m })
    }
    LaunchedEffect(Unit) { if (Vault.bioEnabled) tryBio() }

    if (busy) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }; return }
    PinPad("Coffre verrouillé", "Tapez votre code${if (Vault.bioEnabled) " ou utilisez l'empreinte" else ""}", error,
        bioButton = if (Vault.bioEnabled) ({ tryBio() }) else null) { p ->
        val wait = Vault.waitSeconds()
        if (wait > 0) { error = "Trop d'essais : patientez $wait s"; return@PinPad }
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) { Vault.unlockWithPin(p) }
            busy = false
            if (ok) onUnlocked()
            else {
                val w = Vault.waitSeconds()
                error = if (w > 0) "Code faux. Trop d'essais : patientez $w s" else "Code faux (${Vault.failCount()} essai${if (Vault.failCount() > 1) "s" else ""})"
            }
        }
    }
}

// ---------------------------------------------------------------- liste

@Composable
fun MainScreen(onLock: () -> Unit) {
    var list by remember { mutableStateOf(Vault.entries) }
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf<Entry?>(null) }
    var settings by remember { mutableStateOf(false) }
    val revealed = remember { mutableStateListOf<Long>() }
    val ctx = LocalContext.current
    fun reload() { list = Vault.entries }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Coffre", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
                Text("${list.size} code${if (list.size > 1) "s" else ""} chiffré${if (list.size > 1) "s" else ""}", fontSize = 14.sp, color = Muted)
            }
            IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Réglages", tint = TextC) }
            IconButton(onClick = onLock) { Icon(Icons.Filled.Lock, contentDescription = "Verrouiller", tint = Orange) }
        }
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Rechercher un code, une pièce, un lieu…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), shape = RoundedCornerShape(14.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Tous ${list.size}", cat == null) { cat = null }
            CATS.forEach { c ->
                val n = list.count { it.cat == c }
                if (n > 0) Chip("$c $n", cat == c) { cat = if (cat == c) null else c }
            }
        }
        val q = query.trim().lowercase()
        val shown = list.filter { (cat == null || it.cat == cat) && (q.isEmpty() || "${it.name} ${it.extra} ${it.note} ${it.value} ${it.cat}".lowercase().contains(q)) }
            .sortedWith(compareBy({ CATS.indexOf(it.cat) }, { it.name.lowercase() }))
        Box(Modifier.weight(1f)) {
            if (list.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Votre coffre est vide.", color = TextC, fontWeight = FontWeight.SemiBold)
                    Text("Ajoutez le code Wi-Fi, le digicode de l'immeuble, la référence du filtre de hotte…", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            } else LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { e ->
                    val open = e.id in revealed
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).border(1.dp, LineC, RoundedCornerShape(16.dp))
                            .clickable { showing = e }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22F08A1C)), contentAlignment = Alignment.Center) {
                            Icon(catIcon(e.cat), contentDescription = null, tint = Orange)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = TextC, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(if (open) e.value else "•".repeat(e.value.length.coerceIn(4, 12)), fontFamily = FontFamily.Monospace, color = if (open) Orange else Muted, maxLines = 1)
                            if (e.extra.isNotBlank()) Text(e.extra, fontSize = 12.sp, color = Muted, maxLines = 1)
                        }
                        IconButton(onClick = { if (open) revealed.remove(e.id) else revealed.add(e.id) }) {
                            Icon(if (open) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Afficher", tint = Muted)
                        }
                        IconButton(onClick = { copySecret(ctx, e.name, e.value) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copier", tint = Muted) }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { creating = true }, containerColor = Orange, contentColor = OnOrange,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("Ajouter", fontWeight = FontWeight.Bold) },
            )
        }
    }

    showing?.let { e ->
        DetailDialog(e, onClose = { showing = null }, onEdit = { showing = null; editing = e }, onDelete = { Vault.delete(e.id); showing = null; reload() })
    }
    if (creating || editing != null) {
        EditDialog(editing, cat, onClose = { creating = false; editing = null; reload() })
    }
    if (settings) SettingsDialog(onClose = { settings = false; reload() })
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Orange else Card2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(text, color = if (selected) OnOrange else TextC, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

// ---------------------------------------------------------------- détail, QR Wi-Fi

fun wifiQrText(ssid: String, pass: String): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
    return if (pass.isEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;" else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        try { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) } catch (e: Exception) { null }
    }
    if (matrix == null) { Text("QR code impossible", color = Red); return }
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
    }
}

@Composable
fun DetailDialog(e: Entry, onClose: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var qr by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Card2,
        icon = { Icon(catIcon(e.cat), contentDescription = null, tint = Orange) },
        title = { Text(e.name, textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (qr && e.cat == "Wi-Fi") {
                    QrCode(wifiQrText(e.extra.ifBlank { e.name }, e.value), Modifier.fillMaxWidth())
                    Text("Vos invités scannent ce code avec l'appareil photo pour se connecter.", fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center)
                } else {
                    Text(valueLabel(e.cat), fontSize = 12.sp, color = Muted)
                    Text(e.value, fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Orange)
                    if (e.extra.isNotBlank()) { Text(extraLabel(e.cat), fontSize = 12.sp, color = Muted); Text(e.extra, color = TextC) }
                    if (e.note.isNotBlank()) { Text("Note", fontSize = 12.sp, color = Muted); Text(e.note, color = TextC) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copySecret(ctx, e.name, e.value) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copier")
                    }
                    if (e.cat == "Wi-Fi") OutlinedButton(onClick = { qr = !qr }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (qr) "Code" else "QR code")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Modifier") }
                    TextButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = null, tint = Red); Spacer(Modifier.width(6.dp)); Text("Supprimer", color = Red) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer", color = Orange) } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Card2,
        title = { Text("Supprimer « ${e.name} » ?") }, text = { Text("Ce code sera effacé définitivement du coffre.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Supprimer", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- ajout / modification

@Composable
fun EditDialog(e: Entry?, defaultCat: String?, onClose: () -> Unit) {
    var cat by remember { mutableStateOf(e?.cat ?: defaultCat ?: "Wi-Fi") }
    var name by remember { mutableStateOf(e?.name ?: "") }
    var value by remember { mutableStateOf(e?.value ?: "") }
    var extra by remember { mutableStateOf(e?.extra ?: "") }
    var note by remember { mutableStateOf(e?.note ?: "") }
    var show by remember { mutableStateOf(e == null) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Card2,
        title = { Text(if (e == null) "Nouveau code" else "Modifier") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CATS.forEach { c -> Chip(c, cat == c) { cat = c } }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(when (cat) { "Wi-Fi" -> "Ex. Box de la maison"; "Digicode" -> "Ex. Immeuble de Mamie"; "Pièces" -> "Ex. Filtre de hotte"; else -> "Nom" }) })
                OutlinedTextField(
                    value, { value = it }, label = { Text(valueLabel(cat)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (cat in listOf("Digicode", "Alarme", "Cadenas")) KeyboardType.Ascii else KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null) } },
                )
                OutlinedTextField(extra, { extra = it }, label = { Text(extraLabel(cat)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (facultatif)") }, modifier = Modifier.fillMaxWidth())
                if (cat == "Wi-Fi") Text("Indiquez le nom exact du réseau pour pouvoir partager un QR code.", fontSize = 12.sp, color = Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() || value.isBlank()) return@TextButton
                Vault.put(Entry(e?.id ?: System.currentTimeMillis(), cat, name.trim(), value.trim(), extra.trim(), note.trim(), System.currentTimeMillis()))
                onClose()
            }) { Text("Enregistrer", color = Orange, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annuler", color = Muted) } },
    )
}

// ---------------------------------------------------------------- réglages et sauvegarde

@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bio by remember { mutableStateOf(Vault.bioEnabled) }
    var mode by remember { mutableStateOf("menu") } // menu, pin1, pin2, export, import
    var newPin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importBytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
            if (importBytes != null) { mode = "import"; password = ""; msg = null } else msg = "Fichier illisible"
        }
    }

    AlertDialog(
        onDismissRequest = onClose, containerColor = Card2,
        title = { Text(when (mode) { "pin1", "pin2" -> "Changer le code"; "export" -> "Sauvegarde chiffrée"; "import" -> "Restaurer une sauvegarde"; else -> "Réglages" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    "menu" -> {
                        if (bioAvailable(ctx)) Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Ouvrir avec l'empreinte", color = TextC)
                                Text("Ou la reconnaissance du visage", fontSize = 12.sp, color = Muted)
                            }
                            Switch(checked = bio, onCheckedChange = { on ->
                                if (!on) { Vault.disableBio(); bio = false }
                                else try {
                                    val c = Vault.bioEnrollCipher()
                                    biometric(ctx, "Activer l'empreinte", c, onOk = { ok -> Vault.finishBioEnroll(ok); bio = true }, onFail = { m -> msg = m })
                                } catch (e: Exception) { msg = "Empreinte indisponible" }
                            })
                        }
                        SettingRow(Icons.Filled.Password, "Changer le code") { mode = "pin1"; newPin = ""; pin2 = ""; msg = null }
                        SettingRow(Icons.Filled.Upload, "Exporter une sauvegarde chiffrée") { mode = "export"; password = ""; msg = null }
                        SettingRow(Icons.Filled.Download, "Restaurer une sauvegarde") { picker.launch(arrayOf("*/*")) }
                        Text(
                            "Vos codes ne quittent jamais le téléphone et ne sont pas inclus dans les sauvegardes Google. " +
                                "Avant de changer de téléphone, exportez une sauvegarde chiffrée et gardez son mot de passe.",
                            fontSize = 12.sp, color = Muted,
                        )
                    }
                    "pin1", "pin2" -> {
                        OutlinedTextField(newPin, { if (it.length <= 6 && it.all(Char::isDigit)) newPin = it }, label = { Text("Nouveau code (6 chiffres)") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pin2, { if (it.length <= 6 && it.all(Char::isDigit)) pin2 = it }, label = { Text("Confirmez") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    }
                    "export", "import" -> {
                        Text(
                            if (mode == "export") "Choisissez un mot de passe d'au moins 8 caractères. Sans lui, la sauvegarde est illisible : notez-le à part."
                            else "Tapez le mot de passe choisi lors de l'export. Les codes déjà présents sont conservés.",
                            fontSize = 13.sp, color = Muted,
                        )
                        OutlinedTextField(password, { password = it }, label = { Text("Mot de passe de la sauvegarde") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    }
                }
                msg?.let { Text(it, color = if (it.startsWith("✓")) Green else Red, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            when (mode) {
                "menu" -> TextButton(onClick = onClose) { Text("Fermer", color = Orange) }
                "pin1", "pin2" -> TextButton(onClick = {
                    when {
                        newPin.length != 6 -> msg = "Le code doit faire 6 chiffres"
                        newPin != pin2 -> msg = "Les deux codes sont différents"
                        else -> scope.launch {
                            withContext(Dispatchers.Default) { Vault.changePin(newPin) }
                            msg = "✓ Code changé"; mode = "menu"
                        }
                    }
                }) { Text("Changer", color = Orange) }
                "export" -> TextButton(onClick = {
                    if (password.length < 8) { msg = "Au moins 8 caractères"; return@TextButton }
                    scope.launch {
                        val bytes = withContext(Dispatchers.Default) { Vault.exportBackup(password) }
                        val dir = File(ctx.cacheDir, "backup").apply { mkdirs() }
                        val f = File(dir, "coffre-phoenix-pro.phx").apply { writeBytes(bytes) }
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(i, "Enregistrer la sauvegarde"))
                        msg = "✓ Sauvegarde prête : enregistrez-la dans vos fichiers ou sur un Drive"; mode = "menu"
                    }
                }) { Text("Exporter", color = Orange) }
                else -> TextButton(onClick = {
                    val b = importBytes ?: return@TextButton
                    scope.launch {
                        val n = withContext(Dispatchers.Default) { Vault.importBackup(b, password) }
                        if (n < 0) msg = "Mot de passe faux ou fichier illisible"
                        else { msg = "✓ $n code${if (n > 1) "s" else ""} ajouté${if (n > 1) "s" else ""}"; mode = "menu" }
                    }
                }) { Text("Restaurer", color = Orange) }
            }
        },
        dismissButton = { if (mode != "menu") TextButton(onClick = { mode = "menu"; msg = null }) { Text("Retour", color = Muted) } },
    )
}

@Composable
fun SettingRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Orange); Spacer(Modifier.width(12.dp)); Text(text, color = TextC)
    }
}
