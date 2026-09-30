import tvbro.GeckoMediaFullscreenPatchTask

plugins {
    id("tvbro.android.library")
}

val geckoAssetSource by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(geckoAssetSource.name, libs.geckoview) }
val patchedGeckoAssets = tasks.register<GeckoMediaFullscreenPatchTask>("patchGeckoMediaFullscreen") {
    geckoAar.from(geckoAssetSource)
    outputDirectory.set(layout.buildDirectory.dir("generated/gecko-media-assets"))
}
extensions.getByType<com.android.build.api.variant.LibraryAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(patchedGeckoAssets) { it.outputDirectory }
}

android {
    namespace = "com.phlox.tvwebbrowser.webengine.gecko"
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(project(":app:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.geckoview)
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-ui:1.9.3")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.3")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.3")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.3")
    testImplementation(libs.junit)
}
