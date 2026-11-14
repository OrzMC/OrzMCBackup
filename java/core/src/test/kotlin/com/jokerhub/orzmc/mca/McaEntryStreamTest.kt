package com.jokerhub.orzmc.mca

import com.jokerhub.orzmc.util.CompressionKind
import com.jokerhub.orzmc.util.McaMemoryBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WOS-10.2: the decompressed payload can be consumed as a stream, so memory stays bounded by the
 * compressed block size instead of the decompressed size (the upstream PHP implementation reads
 * chunks the same way). The buffered API must keep returning identical bytes.
 */
class McaEntryStreamTest {
    private fun entry(kind: CompressionKind): McaEntry {
        val mca = McaMemoryBuilder.buildSingleEntryMca(0, 7_000, kind)
        return McaReader.openFromBytes("r.0.0.mca", mca).get(0)!!
    }

    @Test
    fun `streaming view matches the buffered view for every compression`() {
        // EXT_* chunks have no inline payload (data lives in a .mcc file) — covered elsewhere.
        CompressionKind.entries.filterNot { it.name.startsWith("EXT_") }.forEach { kind ->
            val e = entry(kind)
            val buffered = e.allDataUncompressed()
            val streamed = e.openDataStream().use { it.readBytes() }
            assertArrayEquals(buffered, streamed, "compression $kind must stream the same bytes")
            assertTrue(buffered.isNotEmpty(), "payload should not be empty for $kind")
        }
    }

    @Test
    fun `scanner accepts the streaming view`() {
        val e = entry(CompressionKind.LZ4)
        val result = e.openDataStream().use { ChunkNbtScanner.scan(it) }
        assertEquals(ChunkNbtScanner.ScanResult.Found(7_000), result)
    }

    @Test
    fun `invalid length field is rejected instead of being treated as empty`() {
        val mca = McaMemoryBuilder.buildSingleEntryMca(0, 1_000, CompressionKind.RAW)
        // absurd length (0x0ac9fbd1) exactly like the corrupt fixtures
        val offset =
            (
                (mca[0].toInt() and 0xFF shl 24) or (mca[1].toInt() and 0xFF shl 16) or
                    (mca[2].toInt() and 0xFF shl 8) or (mca[3].toInt() and 0xFF)
            ) ushr 8
        val dataOffset = offset * 4096
        byteArrayOf(0x0a, 0xc9.toByte(), 0xfb.toByte(), 0xd1.toByte()).copyInto(mca, dataOffset)
        val e = McaReader.openFromBytes("r.0.0.mca", mca).get(0)!!
        val ex = assertThrows(IllegalArgumentException::class.java) { e.openDataStream() }
        assertTrue(ex.message!!.contains("length field is invalid"), ex.message)
    }

    @Test
    fun `decompression bomb is stopped while streaming`() {
        val bomb = ByteArray(McaEntry.MAX_UNCOMPRESSED_CHUNK_LENGTH + 1) { 1 } // valid NBT prefix, huge body
        val mca = McaMemoryBuilder.buildCustomPayloadMca(0, bomb, CompressionKind.ZLIB)
        val e = McaReader.openFromBytes("r.0.0.mca", mca).get(0)!!
        assertThrows(IllegalArgumentException::class.java) {
            e.openDataStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (input.read(buf) >= 0) {
                    // drain: must hit the cap instead of allocating the whole payload
                }
            }
        }
    }
}
