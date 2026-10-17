pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx is published only as a GitHub-release AAR. A flatDir repo rather than a
        // direct file() dependency because AGP refuses local .aar file deps inside a library
        // module - the AAR it produced would silently omit them.
        flatDir { dirs("$settingsDir/wakeword/libs") }
    }
}
rootProject.name = "hermesconsole"

// Reusable, portable modules (D-09):
//   voiceclient / hermesclient - pure Kotlin/JVM, no Android
//   audio / wakeword           - Android, but UI-agnostic (no Compose, no app/lcars)
//   lcars                      - Compose design system, knows nothing of the protocols
//   app                        - the shell; composes the above, owns no reusable logic
include(":app", ":lcars", ":audio", ":wakeword", ":voiceclient", ":hermesclient")
