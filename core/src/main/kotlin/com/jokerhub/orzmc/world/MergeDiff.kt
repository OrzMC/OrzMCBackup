package com.jokerhub.orzmc.world

import java.nio.file.Path
import java.util.regex.Pattern

/** Method used to derive the "patch era" reference timestamp reported in [MergeDiffReport]. */
const val MERGE_DIFF_PATCH_ERA_METHOD: String =
    "P90 of patch slot save timestamps (day buckets, end of day)"

/** Where a chunk slot of the merged output comes from. */
enum class SlotSource {
    /** Taken from the (newer) optimized backup. */
    PATCH,

    /** Filled from the (older) full backup — this chunk reverts to an earlier snapshot. */
    BASE,

    /** Absent in both worlds; the game regenerates it. */
    EMPTY,
}

/**
 * One chunk-level diff row.
 *
 * @property region region file path relative to the world root (forward slashes)
 * @property slot sector index inside the region file (0..1023)
 * @property chunkX global chunk X of the slot
 * @property chunkZ global chunk Z of the slot
 * @property baseTime save timestamp (epoch seconds) of the base copy, null when absent
 * @property patchTime save timestamp (epoch seconds) of the patch copy, null when absent
 * @property revertSeconds how much older the base copy is than the patch era reference,
 *   only set for [SlotSource.BASE] rows (negative means the base copy is not older)
 */
data class SlotDiffRow(
    val region: String,
    val slot: Int,
    val chunkX: Int,
    val chunkZ: Int,
    val source: SlotSource,
    val baseTime: Int?,
    val patchTime: Int?,
    val revertSeconds: Long?,
)

/**
 * Row-selection policy for a merge dry run.
 *
 * @property includePatchRows also emit rows for slots that come from patch (large)
 * @property includeEmptyRows also emit rows for slots present in neither world
 * @property maxRows hard cap on emitted rows (0 = unlimited); counts stay exact
 */
data class MergeDiffOptions(
    val includePatchRows: Boolean = false,
    val includeEmptyRows: Boolean = false,
    val maxRows: Int = 0,
)

/**
 * Result of a merge dry run: what a real merge would do, without writing anything.
 *
 * @property patchSlots slots that would be taken from patch (newest data preserved)
 * @property baseSlots slots that would be taken from base (newer data already lost)
 * @property emptySlots slots absent in both worlds (game will regenerate)
 * @property mergedRegions regions present in both worlds (slot-level merge)
 * @property patchOnlyRegions regions only in patch (whole file copied)
 * @property baseOnlyRegions regions only in base (carried over untouched)
 * @property unreadablePatchRegions patch region files that cannot be read
 *   (a real merge carries the base file over byte-for-byte for these)
 * @property unreadableBaseRegions base region files that cannot be read
 *   (a real merge keeps only the patch slots of these regions — a data gap)
 * @property patchEraEpochSeconds reference "now" of the patch world, see [patchEraMethod]
 * @property rows chunk-level rows selected by [MergeDiffOptions]
 * @property rowsTruncated true when [MergeDiffOptions.maxRows] cut the row list short
 */
data class MergeDiffReport(
    val patchSlots: Long = 0,
    val baseSlots: Long = 0,
    val emptySlots: Long = 0,
    val mergedRegions: Long = 0,
    val patchOnlyRegions: Long = 0,
    val baseOnlyRegions: Long = 0,
    val unreadablePatchRegions: List<String> = emptyList(),
    val unreadableBaseRegions: List<String> = emptyList(),
    val patchEraEpochSeconds: Long = 0,
    val patchEraMethod: String = MERGE_DIFF_PATCH_ERA_METHOD,
    val rows: List<SlotDiffRow> = emptyList(),
    val rowsTruncated: Boolean = false,
    val errors: List<OptimizeError> = emptyList(),
)

/**
 * Read-only preview of [DefaultMerger]'s slot-level merge decision.
 *
 * It performs the same base/patch/output alias guard, reads only region-file headers, and
 * never creates, deletes or modifies anything — [MergeRequest.output] may therefore point at
 * a directory that does not exist yet.
 */
object MergeDiff {
    private const val ERR_INPUT = "Input"
    private const val ERR_MCA = "MCA"
    private const val SECONDS_PER_DAY = 86_400L
    private val REGION_RE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca$")

