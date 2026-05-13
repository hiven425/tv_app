package com.tvcast.app.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal `bplist00` parser sufficient for AirPlay request payloads.
 *
 * AirPlay clients (modern iOS) send `POST /play`, `SET_PARAMETER`, etc. with a binary plist body
 * containing a top-level dictionary. We only need to read strings, numbers, booleans, and data
 * inside such dictionaries — the full Apple spec also allows sets, dates and ordered sets which
 * iOS does not use here.
 *
 * Format reference: https://opensource.apple.com/source/CF/CF-855.17/CFBinaryPList.c
 */
object BinaryPlist {

    fun isBinaryPlist(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 'b'.code.toByte() && bytes[1] == 'p'.code.toByte() &&
            bytes[2] == 'l'.code.toByte() && bytes[3] == 'i'.code.toByte() &&
            bytes[4] == 's'.code.toByte() && bytes[5] == 't'.code.toByte()

    /** Returns the root object — typically a Map<String,Any?> for AirPlay payloads. */
    fun parse(bytes: ByteArray): Any? {
        require(isBinaryPlist(bytes)) { "Not a bplist00 stream" }
        require(bytes.size >= 32 + 8) { "bplist truncated" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xFF
        val objectRefSize = bytes[trailer + 7].toInt() and 0xFF
        val numObjects = readBigEndianLong(bytes, trailer + 8, 8).toInt()
        val rootIndex = readBigEndianLong(bytes, trailer + 16, 8).toInt()
        val offsetTableStart = readBigEndianLong(bytes, trailer + 24, 8).toInt()

        val offsets = IntArray(numObjects) { i ->
            readBigEndianLong(bytes, offsetTableStart + i * offsetSize, offsetSize).toInt()
        }

        val cache = arrayOfNulls<Any?>(numObjects)
        val seen = BooleanArray(numObjects)
        return readObject(rootIndex, bytes, offsets, objectRefSize, cache, seen)
    }

    private fun readObject(
        idx: Int,
        bytes: ByteArray,
        offsets: IntArray,
        objectRefSize: Int,
        cache: Array<Any?>,
        seen: BooleanArray,
    ): Any? {
        if (seen[idx]) return cache[idx]
        seen[idx] = true

        val off = offsets[idx]
        val marker = bytes[off].toInt() and 0xFF
        val high = marker ushr 4
        val low = marker and 0x0F

        val (size, body) = countAndBodyOffset(high, low, bytes, off)

        val value: Any? = when (high) {
            0x0 -> when (low) {
                0x0 -> null      // null
                0x8 -> false
                0x9 -> true
                else -> null
            }
            0x1 -> readBigEndianLong(bytes, body, 1 shl low)
            0x2 -> when (1 shl low) {
                4 -> ByteBuffer.wrap(bytes, body, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble()
                8 -> ByteBuffer.wrap(bytes, body, 8).order(ByteOrder.BIG_ENDIAN).double
                else -> 0.0
            }
            0x3 -> {
                val seconds = ByteBuffer.wrap(bytes, body, 8).order(ByteOrder.BIG_ENDIAN).double
                seconds // CFAbsoluteTime — return as Double for simplicity
            }
            0x4 -> bytes.copyOfRange(body, body + size)
            0x5 -> String(bytes, body, size, Charsets.US_ASCII)
            0x6 -> String(bytes, body, size * 2, Charsets.UTF_16BE)
            0xA, 0xB, 0xC -> {
                val list = ArrayList<Any?>(size)
                for (i in 0 until size) {
                    val refIdx = readBigEndianLong(bytes, body + i * objectRefSize, objectRefSize).toInt()
                    list.add(readObject(refIdx, bytes, offsets, objectRefSize, cache, seen))
                }
                list
            }
            0xD -> {
                val keys = IntArray(size) {
                    readBigEndianLong(bytes, body + it * objectRefSize, objectRefSize).toInt()
                }
                val values = IntArray(size) {
                    readBigEndianLong(
                        bytes, body + (size + it) * objectRefSize, objectRefSize
                    ).toInt()
                }
                val map = LinkedHashMap<String, Any?>(size)
                for (i in 0 until size) {
                    val k = readObject(keys[i], bytes, offsets, objectRefSize, cache, seen)?.toString()
                        ?: continue
                    map[k] = readObject(values[i], bytes, offsets, objectRefSize, cache, seen)
                }
                map
            }
            else -> null
        }
        cache[idx] = value
        return value
    }

    private fun countAndBodyOffset(high: Int, low: Int, bytes: ByteArray, off: Int): Pair<Int, Int> {
        // Containers with low nibble 0xF have an extended count encoded as int after marker.
        if (high in 0x4..0xD && low == 0xF) {
            val sizeMarker = bytes[off + 1].toInt() and 0xFF
            val sizeIntBytes = 1 shl (sizeMarker and 0x0F)
            val size = readBigEndianLong(bytes, off + 2, sizeIntBytes).toInt()
            return size to off + 2 + sizeIntBytes
        }
        return low to off + 1
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Int, length: Int): Long {
        var v = 0L
        for (i in 0 until length) {
            v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return v
    }
}
