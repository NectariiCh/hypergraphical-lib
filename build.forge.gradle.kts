plugins {
    id("net.neoforged.moddev.legacyforge")
}

apply(from = rootProject.file("build.common.gradle.kts"))

val modId = property("mod_id") as String

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

legacyForge {
    version = "${property("minecraft_version")}-${property("forge_version")}"

    runs {
        configureEach {
            gameDirectory = rootProject.file("run")
            systemProperty("forge.logging.markers", "")
            systemProperty("forge.logging.console.level", "debug")
            // Our renderer talks to raw GL; this surfaces driver errors immediately in dev.
            systemProperty("hypergraphical.glDebug", "true")
            systemProperty("forge.enabledGameTestNamespaces", modId)
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
            gameDirectory = rootProject.file("run-data")
            programArguments.addAll(
                "--mod", modId, "--all",
                "--output", rootProject.file("src/generated/resources").absolutePath,
                "--existing", rootProject.file("src/main/resources").absolutePath,
            )
        }
    }

    mods { register(modId) { sourceSet(sourceSets.main.get()) } }
}

// Mods the dev run needs, kept out of the published metadata.
//
// Embeddium is a mandatory client dependency, but declaring it as runtimeOnly would put it in
// our runtimeElements, so every consumer (Create: Flippies included) would inherit it. mods.toml
// already states the requirement, and that is what the loader enforces. So: a configuration
// of our own, added to the run classpath and nothing else. MDG's remapping wrapper around it
// (modDevRuntime) turns the published SRG jar into official names, the job fg.deobf used to do.
// MDG also sets the mixin refmap remapping properties on every run, so Embeddium's mixins find
// their targets without the hand-written mixin.env.* properties.
val devRuntime: Configuration = configurations.create("devRuntime")
configurations.runtimeClasspath { extendsFrom(devRuntime) }
val modDevRuntime: Configuration = obfuscation.createRemappingConfiguration(devRuntime)

dependencies {
    // Neither mod is on the compile classpath: Embeddium is reached through ModList and
    // Oculus through reflection on IrisApi (see the ...hypergraphical.compat package), which
    // keeps our build independent of two fast-moving APIs.
    modDevRuntime("maven.modrinth:embeddium:${property("embeddium_version")}")
    if (property("dev_with_oculus").toString().toBoolean()) {
        modDevRuntime("maven.modrinth:oculus:${property("oculus_version")}")
    }
}

tasks.processResources {
    exclude("META-INF/neoforge.mods.toml")
}

tasks.named("createMinecraftArtifacts") { dependsOn("stonecutterGenerate") }

// ---------------------------------------------------------------------------
// Offline validation tools (this node only)
// ---------------------------------------------------------------------------
//
// These live in their own source set so they never reach the mod jar, and they run against
// the real main classpath so they exercise the same code and the same LWJGL the game does.
// tools/ sits outside src/, so Stonecutter does not preprocess it; it is written against
// 1.20.1 and wired only here.
//
//   gradlew :1.20.1-forge:checkShaders   compile + link every GLSL program in a real GL 3.3
//                                        core context
//   gradlew :1.20.1-forge:checkObj       run the OBJ importer over the shipped test assets and
//                                        assert index ranges, sub-mesh coverage, unit normals,
//                                        orthogonal tangents and face winding
//   gradlew :1.20.1-forge:checkAo        run the ambient occlusion bake over synthetic scenes:
//                                        BVH against brute force, contact shadows where
//                                        expected, determinism, a 100k-triangle timing, and
//                                        MeshInfo elements
//   gradlew :1.20.1-forge:checkAssets    all of the above
//
// checkShaders needs a GPU, so it is not wired into `check`; a headless machine would fail
// it for the wrong reason.

val main: SourceSet = sourceSets.main.get()
val tools: SourceSet = sourceSets.create("tools") {
    java.setSrcDirs(listOf(rootProject.file("tools")))
    compileClasspath += main.compileClasspath + main.output
    runtimeClasspath += main.runtimeClasspath + main.output
}
val assetsDir = rootProject.file("src/main/resources/assets/hypergraphical").absolutePath

tasks.register<JavaExec>("checkShaders") {
    group = "verification"
    description = "Compiles and links every Hypergraphical GLSL program on the real driver."
    classpath = tools.runtimeClasspath
    mainClass = "ShaderCheck"
    // macOS only lets GLFW run on the process's first thread
    if (System.getProperty("os.name").lowercase().contains("mac")) jvmArgs("-XstartOnFirstThread")
    args(assetsDir)
}

tasks.register<JavaExec>("checkObj") {
    group = "verification"
    description = "Validates the OBJ importer against the shipped test assets."
    classpath = tools.runtimeClasspath
    mainClass = "ObjCheck"
    args(assetsDir)
}

tasks.register<JavaExec>("checkAo") {
    group = "verification"
    description = "Validates the ambient occlusion bake and mesh element introspection."
    classpath = tools.runtimeClasspath
    mainClass = "AoCheck"
    args(assetsDir)
}

tasks.register("checkAssets") {
    group = "verification"
    description = "Runs the shader, OBJ and occlusion validators."
    dependsOn("checkObj", "checkAo", "checkShaders")
}
