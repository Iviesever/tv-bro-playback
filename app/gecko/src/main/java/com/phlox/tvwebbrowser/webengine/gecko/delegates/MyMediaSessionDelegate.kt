package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import com.phlox.tvwebbrowser.webengine.gecko.NativeVideoActivity
import com.phlox.tvwebbrowser.webengine.gecko.NativeMediaResolver
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import org.mozilla.gecko.util.GeckoBundle
import java.util.concurrent.Future

/** Uses Gecko's media controller; never installs handlers or controls into a website. */
class MyMediaSessionDelegate(private val engine: GeckoWebEngine) : MediaSession.Delegate {
    var mediaSession: MediaSession? = null
        private set
    var paused = true
        private set
    private var fullscreen = false
    private var metadata: MediaSession.ElementMetadata? = null
    private var position = 0.0
    private var duration = Double.NaN
    private var rate = 1.0
    private var positionAt = SystemClock.elapsedRealtime()
    private var pendingRestore: Pair<Long, Boolean>? = null
    private val failedSources = mutableSetOf<String>()
    private var launching = false
    private var documentUrl = ""
    private var generation = 0
    private var resolution: Future<*>? = null
    private var browserState: GeckoBundle? = null

    fun onBrowserMediaState(state: GeckoBundle) {
        val enabled = state.getBoolean("enabled")
        val source = state.getString("source")
        if (enabled && source.isNullOrEmpty()) return
        val oldSource = browserState?.getString("source")
        if (!enabled || oldSource != null && source != oldSource) {
            generation++
            resolution?.cancel(true)
            if (!engine.nativeVideoActive) launching = false
        }
        fullscreen = enabled
        browserState = if (enabled) state else null
        if (!enabled) { failedSources.clear(); metadata = null; return }
        position = state.getDouble("position").coerceAtLeast(0.0)
        duration = state.getDouble("duration")
        rate = state.getDouble("rate").takeIf { it.isFinite() && it > 0 } ?: 1.0
        positionAt = SystemClock.elapsedRealtime()
        paused = state.getBoolean("paused")
        if (paused && !engine.nativeVideoActive) {
            generation++
            resolution?.cancel(true)
            launching = false
        }
        if (!paused && mediaSession?.isActive == true) maybeOptimize()
    }

    private fun positionMs(): Long {
        val elapsed = if (paused || engine.nativeVideoActive) 0.0 else
            (SystemClock.elapsedRealtime() - positionAt) / 1000.0 * rate
        val value = (position + elapsed).coerceAtLeast(0.0)
        return ((if (duration.isFinite() && duration > 0) value.coerceAtMost(duration) else value) * 1000).toLong()
    }

