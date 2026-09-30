package tvbro

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Repairs the fullscreen/activation race in the pinned Gecko147 browser actor, not website code. */
abstract class GeckoMediaFullscreenPatchTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val geckoAar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun patch() {
        val sourceArchive = temporaryDir.resolve("original-omni.ja")
        ZipFile(geckoAar.singleFile).use { aar ->
            val entry = requireNotNull(aar.getEntry("assets/omni.ja")) { "Gecko AAR has no omni.ja" }
            aar.getInputStream(entry).use { input -> sourceArchive.outputStream().use(input::copyTo) }
        }
        val actorPath = "actors/MediaControlDelegateChild.sys.mjs"
        val output = outputDirectory.file("omni.ja").get().asFile
        output.parentFile.mkdirs()
        ZipFile(sourceArchive).use { original ->
            val actor = original.getInputStream(requireNotNull(original.getEntry(actorPath))).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(actor).joinToString("") { "%02x".format(it) }
            require(digest == "27b7587986e95dabed3aafb3af88095422e08defe529e79d67d6564e99185ff1") {
                "Gecko media actor changed; review the fullscreen fix before changing Gecko versions"
            }
            val patched = actor.toString(Charsets.UTF_8)
                .replace("  handleEvent(aEvent) {", """
                  notifyApplication(aEvent) {
                    const element = this.document.fullscreenElement;
                    if (!element && aEvent.type !== "MozDOMFullscreen:Exited") return;
                    const media = lazy.MediaUtils.findMediaElement(element);
                    if (element && (!media || !media.currentSrc)) return;
                    const showing = media ? Array.from(media.textTracks || []).filter(t => t.mode === "showing" && ["subtitles", "captions"].includes(t.kind)) : [];
                    const subtitles = media ? Array.from(media.querySelectorAll("track")).filter(t => t.track?.mode === "showing" && ["subtitles", "captions"].includes(t.kind)).map(t => ({url:t.src, language:t.srclang, label:t.label})) : [];
                    this.eventDispatcher.sendRequest({
                      type: "GeckoView:TVBro:FullscreenMediaState", enabled: !!element,
                      source: media?.currentSrc || "", frame: this.document.documentURI,
                      position: media?.currentTime || 0, duration: media?.duration,
                      rate: media?.playbackRate || 1, volume: media?.volume ?? 1,
                      paused: media?.paused ?? true, muted: media?.muted ?? false,
                      encrypted: !!(media?.isEncrypted || media?.mediaKeys),
                      textEnabled: showing.length > 0, textLanguage: showing[0]?.language || "",
                      subtitles
                    });
                  }

                  handleEvent(aEvent) {
                    this.notifyApplication(aEvent);
                """.trimIndent())
                .replace("case \"MozDOMFullscreen:Entered\":", "case \"playing\":\n        if (this.document.fullscreenElement) {\n          this.handleFullscreenChanged(Date.now() + 5000);\n        }\n        break;\n      case \"MozDOMFullscreen:Entered\":")
                .replace("this.handleFullscreenChanged(true);", "this.handleFullscreenChanged(Date.now() + 5000);")
                .replace("async handleFullscreenChanged(retry) {", "async handleFullscreenChanged(retryUntil) {")
                .replace("if (retry && element) {", "if (element && element === this.document.fullscreenElement && Date.now() < retryUntil) {")
                .replace("this.handleFullscreenChanged(false);", "this.handleFullscreenChanged(retryUntil);")
                .toByteArray(Charsets.UTF_8)
            val registryPath = "chrome/geckoview/content/geckoview.js"
            val registry = original.getInputStream(requireNotNull(original.getEntry(registryPath))).use { it.readBytes() }
            val registryDigest = MessageDigest.getInstance("SHA-256").digest(registry).joinToString("") { "%02x".format(it) }
            require(registryDigest == "501f149a480142790185f8826c2ca91c52c79bca95985505535417610e69eb0b") {
                "Gecko actor registry changed; review the media playing event registration"
            }
            val registryText = registry.toString(Charsets.UTF_8)
            val actorIndex = registryText.indexOf("\"resource:///actors/MediaControlDelegateChild.sys.mjs\"")
            val eventsIndex = registryText.indexOf("events: {", actorIndex) + "events: {".length
            require(actorIndex >= 0 && eventsIndex >= "events: {".length)
            val patchedRegistry = registryText.substring(0, eventsIndex) +
                "\n" + listOf("playing", "pause", "timeupdate", "seeked", "volumechange", "ratechange", "loadedmetadata", "encrypted")
                    .joinToString("\n") { "                $it: { capture: true, mozSystemGroup: true }," } +
                registryText.substring(eventsIndex)
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                original.entries().asSequence().forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    if (!entry.isDirectory) {
                        if (entry.name == actorPath) zip.write(patched)
                        else if (entry.name == registryPath) zip.write(patchedRegistry.toByteArray(Charsets.UTF_8))
                        else if (entry.name.startsWith("defaults/pref/") && entry.name.endsWith("/geckoview-prefs.js")) {
                            original.getInputStream(entry).use { it.copyTo(zip) }
                            // Release scarce TV hardware decoders on pause rather than holding them
                            // for five seconds while the native renderer is taking over.
                            zip.write("\npref(\"media.dormant-on-pause-timeout-ms\", 0);\n".toByteArray(Charsets.UTF_8))
                        }
                        else original.getInputStream(entry).use { it.copyTo(zip) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}
