package com.jokerhub.orzmc.world

import java.nio.file.Path

/**
 * Parser for Minecraft force-loaded chunk lists.
 *
 * Supports two file locations (checked in order of priority):
 *  1. `data/minecraft/chunk_tickets.dat` — Minecraft 26.1+ world format
 *  2. `data/chunks.dat` — legacy format
 *
 * Within each file, two NBT structures are supported:
 *  - `data.Forced` (LongArray) — legacy packed 64-bit x/z pairs
 *  - `data.tickets[].chunk_pos` (IntArray[2]) — modern format with `minecraft:forced` type
 */
object ForceLoad {
    private val FILE_PATHS =
        listOf(
            "data/minecraft/chunk_tickets.dat",
            "data/chunks.dat",
        )

    /** Parse force-loaded chunk data in [dimension], returning global (x, z) coordinate pairs. */
    fun parse(
        dimension: Path,
        strict: Boolean,
    ): List<Pair<Int, Int>> {
        for (relPath in FILE_PATHS) {
            val f = dimension.resolve(relPath).toFile()
            if (f.isFile) {
                return try {
                    NbtForceLoader.parse(f)
                } catch (e: Exception) {
                    if (strict) {
                        throw ForceLoadedParseException(
                            "Failed to parse force-loaded chunk list: $f",
                            e,
                        )
                    } else {
                        emptyList()
                    }
                }
            }
        }
        return emptyList()
    }
}
