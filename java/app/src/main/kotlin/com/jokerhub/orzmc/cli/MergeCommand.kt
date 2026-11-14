package com.jokerhub.orzmc.cli

import com.jokerhub.orzmc.world.*
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "merge",
    description = [
        "Merge an OrzMCBackup-optimized world back onto a full world backup.",
        "Overlays the optimized (newer) chunk data onto the full backup at chunk-slot " +
            "granularity; pruned slots are filled from the full backup so nothing is lost.",
    ],
    mixinStandardHelpOptions = true,
    versionProvider = BuildVersionProvider::class,
)
class MergeCommand : Callable<Int> {
    var logger: LoggerSink = ConsoleLoggerSink()

    @Parameters(index = "0", description = ["Full world backup (older, complete)"], paramLabel = "BASE")
    lateinit var base: Path

    @Parameters(index = "1", description = ["Optimized world (newer, partial)"], paramLabel = "PATCH")
    lateinit var patch: Path

    @Parameters(index = "2", description = ["Output directory"], paramLabel = "OUTPUT")
    lateinit var output: Path

    @Option(
        names = ["-f", "--force"],
        description = ["Force overwrite OUTPUT if it exists"],
        defaultValue = "false",
    )
    var force: Boolean = false

    @Option(
        names = ["--progress-mode"],
        description = ["Progress display: Off | Global | Region"],
        defaultValue = "Off",
    )
    lateinit var progressMode: ProgressMode

    @Option(
        names = ["--parallelism"],
        description = ["Parallel region merge threads (1 = sequential)"],
        defaultValue = "1",
    )
    var parallelism: Int = 1

    @Option(
        names = ["--no-fsync"],
        description = ["Skip per-region fsync after finalize (faster, less durable)"],
        defaultValue = "false",
    )
    var noFsync: Boolean = false

    @Option(
        names = ["--progress-interval"],
        description = ["Progress callback interval (files)"],
        defaultValue = "1000",
    )
    var progressInterval: Long = 1000

    @Option(names = ["--report"], description = ["Print summary report"], defaultValue = "false")
    var report: Boolean = false

    @Option(
        names = ["--dry-run"],
        description = ["Preview the slot-level merge decision and write nothing"],
        defaultValue = "false",
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--diff-file"],
        description = ["Write a chunk-level diff of the merge decision (see --diff-format)"],
        required = false,
    )
    var diffFile: Path? = null

    @Option(
        names = ["--diff-format"],
        description = ["Diff format: csv | json | text"],
        defaultValue = "csv",
    )
    var diffFormat: String = "csv"

    @Option(
        names = ["--diff-include-patch"],
        description = ["Also emit diff rows for chunks taken from PATCH (much larger output)"],
        defaultValue = "false",
    )
    var diffIncludePatch: Boolean = false

    @Option(
        names = ["--diff-limit"],
        description = ["Hard cap on emitted diff rows (0 = unlimited; counts stay exact)"],
        defaultValue = "0",
    )
    var diffLimit: Int = 0

    @Option(names = ["--report-file"], description = ["Write report to file (JSON/CSV)"], required = false)
    var reportFile: Path? = null

    @Option(names = ["--report-format"], description = ["Report format: json | csv"], defaultValue = "json")
    var reportFormat: String = "json"

    override fun call(): Int =
        try {
            val progressPrinter: ((ProgressEvent) -> Unit)? =
                when (progressMode) {
                    ProgressMode.Off -> null
                    else -> { e ->
                        when (e.stage) {
                            ProgressStage.Init -> logger.info("开始合并")
                            ProgressStage.CopyMisc -> logger.info("覆盖/合并优化备份文件")
                            ProgressStage.CopyMiscProgress -> {
                                val cur = e.current ?: 0
                                val tot = e.total ?: 0
                                if (progressMode == ProgressMode.Global) {
                                    val percent = if (tot > 0) (cur * 100) / tot else 0
                                    logger.info("进度：$percent%（$cur/$tot）")
                                }
                            }
                            ProgressStage.Done -> {
                                val cur = e.current ?: 0
                                logger.info("完成：$cur")
                            }
                            else -> {}
                        }
                    }
                }
            val progressSink = progressPrinter?.let { CallbackProgressSink(it) } ?: NoopProgressSink
            val request =
                MergeRequest(
                    base = base,
                    patch = patch,
                    output = output,
                    outputOptions = OutputOptions(force = force, copyMisc = true),
                    progress = ProgressOptions(interval = progressInterval, sink = progressSink),
                    runtime = RuntimeOptions(parallelism = parallelism),
                    hooks = Hooks(),
                    io = IOOptions(syncOnFinalize = !noFsync),
                )
            val r = runMergeOrDryRun(request)
            if (report) logger.info(MergeReportIO.toText(r))
            reportFile?.let { path ->
                MergeReportIO.write(r, path, reportFormat)
                logger.info("报告已写入：$path")
            }
            if (r.errors.isNotEmpty()) 1 else 0
        } catch (e: OptimizeException) {
            logger.error(e.message ?: "发生错误")
            1
        } catch (e: Exception) {
            logger.error("发生错误：" + (e.message ?: e.toString()))
            1
        }

    /**
     * Runs the merge, unless `--dry-run` is set.
     *
     * A dry run (or `--diff-file` alone) first computes the read-only slot-level diff, then:
     * `--dry-run` stops there (nothing is created on disk), `--diff-file` writes the diff and
     * the real merge continues, so the audit file describes exactly what is about to happen.
     */
    private fun runMergeOrDryRun(request: MergeRequest): MergeReport {
        if (!dryRun && diffFile == null) return WorldMerger.run(request)
        val diff =
            WorldMerger.dryRun(
                request,
                MergeDiffOptions(includePatchRows = diffIncludePatch, maxRows = diffLimit),
            )
        if (report || dryRun) logger.info(MergeDiffIO.toText(diff))
        diffFile?.let { path ->
            MergeDiffIO.write(diff, path, diffFormat)
            logger.info("差异清单已写入：$path")
        }
        if (dryRun) return MergeReport(errors = diff.errors)
        return WorldMerger.run(request)
    }
}
