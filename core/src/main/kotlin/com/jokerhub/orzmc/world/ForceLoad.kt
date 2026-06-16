package com.jokerhub.orzmc.world

import java.nio.file.Path

/** Parser for Minecraft force-loaded chunk lists from `data/chunks.dat`. */
object ForceLoad {
    /** Parse the chunks.dat file in [dimension], returning global (x, z) coordinate pairs. */
    fun parse(dimension: Path, strict: Boolean): List<Pair<Int, Int>> {
        val f = dimension.resolve("data").resolve("chunks.dat").toFile()
        if (!f.isFile) return emptyList()
        return try { NbtForceLoader.parse(f) } catch (e: Exception) {
            if (strict) throw ForceLoadedParseException("Failed to parse force-loaded chunk list: ${f}", e) else emptyList()
        }
    }
}
