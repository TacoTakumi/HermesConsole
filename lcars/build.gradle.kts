import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The LCARS design system (REQ-19): a self-contained Compose kit that knows nothing of
// Hermes, VoiceManager, or the console shell. Its one project edge is :audio, and only
// for the output-path seam the cue set plays through (REQ-36) - never the capture side.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.hermesconsole.lcars"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
dependencies {
    // The cue player's playback seam (REQ-36): cues are written to an AudioSink the audio module
    // builds, so UI sound rides the same echo-cancelled output path as TTS (REQ-33). api, not
    // implementation: the sink factory is in LcarsCuePlayer's constructor signature.
    api(project(":audio"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    // @Preview lives in ui-tooling-preview; ui-tooling renders it, and hosts the on-device
    // gallery Activity - both debug-only, so neither reaches a release build.
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.activity.compose)
    testImplementation(libs.junit5)
    testRuntimeOnly(libs.junit5.launcher)
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
