package com.jokerhub.orzmc.world

import java.nio.file.Path

/** Writes an [OptimizeReport] to a destination. */
interface ReportSink {
    fun write(report: OptimizeReport)
}

/**
 * Writes reports to a file.
 * @param path output file path
 * @param format "json", "csv", or "text"
 */
class FileReportSink(
    private val path: Path,
    private val format: String = "json"
) : ReportSink {
    override fun write(report: OptimizeReport) {
        ReportIO.write(report, path, format)
    }
}

/** A [ReportSink] that discards reports. */
class NoopReportSink : ReportSink {
    override fun write(report: OptimizeReport) {}
}
