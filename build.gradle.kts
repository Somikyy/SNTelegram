import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "network.somikyy"
version = "26.8.1"
description = "Two-way chat bridge between a Minecraft server and Telegram, with moderation from the chat itself"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The ONLY dependency, and it is compile-only: the server supplies these classes.
    // SNTelegram ships zero runtime dependencies on purpose. Every bridge that came before it
    // shades either a Telegram library or a JSON one, and a shaded library on someone else's
    // classpath is the most common way a plugin breaks a server it was supposed to help.
    // The whole Bot API client is about two hundred lines on java.net.http; the JSON reader is
    // another two hundred. That is a better trade than two megabytes of someone else's code.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
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
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

java {
    // The COMPILER is 21, the OUTPUT is 17 - see options.release below.
    // .set() rather than `=`: assignment to a Property works only in newer Kotlin DSL, and a
    // build file that fails to parse on someone's Gradle is a support ticket for nothing.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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
