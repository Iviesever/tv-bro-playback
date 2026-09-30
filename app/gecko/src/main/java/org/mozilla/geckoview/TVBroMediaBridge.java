package org.mozilla.geckoview;

import org.mozilla.gecko.util.BundleEventListener;
import org.mozilla.gecko.util.GeckoBundle;

/** Version-pinned adapter for the app-owned Gecko browser actor; no website JS interface. */
public final class TVBroMediaBridge {
    private static final String EVENT = "GeckoView:TVBro:FullscreenMediaState";
    public interface Listener { void onState(GeckoBundle state); }
    private TVBroMediaBridge() {}
    public static BundleEventListener attach(GeckoSession session, Listener listener) {
        BundleEventListener bridge = (event, message, callback) -> {
            if (message != null) listener.onState(message);
        };
        session.getEventDispatcher().registerUiThreadListener(bridge, EVENT);
        return bridge;
    }
    public static void detach(GeckoSession session, BundleEventListener bridge) {
        session.getEventDispatcher().unregisterUiThreadListener(bridge, EVENT);
    }
}
