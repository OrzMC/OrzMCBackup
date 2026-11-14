package com.jokerhub.orzmc

import com.jokerhub.orzmc.mca.ChunkNbtScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WOS-3.1 / WOS-3.4: `InhabitedTime` is read from the root compound or `root.Level` only. */
class ChunkNbtScannerTest {
    private fun str(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            ByteBuffer
                .allocate(2)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(s.length.toShort())
                .array(),
        )
        out.write(s.toByteArray())
        return out.toByteArray()
    }

    private fun longTag(
        name: String,
        value: Long,
    ): ByteArray =
        byteArrayOf(0x04) + str(name) +
            ByteBuffer
                .allocate(8)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(value)
                .array()

    private fun compound(vararg entries: ByteArray): ByteArray =
        entries.fold(ByteArray(0)) { a, b -> a + b } + byteArrayOf(0)

    private fun compoundTag(
        name: String,
        vararg entries: ByteArray,
    ): ByteArray = byteArrayOf(0x0A) + str(name) + compound(*entries)

    private fun root(vararg entries: ByteArray): ByteArray = byteArrayOf(0x0A, 0x00, 0x00) + compound(*entries)

    @Test
    fun `reads root level inhabited time`() {
        assertEquals(42L, ChunkNbtScanner.findInhabitedTime(root(longTag("InhabitedTime", 42))))
    }

    @Test
    fun `reads legacy Level inhabited time`() {
        val data = root(compoundTag("Level", longTag("InhabitedTime", 7_000)))
        assertEquals(7_000L, ChunkNbtScanner.findInhabitedTime(data))
    }

    @Test
    fun `ignores nested decoy before the real root tag`() {
        val decoy = compoundTag("structures", longTag("InhabitedTime", 999_999))
        val data = root(decoy, longTag("InhabitedTime", 0))
        assertEquals(
            0L,
            ChunkNbtScanner.findInhabitedTime(data),
            "a byte scan would return the decoy and keep the chunk",
        )
    }

    @Test
    fun `ignores tags nested two levels deep`() {
        val deep = compoundTag("a", compoundTag("b", longTag("InhabitedTime", 123_456)))
        assertNull(ChunkNbtScanner.findInhabitedTime(root(deep)))
    }

    @Test
    fun `missing tag and malformed NBT return null`() {
        assertNull(ChunkNbtScanner.findInhabitedTime(root(compoundTag("sections"))))
        val truncated = root(longTag("InhabitedTime", 5)).copyOf()
        assertNull(ChunkNbtScanner.findInhabitedTime(truncated.copyOf(truncated.size - 3)))
        assertNull(ChunkNbtScanner.findInhabitedTime("not-nbt".toByteArray()))
    }

    @Test
    fun `bare tag without root compound is not valid chunk NBT`() {
        assertNull(ChunkNbtScanner.findInhabitedTime(longTag("InhabitedTime", 42)))
    }

    @Test
    fun `skips list and array payloads correctly`() {
        // list of compounds + byte/int/long arrays before the real tag
        val list =
            byteArrayOf(0x09) + str("items") + byteArrayOf(0x0A) +
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(1)
                    .array() +
                compound(longTag("InhabitedTime", 111)) // nested decoy inside the list element
        val arrays =
            byteArrayOf(0x07) + str("bytes") +
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(3)
                    .array() + ByteArray(3) +
                byteArrayOf(0x0B) + str("ints") +
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(2)
                    .array() + ByteArray(8) +
                byteArrayOf(0x0C) + str("longs") +
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(1)
                    .array() + ByteArray(8)
        val data = root(list, arrays, longTag("InhabitedTime", 777))
        assertEquals(777L, ChunkNbtScanner.findInhabitedTime(data))
    }
}
