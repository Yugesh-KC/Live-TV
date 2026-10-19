package com.livetv.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

sealed interface ListItem {
    data class Header(val title: String) : ListItem
    data class Row(val channel: Channel, val favorite: Boolean, val inFavoritesSection: Boolean) : ListItem
}

data class ListState(
    val items: List<ListItem> = emptyList(),
    /** Channels in on-screen order without duplicates; used for next/previous. */
    val playOrder: List<Channel> = emptyList(),
    val matchCount: Int = 0,
)

data class FilterOptions(
    val categories: List<String> = emptyList(),
    /** Pairs of (country code, display name), sorted by name. */
    val countries: List<Pair<String, String>> = emptyList(),
)

sealed interface UiEvent {
    data class ChannelChanged(val channel: Channel) : UiEvent
    data class Message(val text: String) : UiEvent
}

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    private val repo = PlaylistRepository(app)

    private val channels = MutableStateFlow<List<Channel>>(emptyList())
    private val favorites = MutableStateFlow(prefs.favorites)
    val query = MutableStateFlow("")
    val category = MutableStateFlow<String?>(null)
    val country = MutableStateFlow<String?>(null)

    val loading = MutableStateFlow(false)
    val loadError = MutableStateFlow<String?>(null)
    /** [Prefs.playlistKey] of the playlists currently loaded. */
    var loadedKey: String? = null
        private set

    val currentChannel = MutableStateFlow<Channel?>(null)
    /** Non-null while the current channel failed to play. */
    val playerError = MutableStateFlow<String?>(null)
    val isPlaying = MutableStateFlow(false)
    /** True while nothing is loaded in the player (before the first play or after an error). */
    val playerIdle = MutableStateFlow(true)
    /** True while the screen should stay awake (playing or buffering). */
    val keepAwake = MutableStateFlow(false)

    /** Chosen quality cap: a max video height, [Prefs.QUALITY_AUTO] or [Prefs.QUALITY_DATA_SAVER]. */
    val quality = MutableStateFlow(prefs.quality)
    /** Video heights the current stream offers, highest first. A single entry means no choice. */
    val availableHeights = MutableStateFlow<List<Int>>(emptyList())
    /** Height of the video currently playing, or 0 if unknown. */
    val videoHeight = MutableStateFlow(0)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events

    private var loadJob: Job? = null
    private var timeoutJob: Job? = null
    /** True once the current channel has rendered at least once. */
    private var startedOk = false
    private var lastEndedRestart = 0L
    /** True when the current channel is being retried as HLS because its URL had no recognizable extension. */
    private var forcedHls = false
    /** Index into the current channel's [Channel.sources] being played, and how many were tried. */
    private var sourceIndex = 0
    private var sourcesTried = 0
    /** Channel url -> link that last played, so a channel starts with the link that works. */
    private val workingSource = HashMap<String, String>()
    private var warnedNoAudio = false
    /** Times of recent automatic reconnects after network errors on a channel that was playing. */
    private val reconnects = ArrayDeque<Long>()

    // Many IPTV streams carry MP2 or AC-3 audio, which most phones can't decode in hardware.
    // EXTENSION_RENDERER_MODE_ON adds the FFmpeg audio decoder as a fallback for those codecs.
    private val renderersFactory = DefaultRenderersFactory(app)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)

    // After a stall, wait for 6 s of video (default 5 s) so a slow stream doesn't stall again right away.
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(20_000, 60_000, 2_500, 6_000)
        .build()

    val player: ExoPlayer = ExoPlayer.Builder(app, renderersFactory).setLoadControl(loadControl).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Deliberately not pausing on "audio becoming noisy": unplugging the HDMI cable
        // re-routes audio and must not stop the stream.
        setHandleAudioBecomingNoisy(false)
        addListener(PlayerListener())
    }

    val filterOptions: StateFlow<FilterOptions> = channels
        .map { list ->
            FilterOptions(
                categories = list.asSequence().flatMap { it.groups }.distinct().sortedBy { it.lowercase() }.toList(),
                countries = list.asSequence().mapNotNull { it.country }.distinct()
                    .map { it to Channel.countryName(it) }
                    .sortedBy { it.second.lowercase() }
                    .toList(),
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, FilterOptions())

    @kotlin.OptIn(FlowPreview::class)
    val listState: StateFlow<ListState> = combine(
        channels, query.debounce(150), category, country, favorites,
    ) { all, q, cat, ctry, favs ->
        buildList(all, q.trim(), cat, ctry, favs)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListState())

    private fun buildList(
        all: List<Channel>, q: String, cat: String?, ctry: String?, favs: Set<String>,
    ): ListState {
        val matches = all.filter { ch ->
            (cat == null || cat in ch.groups) &&
                (ctry == null || ch.country == ctry) &&
                (q.isEmpty() || ch.name.contains(q, ignoreCase = true))
        }
        val favMatches = matches.filter { it.url in favs }
        val items = ArrayList<ListItem>(matches.size + favMatches.size + 2)
        if (favMatches.isNotEmpty()) {
            items += ListItem.Header("★ Favorites (${favMatches.size})")
            favMatches.mapTo(items) { ListItem.Row(it, favorite = true, inFavoritesSection = true) }
            items += ListItem.Header("All channels (${matches.size})")
        }
        matches.mapTo(items) { ListItem.Row(it, favorite = it.url in favs, inFavoritesSection = false) }
        val order = favMatches + matches.filter { it.url !in favs }
        return ListState(items, order, matches.size)
    }

    init {
        applyQuality()
        loadPlaylist()
    }

    fun setQuality(value: Int) {
        quality.value = value
        prefs.quality = value
        applyQuality()
    }

    /** Lower quality only helps on adaptive (multi-quality) streams; ExoPlayer switches at the next segment. */
    private fun applyQuality() {
        val q = quality.value
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .setForceLowestBitrate(q == Prefs.QUALITY_DATA_SAVER)
            .apply { if (q > 0) setMaxVideoSize(Int.MAX_VALUE, q) }
            .build()
    }

    /** Shows the cached playlist immediately (if any), then refreshes it from the network. */
    fun loadPlaylist() {
        val url = prefs.playlistUrl
        val backups = prefs.backupPlaylists
        val key = prefs.playlistKey
        val urlChanged = key != loadedKey
        loadedKey = key
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (urlChanged) {
                category.value = null
                country.value = null
                val cached = repo.loadCached(url, backups)
                channels.value = cached ?: emptyList()
                if (cached != null) restoreLastChannel(cached)
            }
            loading.value = true
            loadError.value = null
            try {
                val fresh = repo.download(url, backups)
                channels.value = fresh
                if (currentChannel.value == null) restoreLastChannel(fresh)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val reason = e.message ?: e.javaClass.simpleName
                if (channels.value.isEmpty()) {
                    loadError.value = "Couldn't load playlist ($reason). Pull down to retry."
                } else {
                    _events.tryEmit(UiEvent.Message("Offline: showing cached channel list"))
                }
            } finally {
                loading.value = false
            }
        }
    }

    private fun restoreLastChannel(list: List<Channel>) {
        if (currentChannel.value != null) return
        val last = prefs.lastChannelUrl ?: return
        // Only select it; don't auto-play on launch.
        list.firstOrNull { ch -> ch.sources.any { it.url == last } }?.let { currentChannel.value = it }
    }

    fun toggleFavorite(channel: Channel) {
        val updated = favorites.value.toMutableSet().apply {
            if (!add(channel.url)) remove(channel.url)
        }
        favorites.value = updated
        prefs.favorites = updated
    }

    /** Plays [channel], starting with the link that worked last time and falling back to the others. */
    fun play(channel: Channel) {
        val sources = channel.sources
        val start = sources.indexOfFirst { it.url == workingSource[channel.url] }.coerceAtLeast(0)
        sourcesTried = 1
        playSource(channel, start, asHls = false)
        _events.tryEmit(UiEvent.ChannelChanged(channel))
    }

    private fun playSource(channel: Channel, index: Int, asHls: Boolean) {
        val stream = channel.sources[index]
        sourceIndex = index
        forcedHls = asHls
        warnedNoAudio = false
        reconnects.clear()
        availableHeights.value = emptyList()
        videoHeight.value = 0
        currentChannel.value = channel
        playerError.value = null
        prefs.lastChannelUrl = channel.url

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent(stream.userAgent ?: DEFAULT_USER_AGENT)
        stream.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            .build()
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(getApplication(), http))
            .createMediaSource(item)

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true

        startedOk = false
        lastEndedRestart = 0L
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(STREAM_TIMEOUT_MS)
            if (!startedOk) failSource("no playback within ${STREAM_TIMEOUT_MS / 1000}s")
        }
    }

    fun playCurrentOrFirst() {
        val ch = currentChannel.value ?: listState.value.playOrder.firstOrNull() ?: return
        if (player.playbackState == Player.STATE_IDLE || playerError.value != null || ch != currentChannel.value) {
            play(ch)
        } else {
            player.play()
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)) {
            player.pause()
        } else {
            playCurrentOrFirst()
        }
    }

    fun next() = step(+1)
    fun previous() = step(-1)

    private fun step(delta: Int) {
        val order = listState.value.playOrder
        if (order.isEmpty()) return
        val idx = order.indexOfFirst { it.url == currentChannel.value?.url }
        val target = if (idx < 0) {
            if (delta > 0) 0 else order.lastIndex
        } else {
            (idx + delta).mod(order.size)
        }
        play(order[target])
    }

    /** The current link failed: try the channel's next link, or give up once all were tried. */
    private fun failSource(reason: String) {
        val ch = currentChannel.value
        val sources = ch?.sources.orEmpty()
        Log.w(TAG, "Link ${sourceIndex + 1}/${sources.size} failed (${sources.getOrNull(sourceIndex)?.url}): $reason")
        if (ch != null && sourcesTried < sources.size) {
            sourcesTried++
            if (workingSource[ch.url] == sources[sourceIndex].url) workingSource.remove(ch.url)
            _events.tryEmit(UiEvent.Message("Link didn't work, trying another ($sourcesTried of ${sources.size})"))
            playSource(ch, (sourceIndex + 1) % sources.size, asHls = false)
            return
        }
        markUnavailable()
    }

    private fun markUnavailable() {
        Log.w(TAG, "Channel unavailable: ${currentChannel.value?.name}")
        timeoutJob?.cancel()
        player.stop()
        playerError.value = "Channel unavailable"
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    startedOk = true
                    currentChannel.value?.let { ch -> workingSource[ch.url] = ch.sources[sourceIndex].url }
                    timeoutJob?.cancel()
                }
                Player.STATE_ENDED -> {
                    // A live channel shouldn't end. Reload it once; if it keeps ending, give up.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastEndedRestart > 60_000) {
                        lastEndedRestart = now
                        player.seekToDefaultPosition()
                        player.prepare()
                    } else {
                        failSource("stream keeps ending")
                    }
                }
            }
            playerIdle.value = playbackState == Player.STATE_IDLE
            updateAwake()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateAwake()

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying.value = playing
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Happens after a long pause on a live stream: jump back to the live edge.
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            if (startedOk && error.errorCode in IO_ERRORS && reconnectAllowed()) {
                // The channel was playing, so this is most likely a glitch on the server (a bad or
                // missing segment, a dropped connection). Reconnect at the live edge instead of giving up.
                Log.i(TAG, "Reconnecting after ${error.errorCodeName}")
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            val ch = currentChannel.value
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                !forcedHls && ch != null && !HAS_KNOWN_EXTENSION.containsMatchIn(ch.sources[sourceIndex].url)
            ) {
                // No .m3u8/.mpd in the URL, so ExoPlayer guessed a progressive stream. Most such
                // IPTV URLs are really HLS playlists: retry once as HLS.
                Log.i(TAG, "Retrying as HLS: ${ch.sources[sourceIndex].url}")
                playSource(ch, sourceIndex, asHls = true)
                return
            }
            failSource("${error.errorCodeName}: ${error.message}")
        }

        private fun reconnectAllowed(): Boolean {
            val now = SystemClock.elapsedRealtime()
            while (reconnects.isNotEmpty() && now - reconnects.first() > RECONNECT_WINDOW_MS) reconnects.removeFirst()
            if (reconnects.size >= MAX_RECONNECTS) return false
            reconnects.addLast(now)
            return true
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoHeight.value = videoSize.height
            if (videoSize.height > 0) Log.i(TAG, "Video size ${videoSize.width}x${videoSize.height}")
        }

        override fun onTracksChanged(tracks: Tracks) {
            availableHeights.value = tracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height } }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
            if (warnedNoAudio) return
            val hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
            if (hasAudio && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) {
                warnedNoAudio = true
                val mime = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                    ?.getTrackFormat(0)?.sampleMimeType
                Log.w(TAG, "No decoder for audio ($mime) on ${currentChannel.value?.url}")
                _events.tryEmit(UiEvent.Message("No sound: this device can't decode the channel's audio ($mime)"))
            }
        }

        private fun updateAwake() {
            val state = player.playbackState
            keepAwake.value = player.playWhenReady &&
                (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        }
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private const val TAG = "LiveTV"
        private const val STREAM_TIMEOUT_MS = 20_000L
        private const val MAX_RECONNECTS = 5
        private const val RECONNECT_WINDOW_MS = 60_000L
        /** Network/IO error codes (2000–2999): worth a reconnect on a live stream. */
        private val IO_ERRORS = 2000..2999
        private val HAS_KNOWN_EXTENSION = Regex("""\.(m3u8?|mpd|ism|mp4|ts|aac|mp3)(\?|#|$)""", RegexOption.IGNORE_CASE)
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
