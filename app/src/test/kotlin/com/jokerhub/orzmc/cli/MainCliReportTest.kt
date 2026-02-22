package com.jokerhub.orzmc.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import com.jokerhub.orzmc.world.Cleaner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainCliReportTest {
    private fun buildSingleEntryMca(index: Int, inhabited: Long): ByteArray {
        val name = "InhabitedTime".toByteArray()
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(4))
        val nameLen = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        nameLen.putShort(name.size.toShort())
        payload.write(nameLen.array())
        payload.write(name)
        val value = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        value.putLong(inhabited)
        payload.write(value.array())
        val body = payload.toByteArray()
        val bos = ByteArrayOutputStream()
        val loc = ByteArray(4096)
        val bbLoc = ByteBuffer.wrap(loc).order(ByteOrder.BIG_ENDIAN)
        val time = ByteArray(4096)
        val bbTime = ByteBuffer.wrap(time).order(ByteOrder.BIG_ENDIAN)
        var offsetBytes = 8192
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1 + body.size).array()
        bos.write(lenBuf)
        bos.write(byteArrayOf(3))
        bos.write(body)
        val written = 4 + 1 + body.size
        val pad = (4096 - (written % 4096)) % 4096
        if (pad > 0) bos.write(ByteArray(pad))
        val offSectors = (offsetBytes / 4096)
        val sizeSectors = (written + pad) / 4096
        val v = (offSectors shl 8) or (sizeSectors and 0xFF)
        bbLoc.position(index * 4)
        bbLoc.putInt(v)
        bbTime.position(index * 4)
        bbTime.putInt((System.currentTimeMillis() / 1000).toInt())
        val header = ByteArrayOutputStream()
        header.write(loc)
        header.write(time)
        val final = ByteArrayOutputStream()
        final.write(header.toByteArray())
        final.write(bos.toByteArray())
        return final.toByteArray()
    }

    private fun extractProcessedChunks(json: String): Long? {
        val m = Regex("\"processedChunks\":(\\d+)").find(json) ?: return null
        return m.groupValues[1].toLong()
    }

    @Test
    fun `cli writes report file in json`() {
        val input = Files.createTempDirectory("cli-world-")
        Files.createDirectories(input.resolve("region"))
        Files.write(input.resolve("region").resolve("r.0.0.mca"), buildSingleEntryMca(0, 1000))
        val out = Files.createTempDirectory("cli-out-")
        val report = Files.createTempFile("cli-report-", ".json")
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force",
            "--report-file", report.toString(),
            "--report-format", "json"
        )
        assertTrue(exit == 0)
        val content = String(Files.readAllBytes(report), Charsets.UTF_8)
        val processed = extractProcessedChunks(content)
        assertTrue(processed != null && processed > 0)
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
        Cleaner.deleteTreeWithRetry(report.parent, 5, 10)
    }
}
