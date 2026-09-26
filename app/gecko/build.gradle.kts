plugins {
    id("tvbro.android.library")
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
    testImplementation(libs.junit)
}
