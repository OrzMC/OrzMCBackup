package com.jokerhub.orzmc.world

import java.nio.file.Path

/**
 * Interface for the world optimization engine.
 * Implementations scan Minecraft world directories, process MCA region/entities/poi files,
 * and remove chunks that fall below the configured thresholds.
 *
 * The default implementation is [DefaultOptimizer].
 *
 * Use this interface for injection/mock/testing scenarios.
 * For simple use, call [Optimizer.run] directly (backward-compatible static access).
 */
interface OptimizerEngine {
    fun run(request: OptimizerRequest): OptimizeReport

    fun run(
        input: Path,
        output: Path? = null,
        block: OptimizerRequestBuilder.() -> Unit = {},
    ): OptimizeReport
}
