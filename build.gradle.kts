plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
    id("org.jetbrains.kotlinx.kover") version "0.8.3" apply false
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
