package fr.phoenixpro.music

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

data class Track(
    val id: Long,
    val uri: Uri,
    /** Identifiant stable : volume + dossier + nom de fichier. */
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val trackNo: Int,
    val disc: Int,
    val year: Int,
    val durationMs: Long,
    val mime: String,
    /** volume/dossier relatif, ex. « external_primary/Music/Artiste/Album/ ». */
    val folder: String,
    val fileName: String,
    val size: Long,
    val dateAdded: Long,
    val bitrate: Int,
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val tracks: List<Track>,
    val dateAdded: Long,
) {
    val folder: String get() = tracks.first().folder
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
    /** Clé stable pour les pochettes et infos en ligne. */
    val coverKey: String get() = Library.norm("$artist|$title")
}

data class Artist(val name: String, val albums: List<Album>) {
    val trackCount: Int get() = albums.sumOf { it.tracks.size }
}

object Library {
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val artists = MutableStateFlow<List<Artist>>(emptyList())
    val scanning = MutableStateFlow(false)
    @Volatile var byKey: Map<String, Track> = emptyMap(); private set
    @Volatile private var albumById: Map<Long, Album> = emptyMap()

    private val lock = Mutex()
    const val UNKNOWN_ARTIST = "Artiste inconnu"

    fun album(id: Long): Album? = albumById[id]
    fun albumOf(t: Track): Album? = albumById[t.albumId]
    fun artist(name: String): Artist? = artists.value.firstOrNull { it.name == name }

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").trim()

    private fun clean(s: String?, fallback: String): String =
        if (s.isNullOrBlank() || s == "<unknown>") fallback else s.trim()

    suspend fun scan(ctx: Context) = lock.withLock {
        scanning.value = true
        try {
            val list = withContext(Dispatchers.IO) { query(ctx) }
            publish(list)
        } finally {
            scanning.value = false
        }
    }

    private fun publish(list: List<Track>) {
        val albumList = list.groupBy { it.albumId }.map { (id, ts) ->
            val sorted = ts.sortedWith(compareBy<Track> { it.disc }.thenBy { it.trackNo }.thenBy { it.fileName })
            val aa = sorted.map { it.albumArtist }.filter { it.isNotBlank() }
            val artist = (aa.ifEmpty { sorted.map { it.artist } })
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: UNKNOWN_ARTIST
            Album(
                id = id,
                title = sorted.first().album,
                artist = artist,
                year = sorted.maxOf { it.year },
                tracks = sorted,
                dateAdded = sorted.maxOf { it.dateAdded },
            )
        }
        val artistList = albumList.groupBy { it.artist }.map { (name, al) ->
            Artist(name, al.sortedWith(compareBy<Album> { it.year }.thenBy { norm(it.title) }))
        }.sortedBy { norm(it.name) }

        byKey = list.associateBy { it.key }
        albumById = albumList.associateBy { it.id }
        tracks.value = list.sortedBy { norm(it.title) }
        albums.value = albumList
        artists.value = artistList
    }

    private fun Cursor.str(col: String): String? {
        val i = getColumnIndex(col); return if (i < 0 || isNull(i)) null else getString(i)
    }
    private fun Cursor.long(col: String): Long {
        val i = getColumnIndex(col); return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private fun query(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val volumes = MediaStore.getExternalVolumeNames(ctx)
        for (vol in volumes) {
            val coll = MediaStore.Audio.Media.getContentUri(vol)
            val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000"
            try {
                ctx.contentResolver.query(coll, null, sel, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.long(MediaStore.Audio.Media._ID)
                        val rel = c.str(MediaStore.Audio.Media.RELATIVE_PATH) ?: ""
                        val name = c.str(MediaStore.Audio.Media.DISPLAY_NAME) ?: "$id"
                        val volume = c.str(MediaStore.Audio.Media.VOLUME_NAME) ?: vol
                        val rawTrack = c.long(MediaStore.Audio.Media.TRACK).toInt()
                        val artist = clean(c.str(MediaStore.Audio.Media.ARTIST), UNKNOWN_ARTIST)
                        out += Track(
                            id = id,
                            uri = ContentUris.withAppendedId(coll, id),
                            key = "$volume/$rel$name",
                            title = clean(c.str(MediaStore.Audio.Media.TITLE), name.substringBeforeLast('.')),
                            artist = artist,
                            album = clean(c.str(MediaStore.Audio.Media.ALBUM), "Album inconnu"),
                            albumId = c.long(MediaStore.Audio.Media.ALBUM_ID),
                            albumArtist = c.str("album_artist")?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "",
                            trackNo = rawTrack % 1000,
                            disc = (rawTrack / 1000).coerceAtLeast(1),
                            year = c.long(MediaStore.Audio.Media.YEAR).toInt(),
                            durationMs = c.long(MediaStore.Audio.Media.DURATION),
                            mime = c.str(MediaStore.Audio.Media.MIME_TYPE) ?: "",
                            folder = "$volume/$rel",
                            fileName = name,
                            size = c.long(MediaStore.Audio.Media.SIZE),
                            dateAdded = c.long(MediaStore.Audio.Media.DATE_ADDED),
                            bitrate = c.long("bitrate").toInt(),
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }
}
