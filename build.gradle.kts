import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "network.somikyy"

// Read from Build.java rather than repeated here. The offline build script already reads that
// file, and the release workflow checks the git tag against it - three places that must agree,
// so there is exactly one place to change. A jar whose manifest says one version while the code
// reports another is a bug report nobody can make sense of.
version = file("src/main/java/network/somikyy/sntelegram/core/Build.java")
    .readLines()
    .first { it.contains("VERSION = \"") }
    .substringAfter("VERSION = \"")
    .substringBefore('"')
description = "Two-way chat bridge between a Minecraft server and Telegram, with moderation from the chat itself"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Which server API to compile against. The default is the oldest supported line, because that is
// what catches "this method does not exist yet"; CI additionally compiles against 26.2 to catch
// the opposite - "this method no longer exists". Paper 26.2 ships Java 25 class files, so that
// build needs a Java 25 compiler even though the output stays Java 17.
val paperApi = (findProperty("paperApi") as String?) ?: "1.21.4-R0.1-SNAPSHOT"
val compilerJava = if (paperApi.startsWith("26.")) 25 else 21

dependencies {
    // The ONLY dependency, and it is compile-only: the server supplies these classes.
    // SNTelegram ships zero runtime dependencies on purpose. Every bridge that came before it
    // shades either a Telegram library or a JSON one, and a shaded library on someone else's
    // classpath is the most common way a plugin breaks a server it was supposed to help.
    // The whole Bot API client is about two hundred lines on java.net.http; the JSON reader is
    // another two hundred. That is a better trade than two megabytes of someone else's code.
    compileOnly("io.papermc.paper:paper-api:$paperApi")
}

// paper-api 1.21.4 ships Java 21 class files (major version 65). Gradle will not put a Java 21
// library on a classpath it believes targets Java 17, and `options.release` below is exactly what
// tells it we target 17 - so raising the toolchain alone does not help, the release flag
// re-declares 17 and resolution fails again with "looking for a library compatible with JVM
// runtime version 17".
//
// The two requirements are not actually in conflict. javac reads Java 21 class files off the
// classpath perfectly well while emitting Java 17 bytecode; only Gradle's variant matching
// objects. paper-api is compileOnly and never reaches anyone's runtime, so what the compile
// classpath is allowed to contain and what we emit are separate questions. Say so.
configurations.named("compileClasspath") {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, compilerJava)
    }
}

java {
    // The COMPILER is 21, the OUTPUT is 17 - see options.release below.
    // .set() rather than `=`: assignment to a Property works only in newer Kotlin DSL, and a
    // build file that fails to parse on someone's Gradle is a support ticket for nothing.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(compilerJava))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")

    // Java 17 bytecode rather than 21 or 25. 26.2 itself requires Java 25, but a bridge that also
    // runs on a 1.21.x server on Java 17 reaches the servers that have not moved - which, in the
    // Russian segment this plugin is written for, is most of them.
    options.release.set(17)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "SNTelegram",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Somikyy Network",
        )
    }
    archiveFileName.set("SNTelegram-${project.version}.jar")
}

// Convenience: gradle selftest  (needs bash; runs the dependency-free, network-free suite)
tasks.register<Exec>("selftest") {
    group = "verification"
    description = "Builds offline against the stubs and asserts the whole bridge, with no network"
    commandLine("bash", "tools/offline/verify.sh")
}
