package com.tvcast.app.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bare DMAP (DAAP/DACP) tagged-binary parser.
 *
 * Format: stream of `[4-byte ASCII tag][4-byte big-endian length][payload]` records, optionally nested.
 * AirPlay uses this for now-playing metadata in SET_PARAMETER with content-type
 * `application/x-dmap-tagged`. We only need a few well-known string tags.
 *
 * Spec: https://github.com/jkiddo/jolivia/blob/master/jolivia.protocol/src/main/resources/dmap.json
 */
object DmapParser {

    private val STRING_TAGS = setOf(
        "minm", // item name (track title)
        "asar", // song artist
        "asal", // song album
        "ascp", // song composer
        "asgn", // song genre
        "ascm", // comment
        "aeMK", // playback channel
    )

    private val CONTAINER_TAGS = setOf("mlit", "mlcl", "adbs", "abro", "abal", "abar", "abcp", "abgn")

    fun parse(bytes: ByteArray): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        parseInto(bytes, 0, bytes.size, out)
        return out
    }

    private fun parseInto(bytes: ByteArray, from: Int, to: Int, out: MutableMap<String, Any>) {
        var p = from
        while (p + 8 <= to) {
            val tag = String(bytes, p, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(bytes, p + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            val payloadStart = p + 8
            val payloadEnd = payloadStart + len
            if (len < 0 || payloadEnd > to) return
            when (tag) {
                in CONTAINER_TAGS -> parseInto(bytes, payloadStart, payloadEnd, out)
                in STRING_TAGS -> {
                    val s = String(bytes, payloadStart, len, Charsets.UTF_8).trim()
                    if (s.isNotEmpty()) out[tag] = s
                }
                else -> {
                    // Numeric / unknown — skip but record raw bytes for inspection if length is small.
                    if (len in 1..32) {
                        out[tag] = bytes.copyOfRange(payloadStart, payloadEnd)
                    }
                }
            }
            p = payloadEnd
        }
    }
}
