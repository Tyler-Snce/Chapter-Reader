package com.chapterreader.app

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shortens the long silences a voice leaves after full stops, so reading
 * flows naturally. Works on the 16-bit WAV files the voice engine records.
 */
object Silence {
    private const val THRESHOLD = 600      // quieter than this counts as silence (of 32767)
    private const val LEAD_MS = 30         // silence kept before speech starts
    private const val TRAIL_MS = 70        // silence kept after speech ends
    private const val MAX_GAP_MS = 220     // longest pause kept inside a sentence piece

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    fun tighten(f: File) {
        try {
            val b = f.readBytes()
            if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return
            var pos = 12
            var channels = 1
            var rate = 22050
            var bits = 16
            var format = 1
            var dataOff = -1
            var dataLen = 0
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4)
                val len = le32(b, pos + 4)
                val body = pos + 8
                if (id == "fmt ") {
                    format = le16(b, body)
                    channels = le16(b, body + 2)
                    rate = le32(b, body + 4)
                    bits = le16(b, body + 14)
                } else if (id == "data") {
                    dataOff = body
                    dataLen = if (len <= 0 || body + len > b.size) b.size - body else len
                    break
                }
                pos = body + len + (len and 1)
            }
            val isFloat = format == 3 && bits == 32
            if (dataOff < 0 || channels < 1 || rate <= 0) return
            if (!(bits == 16 && format == 1) && !isFloat) return

            val bytesPer = bits / 8
            val frame = bytesPer * channels
            val winFrames = max(1, rate / 100)           // 10 ms windows
            val winBytes = winFrames * frame
            val nWin = dataLen / winBytes
            if (nWin < 3) return

            val loud = BooleanArray(nWin) { w ->
                var peak = 0
                var o = dataOff + w * winBytes
                val end = o + winBytes
                while (o < end) {
                    val s = if (isFloat) {
                        (java.lang.Float.intBitsToFloat(le32(b, o)) * 32767f).toInt()
                    } else (le16(b, o).toShort()).toInt()
                    if (abs(s) > peak) peak = abs(s)
                    o += bytesPer
                }
                peak > THRESHOLD
            }
            val first = loud.indexOfFirst { it }
            val last = loud.indexOfLast { it }
            if (first < 0) return

            val keep = BooleanArray(nWin)
            val lead = LEAD_MS / 10
            val trail = TRAIL_MS / 10
            val maxGap = MAX_GAP_MS / 10
            for (w in max(0, first - lead)..min(nWin - 1, last + trail)) keep[w] = true
            // Shorten long pauses between the first and last sounds
            var w = first
            while (w <= last) {
                if (!loud[w]) {
                    var e = w
                    while (e <= last && !loud[e]) e++
                    val run = e - w
                    if (run > maxGap) {
                        val drop = run - maxGap
                        val from = w + maxGap / 2
                        for (k in from until from + drop) keep[k] = false
                    }
                    w = e
                } else w++
            }

            val out = ByteArrayOutputStream(dataLen + 44)
            out.write(ByteArray(44))
            for (i in 0 until nWin) if (keep[i]) out.write(b, dataOff + i * winBytes, winBytes)
            val res = out.toByteArray()
            val pcm = res.size - 44
            fun put32(o: Int, v: Int) { res[o] = v.toByte(); res[o + 1] = (v shr 8).toByte(); res[o + 2] = (v shr 16).toByte(); res[o + 3] = (v shr 24).toByte() }
            fun put16(o: Int, v: Int) { res[o] = v.toByte(); res[o + 1] = (v shr 8).toByte() }
            "RIFF".toByteArray().copyInto(res, 0)
            put32(4, 36 + pcm)
            "WAVEfmt ".toByteArray().copyInto(res, 8)
            put32(16, 16)
            put16(20, if (isFloat) 3 else 1)
            put16(22, channels)
            put32(24, rate)
            put32(28, rate * frame)
            put16(32, frame)
            put16(34, bits)
            "data".toByteArray().copyInto(res, 36)
            put32(40, pcm)
            f.writeBytes(res)
        } catch (_: Exception) {
            // leave the file as it was
        }
    }
}
