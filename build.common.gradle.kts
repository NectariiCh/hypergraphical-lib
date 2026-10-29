// Build logic both nodes share. Applied by build.forge.gradle.kts and
// build.neoforge.gradle.kts after their loader plugin, so the java plugin is already present.
//
// A script plugin gets no type-safe accessors, hence the<...>() and configure<...>.

val modId = property("mod_id") as String
val loader = project.name.substringAfterLast('-')

apply(plugin = "maven-publish")

// Coordinates are dev.nectariich.hypergraphical:hypergraphical-<loader>:0.1.0+mc<version>.
// Create: Flippies substitutes exactly these onto the node projects.
group = property("mod_group") as String
version = "${property("mod_version")}+mc${property("minecraft_version")}"
the<BasePluginExtension>().archivesName = "$modId-$loader"

configure<JavaPluginExtension> {
    withSourcesJar()
}

the<SourceSetContainer>().named("main") {
    resources.srcDir(rootProject.file("src/generated/resources"))
}

repositories {
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

tasks.named<ProcessResources>("processResources") {
    // Each node has only some of these (forge_version_range on Forge, sodium_version_range on
    // NeoForge), and each excludes the other loader's mods.toml, so expand never sees a
    // token it has no value for.
    val replacements = listOf(
        "mod_id", "mod_name", "mod_version", "mod_authors", "mod_license", "mod_description",
        "minecraft_version_range", "loader_version_range", "pack_format",
        "forge_version_range", "embeddium_version_range", "oculus_version_range",
        "neoforge_version_range", "sodium_version_range", "iris_version_range",
    ).mapNotNull { key -> project.findProperty(key)?.let { key to it.toString() } }.toMap()
    inputs.properties(replacements)
    filesMatching(listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "pack.mcmeta")) {
        expand(replacements)
    }
}

val modAuthors = property("mod_authors") as String
val modName = property("mod_name") as String
val modVersion = property("mod_version") as String

tasks.named<Jar>("jar") {
    manifest.attributes(
        "Specification-Title" to modId,
        "Specification-Vendor" to modAuthors,
        "Specification-Version" to "1",
        "Implementation-Title" to modName,
        "Implementation-Version" to modVersion,
        "Implementation-Vendor" to modAuthors,
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all,-processing,-serial")
}

configure<PublishingExtension> {
    publications.register<MavenPublication>("mavenJava") {
        from(components["java"])
        artifactId = "$modId-$loader"
    }
    repositories { maven(rootProject.file("mcmodsrepo")) }
}
