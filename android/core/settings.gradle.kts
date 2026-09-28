// Standalone build of the pure-Kotlin core module, used where Google Maven is unreachable (D-003):
//   gradle -p android/core test
// The full Android build (android/settings.gradle.kts) includes this directory as project :core
// and ignores this file.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "strategyforge-android-core"
