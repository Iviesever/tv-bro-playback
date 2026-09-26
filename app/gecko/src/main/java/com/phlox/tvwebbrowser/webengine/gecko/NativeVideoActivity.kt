package com.phlox.tvwebbrowser.webengine.gecko

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/** A bounded-buffer, original-quality alternative for direct MP4 video on older TVs. */
@UnstableApi
class NativeVideoActivity : Activity() {
    companion object {
        private const val TAG = "TVBroPlayback"
        private var onReturn: ((Long) -> Unit)? = null

        fun launch(context: Context, url: String, referer: String, userAgent: String?, position: Long,
                   callback: (Long) -> Unit) {
            if (onReturn != null) return
            onReturn = callback
            try {
                context.startActivity(Intent(context, NativeVideoActivity::class.java)
                    .putExtra("url", url).putExtra("referer", referer)
                    .putExtra("userAgent", userAgent).putExtra("position", position)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                onReturn = null
                throw e
            }
        }
    }

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var position = 0L
    private var playWhenReady = true
    private var decoder = ""
    private val handler = Handler(Looper.getMainLooper())
    private val sample = object : Runnable {
        override fun run() {
            player?.let { p ->
                val counters = p.videoDecoderCounters
                counters?.ensureUpdated()
                val format = p.videoFormat
                Log.i(TAG, JSONObject().put("positionMs", p.currentPosition)
                    .put("bufferMs", p.totalBufferedDuration).put("state", p.playbackState)
                    .put("playing", p.isPlaying).put("decoder", decoder)
                    .put("width", format?.width).put("height", format?.height)
                    .put("fps", format?.frameRate).put("rendered", counters?.renderedOutputBufferCount)
                    .put("dropped", counters?.droppedBufferCount)
                    .put("maxConsecutiveDropped", counters?.maxConsecutiveDroppedBufferCount)
                    .put("skipped", counters?.skippedOutputBufferCount).toString())
            }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        position = savedInstanceState?.getLong("position") ?: intent.getLongExtra("position", 0)
        playWhenReady = savedInstanceState?.getBoolean("playWhenReady") ?: true
        playerView = PlayerView(this).apply {
            controllerShowTimeoutMs = 2500
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        setContentView(playerView)
        playerView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(intent.getStringExtra("userAgent") ?: "TV Bro")
            .setDefaultRequestProperties(mapOf("Referer" to (intent.getStringExtra("referer") ?: "")))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 45000, 1500, 3000)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl).build()
        player = p
        playerView.player = p
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                decoder = decoderName
                Log.i(TAG, "decoder=$decoderName initializationMs=$initializationDurationMs")
            }
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime,
                bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                Log.w(TAG, "audioUnderrun bufferMs=$bufferSizeMs elapsedMs=$elapsedSinceLastFeedMs")
            }
        })
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "state=$playbackState positionMs=${p.currentPosition}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "isPlaying=$isPlaying positionMs=${p.currentPosition}")
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "errorCode=${error.errorCodeName}")
                AlertDialog.Builder(this@NativeVideoActivity)
                    .setMessage("原生播放失败 (${error.errorCodeName})，请返回网页播放。")
                    .setPositiveButton("返回网页") { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.seekTo(position)
        p.prepare()
        p.playWhenReady = playWhenReady
        handler.post(sample)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playWhenReady", player?.playWhenReady ?: playWhenReady)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        handler.removeCallbacks(sample)
        player?.let { p ->
            position = p.currentPosition
            playWhenReady = p.playWhenReady
            playerView.player = null
            p.release()
        }
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            val callback = onReturn
            onReturn = null
            callback?.invoke(position)
        }
        super.onDestroy()
    }
}
