import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM (REQ-29): lifts unchanged into any Android, Android TV, or JVM consumer.
// Do not add the Android plugin or any android.*/androidx.* dependency to this module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit5.launcher)
}
tasks.test { useJUnitPlatform() }
