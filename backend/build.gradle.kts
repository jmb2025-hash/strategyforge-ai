import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.cyclonedx)
}

group = "app.strategyforge"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        allWarningsAsErrors = false
    }
}

// Spring Boot 3.5 manages an older Kotlin; align the runtime with the compiler.
extra["kotlin.version"] = libs.versions.kotlin.get()

// Security patch levels above the Spring Boot 3.5.16 BOM (Trivy findings, decision D-025).
extra["tomcat.version"] = "10.1.60"
extra["postgresql.version"] = "42.7.13"
extra["httpcore5.version"] = "5.4.3"
extra["jackson-bom.version"] = "2.21.6"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation(libs.springdoc.webmvc)
    implementation(libs.bcprov)
    implementation(libs.logstash.encoder)
    implementation(libs.json.schema.validator)
    implementation(libs.google.auth)
    implementation(libs.anthropic.java)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.jqwik)
    testImplementation(libs.jqwik.kotlin)
    testImplementation(libs.archunit)
    testImplementation(libs.swagger.parser)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    systemProperty("sf.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    reports.junitXml.required.set(true)
    reports.html.required.set(true)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("strategyforge-backend.jar")
}
tasks.named<Jar>("jar") { enabled = false }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt.yml"))
    parallel = true
}
tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
    }
}
// detekt 1.23.x is compiled against Kotlin 2.0.x; pin its own classpath.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
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
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
    format("sql") {
        target("src/main/resources/db/migration/*.sql")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setProjectType("application")
    setDestination(
        layout.buildDirectory
            .dir("reports/sbom")
            .get()
            .asFile,
    )
    setOutputName("strategyforge-backend-sbom")
    setOutputFormat("json")
}

// Bundle the deterministic replay fixtures into the application (classpath:replay/...).
sourceSets {
    main {
        resources.srcDir("../fixtures")
    }
}
