import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.cyclonedx.bom")
}

// Release signing comes from the environment (CI secrets or the owner's shell), never from the
// repository. Without it, CI signs release builds with an ephemeral key (see docs/ANDROID.md).
val releaseKeystore: File? =
    System.getenv("SF_RELEASE_KEYSTORE_B64")?.takeIf { it.isNotBlank() }?.let { b64 ->
        layout.buildDirectory.file("signing/release.jks").get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(Base64.getMimeDecoder().decode(b64))
        }
    } ?: System.getenv("SF_RELEASE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }

android {
    namespace = "app.strategyforge.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.strategyforge.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SF_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SF_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("SF_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes +=
            setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.md",
                "/META-INF/FastDoubleParser-*",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/*/module-info.class",
                "/module-info.class",
            )
    }

    // Recorded replay market data for demo mode (D-006), read by the engine from assets/replay.
    sourceSets["main"].assets.srcDir("../../fixtures")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    // The on-device trading engine (D-027).
    implementation(project(":engine"))
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.splashscreen)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

tasks.cyclonedxBom {
    setIncludeConfigs(listOf("releaseRuntimeClasspath"))
    setProjectType("application")
    setDestination(
        layout.buildDirectory
            .dir("reports/sbom")
            .get()
            .asFile,
    )
    setOutputName("strategyforge-android-sbom")
    setOutputFormat("json")
}
