package org.mozilla.geckoview;

import org.mozilla.gecko.util.BundleEventListener;
import org.mozilla.gecko.util.GeckoBundle;

/** Version-pinned adapter for the app-owned Gecko browser actor; no website JS interface. */
public final class TVBroMediaBridge {
    private static final String EVENT = "GeckoView:TVBro:FullscreenMediaState";
    private static final String RESTORE_RESULT = "GeckoView:TVBro:RestoreResult";
    public interface Listener { void onState(GeckoBundle state); }
    private TVBroMediaBridge() {}
    public static BundleEventListener attach(GeckoSession session, Listener listener) {
        BundleEventListener bridge = (event, message, callback) -> {
            if (message == null) return;
            if (EVENT.equals(event)) listener.onState(message);
            else android.util.Log.i("TVBroAutoVideo", "browser-restore=" + message.getBoolean("restored"));
        };
        session.getEventDispatcher().registerUiThreadListener(bridge, EVENT, RESTORE_RESULT);
        return bridge;
    }
    public static void detach(GeckoSession session, BundleEventListener bridge) {
        session.getEventDispatcher().unregisterUiThreadListener(bridge, EVENT, RESTORE_RESULT);
    }
    public static void restore(GeckoSession session, GeckoBundle state, long positionMs, boolean resume) {
        GeckoBundle request = new GeckoBundle(4);
        request.putString("source", state.getString("source"));
        request.putString("frame", state.getString("frame"));
        request.putDouble("time", positionMs < 0 ? -1 : positionMs / 1000.0);
        request.putBoolean("resume", resume);
        session.getEventDispatcher().dispatch("GeckoView:TVBro:RestoreMedia", request);
    }
}
