import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.etran"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    // Compiled against the OLDEST supported API (1.8.8) on purpose: anything that compiles here
    // exists on every newer version too. Version-specific things (materials, enchants) are
    // resolved by name at runtime in util/Items.kt instead of referenced directly.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") {
        // The 1.8 API pins a bungeecord-chat SNAPSHOT that is no longer published anywhere.
        // This plugin never touches chat components, so it is safe to leave out of the compile classpath.
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }

    // BedWars1058 is accessed through reflection (see bedwars/BedWarsHook.kt), so there is no
    // compile-time dependency on its API artifact.
    implementation(kotlin("stdlib"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveClassifier.set("")
        // Spigot 1.8 - 1.15 cannot load libraries from plugin.yml, so the Kotlin stdlib is shaded
        // and relocated (avoids clashing with other Kotlin plugins on the same server).
        val base = "dev.etran.bedwars1058.arenamanagement.libs"
        relocate("kotlin", "$base.kotlin")
        relocate("org.jetbrains", "$base.jetbrains")
        relocate("org.intellij", "$base.intellij")
    }

    build {
        dependsOn(shadowJar)
    }
}
