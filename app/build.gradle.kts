import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // detekt 1.23.x requires JDK ≤ 21 for type-resolution compilation.
    // On JDK 25+ the task is skipped locally; CI runs JDK 21 so it always works.
    val jvmVersion = System.getProperty("java.version")?.substringBefore(".")?.toIntOrNull() ?: 0
    onlyIf("detekt requires JDK ≤ 21 (current: $jvmVersion)") { jvmVersion <= 21 }
}

// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.jokerhub.orzmc.cli.Main")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Jar>().named("shadowJar") {
    archiveBaseName.set("backup")
    archiveClassifier.set("")
    manifest {
        attributes(
            mapOf(
                "Main-Class" to "com.jokerhub.orzmc.cli.Main",
                "Implementation-Version" to project.version.toString(),
            ),
        )
    }
}
