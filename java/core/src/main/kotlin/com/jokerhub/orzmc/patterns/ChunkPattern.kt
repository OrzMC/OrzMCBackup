package com.jokerhub.orzmc.patterns

import com.jokerhub.orzmc.mca.McaEntry

/**
 * A predicate that determines whether a chunk entry should be kept.
 *
 * Implementations inspect [McaEntry] data (coordinates, inhabitation time,
 * force-load status) to decide retention. Entries matching any pattern
 * in the active set are preserved; non-matching entries are removed.
 */
interface ChunkPattern {
    /** Returns true if [entry] should be kept during optimization. */
    fun matches(entry: McaEntry): Boolean
}
