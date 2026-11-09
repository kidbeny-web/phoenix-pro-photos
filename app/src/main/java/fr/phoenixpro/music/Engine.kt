package fr.phoenixpro.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class PlayState(
    val key: String? = null,
    val playing: Boolean = false,
    val index: Int = 0,
    val count: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0,
    val info: AudioInfo? = null,
)

/** Le lecteur (un seul pour toute l'appli), partagé avec le service de lecture. */
object Engine {
    lateinit var player: ExoPlayer
        private set
    val state = MutableStateFlow(PlayState())

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val ready: Boolean get() = ::player.isInitialized

    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        Store.init(app)
        BitPerfect.init(app)
        @Suppress("DEPRECATION")
        val renderers = DefaultRenderersFactory(app)
            .setEnableAudioFloatOutput(Store.settings.value.hiRes)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        player = ExoPlayer.Builder(app, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = publish()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val key = mediaItem?.mediaId ?: return
                Store.recordPlay(key)
                val t = Library.byKey[key] ?: return
                scope.launch {
                    val info = AudioInfos.read(app, t)
                    if (state.value.key == key) state.value = state.value.copy(info = info)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                for (g in tracks.groups) {
                    if (g.type != C.TRACK_TYPE_AUDIO || g.length == 0) continue
                    val f = g.getTrackFormat(0)
                    BitPerfect.onFormat(f.sampleRate, f.channelCount)
                    break
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) = Eq.attach(audioSessionId)
        })
        Eq.attach(player.audioSessionId)
    }

    private fun publish() {
        val p = player
        val key = p.currentMediaItem?.mediaId
        val old = state.value
        state.value = PlayState(
            key = key,
            playing = p.isPlaying,
            index = p.currentMediaItemIndex,
            count = p.mediaItemCount,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: (key?.let { Library.byKey[it]?.durationMs } ?: 0),
            info = if (old.key == key) old.info else null,
        )
    }

    private fun item(t: Track): MediaItem {
        val album = Library.albumOf(t)
        val art = album?.let { Covers.readyFile(app, it) }
        return MediaItem.Builder()
            .setMediaId(t.key)
            .setUri(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setAlbumArtist(album?.artist)
                    .setTrackNumber(t.trackNo)
                    .setArtworkUri(art?.let { Uri.fromFile(it) })
                    .build()
            )
            .build()
    }

    private fun startService() {
        try { app.startService(Intent(app, PlaybackService::class.java)) } catch (_: Exception) {}
    }

    fun play(list: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        startService()
        val first = if (shuffle) list.indices.random() else start.coerceIn(0, list.lastIndex)
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(list.map(::item), first, 0)
        player.prepare()
        player.play()
    }

    fun playNext(list: List<Track>) {
        if (player.mediaItemCount == 0) return play(list)
        player.addMediaItems(player.currentMediaItemIndex + 1, list.map(::item))
    }

    fun enqueue(list: List<Track>) {
        if (player.mediaItemCount == 0) return play(list)
        player.addMediaItems(list.map(::item))
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex, 0)
            startService()
            player.play()
        }
    }

    fun queue(): List<Track> = (0 until player.mediaItemCount).mapNotNull { Library.byKey[player.getMediaItemAt(it).mediaId] }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
