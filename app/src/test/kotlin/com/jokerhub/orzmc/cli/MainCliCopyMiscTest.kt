package com.jokerhub.orzmc.cli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import com.jokerhub.orzmc.world.Cleaner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainCliCopyMiscTest {
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

    private fun createMinimalWorld(): Pair<Path, Path> {
        val input = Files.createTempDirectory("cli-world-copy-")
        Files.createDirectories(input.resolve("region"))
        Files.write(input.resolve("region").resolve("r.0.0.mca"), buildSingleEntryMca(0, 1000))
        Files.createDirectories(input.resolve("entities"))
        Files.write(input.resolve("entities").resolve("ignore.txt"), "e".toByteArray(Charsets.UTF_8))
        Files.write(input.resolve("foo.txt"), "x".toByteArray(Charsets.UTF_8))
        Files.createDirectories(input.resolve("misc"))
        Files.write(input.resolve("misc").resolve("note.txt"), "y".toByteArray(Charsets.UTF_8))
        val out = Files.createTempDirectory("cli-out-copy-")
        return input to out
    }

    @Test
    fun `default copy-misc copies non-reserved files and folders`() {
        val (input, out) = createMinimalWorld()
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force"
        )
        assertTrue(exit == 0)
        assertTrue(Files.exists(out.resolve("foo.txt")))
        assertTrue(Files.exists(out.resolve("misc").resolve("note.txt")))
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        assertFalse(Files.exists(out.resolve("entities").resolve("ignore.txt")))
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }

    @Test
    fun `copy-misc=false does not copy non-reserved files and folders`() {
        val (input, out) = createMinimalWorld()
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force",
            "--copy-misc=false"
        )
        assertTrue(exit == 0)
        assertFalse(Files.exists(out.resolve("foo.txt")))
        assertFalse(Files.exists(out.resolve("misc").resolve("note.txt")))
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        assertFalse(Files.exists(out.resolve("entities").resolve("ignore.txt")))
        Cleaner.deleteTreeWithRetry(out, 5, 10)
        Cleaner.deleteTreeWithRetry(input, 5, 10)
    }
}
