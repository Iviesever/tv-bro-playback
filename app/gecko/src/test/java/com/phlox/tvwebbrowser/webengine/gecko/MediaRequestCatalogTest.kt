package com.phlox.tvwebbrowser.webengine.gecko

import org.junit.Assert.*
import org.junit.Test

class MediaRequestCatalogTest {
    private val page = "https://watch.example/episode/1"
    private fun hls(url: String, frame: String = "https://embed.example/player", privateMode: Boolean = false) =
        MediaRequestCatalog.Request(url, page, frame, privateMode, MediaRequestCatalog.Kind.HLS)

    @Test fun directMediaDoesNotDependOnDomainOrFilenameExtension() {
        val result = MediaRequestCatalog().select("https://another.cdn.example/signed?id=1", page, false)
        assertTrue(result is MediaRequestCatalog.Selection.Resolved)
    }
    @Test fun blobMediaIsCorrelatedToItsIframeAndPrivacyContext() {
        val catalog = MediaRequestCatalog()
        catalog.observe(hls("https://cdn.example/a.m3u8"))
        catalog.observe(hls("https://ads.example/ad.m3u8", "https://ads.example/iframe"))
        catalog.observe(hls("https://private.example/a.m3u8", privateMode = true))
        val result = catalog.select("blob:https://embed.example/123", page, false) as MediaRequestCatalog.Selection.Resolved
        assertEquals("https://cdn.example/a.m3u8", result.request.url)
    }
    @Test fun ambiguousMediaIsNeverChosenByRecency() {
        val catalog = MediaRequestCatalog()
        catalog.observe(hls("https://cdn.example/film.m3u8"))
        catalog.observe(hls("https://cdn.example/ad.m3u8"))
        assertTrue(catalog.select("blob:https://embed.example/123", page, false) is MediaRequestCatalog.Selection.Ambiguous)
    }
    @Test fun identicalPagesInDifferentTabsDoNotShareMedia() {
        val catalog = MediaRequestCatalog()
        catalog.observe(hls("https://cdn.example/a.m3u8").copy(tabId = 10))
        catalog.observe(hls("https://cdn.example/b.m3u8").copy(tabId = 11))
        val result = catalog.select("blob:https://embed.example/123", page, false, 10) as MediaRequestCatalog.Selection.Resolved
        assertEquals("https://cdn.example/a.m3u8", result.request.url)
    }
    @Test fun masterPlaylistWinsOverObservedVariants() {
        val catalog = MediaRequestCatalog()
        val master = hls("https://cdn.example/master.m3u8")
        val variant = hls("https://cdn.example/high.m3u8")
        catalog.observe(master); catalog.observe(variant)
        catalog.annotate(master, setOf(variant.url), false)
        val result = catalog.select("blob:https://embed.example/123", page, false) as MediaRequestCatalog.Selection.Resolved
        assertEquals(master.url, result.request.url)
    }
    @Test fun staleAndProtectedMediaStayInBrowser() {
        var now = 1L
        val catalog = MediaRequestCatalog { now }
        val media = hls("https://cdn.example/a.m3u8")
        catalog.observe(media)
        catalog.annotate(media, emptySet(), true)
        assertTrue(catalog.select("blob:https://embed.example/123", page, false) is MediaRequestCatalog.Selection.Unavailable)
        now += 31 * 60 * 1000L
        assertTrue(catalog.snapshot(page, false).isEmpty())
    }
    @Test fun credentialsDoNotCrossOriginsOrObservedDirectory() {
        val media = hls("https://cdn.example/private/master.m3u8").copy(
            headers = mapOf("Cookie" to "sid=private", "Authorization" to "Bearer secret", "Origin" to "https://embed.example"))
        val context = MediaRequestContext(media, listOf(media), "test-agent")
        assertEquals("sid=private", context.headersFor("https://cdn.example/private/seg1.ts")["Cookie"])
        assertFalse(context.headersFor("https://evil.example/seg1.ts").containsKey("Cookie"))
        assertFalse(context.headersFor("https://cdn.example/public/seg1.ts").containsKey("Authorization"))
        assertFalse(context.headersFor("http://cdn.example/private/seg1.ts").containsKey("Authorization"))
        assertFalse(context.headersFor("https://cdn.example/private/../public/seg1.ts").containsKey("Cookie"))
        assertFalse(context.headersFor("https://cdn.example/private/%2e%2e/public/seg1.ts").containsKey("Cookie"))
        assertEquals("https://embed.example/", context.headersFor("https://evil.example/seg1.ts")["Referer"])
    }
    @Test fun transportHeadersAndHeaderInjectionAreRejected() {
        val headers = MediaRequestCatalog.sanitizeHeaders(mapOf("Host" to "evil", "Range" to "bytes=99-", "X-Token" to "a\r\nb", "Authorization" to "ok"))
        assertEquals(mapOf("Authorization" to "ok"), headers)
        assertFalse(MediaRequestCatalog.isHttp("file:///data/private"))
        assertFalse(MediaRequestCatalog.isHttp("https://user:password@cdn.example/video"))
    }
}