    fun compute(
        request: MergeRequest,
        options: MergeDiffOptions = MergeDiffOptions(),
    ): MergeDiffReport {
        val fs = request.io.fs
        val errors = mutableListOf<OptimizeError>()

        fun record(
            path: Path,
            kind: String,
            msg: String,
        ) {
            errors.add(OptimizeError(path.toString(), kind, msg))
        }

        // Same alias guard as the real merge: overlapping directories are a user error even
        // for a dry run, because the equivalent command would be unsafe if executed.
        if (
            OverlapGuard.overlaps(fs, request.base, request.output) ||
            OverlapGuard.overlaps(fs, request.patch, request.output) ||
            OverlapGuard.overlaps(fs, request.base, request.patch)
        ) {
            record(
                request.base,
                ERR_INPUT,
                "base, patch and output must be three distinct, non-overlapping directories",
            )
            return MergeDiffReport(errors = errors)
        }
        if (!fs.isDirectory(request.base) || !fs.isDirectory(request.patch)) {
            record(request.base, ERR_INPUT, "base/patch must be existing directories")
            return MergeDiffReport(errors = errors)
        }

        val baseRegions = regionRels(fs, request.base)
        val patchRegions = regionRels(fs, request.patch)
        val ctx =
            DiffContext(
                fs = fs,
                ioFactory = request.io.ioFactory,
                options = options,
                patchEra = patchEraReference(fs, request.io.ioFactory, patchRegions),
                record = { p, k, m -> record(p, k, m) },
            )
        val acc = DiffAccumulator()
        for (rel in (baseRegions.keys + patchRegions.keys).sortedBy { it.toString() }) {
            diffRegion(rel, baseRegions[rel], patchRegions[rel], ctx, acc)
        }
        return acc.toReport(ctx.patchEra, errors)
    }

    private class DiffContext(
        val fs: FileSystem,
        val ioFactory: McaIOFactory,
        val options: MergeDiffOptions,
        val patchEra: Long,
        val record: (Path, String, String) -> Unit,
    )

    private class DiffAccumulator {
        var patchSlots = 0L
        var baseSlots = 0L
        var emptySlots = 0L
        var mergedRegions = 0L
        var patchOnlyRegions = 0L
        var baseOnlyRegions = 0L
        val unreadablePatch = mutableListOf<String>()
        val unreadableBase = mutableListOf<String>()
        val rows = mutableListOf<SlotDiffRow>()
        var truncated = false

        fun rowLimitReached(limit: Int): Boolean {
            if (limit > 0 && rows.size >= limit) {
                truncated = true
                return true
            }
            return false
        }

        fun toReport(
            patchEra: Long,
            errors: List<OptimizeError>,
        ): MergeDiffReport =
            MergeDiffReport(
                patchSlots = patchSlots,
                baseSlots = baseSlots,
                emptySlots = emptySlots,
                mergedRegions = mergedRegions,
                patchOnlyRegions = patchOnlyRegions,
                baseOnlyRegions = baseOnlyRegions,
                unreadablePatchRegions = unreadablePatch,
                unreadableBaseRegions = unreadableBase,
                patchEraEpochSeconds = patchEra,
                rows = rows,
                rowsTruncated = truncated,
                errors = errors,
            )
    }

    /** Classifies one region file pair and, when both sides exist, walks its 1024 slots. */
    private fun diffRegion(
        rel: Path,
        basePath: Path?,
        patchPath: Path?,
        ctx: DiffContext,
        acc: DiffAccumulator,
    ) {
        if (basePath != null && patchPath != null) {
            acc.mergedRegions += 1
            diffMergedRegion(rel, basePath, patchPath, ctx, acc)
        } else if (patchPath != null) {
            acc.patchOnlyRegions += 1
            openRegion(ctx, patchPath)?.use { acc.patchSlots += it.count() }
        } else if (basePath != null) {
            acc.baseOnlyRegions += 1
        }
    }

    private fun diffMergedRegion(
        rel: Path,
        basePath: Path,
        patchPath: Path,
        ctx: DiffContext,
        acc: DiffAccumulator,
    ) {
        val regionName = rel.toString().replace('\\', '/')
        val patchReader = openRegion(ctx, patchPath)
        if (patchReader == null) {
            // A real merge copies the base region (and its entities/poi siblings) verbatim.
            acc.unreadablePatch += regionName
            openRegion(ctx, basePath)?.use { acc.baseSlots += it.count() }
            return
        }
        patchReader.use { pr ->
            val baseReader = openRegion(ctx, basePath)
            if (baseReader == null) {
                // Known gap: the real merge keeps only the patch slots of this region.
                acc.unreadableBase += regionName
                acc.patchSlots += pr.count()
            } else {
                baseReader.use { br -> diffSlots(regionName, rel, pr, br, ctx, acc) }
            }
        }
    }

