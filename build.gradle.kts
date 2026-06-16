plugins {
    kotlin("jvm") version "1.9.24" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
    id("org.jetbrains.kotlinx.kover") version "0.7.5" apply false
}

// Ensure JDK 21+ for Kotlin compiler compatibility
val jvmVersion = System.getProperty("java.version")?.substringBefore(".")?.toIntOrNull() ?: 0
if (jvmVersion >= 25) {
    throw GradleException(
        "JDK 25+ is not yet supported by the embedded Kotlin compiler. " +
        "Please use JDK 21-24. Current JDK: ${System.getProperty("java.version")}"
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
