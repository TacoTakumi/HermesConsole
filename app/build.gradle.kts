import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The console shell (REQ-31): composes the shared modules and holds no reusable logic
// of its own. Every dependency edge here points outward, into a module that could be
// lifted into another consumer unchanged.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.hermesconsole.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.hermesconsole"
        minSdk = 31
        // Android 15 foreground-service rules are the baseline the app is written against
        // (REQ-27); compileSdk 36 keeps the Android 16 / One UI 8 OTA in view.
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
        // The SM-X210 is arm64; no reason to carry the other ABIs.
        ndk { abiFilters += "arm64-v8a" }
        // The AEC hardware gate (T-04) runs here, not in :audio: since Android 12 a process with
        // no visible Activity gets SILENCE from the mic rather than an error, so the gate must run
        // in the app process with MainActivity on screen or it measures nothing at all.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The loop logs its state transitions through android.util.Log, which is a stub on the JVM and
    // throws unless told to behave. Nothing under test asserts on a log line.
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
dependencies {
    implementation(project(":lcars"))
    implementation(project(":audio"))
    implementation(project(":wakeword"))
    implementation(project(":voiceclient"))
    implementation(project(":hermesclient"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.webkit)

    // The voice loop's ordering guarantee (REQ-12) is provable on a plain JVM: it talks to ports,
    // so the turn can be driven with fakes and no socket, server or tablet in sight.
    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    // The wall-assembly test (T-49) drives the shipping WallConsole against a canned gateway.
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit5.launcher)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    // The scroll-follow test (T-56) renders the shell and taps its jump-to-latest control, so it
    // needs Compose's node finders - the one instrumented test that drives the tree rather than
    // asserting on hoisted state. BOM-managed, like every other compose artifact here.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
