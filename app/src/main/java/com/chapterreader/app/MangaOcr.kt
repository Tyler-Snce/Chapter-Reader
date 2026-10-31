package com.chapterreader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** A speech bubble (or caption) found in a page image. Position is a fraction of the image size. */
data class SpeechBubble(
    val img: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val text: String,
)

/**
 * Downloads manga / webtoon page images and reads the text in them using
 * Google's on-device text recognition (works offline, nothing is uploaded).
 */
class MangaOcr(private val userAgent: String) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun close() {
        try { recognizer.close() } catch (_: Exception) {}
    }

    /** Downloads one page image. Sites often only allow it when it looks like it came from their page. */
    fun download(url: String, referer: String, to: File): Boolean {
        return try {
            var link = url
            var c: HttpURLConnection
            var hops = 0
            while (true) {
                c = URL(link).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Referer", referer)
                c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                val code = c.responseCode
                if (code in 300..399 && hops < 5) {
                    link = URL(URL(link), c.getHeaderField("Location")).toString()
                    c.disconnect(); hops++
                    continue
                }
                if (code !in 200..299) { c.disconnect(); return false }
                break
            }
            c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            to.length() > 1000
        } catch (_: Exception) {
            false
        }
    }

    private class Box(var l: Int, var t: Int, var r: Int, var b: Int, var text: String, val lineH: Int)

    /** Reads the text in one page image, in reading order (top to bottom, left to right). */
    @Suppress("DEPRECATION")
    /** Text read across the join into the next picture; position in fractions of the picture width. */
    class Carry(val l: Float, val t: Float, val r: Float, val b: Float)

    class PageText(val bubbles: List<SpeechBubble>, val carry: List<Carry>)

    /**
     * Reads the text in one page image, in reading order (top to bottom, left to right).
     * The site cuts the strip into pictures, sometimes straight through a line of text, so the
     * top of the [next] picture is attached underneath; [carry] is what the previous page
     * already read from the top of this one.
     */
    fun bubbles(
        file: File, imgIndex: Int, next: File? = null, carry: List<Carry> = emptyList(),
        marks: Set<String> = emptySet(), onMark: (String) -> Unit = {},
    ): PageText {
        val empty = PageText(emptyList(), emptyList())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return empty

        val decoder = try { BitmapRegionDecoder.newInstance(file.path, false) } catch (_: Exception) { null }
            ?: return empty
        var nextDecoder: BitmapRegionDecoder? = null
        var nw = 0
        var band = 0
        if (next != null) {
            val nb = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(next.path, nb)
            if (nb.outWidth > 0 && nb.outHeight > 0) {
                nextDecoder = try { BitmapRegionDecoder.newInstance(next.path, false) } catch (_: Exception) { null }
                if (nextDecoder != null) {
                    nw = nb.outWidth
                    band = min((nb.outHeight.toLong() * w / nw).toInt(), w * 6 / 10)
                }
            }
        }
        val fullH = h + band
        val sample = if (w > 1600) 2 else 1
        // Long webtoon strips are read in overlapping slices so small text stays readable
        val tileH = min(fullH, w * 2)
        val overlap = min(tileH / 4, w / 3)
        val found = ArrayList<Box>()
        var top = 0
        try {
            while (top < fullH) {
                val bottom = min(fullH, top + tileH)
                val raw = stitch(decoder, w, h, nextDecoder, nw, top, bottom, sample)
                if (raw != null) {
                    // Small pictures are enlarged first so small lettering can still be read
                    val up = if (raw.width < 1000) min(2f, 1200f / raw.width) else 1f
                    val bmp = if (up > 1.05f) Bitmap.createScaledBitmap(raw, (raw.width * up).toInt(), (raw.height * up).toInt(), true) else raw
                    val k = sample / (if (bmp === raw) 1f else up)
                    try {
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
                        for (block in result.textBlocks) for (line in block.lines) {
                            for (g in segments(line, marks, onMark)) {
                                found.add(Box((g.l * k).toInt(), (g.t * k).toInt() + top, (g.r * k).toInt(), (g.b * k).toInt() + top, g.text, max(1, (g.h * k).toInt())))
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (bmp !== raw) bmp.recycle()
                        raw.recycle()
                    }
                }
                if (bottom >= fullH) break
                top = bottom - overlap
            }
        } finally {
            decoder.recycle()
            nextDecoder?.recycle()
        }

        // Leave out text the previous page already read across the join
        if (carry.isNotEmpty()) {
            val skip = carry.map { Box((it.l * w).toInt(), (it.t * w).toInt(), (it.r * w).toInt(), (it.b * w).toInt(), "", 1) }
            found.removeAll { f ->
                val area = max(1, (f.r - f.l) * (f.b - f.t))
                skip.any { c ->
                    val iw = min(f.r, c.r) - max(f.l, c.l)
                    val ih = min(f.b, c.b) - max(f.t, c.t)
                    iw > 0 && ih > 0 && iw.toLong() * ih > area * 0.3
                }
            }
        }

        // Remove text seen twice where slices overlap
        val unique = ArrayList<Box>()
        for (b in found.sortedByDescending { it.text.length }) {
            val dupe = unique.any { u -> overlapShare(u, b) > 0.5f }
            if (!dupe) unique.add(b)
        }
        // Join pieces of the same bubble that were read separately
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in unique.indices) for (j in unique.indices) {
                if (i == j) continue
                val a = unique[i]; val c = unique[j]
                val gap = c.t - a.b
                val lh = max(a.lineH, c.lineH)
                val horiz = min(a.r, c.r) - max(a.l, c.l)
                if (gap in -lh..(lh * 7 / 10) && horiz > min(a.r - a.l, c.r - c.l) / 3 && c.t >= a.t) {
                    a.text = a.text + " " + c.text
                    a.l = min(a.l, c.l); a.r = max(a.r, c.r); a.b = max(a.b, c.b)
                    unique.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        // Reading order: rows from top to bottom, left to right within a row
        val ordered = unique.sortedWith(Comparator { a, c ->
            val sameRow = min(a.b, c.b) - max(a.t, c.t) > min(a.b - a.t, c.b - c.t) * 0.4
            if (sameRow) a.l.compareTo(c.l) else a.t.compareTo(c.t)
        })
        // Bubbles starting in the attached part belong to the next picture and are read with it
        val mine = ordered.filter { it.t < h }
        val nextCarry = mine.filter { it.b > h }.map {
            Carry(it.l.toFloat() / w, (it.t - h).toFloat() / w, it.r.toFloat() / w, (it.b - h).toFloat() / w)
        }
        val list = mine.mapNotNull { b ->
            val text = tidy(b.text) ?: return@mapNotNull null
            SpeechBubble(imgIndex, b.l.toFloat() / w, b.t.toFloat() / h, (b.r - b.l).toFloat() / w, (b.b - b.t).toFloat() / h, text)
        }
        return PageText(list, nextCarry)
    }

    /** Rows [top, bottom) of this picture with the next picture (scaled to the same width) joined below. */
    private fun stitch(
        decoder: BitmapRegionDecoder, w: Int, h: Int, next: BitmapRegionDecoder?, nw: Int,
        top: Int, bottom: Int, sample: Int,
    ): Bitmap? {
        if (bottom <= h || next == null) {
            return decoder.decodeRegion(Rect(0, top, w, min(bottom, h)), BitmapFactory.Options().apply { inSampleSize = sample })
        }
        val out = Bitmap.createBitmap(max(1, w / sample), max(1, (bottom - top) / sample), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (top < h) {
            decoder.decodeRegion(Rect(0, top, w, h), BitmapFactory.Options().apply { inSampleSize = sample })?.let {
                canvas.drawBitmap(it, null, RectF(0f, 0f, out.width.toFloat(), (h - top).toFloat() / sample), paint)
                it.recycle()
            }
        }
        val from = max(top, h)
        val k = nw.toFloat() / w
        val srcTop = ((from - h) * k).toInt()
        val srcBottom = max(srcTop + 1, ((bottom - h) * k).toInt())
        val nSample = max(1, (sample * k).toInt())
        next.decodeRegion(Rect(0, srcTop, nw, srcBottom), BitmapFactory.Options().apply { inSampleSize = nSample })?.let {
            canvas.drawBitmap(it, null, RectF(0f, (from - top).toFloat() / sample, out.width.toFloat(), (bottom - top).toFloat() / sample), paint)
            it.recycle()
        }
        return out
    }

    private class Seg(val l: Int, val t: Int, val r: Int, val b: Int, val text: String, val h: Int)
    private class Word(val text: String, val l: Int, val t: Int, val r: Int, val b: Int)

    /**
     * Splits one recognised line where there's a wide gap between words (e.g. the end of a
     * speech bubble and a stamp beside it), and drops watermark words, so the rest of the
     * line still joins up with its own bubble.
     */
    private fun segments(line: Text.Line, marks: Set<String>, onMark: (String) -> Unit): List<Seg> {
        val lb = line.boundingBox ?: return emptyList()
        val words = line.elements.mapNotNull { e -> e.boundingBox?.let { Word(e.text, it.left, it.top, it.right, it.bottom) } }
            .ifEmpty { listOf(Word(line.text, lb.left, lb.top, lb.right, lb.bottom)) }
        val h = max(1, lb.height())
        val keys = words.map { norm(it.text) }
        val keep = BooleanArray(words.size) { true }
        for (k in words.indices) {
            if (!markWord.matches(keys[k])) continue
            keep[k] = false
            var p = k - 1
            while (p >= max(0, k - 2) && markLead.matches(keys[p])) { keep[p] = false; p-- }
        }
        if (marks.isNotEmpty()) for (i in words.indices) {
            var acc = ""; var end = -1
            for (j in i until min(words.size, i + 6)) {
                acc += keys[j]
                if (acc.length > 30) break
                if (acc.length >= 4 && acc in marks) end = j
            }
            for (j in i..end) keep[j] = false
        }
        if (keep.any { !it }) onMark(words.filterIndexed { i, _ -> !keep[i] }.joinToString(" ") { it.text })
        val pieces = ArrayList<MutableList<Int>>()
        var cur = ArrayList<Int>()
        for (i in words.indices) {
            if (cur.isNotEmpty() && words[i].l - words[i - 1].r > h * 3 / 2) { pieces.add(cur); cur = ArrayList() }
            cur.add(i)
        }
        pieces.add(cur)
        return pieces.mapNotNull { piece ->
            val ws = piece.filter { keep[it] }.map { words[it] }
            if (ws.isEmpty()) null
            else Seg(ws.minOf { it.l }, ws.minOf { it.t }, ws.maxOf { it.r }, ws.maxOf { it.b }, ws.joinToString(" ") { it.text }, h)
        }
    }

    private fun overlapShare(a: Box, b: Box): Float {
        val iw = min(a.r, b.r) - max(a.l, b.l)
        val ih = min(a.b, b.b) - max(a.t, b.t)
        if (iw <= 0 || ih <= 0) return 0f
        val smaller = min((a.r - a.l) * (a.b - a.t), (b.r - b.l) * (b.b - b.t)).coerceAtLeast(1)
        return iw.toFloat() * ih / smaller
    }

    companion object {
        // A word that is (part of) a site stamp such as "I ♥ MANGA"
        private val markWord = Regex("^(?:i[vy]?|i?love|i?luv)?(?:manga|manhwa|manhua|webtoons?)$")
        private val markLead = Regex("^(?:|i|1|l|3|lt3|v|y|love|luv|heart)$")
        fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9]"), "")

        private val watermark = Regex("https?://|www\\.|\\.(com|net|org|io|gg|me)\\b|discord|patreon|ko-fi", RegexOption.IGNORE_CASE)

        /** Cleans up recognised text, or returns null for scraps and site watermarks. */
        private val edgePunct = Regex("^([^\\p{L}\\p{N}|]*)(.*?)([^\\p{L}\\p{N}|]*)$")
        private val unitRe = Regex("^\\d+(st|nd|rd|th|s|am|pm|k|m|kg|km|x|h)$", RegexOption.IGNORE_CASE)
        private val tinyUnitRe = Regex("^[01](h|k|x|m|s)$", RegexOption.IGNORE_CASE)
        private val loneI = Regex("^[1|l]((['\u2019])(m|ll|d|ve))?$", RegexOption.IGNORE_CASE)

        private fun isWord(w: String?): Boolean =
            w != null && Regex("\\p{L}{2,}").containsMatchIn(w) && !w.any { it.isDigit() }

        /** Fixes letters misread as digits in comic lettering: "1 HEARD" -> "I HEARD", "N0" -> "NO". */
        fun fixDigits(text: String): String {
            val words = text.split(" ")
            val out = words.toMutableList()
            for (i in words.indices) {
                val m = edgePunct.find(words[i]) ?: continue
                val pre = m.groupValues[1]
                var core = m.groupValues[2]
                val post = m.groupValues[3]
                if (core.isEmpty()) continue
                val next = words.getOrNull(i + 1)
                val prev = words.getOrNull(i - 1)
                if (loneI.matches(core) && (isWord(next) || core.length > 1)) {
                    if (!(core == "l" && !isWord(next))) core = "I" + core.substring(1)
                } else if (core == "0" && (isWord(next) || isWord(prev))) {
                    core = "O"
                } else if (core.any { it.isLetter() } && core.any { it in "0158|" } &&
                    !(unitRe.matches(core) && !tinyUnitRe.matches(core))
                ) {
                    core = core.map { when (it) { '0' -> 'O'; '1' -> 'I'; '5' -> 'S'; '8' -> 'B'; '|' -> 'I'; else -> it } }.joinToString("")
                }
                out[i] = pre + core + post
            }
            return out.joinToString(" ")
        }

        fun tidy(raw: String): String? {
            var t = fixDigits(raw.replace(Regex("\\s+"), " ").trim())
            t = t.replace(Regex("(\\p{L})- (\\p{L})"), "$1$2")          // re-join hyphenated line breaks
            if (watermark.containsMatchIn(t)) return null
            val letters = t.count { it.isLetter() }
            if (letters < 2 || letters < t.length / 3) return null
            // Comic lettering is often ALL CAPS, which voices read awkwardly
            val upper = t.count { it.isUpperCase() }
            if (upper > letters * 0.7) {
                t = t.lowercase()
                t = Regex("(^|[.!?]\\s+)(\\p{Ll})").replace(t) { it.groupValues[1] + it.groupValues[2].uppercase() }
                t = Regex("\\bi\\b").replace(t, "I")
                t = Regex("\\bi'").replace(t, "I'")
            }
            return t
        }
    }
}
