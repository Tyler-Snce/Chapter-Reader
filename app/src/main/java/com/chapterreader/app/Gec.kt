package com.chapterreader.app

/**
 * The "Sec-MS-GEC" token Microsoft's online voices require (version 2).
 * Ported from the edge-tts project (github.com/rany2/edge-tts, src/edge_tts/drm.py, LGPL-3.0),
 * which worked out how Edge's Read Aloud creates it. Values are 32-bit, kept in Longs.
 */
object Gec {
    const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val M = 0xFFFFFFFFL
    private const val WIN_EPOCH = 11644473600L

    private val INITIAL = longArrayOf(0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19)
    private val STATE = longArrayOf(0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2)
    private val SCHEDULE = longArrayOf(0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564)
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2)
    private const val ADJ = 0xC9DC06A7L
    private val BLOCK_K = K.copyOf().also { it[14] = ADJ }
    private const val SIGMA0_ADJ = 0x059C0B80L
    private const val SIGMA1_ADJ = 0x000B92C0L
    private val CS64_COEF = longArrayOf(0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1,
        0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6)
    private val CS64_OFFSET = longArrayOf(0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6,
        0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167)
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJ0 = 0xF2F90B1AL
    private const val CS64_ADJ1 = 0x0B1A0000L

    private fun rol16(v0: Long): Long { val v = v0 and M; return ((v shl 16) or (v ushr 16)) and M }
    private fun sigma0(v: Long) = ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and M
    private fun sigma1(v: Long) = ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and M
    private fun rotr(x: Long, n: Int) = ((x ushr n) or (x shl (32 - n))) and M

    private fun rounds(state: LongArray, w: List<Long>, first: Int = 0): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (first == 14) BLOCK_K else K
        for (i in first until 64) {
            val t1 = (h + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) + (g xor (e and (f xor g))) + k[i] + w[i]) and M
            val t2 = ((rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) + ((a and b) or (c and (a or b)))) and M
            h = g; g = f; f = e; e = (d + t1) and M; d = c; c = b; b = a; a = (t1 + t2) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: MutableList<Long>, first: Int = 16) {
        for (i in first until 64) {
            val x = w[i - 2]; val y = w[i - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            w.add((s1 + w[i - 7] + s0 + w[i - 16]) and M)
        }
    }

    private fun be(b: ByteArray, from: Int, to: Int): Long { var v = 0L; for (i in from until to) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }

    private fun shaInit(d: ByteArray): LongArray {
        val w = MutableList(14) { 0L }
        w.add(be(d, 0, 3)); w.add(be(d, 3, 7))
        for (i in 16 until 30) {
            var v = SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) (sigma1(w[i - 2]) xor SIGMA1_ADJ) else sigma1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) (sigma0(w[i - 15]) xor SIGMA0_ADJ) else sigma0(w[i - 15])
            w.add(v and M)
        }
        w.add((sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + ADJ - K[14]) and M)
        expand(w, 31)
        val r = rounds(STATE, w, 14)
        return LongArray(8) { (INITIAL[it] + r[it]) and M }
    }

    private fun shaDigest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val padLen = ((55 - length) % 64 + 64) % 64
        val rest = data.copyOfRange(7, data.size)
        val padded = ByteArray(rest.size + 1 + padLen + 8)
        System.arraycopy(rest, 0, padded, 0, rest.size)
        padded[rest.size] = 0x80.toByte()
        val bits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        var state = shaInit(data.copyOfRange(0, 7))
        var off = 0
        while (off < padded.size) {
            val w = MutableList(16) { j -> be(padded, off + j * 4, off + j * 4 + 4) }
            expand(w)
            val r = rounds(state, w)
            state = LongArray(8) { (state[it] + r[it]) and M }
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (state[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    private fun le(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun cs64(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val keyA = le(digest, 0) or 1L
        val keyB = le(digest, 4) or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and M, (keyB * CS64_FACTOR) and M)
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for (i in CS64_COEF.indices) { terms.add((CS64_COEF[i] * factors[i % 2]) and M); offsets.add(CS64_OFFSET[i]) }
        // Little-endian words of 0x00 + data, zero-padded to an even number of words
        val src = byteArrayOf(0) + data
        val padded = src + ByteArray((4 - src.size % 4) % 4)
        val words = ArrayList<Long>()
        var p = 0
        while (p < padded.size) { words.add(le(padded, p)); p += 4 }
        if (words.size % 2 == 1) words.add(0L)
        for ((i, word) in words.withIndex()) {
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var off = rol16(word) * (if (i % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (i == 0) { term += CS64_ADJ0 * factors[0]; off += CS64_ADJ1 }
            terms.add(term and M)
            offsets.add(off and M)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M)
            var q = (t * 0x76F50F34L) and M
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and M
            t = rol16((offsets[i + 1] + y) and M)
            q = (t * 0x1881CE3CL) and M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M
            swapAcc = (swapAcc + swapLast) and M
            state = (state * keyA + x) and M
            state = (rol16(state) * 0xCF98B111L) and M
            state = (rol16(state) * 0x87085B9FL) and M
            state = (rol16(state) * 0x12CEB96DL) and M
            state = (rol16(state) * 0x257E1D83L) and M
            acc += state
            t = (state * keyB + y) and M
            t = (rol16(t) * 0xA27416F5L) and M
            t = (rol16(t) * 0xD38396FFL) and M
            state = (rol16((rol16(t) * 0x7C932B89L) and M) * 0xBFA49F69L) and M
            acc = (acc + state) and M
            i += 2
        }
        return Pair(swapLast xor state, swapAcc xor acc)
    }

    /** The token for [unixSeconds] (default: now). */
    fun token(unixSeconds: Double = System.currentTimeMillis() / 1000.0): String {
        var secs = Math.floor(unixSeconds).toLong() + WIN_EPOCH
        secs -= secs % 300
        val data = ((secs * 10_000_000L).toString() + TOKEN).toByteArray(Charsets.US_ASCII)
        val digest = shaDigest(data)
        val (c0, c1) = cs64(data, digest)
        val cs = ByteArray(8)
        for (j in 0 until 4) { cs[j] = (c0 ushr (8 * j)).toByte(); cs[4 + j] = (c1 ushr (8 * j)).toByte() }
        fun hex(b: ByteArray) = b.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        return hex(cs) + "-" + hex(digest)
    }
}
