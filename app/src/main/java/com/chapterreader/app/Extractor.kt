package com.chapterreader.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class Chapter(
    val url: String,
    val title: String,
    val heading: String,
    val paras: List<String>,
    val next: String?,
    val prev: String?,
    /** Page images, for manga / webtoon chapters (empty for novels). */
    val images: List<String> = emptyList(),
) {
    val isManga: Boolean get() = images.isNotEmpty()
}

/**
 * Finds the story text and the next/previous chapter links in a page.
 * Uses the same rules as the in-page script in [Scripts], so paragraph
 * numbers line up for highlighting.
 */
object Extractor {
    private val ws = Regex("\\s+")
    private val headingRe = Regex("chap|ch\\.?\\s*\\d|episode|part\\s*\\d", RegexOption.IGNORE_CASE)
    private val nextRel = Regex("\\bnext\\b")
    private val prevRelLink = Regex("\\bprev(ious)?\\b")
    private val prevRel = Regex("\\bprev")
    private val chapRe = Regex("chap|\\bch\\b")
    private val disabledRe = Regex("\\bdisabled\\b")
    private val sentenceRe =
        Regex("[^.!?…]+[.!?…]+[\"'”’)\\]]*\\s*|[^.!?…]+$")

    fun clean(s: String?): String = (s ?: "").replace(ws, " ").trim()

    fun parse(html: String, url: String): Chapter? {
        val doc = Jsoup.parse(html, url)
        val paras = paragraphs(doc)
        val images = images(doc)
        val (next, prev) = links(doc, url)
        // A page that's mostly pictures with little text is a manga / webtoon chapter
        if (images.size >= 2 && paras.sumOf { it.length } < 400) {
            return Chapter(url, clean(doc.title()), heading(doc), emptyList(), next, prev, images)
        }
        if (paras.isEmpty()) return null
        return Chapter(url, clean(doc.title()), heading(doc), paras, next, prev)
    }

    private val skipImg = Regex("logo|avatar|icon|banner|emoji|gravatar|sprite|/ads?/|advert|loading|placeholder|spinner|thumb", RegexOption.IGNORE_CASE)

