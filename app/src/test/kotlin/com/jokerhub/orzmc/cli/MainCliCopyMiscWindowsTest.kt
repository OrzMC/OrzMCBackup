package com.jokerhub.orzmc.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainCliCopyMiscWindowsTest {
    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")

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

    private fun createWorldWithAttributes(): Pair<Path, Path> {
        val input = Files.createTempDirectory("cli-world-copy-win-")
        Files.createDirectories(input.resolve("region"))
        Files.write(input.resolve("region").resolve("r.0.0.mca"), buildSingleEntryMca(0, 1000))
        val misc = input.resolve("misc").resolve("sub").resolve("inner")
        Files.createDirectories(misc)
        val ro = misc.resolve("readonly.txt")
        Files.write(ro, "ro".toByteArray(Charsets.UTF_8))
        val hidden = input.resolve("misc").resolve("hidden.txt")
        Files.write(hidden, "hidden".toByteArray(Charsets.UTF_8))
        if (isWindows()) {
            val roView = Files.getFileAttributeView(ro, DosFileAttributeView::class.java)
            roView?.setReadOnly(true)
            val hiddenView = Files.getFileAttributeView(hidden, DosFileAttributeView::class.java)
            hiddenView?.setHidden(true)
        } else {
            try { input.resolve("misc").toFile().setWritable(false) } catch (_: Exception) {}
        }
        val out = Files.createTempDirectory("cli-out-copy-win-")
        return input to out
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { p ->
            try {
                val v = Files.getFileAttributeView(p, DosFileAttributeView::class.java)
                v?.setReadOnly(false)
            } catch (_: Exception) {}
            try { Files.deleteIfExists(p) } catch (_: Exception) {}
        }
    }

    @Test
    fun `copy-misc copies nested and attribute-marked files on Windows`() {
        assumeTrue(isWindows())
        val (input, out) = createWorldWithAttributes()
        val exit = CommandLine(Main()).execute(
            input.toString(),
            out.toString(),
            "-t", "0",
            "--progress-mode", "Off",
            "--force"
        )
        assertTrue(exit == 0)
        assertTrue(Files.exists(out.resolve("misc").resolve("sub").resolve("inner").resolve("readonly.txt")))
        assertTrue(Files.exists(out.resolve("misc").resolve("hidden.txt")))
        assertTrue(Files.exists(out.resolve("region").resolve("r.0.0.mca")))
        deleteTree(out)
        deleteTree(input)
    }
}
