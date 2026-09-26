package com.phlox.tvwebbrowser.webengine.gecko.delegates

import android.util.Log
import android.net.Uri
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import com.phlox.tvwebbrowser.webengine.gecko.NativeVideoActivity
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

// This is a leftover from the removal of the abandoned "text selection" feature.
// I'll leave it here for now as a framework for future extension-based features.
class AppContentScriptPortDelegate(val port: WebExtension.Port, val webEngine: GeckoWebEngine): WebExtension.PortDelegate {

    init {
        port.postMessage(JSONObject().put("nativeVideoAvailable",
            AppContext.get().packageName == "com.phlox.tvwebbrowser.playback"))
        webEngine.pendingNativeVideoPositionMs?.let { position ->
            port.postMessage(JSONObject().put("nativeVideoPositionMs", position))
        }
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        if (AppContext.get().packageName != "com.phlox.tvwebbrowser.playback") return
        val data = message as? JSONObject ?: return
        if (data.optString("type") == "nativeVideoPositionApplied") {
            webEngine.pendingNativeVideoPositionMs = null
            return
        }
        if (data.optString("type") != "nativeVideo") return
        val page = webEngine.url ?: return
        val pageUri = Uri.parse(page)
        val url = data.optString("url")
        val mediaUri = Uri.parse(url)
        // Keep this device-specific experiment confined to the verified site and HTTPS MP4 CDN.
        if (pageUri.scheme != "https" || pageUri.host !in setOf("www.cycani.org", "cycani.org") ||
            mediaUri.scheme != "https" || mediaUri.host?.endsWith(".cycstream.com") != true ||
            mediaUri.path?.endsWith(".mp4", true) != true) return
        try {
            val ua = webEngine.userAgentString
            webEngine.suspendForNativeVideo()
            NativeVideoActivity.launch(AppContext.get(), url, page, ua,
                data.optLong("positionMs", 0).coerceAtLeast(0)) { position ->
                webEngine.resumeFromNativeVideo(position)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open native player", e)
            webEngine.resumeFromNativeVideo(data.optLong("positionMs", 0))
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "onDisconnect")
        webEngine.appContentScriptPortDelegate = null
    }

    companion object {
        val TAG: String = AppContentScriptPortDelegate::class.java.simpleName
    }
}