    override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.i("TVBroAutoVideo", "media-activated")
        this.mediaSession = mediaSession
        // Activation does not mean "next episode". Do not dispatch site playlist actions here.
        applyRestore()
        maybeOptimize()
    }

    override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
        Log.i("TVBroAutoVideo", "media-deactivated")
        if (this.mediaSession === mediaSession) this.mediaSession = null
    }

    override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
        Log.i("TVBroAutoVideo", "media-playing")
        if (browserState == null) position = positionMs() / 1000.0
        positionAt = SystemClock.elapsedRealtime()
        paused = false
        applyRestore()
        maybeOptimize()
    }

    override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
        Log.i("TVBroAutoVideo", "media-paused")
        position = positionMs() / 1000.0
        positionAt = SystemClock.elapsedRealtime()
        paused = true
    }

    override fun onPositionState(session: GeckoSession, mediaSession: MediaSession, state: MediaSession.PositionState) {
        if (browserState == null) {
            if (state.position.isFinite()) position = state.position.coerceAtLeast(0.0)
            duration = state.duration
            rate = state.playbackRate.takeIf { it.isFinite() && it > 0 } ?: 1.0
            positionAt = SystemClock.elapsedRealtime()
        }
        applyRestore()
    }

    override fun onFullscreen(session: GeckoSession, mediaSession: MediaSession,
        enabled: Boolean, meta: MediaSession.ElementMetadata?) {
        if (browserState != null && enabled) { maybeOptimize(); return }
        // A fullscreen iframe's ancestor can report an empty media element after its child.
        if (enabled && meta?.source.isNullOrEmpty()) return
        fullscreen = enabled
        if (!enabled) {
            failedSources.clear()
            generation++
            resolution?.cancel(true)
            if (!engine.nativeVideoActive) launching = false
        }
        if (meta?.source != null && metadata?.source != null && meta.source != metadata?.source) {
            generation++
            resolution?.cancel(true)
            if (!engine.nativeVideoActive) launching = false
            position = 0.0
            positionAt = SystemClock.elapsedRealtime()
        }
        metadata = if (enabled) meta else null
        Log.i("TVBroAutoVideo", "fullscreen=$enabled hasMedia=${meta != null}")
        if (enabled) maybeOptimize()
    }

    private fun maybeOptimize() {
        if (!fullscreen || paused || launching || engine.nativeVideoActive || !engine.isForeground ||
            !engine.tab.selected || !AppContext.provideConfig().autoNativeFullscreen) return
        val source = browserState?.getString("source") ?: metadata?.source ?: return
        if (browserState?.getBoolean("encrypted") == true) return
        if (source in failedSources) return
        val page = engine.url ?: return
        documentUrl = page
        launching = true
        val token = generation
        val ua = engine.userAgentString ?: GeckoSession.getDefaultUserAgent()
        resolution = NativeMediaResolver.resolve(source, page, engine.session.settings.usePrivateMode, ua, browserState?.getString("frame")) { resolved, reason ->
            if (token != generation) return@resolve
            if (!fullscreen || paused || !engine.isForeground || !engine.tab.selected ||
                !AppContext.provideConfig().autoNativeFullscreen) { launching = false; return@resolve }
            if (resolved == null) {
                launching = false
                failedSources.add(source)
                Log.i("TVBroAutoVideo", "browser-retained reason=$reason")
                return@resolve
            }
            val start = if (duration.isInfinite()) -1L else positionMs()
            try {
                engine.suspendForNativeVideo()
                val state = browserState
                val subtitles = state?.getBundleArray("subtitles").orEmpty().mapNotNull { track ->
                    val url = track.getString("url") ?: return@mapNotNull null
                    if (!com.phlox.tvwebbrowser.webengine.gecko.MediaRequestCatalog.isHttp(url)) return@mapNotNull null
                    NativeVideoActivity.Subtitle(url, track.getString("language").orEmpty(), track.getString("label").orEmpty())
                }
                val volume = if (state?.getBoolean("muted") == true) 0f else (state?.getDouble("volume", 1.0) ?: 1.0).toFloat().coerceIn(0f, 1f)
                NativeVideoActivity.launch(AppContext.get(), NativeVideoActivity.PlaybackRequest(resolved, start, rate.toFloat(), volume,
                    state?.getBoolean("textEnabled") ?: false, state?.getString("textLanguage").orEmpty(), subtitles)) { result ->
                    launching = false
                    val failed = result.action == NativeVideoActivity.ReturnAction.FAILED
                    val ended = result.action == NativeVideoActivity.ReturnAction.ENDED
                    if (ended) failedSources.add(source)
                    if (failed) {
                        failedSources.add(source)
                        Toast.makeText(AppContext.get(), "优化播放暂不可用，继续网页播放", Toast.LENGTH_SHORT).show()
                    }
                    // Playing an already-ended HTML video restarts it. Let its final fraction play
                    // naturally so the website receives ended and retains its own next-item logic.
                    val restore = if (ended && result.positionMs >= 0) (result.positionMs - 250).coerceAtLeast(0) else result.positionMs
                    engine.resumeFromNativeVideo(restore, failed || ended, !failed && !ended)
                }
            } catch (error: Exception) {
                launching = false
                failedSources.add(source)
                engine.resumeFromNativeVideo(start, true, false)
                Log.w("TVBroAutoVideo", "handoff failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun restorePosition(positionMs: Long, resume: Boolean) {
        pendingRestore = positionMs to resume
        applyRestore()
    }

    private fun applyRestore() {
        val request = pendingRestore ?: return
        val controller = mediaSession ?: return
        if (!engine.session.isOpen || engine.nativeVideoActive) return
        pendingRestore = null
        if (request.first >= 0) {
            position = request.first / 1000.0
            positionAt = SystemClock.elapsedRealtime()
            controller.seekTo(position, false)
        }
        if (request.second) controller.play() else controller.pause()
    }

    fun onNavigation(url: String) {
        generation++
        resolution?.cancel(true)
        if (!engine.nativeVideoActive) launching = false
        fullscreen = false
        metadata = null
        browserState = null
        failedSources.clear()
        if (url.substringBefore('#') != documentUrl.substringBefore('#')) pendingRestore = null
        documentUrl = url
        position = 0.0
        duration = Double.NaN
        paused = true
    }
}
