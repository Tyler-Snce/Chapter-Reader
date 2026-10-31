package com.chapterreader.app

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
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
    fun bubbles(file: File, imgIndex: Int): List<SpeechBubble> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return emptyList()

        val decoder = try { BitmapRegionDecoder.newInstance(file.path, false) } catch (_: Exception) { null }
            ?: return emptyList()
        val sample = if (w > 1600) 2 else 1
        // Long webtoon strips are read in overlapping slices so small text stays readable
        val tileH = min(h, w * 2)
        val overlap = min(tileH / 4, w / 3)
        val found = ArrayList<Box>()
        var top = 0
        try {
            while (top < h) {
                val bottom = min(h, top + tileH)
                val bmp = decoder.decodeRegion(Rect(0, top, w, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
                if (bmp != null) {
                    val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
                    for (block in result.textBlocks) {
                        val r = block.boundingBox ?: continue
                        val lines = block.lines
                        val lineH = if (lines.isNotEmpty()) (r.height() / lines.size) * sample else r.height() * sample
                        val text = lines.joinToString(" ") { it.text }
                        found.add(Box(r.left * sample, r.top * sample + top, r.right * sample, r.bottom * sample + top, text, max(1, lineH)))
                    }
                    bmp.recycle()
                }
                if (bottom >= h) break
                top = bottom - overlap
            }
        } finally {
            decoder.recycle()
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
        return ordered.mapNotNull { b ->
            val text = tidy(b.text) ?: return@mapNotNull null
            SpeechBubble(imgIndex, b.l.toFloat() / w, b.t.toFloat() / h, (b.r - b.l).toFloat() / w, (b.b - b.t).toFloat() / h, text)
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
        private val watermark = Regex("https?://|www\\.|\\.(com|net|org|io|gg|me)\\b|discord|patreon|ko-fi", RegexOption.IGNORE_CASE)

        /** Cleans up recognised text, or returns null for scraps and site watermarks. */
        fun tidy(raw: String): String? {
            var t = raw.replace(Regex("\\s+"), " ").trim()
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
