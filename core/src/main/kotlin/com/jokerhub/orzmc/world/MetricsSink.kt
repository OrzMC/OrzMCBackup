package com.jokerhub.orzmc.world

/** Collects performance metrics during optimization. */
interface MetricsSink {
    fun incProcessed(n: Long)
    fun incRemoved(n: Long)
    fun recordError(error: OptimizeError)
}

/** A [MetricsSink] that discards all metrics. */
class NoopMetricsSink : MetricsSink {
    override fun incProcessed(n: Long) {}
    override fun incRemoved(n: Long) {}
    override fun recordError(error: OptimizeError) {}
}
