package com.stafftoolkit.app

import java.util.Locale

/**
 * Chat rules: decides which chat messages need a staff member's attention.
 * Same rules as the PC app, so both flag the same things.
 */
class ChatRules {
    data class Hit(val player: String?, val message: String, val reasons: List<String>)

    var advertising = true
    var words = true
    var spam = true
    var caps = true
    var flood = true
    var blockedWords: List<String> = emptyList()
    var allowDomains: List<String> = listOf("youtube.com", "youtu.be", "twitch.tv", "imgur.com")
    var watchPlayers: List<String> = emptyList()
    var ignorePlayers: List<String> = emptyList()

    private class Recent(val t: Long, val norm: String)
    private val history = HashMap<String, MutableList<Recent>>()

    companion object {
        private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b', '9' to 'g',
            '@' to 'a', '$' to 's', '!' to 'i', '|' to 'i', '+' to 't', '€' to 'e')
        private const val TLD = "(?:net|com|org|gg|me|io|co|uk|club|xyz|fun|world|pro|us|eu|tk|ml|ga|cf|online|live|games?|network|mc|land|host|cc)"
        private val DOMAIN = Regex("\\b((?:[a-z0-9-]+\\.)+$TLD)\\b", RegexOption.IGNORE_CASE)
        private val IP = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{2,5})?\\b")
        private val INVITE = Regex("discord(?:\\.gg|app\\.com/invite|\\.com/invite)\\s*/?\\s*[a-z0-9-]+", RegexOption.IGNORE_CASE)
        private val ANGLE = Regex("^<([^>]{1,40})>\\s?(.*)$")
        private val SEP = Regex("(?::|»|>>|>|➤|→)\\s")
        private val BRACKETS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}")
        private val NAME = Regex("[A-Za-z0-9_]{2,16}")

        /** Splits "[Rank] Name: hello" / "<Name> hello" / "Name » hello" into who said it and what. */
        fun splitSender(text: String): Pair<String?, String> {
            ANGLE.find(text)?.let { m ->
                val inside = m.groupValues[1]
                return Pair(lastName(inside) ?: inside.trim(), m.groupValues[2])
            }
            val head = text.take(80)
            val m = SEP.find(head)
            if (m != null && m.range.first > 0) {
                val p = lastName(text.substring(0, m.range.first))
                if (p != null) return Pair(p, text.substring(m.range.last + 1))
            }
            return Pair(null, text)
        }

        private fun lastName(prefix: String): String? =
            NAME.findAll(prefix.replace(BRACKETS, " ")).lastOrNull()?.value

        /** Lower case, undoes l33t speak and stretched letters, so "Fr33eeee" matches "free". */
        fun normalize(s: String): String {
            val sb = StringBuilder()
            for (c in s.lowercase(Locale.ROOT)) sb.append(LEET[c] ?: c)
            return sb.toString().replace(Regex("[^a-z\\s]"), "").replace(Regex("(.)\\1{2,}"), "$1$1")
        }

        fun findAdvert(message: String, allow: List<String>): String? {
            val undisguised = message
                .replace(Regex("\\s*[(\\[{]\\s*(?:dot|d0t|\\.)\\s*[)\\]}]\\s*", RegexOption.IGNORE_CASE), ".")
                .replace(Regex("\\s+(?:dot|d0t)\\s+", RegexOption.IGNORE_CASE), ".")
                .replace(Regex("\\s*\\.\\s*"), ".")
            for (s in listOf(message, undisguised)) {
                if (INVITE.containsMatchIn(s)) return "Discord invite"
                if (IP.containsMatchIn(s)) return "IP address"
                val m = DOMAIN.find(s)
                if (m != null) {
                    val d = m.groupValues[1].lowercase(Locale.ROOT)
                    if (allow.none { d == it || d.endsWith(".$it") }) return "Web/server address ($d)"
                }
            }
            return null
        }
    }

    fun check(text: String, time: Long = System.currentTimeMillis()): Hit? {
        val (player, message) = splitSender(text)
        val key = (player ?: "").lowercase(Locale.ROOT)
        if (key.isNotEmpty() && ignorePlayers.any { it.equals(key, true) }) return null
        val reasons = ArrayList<String>()
        if (key.isNotEmpty() && watchPlayers.any { it.equals(key, true) }) reasons.add("Watched player")
        if (player != null) {
            if (advertising) findAdvert(message, allowDomains.map { it.lowercase(Locale.ROOT) })?.let { reasons.add("Advertising: $it") }
            if (words && blockedWords.isNotEmpty()) {
                val norm = normalize(message)
                val tokens = norm.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
                val squashed = norm.replace(Regex("\\s+"), "")
                val hit = blockedWords.firstOrNull { w ->
                    val n = normalize(w).replace(Regex("\\s+"), "")
                    n.length >= 2 && (n in tokens || (n.length >= 4 && squashed.contains(n)))
                }
                if (hit != null) reasons.add("Blocked word: $hit")
            }
            if (caps) {
                val letters = message.filter { it in 'A'..'Z' || it in 'a'..'z' }
                if (letters.length >= 8 && letters.count { it in 'A'..'Z' }.toFloat() / letters.length > 0.7f) reasons.add("Caps")
            }
            if (flood) {
                if (Regex("(.)\\1{7,}").containsMatchIn(message.replace(Regex("\\s"), ""))) reasons.add("Character flood")
                else {
                    val ws = message.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (ws.size >= 5 && ws.groupingBy { it }.eachCount().values.any { it >= 5 }) reasons.add("Word flood")
                }
            }
            if (spam) {
                val h = (history[key] ?: ArrayList()).filter { time - it.t < 30000 }.toMutableList()
                val norm = normalize(message).trim()
                h.add(Recent(time, norm))
                history[key] = h
                val same = h.count { it.norm == norm && norm.isNotEmpty() }
                val fast = h.count { time - it.t < 10000 }
                if (same >= 3) reasons.add("Repeated message (${same}×)")
                else if (fast >= 6) reasons.add("Chat spam ($fast in 10s)")
            }
            if (history.size > 2000) history.clear()
        }
        return if (reasons.isEmpty()) null else Hit(player, message, reasons)
    }
}
