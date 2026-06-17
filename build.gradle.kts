plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Ensure JDK 17+ for Kotlin compiler compatibility
val jvmVersion = System.getProperty("java.version")?.substringBefore(".")?.toIntOrNull() ?: 0
if (jvmVersion >= 30) {
    throw GradleException(
        "JDK 30+ is not yet supported by the embedded Kotlin compiler. " +
        "Please use JDK 17-29. Current JDK: ${System.getProperty("java.version")}"
    )
}

val releasedVersion = (findProperty("version") as String?) ?: "0.1.0"

allprojects {
    repositories {
        mavenCentral()
    }
    group = "io.github.wangzhizhou"
    version = releasedVersion
}

// detekt config is per-module (app and core both have detekt plugin)
