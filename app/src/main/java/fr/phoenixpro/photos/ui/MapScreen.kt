package fr.phoenixpro.photos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import fr.phoenixpro.photos.Db
import fr.phoenixpro.photos.Place
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MapScreen() {
    val nav = LocalNav.current
    val places = rememberData("lieux") { Db.places() }
    Column(Modifier.statusBarsPadding()) {
        ScreenTitle("Lieux", places?.let { if (it.isEmpty()) null else plural(it.sumOf { p -> p.count }, "photo localisée", "photos localisées") })
        if (places != null && places.isEmpty()) {
            EmptyState(
                "Aucune photo localisée",
                "Les photos prises avec la localisation activée apparaissent ici. Vérifiez que l'accès à la « position des photos » est autorisé dans les réglages.",
            )
            return@Column
        }
        if (places != null) PlacesMap(
            places, onPlace = { nav.go(Screen.Place(it)) },
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "label") { SectionLabel("Villes") }
            items(places ?: emptyList(), key = { it.name + it.country }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go(Screen.Place(p.name)) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.coverId),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(p.region, p.country).joinToString(", ") + " · " + plural(p.count, "photo", "photos"),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlacesMap(places: List<Place>, onPlace: (String) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c ->
            MapView(c).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 2.0
            }
        },
        update = { map ->
            map.overlays.clear()
            for (p in places) {
                val m = Marker(map)
                m.position = GeoPoint(p.lat, p.lon)
                m.title = p.name
                m.snippet = plural(p.count, "photo", "photos")
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                m.setOnMarkerClickListener { _, _ -> onPlace(p.name); true }
                map.overlays.add(m)
            }
            if (places.size == 1) {
                map.controller.setZoom(11.0); map.controller.setCenter(GeoPoint(places[0].lat, places[0].lon))
            } else if (places.isNotEmpty()) {
                val box = BoundingBox.fromGeoPoints(places.map { GeoPoint(it.lat, it.lon) })
                map.post { map.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
        modifier = modifier,
    )
}
