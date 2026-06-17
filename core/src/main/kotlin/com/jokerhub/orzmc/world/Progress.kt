package com.jokerhub.orzmc.world

/** Phases of the optimization pipeline, emitted as [ProgressEvent] values. */
enum class ProgressStage {
    /** Initialization */
    Init,

    /** Discovering dimensions */
    Discover,

    /** Processing started for one dimension */
    DimensionStart,

    /** Processing started for one region file */
    RegionStart,

    /** Chunk-level progress update */
    ChunkProgress,

    /** Processing finished for one dimension */
    DimensionEnd,

    /** Finalizing writes */
    Finalize,

    /** Copying miscellaneous (non-MCA) files */
    CopyMisc,

    /** Progress update during misc file copy */
    CopyMiscProgress,

    /** Compressing output to zip archive */
    Compress,

    /** Cleaning up temporary files */
    Cleanup,

    /** Processing complete */
    Done,
}

/**
 * Event emitted during optimization progress.
 * @param stage current pipeline phase
 * @param current number of items processed so far
 * @param total total items expected
 * @param path related file or directory path
 * @param message human-readable description
 */
data class ProgressEvent(
    val stage: ProgressStage,
    val current: Long? = null,
    val total: Long? = null,
    val path: String? = null,
    val message: String? = null,
)
