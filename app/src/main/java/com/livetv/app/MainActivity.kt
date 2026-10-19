package com.livetv.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetv.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var adapter: ChannelAdapter
    private lateinit var displayManager: DisplayManager
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    /** Non-null while video is shown on an external display (TV mode). */
    private var presentation: TvPresentation? = null
    /** An external display exists but can't host a Presentation (plain mirroring). */
    private var mirrorFallback = false
    private var destroying = false
    private var resumeOnStart = false

    /** True while the YouTube tab is selected. */
    private var youtubeTab = false
    private var youtubeLoaded = false
    /** Resume the live channel when coming back from the YouTube tab. */
    private var resumeLiveOnReturn = false
    /** YouTube's fullscreen video view, while shown. */
    private var ytCustomView: View? = null
    private var ytCustomViewCallback: WebChromeClient.CustomViewCallback? = null

    private var categoryKeys: List<String?> = listOf(null)
    private var countryKeys: List<String?> = listOf(null)

    private val hideChannelOverlay = Runnable { b.channelOverlay.isVisible = false }
    private val redim = Runnable { applyBrightness(dimmed = true) }

    private val isTvMode get() = presentation != null
    private val isFullscreen
        get() = !isTvMode && !youtubeTab && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** Live TV fullscreen or YouTube fullscreen video: no system bars, toolbar or tabs. */
    private val hideSystemUi get() = isFullscreen || ytCustomView != null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        volumeControlStream = AudioManager.STREAM_MUSIC

        displayManager = getSystemService(DisplayManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)

        setupInsets()
        setupPlayer()
        setupList()
        setupFilters()
        setupRemote()
        setupQuality()
        setupYouTube()
        setupTabs()
        observe()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ytCustomView != null -> hideYouTubeFullscreen()
                    youtubeTab && b.youtubeWeb.canGoBack() -> b.youtubeWeb.goBack()
                    youtubeTab -> b.bottomNav.selectedItemId = R.id.tab_live
                    isFullscreen && !mirrorFallback -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        displayManager.registerDisplayListener(displayListener, handler)
        updateDisplays()
        applyLayout()
    }

    // ---------------------------------------------------------------- setup

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            if (hideSystemUi) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPlayer() {
        b.playerView.player = vm.player
        b.playerView.setShowPreviousButton(false)
        b.playerView.setShowNextButton(false)
        b.playerView.setShowFastForwardButton(false)
        b.playerView.setShowRewindButton(false)
        b.playerView.setFullscreenButtonClickListener { toggleFullscreen() }
        b.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            b.overlayPrev.isVisible = isFullscreen && visibility == View.VISIBLE
            b.overlayNext.isVisible = isFullscreen && visibility == View.VISIBLE
            b.overlayQuality.isVisible = isFullscreen && visibility == View.VISIBLE
        })

        val minSwipe = 80 * resources.displayMetrics.density
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) > minSwipe && abs(dx) > abs(dy) * 1.5f) {
                    if (dx < 0) vm.next() else vm.previous()
                    return true
                }
                return false
            }
        })
        // Returning true for a fling swallows the ACTION_UP so the controller doesn't toggle.
        b.playerView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }

        b.overlayPrev.setOnClickListener { vm.previous() }
        b.overlayNext.setOnClickListener { vm.next() }
        b.idleHint.setOnClickListener { vm.playCurrentOrFirst() }
        b.errorOverlay.setOnClickListener { vm.playCurrentOrFirst() }

        b.nowPrev.setOnClickListener { vm.previous() }
        b.nowNext.setOnClickListener { vm.next() }
        b.nowFullscreen.setOnClickListener { toggleFullscreen() }
    }

    private fun setupList() {
        adapter = ChannelAdapter(
            onClick = { ch ->
                hideKeyboard()
                vm.play(ch)
            },
            onFavorite = { vm.toggleFavorite(it) },
        )
        b.channelList.layoutManager = LinearLayoutManager(this)
        b.channelList.adapter = adapter
        b.channelList.setHasFixedSize(true)
        b.swipeRefresh.setOnRefreshListener { vm.loadPlaylist() }

        b.search.setText(vm.query.value)
        b.search.doAfterTextChanged { vm.query.value = it?.toString().orEmpty() }
        b.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(); true
            } else false
        }
    }

    private fun setupFilters() {
        b.categorySpinner.onItemSelectedListener = SimpleItemSelected { pos ->
            vm.category.value = categoryKeys.getOrNull(pos)
        }
        b.countrySpinner.onItemSelectedListener = SimpleItemSelected { pos ->
            vm.country.value = countryKeys.getOrNull(pos)
        }
    }

    private fun setupRemote() {
        b.remotePrev.setOnClickListener { vm.previous() }
        b.remoteNext.setOnClickListener { vm.next() }
        b.remotePlayPause.setOnClickListener { vm.togglePlayPause() }
        b.volumeDown.setOnClickListener { adjustVolume(AudioManager.ADJUST_LOWER) }
        b.volumeUp.setOnClickListener { adjustVolume(AudioManager.ADJUST_RAISE) }
        b.volumeSeek.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        b.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        b.dimSwitch.isChecked = vm.prefs.dimPhoneInTvMode
        b.dimSwitch.setOnCheckedChangeListener { _, checked ->
            vm.prefs.dimPhoneInTvMode = checked
            applyBrightness(dimmed = true)
        }
        refreshVolume()
    }

    // ---------------------------------------------------------------- quality

    private fun setupQuality() {
        listOf(b.nowQuality, b.overlayQuality, b.remoteQuality).forEach {
            it.setOnClickListener { showQualityPicker() }
        }
    }

    private fun qualityLabel(quality: Int, height: Int): String = when {
        height > 0 -> getString(R.string.quality_height, height)
        quality == Prefs.QUALITY_AUTO -> getString(R.string.quality_auto)
        quality == Prefs.QUALITY_DATA_SAVER -> getString(R.string.quality_data_saver)
        else -> getString(R.string.quality_height, quality)
    }

    private fun showQualityPicker() {
        val offered = vm.availableHeights.value
        val current = vm.quality.value
        val single = offered.size == 1
        // Offer the stream's own qualities when it has several; otherwise the usual ones, which apply
        // to the next channels that do offer a choice.
        val heights = (if (offered.size > 1) offered else STANDARD_HEIGHTS) + listOfNotNull(current.takeIf { it > 0 })
        val choices = listOf(Prefs.QUALITY_AUTO, Prefs.QUALITY_DATA_SAVER) + heights.distinct().sortedDescending()
        val labels = choices.map {
            when (it) {
                Prefs.QUALITY_AUTO -> getString(R.string.quality_auto_long)
                Prefs.QUALITY_DATA_SAVER -> getString(R.string.quality_data_saver_long)
                else -> getString(R.string.quality_height, it)
            }
        }
        val now = vm.videoHeight.value
        val title = when {
            single -> getString(R.string.quality_single, offered[0])
            now > 0 -> getString(R.string.quality_title_now, now)
            else -> getString(R.string.quality)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), choices.indexOf(current)) { dialog, which ->
                vm.setQuality(choices[which])
                dialog.dismiss()
                if (single && choices[which] != current) toast(getString(R.string.quality_single_toast))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- tabs + YouTube

    private fun setupTabs() {
        b.bottomNav.setOnItemSelectedListener { item ->
            showYouTube(item.itemId == R.id.tab_youtube)
            true
        }
        b.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.tab_youtube) {
                b.youtubeError.isVisible = false
                b.youtubeWeb.loadUrl(YOUTUBE_HOME)
            } else {
                b.channelList.scrollToPosition(0)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupYouTube() {
        val web = b.youtubeWeb
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Let a video that opens after a tap start with sound instead of muted.
        web.settings.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if ((uri.scheme == "https" || uri.scheme == "http") && isYouTubeHost(uri.host.orEmpty())) return false
                // Other sites and "open in app" links leave the WebView.
                val intent = try {
                    if (uri.scheme == "intent") {
                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                            component = null
                            selector = null
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, uri)
                    }
                } catch (_: Exception) {
                    return true
                }
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                b.youtubeError.isVisible = false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) b.youtubeError.isVisible = true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.youtubeProgress.isVisible = newProgress < 100
                b.youtubeProgress.setProgressCompat(newProgress, true)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) =
                showYouTubeFullscreen(view, callback)

            override fun onHideCustomView() = hideYouTubeFullscreen()
        }
        b.youtubeError.setOnClickListener {
            b.youtubeError.isVisible = false
            web.reload()
        }
    }

    private fun isYouTubeHost(host: String): Boolean =
        YOUTUBE_HOSTS.any { host == it || host.endsWith(".$it") }

    private fun showYouTube(on: Boolean) {
        if (youtubeTab == on) return
        youtubeTab = on
        if (on) {
            hideKeyboard()
            resumeLiveOnReturn = vm.player.playWhenReady
            vm.player.pause()
            b.youtubeWeb.onResume()
            if (!youtubeLoaded) {
                youtubeLoaded = true
                b.youtubeWeb.loadUrl(YOUTUBE_HOME)
            }
        } else {
            pauseYouTube()
            if (resumeLiveOnReturn) {
                resumeLiveOnReturn = false
                vm.player.play()
            }
        }
        // The TV shows the live player only on the Live TV tab; on the YouTube tab it mirrors the phone.
        updateDisplays()
        updateOrientation()
        applyLayout()
    }

    private fun pauseYouTube() {
        b.youtubeWeb.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        b.youtubeWeb.onPause()
    }

    private fun showYouTubeFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (ytCustomView != null) {
            callback.onCustomViewHidden()
            return
        }
        ytCustomView = view
        ytCustomViewCallback = callback
        b.fullscreenContainer.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        updateKeepScreenOn()
        updateOrientation()
        applyLayout()
    }

    private fun hideYouTubeFullscreen() {
        val view = ytCustomView ?: return
        ytCustomView = null
        b.fullscreenContainer.removeView(view)
        ytCustomViewCallback?.onCustomViewHidden()
        ytCustomViewCallback = null
        updateKeepScreenOn()
        updateOrientation()
        applyLayout()
    }

    private fun observe() {
        val scope = lifecycleScope
        scope.launch {
            vm.listState.collect { state ->
                val lm = b.channelList.layoutManager as LinearLayoutManager
                val wasAtTop = lm.findFirstVisibleItemPosition() <= 0
                adapter.submitList(state.items) {
                    // Keep the Favorites section visible when it appears above the current top row.
                    if (wasAtTop) b.channelList.scrollToPosition(0)
                }
                updateStatus()
            }
        }
        scope.launch { vm.filterOptions.collect { updateSpinners(it) } }
        scope.launch { vm.loading.collect { b.swipeRefresh.isRefreshing = it; updateStatus() } }
        scope.launch { vm.loadError.collect { updateStatus() } }
        scope.launch {
            vm.currentChannel.collect { ch ->
                adapter.playingUrl = ch?.url
                b.nowTitle.text = ch?.name ?: getString(R.string.nothing_playing)
                b.remoteChannel.text = ch?.name ?: getString(R.string.nothing_playing)
            }
        }
        scope.launch {
            combine(vm.playerIdle, vm.playerError, vm.currentChannel) { idle, err, ch -> Triple(idle, err, ch) }
                .collect { (idle, err, ch) ->
                    b.errorOverlay.isVisible = err != null
                    b.idleHint.isVisible = idle && err == null
                    b.idleHint.text = if (ch != null) getString(R.string.tap_to_play, ch.name) else getString(R.string.pick_channel)
                    presentation?.setError(err?.let { getString(R.string.channel_unavailable_tv) })
                }
        }
        scope.launch {
            vm.playerError.collect { err ->
                if (err != null && isTvMode) toast(getString(R.string.channel_unavailable_toast))
            }
        }
        scope.launch {
            vm.isPlaying.collect { playing ->
                b.remotePlayPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                b.remotePlayPause.contentDescription = getString(if (playing) R.string.pause else R.string.play)
            }
        }
        scope.launch { vm.keepAwake.collect { updateKeepScreenOn() } }
        scope.launch {
            combine(vm.quality, vm.videoHeight) { q, h -> qualityLabel(q, h) }.collect { label ->
                b.nowQuality.text = label
                b.overlayQuality.text = label
                b.remoteQuality.text = label
            }
        }
        scope.launch {
            vm.events.collect { e ->
                when (e) {
                    is UiEvent.ChannelChanged -> showChannelName(e.channel.name)
                    is UiEvent.Message -> toast(e.text)
                }
            }
        }
    }

    // ---------------------------------------------------------------- layout

    private fun applyLayout() {
        val tv = isTvMode
        val fullscreen = isFullscreen
        val immersive = hideSystemUi

        b.toolbar.isVisible = !fullscreen && !youtubeTab
        b.liveContent.isVisible = !youtubeTab
        b.youtubeContainer.isVisible = youtubeTab
        b.bottomNav.isVisible = !immersive
        b.fullscreenContainer.isVisible = ytCustomView != null
        b.playerArea.isVisible = !tv
        b.nowBar.isVisible = !tv && !fullscreen
        b.remotePanel.isVisible = tv
        b.listSection.isVisible = !fullscreen

        val lp = b.playerArea.layoutParams as LinearLayout.LayoutParams
        if (fullscreen) {
            lp.height = 0
            lp.weight = 1f
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            lp.weight = 0f
        }
        b.playerArea.layoutParams = lp
        b.playerView.setFullscreenButtonState(fullscreen)
        if (!fullscreen) {
            b.overlayPrev.isVisible = false
            b.overlayNext.isVisible = false
            b.overlayQuality.isVisible = false
        }

        val controller = WindowCompat.getInsetsController(window, b.root)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            hideKeyboard()
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(b.root)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun showChannelName(name: String) {
        presentation?.showChannelName(name)
        b.channelOverlay.text = name
        b.channelOverlay.isVisible = true
        handler.removeCallbacks(hideChannelOverlay)
        handler.postDelayed(hideChannelOverlay, 3_000)
    }

    private fun updateStatus() {
        val err = vm.loadError.value
        val state = vm.listState.value
        val text = when {
            err != null -> err
            vm.loading.value && state.items.isEmpty() -> getString(R.string.loading_playlist)
            state.items.isEmpty() -> getString(R.string.no_matches)
            else -> null
        }
        b.status.text = text
        b.status.isVisible = text != null
    }

    private fun updateSpinners(opts: FilterOptions) {
        categoryKeys = listOf<String?>(null) + opts.categories
        val catLabels = listOf(getString(R.string.all_categories)) + opts.categories
        b.categorySpinner.adapter = spinnerAdapter(catLabels)
        b.categorySpinner.setSelection(categoryKeys.indexOf(vm.category.value).coerceAtLeast(0), false)

        countryKeys = listOf<String?>(null) + opts.countries.map { it.first }
        val countryLabels = listOf(getString(R.string.all_countries)) + opts.countries.map { it.second }
        b.countrySpinner.adapter = spinnerAdapter(countryLabels)
        b.countrySpinner.setSelection(countryKeys.indexOf(vm.country.value).coerceAtLeast(0), false)
    }

    private fun spinnerAdapter(labels: List<String>) =
        ArrayAdapter(this, R.layout.spinner_item, labels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    // ---------------------------------------------------------------- external display

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateDisplays()
        override fun onDisplayRemoved(displayId: Int) = updateDisplays()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) updateDisplays()
        }
    }

    private fun updateDisplays() {
        if (destroying) return
        val target = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }

        val current = presentation
        if (current != null) {
            if (!youtubeTab && target != null && current.display.displayId == target.displayId) return
            closePresentation(current, announce = !youtubeTab)
        }
        // On the YouTube tab the TV simply mirrors the phone.
        if (youtubeTab) return

        if (target != null && showPresentation(target)) {
            mirrorFallback = false
            return
        }

        // No separate screen available. If some external display is attached anyway it is
        // mirroring the phone, so make the phone itself look good: fullscreen landscape.
        val external = displayManager.displays.any { it.displayId != Display.DEFAULT_DISPLAY }
        setMirrorFallback(external || target != null)
    }

    private fun showPresentation(display: Display): Boolean {
        val p = TvPresentation(this, display)
        p.setOnDismissListener { if (presentation === p) closePresentation(p) }
        try {
            p.show()
        } catch (e: RuntimeException) {
            // WindowManager.InvalidDisplayException / BadTokenException: display went away or is not usable.
            return false
        }
        presentation = p
        PlayerView.switchTargetView(vm.player, b.playerView, p.binding.playerView)
        p.setError(vm.playerError.value?.let { getString(R.string.channel_unavailable_tv) })
        vm.currentChannel.value?.let { p.showChannelName(it.name) }

        updateOrientation()
        applyLayout()
        refreshVolume()
        applyBrightness(dimmed = true)
        updateKeepScreenOn()
        toast(getString(R.string.tv_connected))
        return true
    }

    /** Moves the video back to the phone without interrupting playback. */
    private fun closePresentation(p: TvPresentation, announce: Boolean = true) {
        if (presentation !== p) return
        presentation = null
        if (!destroying) {
            PlayerView.switchTargetView(vm.player, p.binding.playerView, b.playerView)
        } else {
            p.binding.playerView.player = null
        }
        try {
            p.dismiss()
        } catch (_: RuntimeException) {
        }
        if (destroying) return

        updateOrientation()
        handler.removeCallbacks(redim)
        applyLayout()
        applyBrightness(dimmed = false)
        updateKeepScreenOn()
        if (announce) toast(getString(R.string.tv_disconnected))
    }

    private fun setMirrorFallback(on: Boolean) {
        if (mirrorFallback == on) return
        mirrorFallback = on
        if (isTvMode) return
        updateOrientation()
        if (on) toast(getString(R.string.mirroring))
    }

    private fun updateOrientation() {
        requestedOrientation = when {
            ytCustomView != null -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isTvMode -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            youtubeTab -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            mirrorFallback -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---------------------------------------------------------------- screen + volume

    private fun updateKeepScreenOn() {
        if (vm.keepAwake.value || ytCustomView != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyBrightness(dimmed: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (dimmed && isTvMode && vm.prefs.dimPhoneInTvMode) {
            0.01f
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    /** Touching a dimmed phone brightens it for a few seconds so the remote is usable. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isTvMode && vm.prefs.dimPhoneInTvMode && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            applyBrightness(dimmed = false)
            handler.removeCallbacks(redim)
            handler.postDelayed(redim, 8_000)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            handler.post { refreshVolume() }
        }
        return handled
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        refreshVolume()
    }

    private fun refreshVolume() {
        b.volumeSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestCommand(intent)
    }

    /**
     * Debug builds only: lets adb drive the app on phones that block simulated taps (e.g. Xiaomi), e.g.
     * `adb shell am start -n com.livetv.app/.MainActivity --es test_play "Channel name" --ei test_quality 360`.
     */
    private fun handleTestCommand(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent.getStringExtra("test_tab")?.let {
            b.bottomNav.selectedItemId = if (it == "youtube") R.id.tab_youtube else R.id.tab_live
        }
        intent.getStringExtra("test_play")?.let { name ->
            vm.listState.value.playOrder.firstOrNull { it.name == name }?.let(vm::play)
        }
        if (intent.hasExtra("test_quality")) vm.setQuality(intent.getIntExtra("test_quality", Prefs.QUALITY_AUTO))
        if (intent.getBooleanExtra("test_quality_picker", false)) showQualityPicker()
        intent.getStringExtra("test_url")?.let { b.youtubeWeb.loadUrl(it) }
        intent.getStringExtra("test_orientation")?.let {
            requestedOrientation = if (it == "portrait") ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (youtubeTab) b.youtubeWeb.onResume()
        if (resumeOnStart) {
            resumeOnStart = false
            vm.player.play()
        }
        refreshVolume()
    }

    override fun onResume() {
        super.onResume()
        if (vm.prefs.playlistKey != vm.loadedKey) vm.loadPlaylist()
        b.dimSwitch.isChecked = vm.prefs.dimPhoneInTvMode
        applyBrightness(dimmed = true)
    }

    override fun onStop() {
        super.onStop()
        if (youtubeTab) pauseYouTube()
        // In TV mode keep playing on the big screen even if the phone goes to the background.
        if (!isTvMode && !isChangingConfigurations) {
            resumeOnStart = vm.player.playWhenReady
            vm.player.pause()
        }
    }

    override fun onDestroy() {
        destroying = true
        displayManager.unregisterDisplayListener(displayListener)
        handler.removeCallbacksAndMessages(null)
        presentation?.let { closePresentation(it) }
        b.playerView.player = null
        b.youtubeWeb.stopLoading()
        b.youtubeWeb.destroy()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            vm.loadPlaylist(); true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(b.search.windowToken, 0)
        b.search.clearFocus()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val YOUTUBE_HOME = "https://m.youtube.com/"
        /** Hosts that stay inside the YouTube tab (Google hosts are needed for sign-in and cookie consent). */
        val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be", "google.com", "youtube-nocookie.com")
        val STANDARD_HEIGHTS = listOf(1080, 720, 480, 360)
    }

    private class SimpleItemSelected(private val onSelected: (Int) -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}
