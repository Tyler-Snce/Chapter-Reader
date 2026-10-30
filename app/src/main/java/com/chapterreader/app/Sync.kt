package com.chapterreader.app

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Saves "which chapter and paragraph am I on" online under your username,
 * so the phone and PC apps can each continue where the other left off.
 */
object Sync {
    /** Your Firebase Realtime Database address. */
    const val DB = "https://chapter-reader-default-rtdb.europe-west1.firebasedatabase.app"

    val configured: Boolean get() = DB.startsWith("https://")

    /** Turns a username (and optional PIN) into the key progress is stored under. */
    fun key(prefs: SharedPreferences): String? {
        val name = (prefs.getString("syncUser", "") ?: "").trim().lowercase()
            .replace(Regex("[^a-z0-9_-]"), "")
        if (name.isEmpty()) return null
        val pin = (prefs.getString("syncPin", "") ?: "").trim()
        if (pin.isEmpty()) return name
        val hash = MessageDigest.getInstance("SHA-256").digest("$name:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return name + "-" + hash.take(10)
    }

    private fun url(key: String) = "${DB.trimEnd('/')}/progress/$key.json"

    /** Uploads progress. Call from a background thread. */
    fun push(prefs: SharedPreferences) {
        if (!configured) return
        val key = key(prefs) ?: return
        val link = prefs.getString("resumeUrl", null) ?: return
        val body = JSONObject()
            .put("url", link)
            .put("para", prefs.getInt("resumePara", 0))
            .put("title", prefs.getString("resumeTitle", "") ?: "")
            .put("updated", prefs.getLong("resumeUpdated", System.currentTimeMillis()))
            .put("device", "phone")
            .toString()
        try {
            val c = URL(url(key)).openConnection() as HttpURLConnection
            c.requestMethod = "PUT"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
            c.disconnect()
        } catch (_: Exception) {}
    }

    /**
     * Downloads the saved progress and, if it's newer than what this phone has,
     * stores it as the place to continue from. Returns true if it changed anything.
     * Call from a background thread.
     */
    fun pull(prefs: SharedPreferences): Boolean {
        if (!configured) return false
        val key = key(prefs) ?: return false
        return try {
            val c = URL(url(key)).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            if (text.isBlank() || text == "null") return false
            val o = JSONObject(text)
            val updated = o.optLong("updated", 0)
            if (updated <= prefs.getLong("resumeUpdated", 0)) return false
            prefs.edit()
                .putString("resumeUrl", o.getString("url"))
                .putInt("resumePara", o.optInt("para", 0))
                .putString("resumeTitle", o.optString("title"))
                .putLong("resumeUpdated", updated)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }
}