    private fun diffSlots(
        regionName: String,
        rel: Path,
        pr: McaReaderLike,
        br: McaReaderLike,
        ctx: DiffContext,
        acc: DiffAccumulator,
    ) {
        val coords = regionCoords(rel)
        val limit = ctx.options.maxRows
        for (i in 0 until 1024) {
            val pe = pr.get(i)
            val be = br.get(i)
            when {
                pe != null -> {
                    acc.patchSlots += 1
                    if (ctx.options.includePatchRows && !acc.rowLimitReached(limit)) {
                        acc.rows +=
                            slotRow(
                                regionName,
                                coords,
                                i,
                                SlotSource.PATCH,
                                be?.modifiedTime(),
                                pe.modifiedTime(),
                                null,
                            )
                    }
                }

                be != null -> {
                    acc.baseSlots += 1
                    val baseTime = be.modifiedTime()
                    if (!acc.rowLimitReached(limit)) {
                        acc.rows +=
                            slotRow(
                                regionName,
                                coords,
                                i,
                                SlotSource.BASE,
                                baseTime,
                                null,
                                revert(baseTime, ctx.patchEra),
                            )
                    }
                }

                else -> {
                    acc.emptySlots += 1
                    if (ctx.options.includeEmptyRows && !acc.rowLimitReached(limit)) {
                        acc.rows += slotRow(regionName, coords, i, SlotSource.EMPTY, null, null, null)
                    }
                }
            }
        }
    }

    private fun revert(
        baseTime: Int,
        patchEra: Long,
    ): Long? = if (patchEra > 0 && baseTime > 0) patchEra - baseTime else null

    private fun slotRow(
        region: String,
        coords: Pair<Int, Int>?,
        slot: Int,
        source: SlotSource,
        baseTime: Int?,
        patchTime: Int?,
        revert: Long?,
    ): SlotDiffRow =
        SlotDiffRow(
            region = region,
            slot = slot,
            chunkX = coords?.let { it.first * 32 + slot % 32 } ?: -1,
            chunkZ = coords?.let { it.second * 32 + slot / 32 } ?: -1,
            source = source,
            baseTime = baseTime,
            patchTime = patchTime,
            revertSeconds = revert,
        )

    private fun openRegion(
        ctx: DiffContext,
        path: Path,
    ): McaReaderLike? =
        try {
            if (ctx.fs.isRegularFile(path) && McaUtils.isValidMca(ctx.fs, path)) {
                ctx.ioFactory.openReader(ctx.fs, path)
            } else {
                ctx.record(path, ERR_MCA, "MCA file is corrupted or incomplete")
                null
            }
        } catch (e: Exception) {
            ctx.record(path, ERR_MCA, "Failed to read MCA file: ${e.message}")
            null
        }

    /** Region files directly under a `region/` directory, keyed by world-relative path. */
    private fun regionRels(
        fs: FileSystem,
        root: Path,
    ): Map<Path, Path> =
        fs
            .walk(root)
            .filter { p ->
                fs.isRegularFile(p) &&
                    p.fileName?.toString()?.endsWith(".mca") == true &&
                    p.parent?.fileName?.toString() == "region"
            }.associateBy { root.relativize(it) }

    private fun regionCoords(rel: Path): Pair<Int, Int>? {
        val m = REGION_RE.matcher(rel.fileName?.toString() ?: return null)
        if (!m.find()) return null
        val x = m.group(1).toIntOrNull() ?: return null
        val z = m.group(2).toIntOrNull() ?: return null
        return x to z
    }

    /**
     * Approximates "when the patch world was last active": the P90 of its slot save
     * timestamps, bucketed by day and rounded up to the end of that day. Using a high
     * percentile instead of the maximum keeps a handful of freshly generated chunks from
     * skewing the reference.
     */
    private fun patchEraReference(
        fs: FileSystem,
        ioFactory: McaIOFactory,
        patchRegions: Map<Path, Path>,
    ): Long {
        val histogram = HashMap<Long, Long>()
        var total = 0L
        for (path in patchRegions.values) {
            try {
                if (!fs.isRegularFile(path) || !McaUtils.isValidMca(fs, path)) continue
                ioFactory.openReader(fs, path).use { r ->
                    for (e in r.entries()) {
                        val t = e.modifiedTime().toLong()
                        if (t <= 0) continue
                        total += 1
                        val day = t / SECONDS_PER_DAY
                        histogram[day] = (histogram[day] ?: 0L) + 1
                    }
                }
            } catch (_: Exception) {
                // Unreadable region files are reported by the diff pass; skip them here.
            }
        }
        if (total == 0L) return 0
        val target = (total * 90 + 99) / 100
        var acc = 0L
        for (day in histogram.keys.sorted()) {
            acc += histogram.getValue(day)
            if (acc >= target) return (day + 1) * SECONDS_PER_DAY
        }
        return 0
    }
}
