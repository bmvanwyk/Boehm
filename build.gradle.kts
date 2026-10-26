plugins {
    kotlin("jvm") version "2.4.21"
    id("dev.detekt") version "2.0.0-alpha.6"
    application
    jacoco
}

jacoco {
    toolVersion = "0.8.16"
}

group = "io.boehm"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.yaml:snakeyaml:2.7")

    // MCP SDK (official Kotlin MCP server)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.15.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.boehm.MainKt")
}

kotlin {
    jvmToolchain(25)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    // Main.kt is the stdio entry point (transport wiring); it is covered by
    // integration usage, not unit tests. Everything else must meet the 80% bar.
    classDirectories.setFrom(
        files(classDirectories.map { dir ->
            fileTree(dir) { exclude("io/boehm/MainKt*") }
        })
    )
}

detekt {
    // Fail the build on new violations; tuned config lives in config/detekt/.
    autoCorrect = false
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("config/detekt/detekt.yml")
}

// detekt runs as part of ./gradlew build and ./gradlew check.
tasks.check {
    dependsOn(tasks.detekt)
}
