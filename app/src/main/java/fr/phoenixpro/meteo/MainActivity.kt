package fr.phoenixpro.meteo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

// Couleurs Phoenix Pro
val Bg = Color(0xFF0E1012)
val Card = Color(0xE6181B1E)
val Card2 = Color(0xFF202427)
val LineC = Color(0xFF2A2F35)
val TextC = Color(0xFFF1F3F4)
val Muted = Color(0xFF9AA1A8)
val Orange = Color(0xFFF08A1C)
val Blue = Color(0xFF6F9BFF)
val PhoenixBlue = Color(0xFF1D63C4)
val Purple = Color(0xFFB48CF0)
private val Fr = Locale.FRANCE

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Orange, secondary = Blue, background = Bg, surface = Bg,
                    onBackground = TextC, onSurface = TextC, outline = LineC,
                ),
            ) { MeteoScreen() }
        }
    }
}

@Composable
fun MeteoScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf(Store.current) }
    var favorites by remember { mutableStateOf(Store.favorites) }
    var forecast by remember { mutableStateOf(Store.cache()?.takeIf { it.first.sameAs(Store.current) }?.second) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selDay by remember { mutableIntStateOf(0) }
    var showSearch by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    fun load(p: Place) {
        place = p
        Store.current = p
        selDay = 0
        loading = true
        error = null
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { Api.fetchJson(p) }
                forecast = Api.parse(json)
                Store.saveCache(p, json)
                WeatherWidget.updateAll(ctx)
            } catch (e: Exception) {
                error = "Impossible de charger la météo. Vérifie ta connexion Internet."
            }
            loading = false
        }
    }

    fun locateMe() {
        locating = true
        getLocation(ctx) { loc ->
            if (loc == null) {
                locating = false
                Toast.makeText(ctx, "Position introuvable : active la localisation du téléphone.", Toast.LENGTH_LONG).show()
            } else {
                scope.launch {
                    val p = withContext(Dispatchers.IO) { Api.reverse(loc.latitude, loc.longitude) }
                    locating = false
                    load(p)
                }
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) locateMe() else Toast.makeText(ctx, "Autorisation de localisation refusée.", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(Unit) {
        load(place)
        while (true) { // actualisation toutes les 30 minutes tant que l'appli est ouverte
            delay(30 * 60 * 1000L)
            load(place)
        }
    }

    val f = forecast
    Box(Modifier.fillMaxSize().background(skyBrush(f?.current))) {
        if (f != null) WeatherParticles(f.current.code, f.current.isDay)

        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // En-tête
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(PhoenixBlue, Color(0xFF7B3FBF)))),
                        contentAlignment = Alignment.Center,
                    ) { Text("⛅", fontSize = 18.sp) }
                    Spacer(Modifier.width(10.dp))
                    Text("Phoenix Pro ", color = TextC, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Météo", color = Orange, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showSearch = true }) { Icon(Icons.Filled.Search, "Rechercher", tint = TextC) }
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locateMe()
                        else permLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }) {
                        if (locating) CircularProgressIndicator(Modifier.size(20.dp), color = Orange, strokeWidth = 2.dp)
                        else Icon(Icons.Filled.MyLocation, "Ma position", tint = TextC)
                    }
                    IconButton(onClick = { load(place) }) { Icon(Icons.Filled.Refresh, "Actualiser", tint = TextC) }
                }
            }

            // Favoris
            if (favorites.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(favorites) { fav ->
                        val on = fav.sameAs(place)
                        Row(
                            Modifier.clip(CircleShape).background(Card)
                                .border(1.dp, if (on) Orange else LineC, CircleShape)
                                .clickable { load(fav) }
                                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("★ ${fav.name}", color = if (on) Orange else TextC, fontSize = 13.sp)
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).clickable {
                                    favorites = favorites.filterNot { it.sameAs(fav) }
                                    Store.favorites = favorites
                                },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Close, "Retirer", tint = Muted, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }

            if (error != null) item {
                CardBox { Text(error!!, color = Color(0xFFFF8A8A), fontSize = 14.sp) }
            }

            if (f == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(color = Orange)
                    }
                }
            } else {
                item {
                    val isFav = favorites.any { it.sameAs(place) }
                    CurrentCard(place, f, loading, isFav) {
                        favorites = if (isFav) favorites.filterNot { it.sameAs(place) } else favorites + place
                        Store.favorites = favorites
                    }
                }
                item { SectionTitle("Prévisions sur 10 jours") }
                val gMin = f.days.minOf { it.tMin }
                val gMax = f.days.maxOf { it.tMax }
                itemsIndexed(f.days) { i, d ->
                    Column {
                        DayRow(i, d, gMin, gMax, i == selDay) { selDay = i }
                        if (i == selDay) {
                            Spacer(Modifier.height(10.dp))
                            DayDetail(f, i)
                        }
                    }
                }
                item {
                    Text(
                        "Données météo : Open-Meteo",
                        color = Muted, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    if (showSearch) SearchDialog(onDismiss = { showSearch = false }) { p ->
        showSearch = false
        load(p)
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
            .border(1.dp, LineC, RoundedCornerShape(18.dp)).padding(16.dp),
    ) { content() }
}

@Composable
fun SectionTitle(t: String) {
    Text(t.uppercase(Fr), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun CurrentCard(place: Place, f: Forecast, loading: Boolean, isFav: Boolean, onFav: () -> Unit) {
    val c = f.current
    val today = f.days.first()
    CardBox {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, color = TextC, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(place.region, "mis à jour à ${hm(c.time)}").filter { it.isNotBlank() }.joinToString(" · "),
                        color = Muted, fontSize = 12.sp, maxLines = 2,
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Orange, strokeWidth = 2.dp)
                IconButton(onClick = onFav) {
                    Icon(if (isFav) Icons.Filled.Star else Icons.Outlined.StarOutline, "Favori", tint = if (isFav) Orange else Muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(Wmo.icon(c.code, c.isDay), fontSize = 56.sp)
                Spacer(Modifier.width(10.dp))
                Text("${c.temp.r()}", color = TextC, fontSize = 64.sp, fontWeight = FontWeight.Light)
                Text("°C", color = Muted, fontSize = 22.sp, modifier = Modifier.align(Alignment.Top).padding(top = 14.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(today.tMax.deg(), color = TextC, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(today.tMin.deg(), color = Muted, fontSize = 16.sp)
                }
            }
            Text("${Wmo.text(c.code)} · ressenti ${c.feels.deg()}", color = TextC, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            StatGrid(
                listOf(
                    "Vent" to "${c.wind.r()} km/h ${windDir(c.windDir)}",
                    "Rafales" to "${c.gusts.r()} km/h",
                    "Humidité" to "${c.humidity} %",
                    "Pluie (jour)" to "${today.pop} % · ${"%.1f".format(Fr, today.precip)} mm",
                    "Indice UV" to "${today.uv.r()} · ${uvLabel(today.uv)}",
                    "Pression" to "${c.pressure.r()} hPa",
                    "Lever" to hm(today.sunrise),
                    "Coucher" to hm(today.sunset),
                    "Durée du jour" to dayLength(today.daylight),
                ),
            )
        }
    }
}

fun dayLength(sec: Double): String {
    val m = (sec / 60).toInt()
    return "${m / 60}h${(m % 60).toString().padStart(2, '0')}"
}

@Composable
fun StatGrid(stats: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (l, v) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Card2).padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(l.uppercase(Fr), color = Muted, fontSize = 10.sp, maxLines = 1)
                        Text(v, color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

fun dayName(i: Int, date: LocalDate): String = when (i) {
    0 -> "Aujourd'hui"
    1 -> "Demain"
    else -> DateTimeFormatter.ofPattern("EEEE", Fr).format(date).replaceFirstChar { it.titlecase(Fr) }
}

@Composable
fun DayRow(i: Int, d: Day, gMin: Double, gMax: Double, selected: Boolean, onClick: () -> Unit) {
    val date = LocalDate.parse(d.date)
    val span = (gMax - gMin).coerceAtLeast(1.0)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Card2 else Card)
            .border(1.dp, if (selected) Orange else LineC, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(98.dp)) {
            Text(dayName(i, date), color = TextC, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(DateTimeFormatter.ofPattern("d MMM", Fr).format(date), color = Muted, fontSize = 12.sp)
        }
        Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Wmo.icon(d.code), fontSize = 24.sp)
            if (d.pop >= 20) Text("💧${d.pop}%", color = Blue, fontSize = 10.sp)
        }
        Text(d.tMin.deg(), color = Muted, fontSize = 14.sp, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(LineC)) {
            val l = ((d.tMin - gMin) / span).toFloat().coerceAtLeast(0.001f)
            val w = ((d.tMax - d.tMin) / span).toFloat().coerceAtLeast(0.05f)
            val r = ((gMax - d.tMax) / span).toFloat().coerceAtLeast(0.001f)
            Spacer(Modifier.weight(l))
            Box(Modifier.weight(w).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Blue, Orange))))
            Spacer(Modifier.weight(r))
        }
        Spacer(Modifier.width(8.dp))
        Text(d.tMax.deg(), color = TextC, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(34.dp))
    }
}

@Composable
fun DayDetail(f: Forecast, i: Int) {
    val d = f.days[i]
    val hours = f.hours.filter { it.time.startsWith(d.date) }
    val nowHour = f.current.time.take(13)
    val listState = rememberLazyListState()
    LaunchedEffect(i) {
        val idx = hours.indexOfFirst { it.time.startsWith(nowHour) }
        if (idx > 0) listState.scrollToItem(idx)
    }
    CardBox {
        Column {
            Text("${Wmo.icon(d.code)}  ${Wmo.text(d.code)}", color = TextC, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            StatGrid(
                listOf(
                    "Min / Max" to "${d.tMin.deg()} / ${d.tMax.deg()}",
                    "Pluie" to "${d.pop} % · ${"%.1f".format(Fr, d.precip)} mm",
                    "Vent max" to "${d.windMax.r()} km/h ${windDir(d.windDir)}",
                    "Rafales" to "${d.gustMax.r()} km/h",
                    "UV max" to "${d.uv.r()} · ${uvLabel(d.uv)}",
                    "Soleil" to "${hm(d.sunrise)} – ${hm(d.sunset)}",
                ),
            )
            Spacer(Modifier.height(14.dp))
            SectionTitle("Heure par heure")
            Spacer(Modifier.height(8.dp))
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hours) { h ->
                    val isNow = h.time.startsWith(nowHour)
                    Column(
                        Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).background(Card2)
                            .border(1.dp, if (isNow) Orange else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (isNow) "Maint." else "${h.time.substring(11, 13)}h", color = if (isNow) Orange else Muted, fontSize = 12.sp)
                        Text(Wmo.icon(h.code, h.isDay), fontSize = 22.sp, modifier = Modifier.padding(vertical = 2.dp))
                        Text(h.temp.deg(), color = TextC, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("💧${h.pop}%", color = Blue, fontSize = 10.sp)
                        if (h.precip > 0) Text("${"%.1f".format(Fr, h.precip)} mm", color = Blue, fontSize = 10.sp)
                        Text("💨${h.wind.r()}", color = Muted, fontSize = 10.sp)
                        Text("raf. ${h.gusts.r()}", color = Muted, fontSize = 10.sp)
                    }
                }
            }
            if (hours.size > 2) {
                Spacer(Modifier.height(12.dp))
                TempCurve(hours)
            }
        }
    }
}

