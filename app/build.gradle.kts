import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.6")
    testImplementation(testFixtures(project(":core")))
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.1.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.0")
}

application {
    mainClass.set("com.jokerhub.orzmc.cli.Main")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.test {
    useJUnitPlatform()
}


tasks.matching { it.name == "shadowJar" }.configureEach {
    (this as com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar).apply {
        archiveBaseName.set("backup")
        archiveClassifier.set("")
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "com.jokerhub.orzmc.cli.Main",
                    "Implementation-Version" to project.version.toString()
                )
            )
        }
    }
}
