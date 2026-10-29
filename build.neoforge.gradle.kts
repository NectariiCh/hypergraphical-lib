plugins {
    id("net.neoforged.moddev")
}

apply(from = rootProject.file("build.common.gradle.kts"))

val modId = property("mod_id") as String

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = property("neoforge_version") as String

    runs {
        configureEach {
            gameDirectory = rootProject.file("run/1.21.1-neoforge")
            systemProperty("forge.logging.markers", "")
            systemProperty("forge.logging.console.level", "debug")
            // Our renderer talks to raw GL; this surfaces driver errors immediately in dev.
            systemProperty("hypergraphical.glDebug", "true")
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }
        register("client") { client() }
        register("server") {
            server()
            programArgument("--nogui")
        }
        // Headless smoke test: loads the mod on a server and exits, no EULA prompt.
        register("gameTestServer") { type = "gameTestServer" }
        register("data") {
            data()
            gameDirectory = rootProject.file("run-data/1.21.1-neoforge")
            programArguments.addAll(
                "--mod", modId, "--all",
                "--output", rootProject.file("src/generated/resources").absolutePath,
                "--existing", rootProject.file("src/main/resources").absolutePath,
            )
        }
    }

    mods { register(modId) { sourceSet(sourceSets.main.get()) } }
}

// Mods the dev run needs, kept out of the published metadata (see build.forge.gradle.kts).
// NeoForge 1.21.1 runs Mojang names in production, so no remapping is needed.
val devRuntime: Configuration = configurations.create("devRuntime")
configurations.runtimeClasspath { extendsFrom(devRuntime) }

dependencies {
    // Sodium is reached through ModList and Iris through reflection on IrisApi, as on 1.20.1.
    devRuntime("maven.modrinth:sodium:${property("sodium_version")}")
    if (property("dev_with_oculus").toString().toBoolean()) {
        devRuntime("maven.modrinth:iris:${property("iris_version")}")
    }
}

tasks.processResources {
    exclude("META-INF/mods.toml")
    // The reference model names Forge's OBJ loader; NeoForge registers the same loader as
    // neoforge:obj.
    filesMatching("assets/$modId/models/block/forge_obj_example.json") {
        filter { line -> line.replace("\"forge:obj\"", "\"neoforge:obj\"") }
    }
}

tasks.named("createMinecraftArtifacts") { dependsOn("stonecutterGenerate") }
