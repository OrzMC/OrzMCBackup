import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

plugins {
    kotlin("jvm")
    `maven-publish`
    signing
    `java-test-fixtures`
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.kotlinx.kover")
}


// Use current JDK; no enforced toolchain to ease local builds

dependencies {
    implementation(kotlin("stdlib"))
    api("org.lz4:lz4-java:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:6.1.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events(
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_OUT,
            org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR
        )
    }
}

// Test resources are expected under src/test/resources/Fixtures committed to VCS

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.register<JavaExec>("printTestPaths") {
    group = "verification"
    description = "Print TestPaths.world() and existence for CI debugging"
    mainClass.set("com.jokerhub.orzmc.util.PrintTestPathsKt")
    classpath = sourceSets["test"].runtimeClasspath
    isIgnoreExitValue = true
}

tasks.register<Jar>("javadocJar") {
    dependsOn("dokkaHtml")
    from(layout.buildDirectory.dir("dokka/html"))
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "io.github.wangzhizhou"
            artifactId = "backup-core"
            artifact(tasks.named("javadocJar"))

            pom {
                name.set("OrzMC Backup Core")
                description.set("Core library for optimizing Minecraft Java worlds")
                url.set("https://github.com/OrzGeeker/OrzMCBackup")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("orzmc")
                        name.set("wangzhizhou")
                        email.set("824219521@qq.com")
                    }
                }
                scm {
                    url.set("https://github.com/OrzGeeker/OrzMCBackup")
                    connection.set("scm:git:https://github.com/OrzGeeker/OrzMCBackup.git")
                    developerConnection.set("scm:git:ssh://git@github.com/OrzGeeker/OrzMCBackup.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "portalRepo"
            url = uri(layout.buildDirectory.dir("portal-repo").map { it.asFile.toURI().toString() }.get())
        }
    }
}

signing {
    val keyId = (findProperty("signing.keyId") as String?)
    val password = (findProperty("signing.password") as String?)
    val key = (findProperty("signing.key") as String?)
    val normalizedKeyId = keyId?.let {
        val s = it.removePrefix("0x")
        val hex40 = Regex("^[0-9A-Fa-f]{40}$")
        val hex8 = Regex("^[0-9A-Fa-f]{8}$")
        when {
            hex40.matches(s) -> s.takeLast(8).uppercase()
            hex8.matches(s) -> s.uppercase()
            else -> it
        }
    }
    if (!key.isNullOrBlank()) {
        val decoded = runCatching {
            String(Base64.getDecoder().decode(key), Charsets.UTF_8)
        }.getOrElse { key }
        useInMemoryPgpKeys(normalizedKeyId, decoded, password)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.register<Zip>("portalBundle") {
    dependsOn("publishMavenJavaPublicationToPortalRepoRepository")
    from(layout.buildDirectory.dir("portal-repo"))
    archiveFileName.set("portal-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}
