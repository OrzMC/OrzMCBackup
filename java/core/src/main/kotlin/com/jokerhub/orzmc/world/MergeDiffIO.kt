package com.jokerhub.orzmc.world

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Serializes a [MergeDiffReport] (merge dry-run preview) to CSV, JSON, or plain text.
 *
 * CSV is the default because the per-chunk rows are meant to be filtered and inspected
 * (Excel / `grep` / `awk`); JSON keeps the full summary including the unreadable-region lists.
 */
object MergeDiffIO {
    private const val CSV_HEADER =
        "region,slot,chunkX,chunkZ,source,baseEpoch,baseUtc,patchEpoch,patchUtc,revertSeconds,revertDays"

    fun toCsv(r: MergeDiffReport): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\n")
        r.rows.forEach { row ->
            sb
                .append(csv(row.region))
                .append(",")
                .append(row.slot)
                .append(",")
                .append(row.chunkX)
                .append(",")
                .append(row.chunkZ)
                .append(",")
                .append(row.source.name)
                .append(",")
                .append(row.baseTime?.toString() ?: "")
                .append(",")
                .append(utc(row.baseTime))
                .append(",")
                .append(row.patchTime?.toString() ?: "")
                .append(",")
                .append(utc(row.patchTime))
                .append(",")
                .append(row.revertSeconds?.toString() ?: "")
                .append(",")
                .append(days(row.revertSeconds))
                .append("\n")
        }
        return sb.toString()
    }

    fun toJson(r: MergeDiffReport): String {
        val sb = StringBuilder()
        sb
            .append("{\"patchSlots\":")
            .append(r.patchSlots)
            .append(",\"baseSlots\":")
            .append(r.baseSlots)
            .append(",\"emptySlots\":")
            .append(r.emptySlots)
            .append(",\"mergedRegions\":")
            .append(r.mergedRegions)
            .append(",\"patchOnlyRegions\":")
            .append(r.patchOnlyRegions)
            .append(",\"baseOnlyRegions\":")
            .append(r.baseOnlyRegions)
            .append(",\"patchEraEpochSeconds\":")
            .append(r.patchEraEpochSeconds)
            .append(",\"patchEraUtc\":\"")
            .append(utc(r.patchEraEpochSeconds.toInt().takeIf { it > 0 }))
            .append("\",\"patchEraMethod\":\"")
            .append(ReportIO.esc(r.patchEraMethod))
            .append("\",\"rowsTruncated\":")
            .append(r.rowsTruncated)
            .append(",\"unreadablePatchRegions\":")
            .append(strArray(r.unreadablePatchRegions))
            .append(",\"unreadableBaseRegions\":")
            .append(strArray(r.unreadableBaseRegions))
            .append(",\"errors\":")
            .append(errors(r.errors))
            .append(",\"rows\":[")
        r.rows.forEachIndexed { i, row ->
            if (i > 0) sb.append(",")
            sb
                .append("{\"region\":\"")
                .append(ReportIO.esc(row.region))
                .append("\",\"slot\":")
                .append(row.slot)
                .append(",\"chunkX\":")
                .append(row.chunkX)
                .append(",\"chunkZ\":")
                .append(row.chunkZ)
                .append(",\"source\":\"")
                .append(row.source.name)
                .append("\",\"baseEpoch\":")
                .append(row.baseTime ?: "null")
                .append(",\"baseUtc\":")
                .append(row.baseTime?.let { "\"${utc(it)}\"" } ?: "null")
                .append(",\"patchEpoch\":")
                .append(row.patchTime ?: "null")
                .append(",\"patchUtc\":")
                .append(row.patchTime?.let { "\"${utc(it)}\"" } ?: "null")
                .append(",\"revertSeconds\":")
                .append(row.revertSeconds ?: "null")
                .append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun toText(r: MergeDiffReport): String {
        val sb = StringBuilder()
        sb
            .append("Merge dry run (nothing written)\n")
            .append("  mergedRegions=")
            .append(r.mergedRegions)
            .append("  patchOnlyRegions=")
            .append(r.patchOnlyRegions)
            .append("  baseOnlyRegions=")
            .append(r.baseOnlyRegions)
            .append("\n  patchSlots=")
            .append(r.patchSlots)
            .append("  baseSlots(revert to older copy)=")
            .append(r.baseSlots)
            .append("  emptySlots=")
            .append(r.emptySlots)
            .append("\n  patchEra=")
            .append(if (r.patchEraEpochSeconds > 0) utc(r.patchEraEpochSeconds.toInt()) else "n/a")
            .append("  (")
            .append(r.patchEraMethod)
            .append(")")
            .append("\n  emittedRows=")
            .append(r.rows.size)
            .append(if (r.rowsTruncated) " (truncated by --diff-limit)" else "")
            .append("\n  errors=")
            .append(r.errors.size)
        appendRegionList(sb, "unreadable patch regions (whole base file carried over)", r.unreadablePatchRegions)
        appendRegionList(sb, "unreadable base regions (base slots will be missing)", r.unreadableBaseRegions)
        if (r.errors.isNotEmpty()) {
            sb.append("\nError list:\n")
            r.errors.forEach { e ->
                sb
                    .append("[")
                    .append(e.kind)
                    .append("] ")
                    .append(e.path)
                    .append(" - ")
                    .append(e.message)
                    .append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    fun write(
        r: MergeDiffReport,
        path: Path,
        format: String,
    ) {
        val content =
            when (format.lowercase()) {
                "json" -> toJson(r)
                "text", "txt" -> toText(r)
                else -> toCsv(r)
            }
        val parent = path.parent
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent)
        }
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun appendRegionList(
        sb: StringBuilder,
        label: String,
        regions: List<String>,
    ) {
        if (regions.isEmpty()) return
        sb
            .append("\n")
            .append("  ")
            .append(label)
            .append(": ")
            .append(regions.size)
        regions.take(20).forEach { sb.append("\n    ").append(it) }
        if (regions.size > 20) sb.append("\n    ...")
    }

    private fun strArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { "\"${ReportIO.esc(it)}\"" }

    private fun errors(errors: List<OptimizeError>): String =
        errors.joinToString(prefix = "[", postfix = "]") { e ->
            "{\"path\":\"${ReportIO.esc(e.path)}\",\"kind\":\"${ReportIO.esc(e.kind)}\"," +
                "\"message\":\"${ReportIO.esc(e.message)}\"}"
        }

    private fun days(seconds: Long?): String =
        seconds?.let { String.format(java.util.Locale.ROOT, "%.1f", it / 86_400.0) } ?: ""

    private fun csv(v: String): String = if (v.any { it == ',' || it == '"' }) "\"${v.replace("\"", "\"\"")}\"" else v

    private fun utc(epochSeconds: Int?): String =
        epochSeconds?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it.toLong()).toString() } ?: ""
}
