package com.stafftoolkit.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Settings, flagged messages and captures, saved on the phone. */
object Store {
    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("toolkit", Context.MODE_PRIVATE)

    data class Flag(
        val id: String, val time: Long, val player: String?, val message: String,
        val reasons: List<String>, var done: Boolean = false, var evidence: String? = null,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("time", time).put("player", player ?: JSONObject.NULL)
            .put("message", message).put("reasons", JSONArray(reasons)).put("done", done).put("evidence", evidence ?: JSONObject.NULL)

        companion object {
            fun from(o: JSONObject) = Flag(
                o.getString("id"), o.optLong("time"), o.optString("player").takeIf { !o.isNull("player") && it.isNotEmpty() },
                o.optString("message"), (0 until (o.optJSONArray("reasons")?.length() ?: 0)).map { o.getJSONArray("reasons").getString(it) },
                o.optBoolean("done"), o.optString("evidence").takeIf { !o.isNull("evidence") && it.isNotEmpty() },
            )
        }
    }

    data class Capture(
        val id: String, val uri: String, val name: String, val kind: String, val time: Long,
        val size: Long, var link: String? = null, var flagId: String? = null,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("uri", uri).put("name", name).put("kind", kind).put("time", time)
            .put("size", size).put("link", link ?: JSONObject.NULL).put("flagId", flagId ?: JSONObject.NULL)

        companion object {
            fun from(o: JSONObject) = Capture(
                o.getString("id"), o.getString("uri"), o.optString("name"), o.optString("kind"), o.optLong("time"), o.optLong("size"),
                o.optString("link").takeIf { !o.isNull("link") && it.isNotEmpty() },
                o.optString("flagId").takeIf { !o.isNull("flagId") && it.isNotEmpty() },
            )
        }
    }

    @Synchronized
    fun flags(ctx: Context): MutableList<Flag> = try {
        val a = JSONArray(prefs(ctx).getString("flags", "[]"))
        (0 until a.length()).map { Flag.from(a.getJSONObject(it)) }.toMutableList()
    } catch (_: Exception) { ArrayList() }

    @Synchronized
    fun saveFlags(ctx: Context, list: List<Flag>) {
        val a = JSONArray(); list.take(400).forEach { a.put(it.json()) }
        prefs(ctx).edit().putString("flags", a.toString()).apply()
    }

    @Synchronized
    fun addFlag(ctx: Context, f: Flag) { val l = flags(ctx); l.add(0, f); saveFlags(ctx, l) }

    @Synchronized
    fun updateFlag(ctx: Context, id: String, change: (Flag) -> Unit) {
        val l = flags(ctx); l.find { it.id == id }?.let(change); saveFlags(ctx, l)
    }

    @Synchronized
    fun captures(ctx: Context): MutableList<Capture> = try {
        val a = JSONArray(prefs(ctx).getString("captures", "[]"))
        (0 until a.length()).map { Capture.from(a.getJSONObject(it)) }.toMutableList()
    } catch (_: Exception) { ArrayList() }

    @Synchronized
    fun saveCaptures(ctx: Context, list: List<Capture>) {
        val a = JSONArray(); list.take(300).forEach { a.put(it.json()) }
        prefs(ctx).edit().putString("captures", a.toString()).apply()
    }

    @Synchronized
    fun addCapture(ctx: Context, c: Capture) { val l = captures(ctx); l.add(0, c); saveCaptures(ctx, l) }

    @Synchronized
    fun updateCapture(ctx: Context, id: String, change: (Capture) -> Unit) {
        val l = captures(ctx); l.find { it.id == id }?.let(change); saveCaptures(ctx, l)
    }

    fun lines(p: SharedPreferences, key: String, def: String = ""): List<String> =
        (p.getString(key, def) ?: def).split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** The chat rules as set in the app. */
    fun rules(ctx: Context): ChatRules {
        val p = prefs(ctx)
        return ChatRules().apply {
            advertising = p.getBoolean("r_advertising", true)
            words = p.getBoolean("r_words", true)
            spam = p.getBoolean("r_spam", true)
            caps = p.getBoolean("r_caps", true)
            flood = p.getBoolean("r_flood", true)
            blockedWords = lines(p, "blockedWords")
            allowDomains = lines(p, "allowDomains", "youtube.com\nyoutu.be\ntwitch.tv\nimgur.com")
            watchPlayers = lines(p, "watchPlayers")
            ignorePlayers = lines(p, "ignorePlayers")
        }
    }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000..9999).random()
}
