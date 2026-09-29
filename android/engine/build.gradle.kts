import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// On-device trading engine (D-027): market data, strategies, backtests, risk, paper execution,
// ledger, signals, recommendations, autonomy and AI research. Pure Kotlin/JVM over a small SQL
// interface: SQLite through JDBC in tests, Android's built-in SQLite in the app.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Deterministic synthetic replay data (D-006) for tests and the offline demo mode.
sourceSets["test"].resources.srcDir("../../fixtures")

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    api(libs.jackson.databind)
    implementation(libs.json.schema.validator)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj)
    testImplementation(libs.jqwik)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
