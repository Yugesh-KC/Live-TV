package com.livetv.app

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.livetv.app.databinding.PresentationTvBinding

/**
 * Fullscreen video on the external (HDMI) display. The phone keeps the player instance;
 * MainActivity moves the video surface here with PlayerView.switchTargetView().
 */
class TvPresentation(context: Context, display: Display) :
    Presentation(context, display, R.style.Theme_LiveTv_Presentation) {

    lateinit var binding: PresentationTvBinding
        private set

    private val hideOverlay = Runnable { binding.channelOverlay.visibility = View.GONE }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = PresentationTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.playerView.useController = false
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.playerView.keepScreenOn = true
    }

    fun showChannelName(name: String) {
        binding.channelOverlay.text = name
        binding.channelOverlay.visibility = View.VISIBLE
        binding.channelOverlay.removeCallbacks(hideOverlay)
        binding.channelOverlay.postDelayed(hideOverlay, OVERLAY_MS)
    }

    fun setError(message: String?) {
        binding.errorOverlay.text = message
        binding.errorOverlay.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    companion object {
        private const val OVERLAY_MS = 3_000L
    }
}
