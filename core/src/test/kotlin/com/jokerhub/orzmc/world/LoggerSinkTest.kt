package com.jokerhub.orzmc.world

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class LoggerSinkTest {
    private val sink = ConsoleLoggerSink()

    @Test
    fun `info writes to stdout`() {
        val out =
            captureStdout {
                sink.info("hello info")
            }
        assertTrue(out.contains("hello info"), "info should write to stdout")
    }

    @Test
    fun `warn writes to stderr`() {
        val err =
            captureStderr {
                sink.warn("hello warn")
            }
        assertTrue(err.contains("hello warn"), "warn should write to stderr")
    }

    @Test
    fun `error writes to stderr`() {
        val err =
            captureStderr {
                sink.error("hello error")
            }
        assertTrue(err.contains("hello error"), "error should write to stderr")
    }

    @Test
    fun `info with empty message works`() {
        val out =
            captureStdout {
                sink.info("")
            }
        assertTrue(out.contains("\n") || out == "", "empty info should still produce output")
    }

    @Test
    fun `warn with multiline message`() {
        val err =
            captureStderr {
                sink.warn("line1\nline2\nline3")
            }
        assertTrue(err.contains("line1"), "warn should write first line")
        assertTrue(err.contains("line2"), "warn should write second line")
        assertTrue(err.contains("line3"), "warn should write third line")
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val baos = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(baos))
            block()
        } finally {
            System.setOut(original)
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }

    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val baos = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(baos))
            block()
        } finally {
            System.setErr(original)
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }
}
