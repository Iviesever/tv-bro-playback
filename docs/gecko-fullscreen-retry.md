# Gecko147 media fullscreen activation race

The native media API drops fullscreen metadata while its media controller is not yet active.
The bundled browser actor retries once after 100ms. On the Android8 test television, the
document fullscreen callback arrived at 18:32:01.441 and media activation at 18:32:03.007.
Consequently a normal play-then-fullscreen gesture did not reach `MediaSession.Delegate.onFullscreen`.

The build generates an asset override from the pinned, unmodified Maven AAR. Actor retries
are bounded by a five-second deadline and stop when fullscreen changes. The browser actor
registry also listens for `playing`, so a video that buffers longer than that deadline can
refresh its fullscreen metadata once playback actually starts. The actor re-reads current
metadata on each attempt. Both changes are in the app's browser resources. This is
browser implementation code, not a website content script, DOM mutation or injected control.

The same browser actor emits read-only state for the actual fullscreen media element. This
avoids using the global Media Session position, which retained a prior iframe video's time
in a DASH test. The app uses the element's position, rate, volume, encryption and selected
text-track state. It does not rewrite the site's player or add DOM controls. A small
version-pinned adapter in `org.mozilla.geckoview.TVBroMediaBridge` receives these browser
events. Ordinary pages have no TV Bro content script.

The source actor is MPL2.0 and retains its original license header in the generated archive.
Its SHA256 is checked before transformation. A Gecko upgrade must review this patch explicitly;
the build fails rather than silently applying it to an unknown actor. Gradle's dependency cache
and the television's system software are never edited.

The generated GeckoView default preferences also set `media.dormant-on-pause-timeout-ms`
to zero. The shipped default is 5000ms. The TV trace showed the paused browser codec being
restarted/released five seconds into the handoff and producing OMX errors while the native
codec was playing. A controlled zero-delay test retained the same document, produced no
HTML video error, and restored the playback position. This releases a paused decoder sooner;
it does not change the website source, system codecs, picture processing or panel settings.

Upstream source: `mobile/android/actors/MediaControlDelegateChild.sys.mjs`, GeckoView
147.0.20260212191108 (Firefox147.0.4). The transformation is in
`buildSrc/src/main/kotlin/tvbro/GeckoMediaFullscreenPatchTask.kt`.

The actor regression check is `node tools/test-gecko-media-actor.cjs <extracted-actor.mjs>`.
It exercises delayed activation, the retry deadline, leaving fullscreen, playback starting
after the deadline, immutable captured state, and source/frame-specific media restoration.
The catalog tests cover manifest aliases,
frame/tab/private context and credential scope. Device verification must also wait for
`seeked`, usable media readiness and absence of HTML errors after returning.

Native playback bounds a continuous buffering attempt to 15 seconds, then restores the
website and suppresses a retry loop for that fullscreen source. A user pause cancels that
timer. Each playback uses a fresh bandwidth meter: throughput measured on a previous CDN
or a LAN stream must not select an excessive initial rendition on another website. Adaptive
streams can change quality with available bandwidth; progressive video retains its source.

A full-episode test also exposed a dormant-controller return failure: after long suspension,
the SDK's MediaSession controller was inactive and the pending seek could never run. The
browser's media module now sends a private restore request to the matching browser actor.
It uses ordinary seek/play/pause media APIs only when both document URI and media source
match. It can restore after leaving fullscreen, refuses ambiguous elements, and does not
depend on controller activation. This module is also pinned by source SHA256. The TV test
must verify returns after longer than the SDK's controller timeout, including natural end.
