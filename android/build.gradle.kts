// All plugins are declared here so they share one classloader (AGP, KGP, KSP and Hilt interoperate).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.cyclonedx) apply false
}

spotless {
    kotlin {
        target("core/src/**/*.kt", "app/src/**/*.kt")
        // Same rules as the backend (and the repository .editorconfig).
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf(
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_code_style" to "ktlint_official",
                "max_line_length" to "off",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "core/*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