@Composable
fun TempCurve(hours: List<Hour>) {
    val temps = hours.map { it.temp }
    val mn = temps.min() - 1
    val mx = temps.max() + 1
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = hours.size
            val stepX = size.width / (n - 1)
            fun x(i: Int) = i * stepX
            fun y(t: Double) = (size.height - 22f - ((t - mn) / (mx - mn)).toFloat() * (size.height - 40f))
            // barres de pluie
            hours.forEachIndexed { i, h ->
                val bh = h.pop / 100f * 30f
                drawRect(Blue.copy(alpha = 0.4f), topLeft = Offset(x(i) - 4f, size.height - 2f - bh), size = androidx.compose.ui.geometry.Size(8f, bh))
            }
            val path = Path()
            temps.forEachIndexed { i, t -> if (i == 0) path.moveTo(x(i), y(t)) else path.lineTo(x(i), y(t)) }
            drawPath(path, Orange, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            temps.forEachIndexed { i, t -> if (i % 3 == 0) drawCircle(Orange, 6f, Offset(x(i), y(t))) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 6, 12, 18, 23).forEach { i ->
                if (i < hours.size) Text("${hours[i].time.substring(11, 13)}h ${hours[i].temp.deg()}", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun SearchDialog(onDismiss: () -> Unit, onPick: (Place) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var msg by remember { mutableStateOf("Tape le nom d'une ville…") }
    LaunchedEffect(q) {
        val t = q.trim()
        if (t.length < 2) { results = emptyList(); msg = "Tape le nom d'une ville…"; return@LaunchedEffect }
        delay(350)
        try {
            results = withContext(Dispatchers.IO) { Api.search(t) }
            msg = if (results.isEmpty()) "Aucune ville trouvée." else ""
        } catch (e: Exception) {
            msg = "Recherche impossible : vérifie la connexion."
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF181B1E))
                .border(1.dp, LineC, RoundedCornerShape(20.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rechercher une ville", color = TextC, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Fermer", tint = Muted) }
            }
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Ex. Bourges, Vierzon…") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange, unfocusedBorderColor = LineC, cursorColor = Orange,
                    focusedTextColor = TextC, unfocusedTextColor = TextC,
                ),
            )
            if (msg.isNotEmpty()) Text(msg, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 6.dp)) {
                items(results) { p ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(p) }.padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(p.name, color = TextC, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(p.region, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Fond qui change selon la météo et le moment de la journée. */
fun skyBrush(c: Current?): Brush {
    val top = when {
        c == null -> Color(0xFF16202E)
        !c.isDay -> Color(0xFF0A0D1A)
        c.code <= 1 -> Color(0xFF1D3F73)
        c.code >= 95 -> Color(0xFF241C36)
        Wmo.isRain(c.code) -> Color(0xFF15202E)
        else -> Color(0xFF1E242C)
    }
    return Brush.verticalGradient(0f to top, 0.7f to Bg)
}

/** Pluie, neige ou étoiles animées en fond. */
@Composable
fun WeatherParticles(code: Int, isDay: Boolean) {
    val rain = Wmo.isRain(code)
    val snow = Wmo.isSnow(code)
    val stars = !isDay && !rain && !snow
    if (!rain && !snow && !stars) return
    val count = if (rain) 70 else if (snow) 45 else 50
    val seeds = remember(code, isDay) { List(count) { Triple(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat() * 0.8f) } }
    val t by rememberInfiniteTransition(label = "sky").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (rain) 1400 else if (snow) 9000 else 4000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    Canvas(Modifier.fillMaxSize()) {
        seeds.forEach { (sx, sy, sp) ->
            val x = sx * size.width
            when {
                rain -> {
                    val y = ((sy + t * sp) % 1f) * size.height
                    drawLine(Color(0x559CC9FF), Offset(x, y), Offset(x - 3f, y + 34f), strokeWidth = 2.5f)
                }
                snow -> {
                    val y = ((sy + t * sp) % 1f) * size.height
                    val dx = kotlin.math.sin((t * 6.28f + sx * 10f).toDouble()).toFloat() * 14f
                    drawCircle(Color(0x99FFFFFF), 4f * sp, Offset(x + dx, y))
                }
                else -> {
                    val a = 0.25f + 0.6f * kotlin.math.abs(kotlin.math.sin((t * 6.28f + sx * 20f).toDouble())).toFloat()
                    drawCircle(Color.White.copy(alpha = a), 2.2f, Offset(x, sy * size.height * 0.5f))
                }
            }
        }
    }
}

/** Position approximative (réseau, sinon GPS). */
@SuppressLint("MissingPermission")
fun getLocation(ctx: Context, cb: (Location?) -> Unit) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    if (lm == null) { cb(null); return }
    val provider = when {
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> { cb(null); return }
    }
    try {
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> cb(loc ?: lm.getLastKnownLocation(provider)) }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { loc -> cb(loc) }, Looper.getMainLooper())
        }
    } catch (e: Exception) {
        cb(lm.getLastKnownLocation(provider))
    }
}
