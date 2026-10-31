package com.chapterreader.app

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * One saved place per series (each manga or novel you're reading), so you can switch
 * between them and carry on where you left off in each.
 */
object Library {
    data class Entry(
        val id: String,
        val series: String,
        val url: String,
        val para: Int,
        val title: String,
        val updated: Long,
        val manga: Boolean,
        val deleted: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("series", series).put("url", url).put("para", para).put("title", title)
            .put("updated", updated).put("manga", manga).put("deleted", deleted)

        companion object {
            fun from(id: String, o: JSONObject) = Entry(
                id, o.optString("series"), o.optString("url"), o.optInt("para", 0), o.optString("title"),
                o.optLong("updated", 0), o.optBoolean("manga", false), o.optBoolean("deleted", false),
            )
        }
    }

    private val chapterWord = Regex("(?i)\\b(chapter|chap|ch|episode|ep|part|vol|volume)\\b\\.?\\s*\\d")
    private val chapterSlug = Regex("(?i)[-_]*(chapter|chap|ch|episode|ep|part)[-_]?\\d.*$")

    /** Works out which series a chapter belongs to: an id to save it under, and a name to show. */
    fun seriesOf(url: String, pageTitle: String): Pair<String, String> {
        val u = try { URI(url) } catch (_: Exception) { null }
        val host = (u?.host ?: "").lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        var segs = (u?.path ?: "").split('/').filter { it.isNotEmpty() }.toMutableList()
        // Drop the chapter part of the address: ".../chapter/123/...", ".../chapter-12", ".../12.html", "name-chapter-12"
        val at = segs.indexOfFirst { Regex("(?i)^(chapters?|chap|episodes?|ep|read)$").matches(it) }
        if (at > 0) segs = segs.subList(0, at).toMutableList()
        else if (segs.isNotEmpty()) {
            val last = segs.last()
            val cut = last.replace(chapterSlug, "")
            if (Regex("^\\d+(\\.\\w+)?$").matches(last) || cut.length < 3) segs.removeAt(segs.size - 1)
            else segs[segs.size - 1] = cut
        }
        val slug = segs.lastOrNull() ?: ""
        val id = (host + "_" + segs.joinToString("_")).lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "_").take(120)

        // Name: the page title before "Chapter 12", or else the address made readable
        var name = ""
        val m = chapterWord.find(pageTitle)
        if (m != null) name = pageTitle.substring(0, m.range.first).trim().trimEnd('-', '–', '—', ':', '|', ',', '·', ' ')
        if (name.length < 3) {
            name = slug.replace(Regex("\\.\\w+$"), "").replace(Regex("[-_]+"), " ").trim()
                .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        }
        if (name.isEmpty()) name = pageTitle.ifEmpty { host }
        return Pair(id.ifEmpty { "page" }, name)
    }

    fun load(prefs: SharedPreferences): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        try {
            val o = JSONObject(prefs.getString("library", "{}") ?: "{}")
            for (k in o.keys()) out[k] = Entry.from(k, o.getJSONObject(k))
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    fun save(prefs: SharedPreferences, map: Map<String, Entry>) {
        val o = JSONObject()
        for ((k, e) in map) o.put(k, e.toJson())
        prefs.edit().putString("library", o.toString()).apply()
    }

    /** The saves you can switch between, most recent first. */
    fun list(prefs: SharedPreferences): List<Entry> =
        load(prefs).values.filter { !it.deleted && it.url.isNotEmpty() }.sortedByDescending { it.updated }

    @Synchronized
    fun record(prefs: SharedPreferences, url: String, pageTitle: String, chapterTitle: String, para: Int, manga: Boolean) {
        val (id, name) = seriesOf(url, pageTitle)
        val map = load(prefs)
        map[id] = Entry(id, name, url, para, chapterTitle, System.currentTimeMillis(), manga)
        save(prefs, map)
    }

    @Synchronized
    fun remove(prefs: SharedPreferences, id: String) {
        val map = load(prefs)
        val e = map[id] ?: return
        // Kept as "deleted" so the other device learns about it too
        map[id] = e.copy(deleted = true, updated = System.currentTimeMillis())
        save(prefs, map)
    }

    /** Takes in saves from the other device, keeping whichever is newer. Returns true if anything changed. */
    @Synchronized
    fun merge(prefs: SharedPreferences, remote: JSONObject?): Boolean {
        if (remote == null) return false
        val map = load(prefs)
        var changed = false
        for (k in remote.keys()) {
            val r = try { Entry.from(k, remote.getJSONObject(k)) } catch (_: Exception) { continue }
            val mine = map[k]
            if (mine == null || r.updated > mine.updated) { map[k] = r; changed = true }
        }
        if (changed) save(prefs, map)
        return changed
    }

    fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            s < 86400 * 30 -> "${s / 86400} days ago"
            else -> "${s / (86400 * 30)} months ago"
        }
    }
}
