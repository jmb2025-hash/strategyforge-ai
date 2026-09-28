import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: API client, DTOs, cache policy, formatting, redaction and UI state holders.
// No Android framework dependency, so it is fully unit-tested on any JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    // Bytecode for Android (Java 17 level); any JDK 17+ can build it.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}
