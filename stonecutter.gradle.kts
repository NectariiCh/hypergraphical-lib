plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.148" apply false
    id("net.neoforged.moddev.legacyforge") version "2.0.148" apply false
}

stonecutter active "1.20.1-forge"

stonecutter parameters {
    // Boolean constants for //? if forge and //? if neoforge
    constants.match(current.project.substringAfterLast('-'), "forge", "neoforge")
    // Whole-file, reversible renames. src/ is written in the 1.20.1 form.
    replacements.string(current.parsed >= "1.21") {
        replace("ForgeConfigSpec", "ModConfigSpec")
        replace("import net.minecraftforge.fml.ModList;", "import net.neoforged.fml.ModList;")
    }
}
