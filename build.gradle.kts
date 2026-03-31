plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

group = "us.ascend-tech"

defaultTasks("buildNative", "build")

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// ── Base JAR: Java API only, no native libraries ───────────────────
tasks.jar {
    exclude("native/**")
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

// ── Per-platform native JARs ───────────────────────────────────────
val platforms = listOf("linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64", "windows-x86_64")

val nativeJarTasks = platforms.map { platform ->
    tasks.register<Jar>("nativeJar-$platform") {
        archiveClassifier = platform
        from("src/main/resources") {
            include("native/$platform/**")
        }
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }
}

// ── Maven Central Publishing ───────────────────────────────────────
mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), "primme-ffm-java", version.toString())

    pom {
        name = "PRIMME FFM Java"
        description = "Java FFM bindings for the PRIMME eigenvalue/SVD solver"
        url = "https://github.com/ascendtech/primme-ffm-java"
        licenses {
            license {
                name = "BSD-3-Clause"
                url = "https://opensource.org/licenses/BSD-3-Clause"
            }
        }
        developers {
            developer {
                id = "ascendtech"
                name = "Ascend Technologies"
                url = "https://ascendtech.us"
            }
        }
        scm {
            connection = "scm:git:https://github.com/ascendtech/primme-ffm-java.git"
            developerConnection = "scm:git:ssh://git@github.com/ascendtech/primme-ffm-java.git"
            url = "https://github.com/ascendtech/primme-ffm-java"
        }
    }
}

// Attach native JARs to the publication
afterEvaluate {
    publishing {
        publications.withType<MavenPublication> {
            nativeJarTasks.forEach { task ->
                artifact(task)
            }
        }
    }
}

// ── Local native build ─────────────────────────────────────────────
tasks.register<Delete>("cleanNative") {
    description = "Removes built native libraries and build cache, forcing a rebuild"
    group = "build"
    delete(layout.projectDirectory.dir("src/main/resources/native"))
    delete(layout.projectDirectory.dir(".native-build"))
}

tasks.register<Exec>("buildNative") {
    description = "Builds the PRIMME native library for the current platform"
    group = "build"

    val nativeDir = layout.projectDirectory.dir("src/main/resources/native")

    onlyIf {
        !nativeDir.asFile.exists() || !nativeDir.asFile.walk()
            .any { it.name.endsWith(".so") || it.name.endsWith(".dylib") || it.name.endsWith(".dll") }
    }

    workingDir = layout.projectDirectory.asFile
    commandLine("bash", "./build-native.sh")
}

// ── Test configuration ─────────────────────────────────────────────
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
        }
    }
}
