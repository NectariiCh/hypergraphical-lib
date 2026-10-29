pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// One shared src/ preprocessed per node. Node names match Create: Flippies, which maps
// dev.nectariich.hypergraphical:hypergraphical-<loader> onto these projects when it
// includes this build.
stonecutter {
    create(rootProject) {
        versions("1.20.1-forge" to "1.20.1").buildscript("build.forge.gradle.kts")
        versions("1.21.1-neoforge" to "1.21.1").buildscript("build.neoforge.gradle.kts")
        // The source committed to git is the Forge 1.20.1 form. Run "Reset active project"
        // before committing.
        vcsVersion = "1.20.1-forge"
    }
}

rootProject.name = "hypergraphical"
