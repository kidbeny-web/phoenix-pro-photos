package fr.phoenixpro.detecteur

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

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

class DetecteurApp : Application()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, onPrimary = OnOrange, secondary = Blue,
                    background = Bg, surface = Bg, onBackground = TextC, onSurface = TextC,
                    outline = LineC, surfaceVariant = Card2, onSurfaceVariant = Muted,
                ),
            ) { DetecteurRoot() }
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
            .border(1.dp, LineC, RoundedCornerShape(18.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
fun Header(title: String, sub: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextC)
        Text(sub, fontSize = 14.sp, color = Muted)
    }
}

/** Demande l'accès à la caméra avant d'afficher le contenu. */
@Composable
fun CameraPermission(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) content()
    else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("La caméra sert à repérer les reflets et la lumière infrarouge. Rien n'est enregistré.", color = TextC, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange)) {
            Text("Autoriser la caméra", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DetecteurRoot() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) MetalScreen() else CamsScreen()
        }
        NavigationBar(containerColor = Card2, tonalElevation = 0.dp) {
            listOf(Triple("Métaux", Icons.Filled.Sensors, 0), Triple("Caméras cachées", Icons.Filled.Videocam, 1)).forEach { (label, icon, i) ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Orange, selectedTextColor = Orange, indicatorColor = Color(0x33F08A1C),
                        unselectedIconColor = Muted, unselectedTextColor = Muted,
                    ),
                )
            }
        }
    }
}