    /** The address of a picture, including lazy-loaded ones that keep it in data-src etc. */
    private fun imageUrl(img: Element): String? {
        for (a in listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "data-pagespeed-lazy-src")) {
            val v = img.absUrl(a)
            if (v.startsWith("http")) return v
        }
        val srcset = img.attr("data-srcset").ifEmpty { img.attr("srcset") }
        if (srcset.isNotBlank()) {
            val last = srcset.split(',').map { it.trim().substringBefore(' ') }.lastOrNull { it.isNotBlank() }
            if (last != null) {
                val abs = try { java.net.URL(java.net.URL(img.baseUri()), last).toString() } catch (_: Exception) { "" }
                if (abs.startsWith("http")) return abs
            }
        }
        val src = img.absUrl("src")
        return if (src.startsWith("http")) src else null
    }

    /** The chapter's page images: the biggest group of pictures that sit together. */
    fun images(doc: Document): List<String> {
        val all = doc.select("img").filter { img ->
            val u = imageUrl(img) ?: return@filter false
            if (skipImg.containsMatchIn(u) || u.endsWith(".svg") || u.endsWith(".gif")) return@filter false
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            !((w != null && w < 150) || (h != null && h < 150))
        }
        if (all.size < 2) return emptyList()
        // Count pictures per parent and grandparent; the busiest one holds the pages
        val counts = ArrayList<Pair<Element, IntArray>>()
        fun add(e: Element?) {
            if (e == null) return
            val g = counts.firstOrNull { it.first === e }
            if (g == null) counts.add(e to intArrayOf(1)) else g.second[0]++
        }
        for (img in all) { add(img.parent()); add(img.parent()?.parent()); add(img.parent()?.parent()?.parent()) }
        var best: Pair<Element, IntArray>? = null
        for (c in counts) if (best == null || c.second[0] > best.second[0]) best = c
        val box = best?.first ?: return emptyList()
        if (best.second[0] < 2) return emptyList()
        val out = LinkedHashSet<String>()
        for (img in all) if (img.parents().any { it === box }) imageUrl(img)?.let { out.add(it) }
        return out.toList()
    }

    private fun paragraphs(doc: Document): List<String> {
        val groups = ArrayList<Pair<Element, IntArray>>()
        for (p in doc.select("p")) {
            val par = p.parent() ?: continue
            val t = clean(p.text())
            if (t.isEmpty()) continue
            val g = groups.firstOrNull { it.first === par }
            if (g == null) groups.add(par to intArrayOf(t.length)) else g.second[0] += t.length
        }
        var best: Pair<Element, IntArray>? = null
        for (g in groups) if (best == null || g.second[0] > best.second[0]) best = g
        if (best != null && best.second[0] > 300) {
            return best.first.children().toList()
                .filter { it.tagName() == "p" }
                .map { clean(it.text()) }
                .filter { it.isNotEmpty() }
        }

        // Fallback: text split up by <br> tags
        val brGroups = ArrayList<Pair<Element, IntArray>>()
        for (br in doc.select("br")) {
            val par = br.parent() ?: continue
            val g = brGroups.firstOrNull { it.first === par }
            if (g == null) brGroups.add(par to intArrayOf(1)) else g.second[0]++
        }
        var bestBr: Pair<Element, IntArray>? = null
        for (g in brGroups) if (bestBr == null || g.second[0] > bestBr.second[0]) bestBr = g
        if (bestBr == null || bestBr.second[0] < 5) return emptyList()
        val clone = bestBr.first.clone()
        clone.select("script,style,noscript").remove()
        for (br in clone.select("br")) br.replaceWith(TextNode("\n"))
        return clone.wholeText().split('\n').map { clean(it) }.filter { it.isNotEmpty() }
    }

    private val strongHeadingRe = Regex("\\b(chapter|ch\\.?|episode|part)\\s*\\d", RegexOption.IGNORE_CASE)
    private val commentRe = Regex("comment", RegexOption.IGNORE_CASE)

    private fun heading(doc: Document): String {
        val hs = doc.select("h1,h2,h3,h4").map { clean(it.text()) }
            .filter { it.length < 150 && !commentRe.containsMatchIn(it) }
        return hs.firstOrNull { strongHeadingRe.containsMatchIn(it) }
            ?: hs.firstOrNull { headingRe.containsMatchIn(it) } ?: ""
    }

    private fun links(doc: Document, base: String): Pair<String?, String?> {
        var next: String? = null
        var prev: String? = null
        var bestNext = 0
        var bestPrev = 0
        val baseNoHash = base.substringBefore('#')

        fun abs(e: Element): String? {
            val u = e.absUrl("href")
            if (u.isEmpty() || u.substringBefore('#') == baseNoHash) return null
            if (!u.startsWith("http")) return null
            return u
        }

        for (l in doc.select("link[rel][href]")) {
            val rel = l.attr("rel").lowercase()
            val u = abs(l) ?: continue
            if (nextRel.containsMatchIn(rel) && bestNext < 3) { next = u; bestNext = 3 }
            if (prevRelLink.containsMatchIn(rel) && bestPrev < 3) { prev = u; bestPrev = 3 }
        }
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", ignoreCase = true)) continue
            val cls = a.attr("class").lowercase()
            if (disabledRe.containsMatchIn(cls) || a.attr("aria-disabled") == "true") continue
            val t = (clean(a.text()) + " " + a.attr("title") + " " + a.attr("aria-label") + " " +
                a.id() + " " + cls).lowercase()
            val rel = a.attr("rel").lowercase()
            val chap = if (chapRe.containsMatchIn(t)) 1 else 0
            val sn = (if (nextRel.containsMatchIn(rel)) 3 else 0) + (if (t.contains("next")) 2 else 0)
            if (sn > 0 && sn + chap > bestNext) {
                val u = abs(a)
                if (u != null) { next = u; bestNext = sn + chap }
            }
            val sp = (if (prevRel.containsMatchIn(rel)) 3 else 0) + (if (t.contains("prev")) 2 else 0)
            if (sp > 0 && sp + chap > bestPrev) {
                val u = abs(a)
                if (u != null) { prev = u; bestPrev = sp + chap }
            }
        }
        return Pair(next, prev)
    }

    /** Splits a paragraph into short pieces that the voice engine handles well. */
    fun chunks(text: String, max: Int = 300): List<String> {
        val parts = sentenceRe.findAll(text).map { it.value }.toList().ifEmpty { listOf(text) }
        val sentences = ArrayList<String>()
        for (part in parts) {
            var s = part.trim()
            if (s.isEmpty()) continue
            while (s.length > 350) {
                var cut = s.lastIndexOf(", ", 350)
                if (cut < 100) cut = s.lastIndexOf(' ', 350)
                if (cut < 100) cut = 350
                sentences.add(s.substring(0, cut + 1).trim())
                s = s.substring(cut + 1).trim()
            }
            if (s.isNotEmpty()) sentences.add(s)
        }
        val out = ArrayList<String>()
        var cur = ""
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > max) {
                out.add(cur); cur = s
            } else {
                cur = if (cur.isEmpty()) s else "$cur $s"
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}
