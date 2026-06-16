plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("com.gradleup.shadow") version "9.4.2" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8" apply false
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
