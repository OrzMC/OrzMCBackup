package com.jokerhub.orzmc

import com.jokerhub.orzmc.world.NbtForceLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

class NbtForceLoaderTest {

    /** Helper: write a UTF string as NBT string prefix (short length + bytes) */
    private fun DataOutputStream.writeNbtString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    /** Build a GZIP-compressed NBT byte array representing chunks.dat content */
    private fun buildChunksDat(builder: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            DataOutputStream(gz).use { dos ->
                dos.apply(builder)
            }
        }
        return bos.toByteArray()
    }

    /** Write a named TAG_Compound entry (for use inside a parent compound) */
    private fun DataOutputStream.writeNamedCompound(name: String, vararg entries: Pair<String, Pair<Byte, DataOutputStream.() -> Unit>>) {
        writeByte(0x0A) // TAG_Compound tag type
        writeNbtString(name)
        writeCompoundEntries(*entries)
    }

    /** Write compound entries without a wrapping tag header — for list element compounds */
    private fun DataOutputStream.writeCompoundEntries(vararg entries: Pair<String, Pair<Byte, DataOutputStream.() -> Unit>>) {
        for (entry in entries) {
            val name = entry.first
            val (tagType, payload) = entry.second
            writeByte(tagType.toInt())
            writeNbtString(name)
            payload()
        }
        writeByte(0) // TAG_End
    }

    @Test
    fun `parse legacy Forced long array format`() {
        val data = buildChunksDat {
            writeByte(0x0A) // TAG_Compound (root)
            writeNbtString("") // root name
            writeByte(0x0A) // TAG_Compound "data"
            writeNbtString("data")
            writeByte(0x0C) // TAG_Long_Array "Forced"
            writeNbtString("Forced")
            writeInt(4) // 4 longs = 2 chunk positions
            writeLong(1) // chunk x=1
            writeLong(2) // chunk z=2
            writeLong(10) // chunk x=10
            writeLong(20) // chunk z=20
            writeByte(0) // TAG_End (data)
            writeByte(0) // TAG_End (root)
        }

        val result = NbtForceLoader.parse(java.io.File.createTempFile("chunks", ".dat").apply {
            writeBytes(data)
            deleteOnExit()
        })

        assertEquals(2, result.size, "should parse 2 forced chunks")
        assertTrue(result.contains(1 to 2), "should contain (1, 2)")
        assertTrue(result.contains(10 to 20), "should contain (10, 20)")
    }

    @Test
    fun `parse new tickets format`() {
        val tagLong = 0x04.toByte()
        val tagString = 0x08.toByte()
        val tagList = 0x09.toByte()
        val tagCompound = 0x0A.toByte()
        val tagIntArray = 0x0B.toByte()
        val tagEnd = 0x00.toByte()

        val data = buildChunksDat {
            writeByte(0x0A) // TAG_Compound (root)
            writeNbtString("")
            writeByte(0x0A) // TAG_Compound "data"
            writeNbtString("data")
            writeByte(0x09) // TAG_List "tickets"
            writeNbtString("tickets")
            writeByte(0x0A) // element type: TAG_Compound
            writeInt(3) // 3 tickets

            // Ticket 1: forced chunk (3, 4)
            writeCompoundEntries(
                "type" to (tagString to { writeNbtString("minecraft:forced") }),
                "chunk_pos" to (tagIntArray to { writeInt(2); writeInt(3); writeInt(4) })
            )

            // Ticket 2: not forced (different type)
            writeCompoundEntries(
                "type" to (tagString to { writeNbtString("minecraft:other") }),
                "chunk_pos" to (tagIntArray to { writeInt(2); writeInt(5); writeInt(6) })
            )

            // Ticket 3: forced chunk (5, 6)
            writeCompoundEntries(
                "type" to (tagString to { writeNbtString("minecraft:forced") }),
                "chunk_pos" to (tagIntArray to { writeInt(2); writeInt(5); writeInt(6) })
            )

            writeByte(0) // TAG_End (data)
            writeByte(0) // TAG_End (root)
        }

        val result = NbtForceLoader.parse(java.io.File.createTempFile("chunks", ".dat").apply {
            writeBytes(data)
            deleteOnExit()
        })

        assertTrue(result.contains(3 to 4), "should contain forced ticket (3, 4)")
        assertTrue(result.contains(5 to 6), "should contain forced ticket (5, 6)")
        assertEquals(2, result.size, "should have exactly 2 forced entries")
    }

    @Test
    fun `empty file throws exception in NbtForceLoader`() {
        assertThrows(Exception::class.java) {
            NbtForceLoader.parse(
                java.io.File.createTempFile("empty", ".dat").apply {
                    deleteOnExit()
                }
            )
        }
    }

    @Test
    fun `file with no data compound returns empty list`() {
        val data = buildChunksDat {
            writeByte(0x0A) // TAG_Compound (root)
            writeNbtString("")
            writeByte(0x0A) // TAG_Compound "other"
            writeNbtString("other")
            writeByte(0) // TAG_End (other)
            writeByte(0) // TAG_End (root)
        }

        val result = NbtForceLoader.parse(java.io.File.createTempFile("chunks", ".dat").apply {
            writeBytes(data)
            deleteOnExit()
        })

        assertTrue(result.isEmpty(), "no data compound should return empty list")
    }

    @Test
    fun `file with empty Forced array returns empty list`() {
        val data = buildChunksDat {
            writeByte(0x0A) // TAG_Compound (root)
            writeNbtString("")
            writeByte(0x0A) // TAG_Compound "data"
            writeNbtString("data")
            writeByte(0x0C) // TAG_Long_Array "Forced"
            writeNbtString("Forced")
            writeInt(0) // empty long array
            writeByte(0) // TAG_End (data)
            writeByte(0) // TAG_End (root)
        }

        val result = NbtForceLoader.parse(java.io.File.createTempFile("chunks", ".dat").apply {
            writeBytes(data)
            deleteOnExit()
        })

        assertTrue(result.isEmpty(), "empty forced array should return empty list")
    }
}
