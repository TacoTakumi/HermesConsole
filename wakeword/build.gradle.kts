import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android but UI-agnostic (REQ-30): no Compose, and no dependency on :app or :lcars.
// Edges point from :app toward this module, never back.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.hermesconsole.wakeword"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
dependencies {
    // The KWS engine, resolved by filename from the flatDir repo in settings.gradle.kts (fetch it
    // with scripts/fetch-wakeword-assets.sh). The @aar suffix is load-bearing: flatDir matches on
    // artifact name and extension, and looks only for a .jar without it.
    // `implementation`, not `api`: the engine is this module's business. :app depends on
    // WakeWordSource and must not be able to reach com.k2fsa.* at all - that is what keeps a future
    // engine swap (D-14a) inside these four walls. The native .so libs still reach the APK: they
    // travel on the runtime classpath, which `implementation` does not cut.
    // Note what is NOT here (REQ-01): no okhttp, no ktor, no :voiceclient - this module has no way
    // to reach the network, and the build file is half that proof.
    implementation("com.k2fsa:sherpa-onnx:${libs.versions.sherpaOnnx.get()}@aar")
    api(libs.coroutines.core)
    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit5.launcher)
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
