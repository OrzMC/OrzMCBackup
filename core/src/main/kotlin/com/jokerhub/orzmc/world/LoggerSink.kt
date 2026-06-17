package com.jokerhub.orzmc.world

/** Receiver for log messages at various severity levels. */
interface LoggerSink {
    fun info(msg: String)

    fun warn(msg: String)

    fun error(msg: String)
}

/** A [LoggerSink] that prints to stdout (info) and stderr (warn/error). */
class ConsoleLoggerSink : LoggerSink {
    override fun info(msg: String) {
        println(msg)
    }

    override fun warn(msg: String) {
        System.err.println(msg)
    }

    override fun error(msg: String) {
        System.err.println(msg)
    }
}
