package com.jokerhub.orzmc.world

import java.io.File
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

private const val TAG_End: Byte = 0
private const val TAG_Byte: Byte = 1
private const val TAG_Short: Byte = 2
private const val TAG_Int: Byte = 3
private const val TAG_Long: Byte = 4
private const val TAG_Float: Byte = 5
private const val TAG_Double: Byte = 6
private const val TAG_Byte_Array: Byte = 7
private const val TAG_String: Byte = 8
private const val TAG_List: Byte = 9
private const val TAG_Compound: Byte = 10
private const val TAG_Int_Array: Byte = 11
private const val TAG_Long_Array: Byte = 12

private const val DEFAULT_MAX_ARRAY_SIZE = 10 * 1024 * 1024  // 10 MB
private const val DEFAULT_MAX_LIST_LENGTH = 65536
private const val DEFAULT_MAX_COMPOUND_DEPTH = 64

/**
 * Full NBT parser for `chunks.dat` (GZip-compressed NBT).
 *
 * Supports all 12 standard NBT tag types and extracts force-loaded chunk
 * coordinates from both the legacy `data.Forced` (LongArray) format and the
 * newer `data.tickets[].chunk_pos` format.
 *
 * All array/list allocations are bounded by configurable limits to prevent OOM
 * from crafted inputs.
 */
object NbtForceLoader {
    /**
     * Parse [file] and return force-loaded chunk coordinates.
     * @param maxArraySize maximum allowed ByteArray/IntArray/LongArray length
     * @param maxListLength maximum allowed List element count
     * @param maxCompoundDepth maximum allowed Compound nesting depth
     */
    fun parse(
        file: File,
        maxArraySize: Int = DEFAULT_MAX_ARRAY_SIZE,
        maxListLength: Int = DEFAULT_MAX_LIST_LENGTH,
        maxCompoundDepth: Int = DEFAULT_MAX_COMPOUND_DEPTH
    ): List<Pair<Int, Int>> {
        GZIPInputStream(BufferedInputStream(file.inputStream())).use { gz ->
            val inp = DataInputStream(gz)
            val rootType = inp.readByte()
            require(rootType == TAG_Compound) { "root must be Compound" }
            readUtf(inp)
            val compound = readCompound(inp, 0, maxCompoundDepth, maxArraySize, maxListLength)
            val dataTag = compound["data"] as? Map<*, *> ?: return emptyList()
            val out = mutableListOf<Pair<Int, Int>>()
            val forced = dataTag["Forced"] as? LongArray
            if (forced != null) {
                var i = 0
                while (i + 1 < forced.size) {
                    out.add(Pair(forced[i].toInt(), forced[i + 1].toInt()))
                    i += 2
                }
            }
            val tickets = dataTag["tickets"] as? List<*>
            if (tickets != null) {
                for (t in tickets) {
                    val tm = t as? Map<*, *> ?: continue
                    val typeStr = tm["type"] as? String ?: continue
                    if (typeStr == "minecraft:forced") {
                        val pos = tm["chunk_pos"] as? IntArray ?: continue
                        if (pos.size == 2) out.add(Pair(pos[0], pos[1]))
                    }
                }
            }
            return out
        }
    }

    private fun readUtf(inp: DataInputStream): String {
        val len = inp.readUnsignedShort()
        val b = ByteArray(len)
        inp.readFully(b)
        return String(b, StandardCharsets.UTF_8)
    }

    private fun readList(
        inp: DataInputStream,
        depth: Int,
        maxCompoundDepth: Int,
        maxArraySize: Int,
        maxListLength: Int
    ): Any {
        val elemType = inp.readByte()
        val len = inp.readInt()
        require(len >= 0 && len <= maxListLength) {
            "NBT list length $len exceeds maximum $maxListLength"
        }
        val list = ArrayList<Any>(len)
        for (i in 0 until len) {
            list.add(readPayload(inp, elemType, depth, maxCompoundDepth, maxArraySize, maxListLength))
        }
        return list
    }

    private fun readCompound(
        inp: DataInputStream,
        depth: Int,
        maxCompoundDepth: Int,
        maxArraySize: Int,
        maxListLength: Int
    ): Map<String, Any> {
        require(depth <= maxCompoundDepth) {
            "NBT compound depth $depth exceeds maximum $maxCompoundDepth"
        }
        val m = HashMap<String, Any>()
        while (true) {
            val t = inp.readByte()
            if (t == TAG_End) break
            val name = readUtf(inp)
            val v = readPayload(inp, t, depth + 1, maxCompoundDepth, maxArraySize, maxListLength)
            m[name] = v
        }
        return m
    }

    private fun readPayload(
        inp: DataInputStream,
        t: Byte,
        depth: Int,
        maxCompoundDepth: Int,
        maxArraySize: Int,
        maxListLength: Int
    ): Any {
        return when (t) {
            TAG_Byte -> inp.readByte()
            TAG_Short -> inp.readShort()
            TAG_Int -> inp.readInt()
            TAG_Long -> inp.readLong()
            TAG_Float -> inp.readFloat()
            TAG_Double -> inp.readDouble()
            TAG_Byte_Array -> {
                val len = inp.readInt()
                require(len >= 0 && len <= maxArraySize) {
                    "NBT ByteArray length $len exceeds maximum $maxArraySize"
                }
                val arr = ByteArray(len)
                inp.readFully(arr)
                arr
            }
            TAG_String -> readUtf(inp)
            TAG_List -> readList(inp, depth, maxCompoundDepth, maxArraySize, maxListLength)
            TAG_Compound -> readCompound(inp, depth, maxCompoundDepth, maxArraySize, maxListLength)
            TAG_Int_Array -> {
                val len = inp.readInt()
                require(len >= 0 && len <= maxArraySize) {
                    "NBT IntArray length $len exceeds maximum $maxArraySize"
                }
                val arr = IntArray(len)
                for (i in 0 until len) arr[i] = inp.readInt()
                arr
            }
            TAG_Long_Array -> {
                val len = inp.readInt()
                require(len >= 0 && len <= maxArraySize) {
                    "NBT LongArray length $len exceeds maximum $maxArraySize"
                }
                val arr = LongArray(len)
                for (i in 0 until len) arr[i] = inp.readLong()
                arr
            }
            else -> throw IllegalArgumentException("unsupported tag $t")
        }
    }
}
