import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android but UI-agnostic (REQ-30): no Compose, and no dependency on :app or :lcars.
// Edges point from :app toward this module, never back.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.hermesconsole.audio"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The classes under test are logic, not framework - but they log, and an unmocked android.util
    // stub throws rather than no-ops. Defaults, so a Log line cannot fail a unit test.
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit5.launcher)
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
