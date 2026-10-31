package com.stafftoolkit.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Uploads captures to Catbox (gives a link anyone can open) and/or posts to a Discord channel. */
object Uploader {
    const val CATBOX_LIMIT = 200L * 1024 * 1024
    const val DISCORD_LIMIT = 10L * 1024 * 1024

    private class Part(val name: String, val value: String? = null, val uri: Uri? = null, val fileName: String? = null, val type: String? = null)

    private fun multipart(ctx: Context, url: String, parts: List<Part>): Pair<Int, String> {
        val boundary = "----StaffToolkit" + System.nanoTime()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 180000
        c.setChunkedStreamingMode(256 * 1024)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setRequestProperty("User-Agent", "StaffToolkit/1.0")
        DataOutputStream(c.outputStream).use { out ->
            for (p in parts) {
                out.writeBytes("--$boundary\r\n")
                if (p.uri != null) {
                    out.writeBytes("Content-Disposition: form-data; name=\"${p.name}\"; filename=\"${p.fileName}\"\r\n")
                    out.writeBytes("Content-Type: ${p.type}\r\n\r\n")
                    ctx.contentResolver.openInputStream(p.uri)!!.use { it.copyTo(out, 256 * 1024) }
                    out.writeBytes("\r\n")
                } else {
                    out.writeBytes("Content-Disposition: form-data; name=\"${p.name}\"\r\n")
                    if (p.type != null) out.writeBytes("Content-Type: ${p.type}\r\n")
                    out.writeBytes("\r\n")
                    out.write(p.value!!.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
            }
            out.writeBytes("--$boundary--\r\n")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, body)
    }

    private fun mime(name: String) = if (name.endsWith(".png", true)) "image/png" else "video/mp4"

    /** Uploads to Catbox and returns the link. */
    fun catbox(ctx: Context, uri: Uri, name: String, size: Long): String {
        if (size > CATBOX_LIMIT) throw Exception("the file is over Catbox's 200 MB limit")
        val (code, body) = multipart(ctx, "https://catbox.moe/user/api.php", listOf(
            Part("reqtype", "fileupload"),
            Part("fileToUpload", uri = uri, fileName = name, type = mime(name)),
        ))
        val link = body.trim()
        if (code !in 200..299 || !link.startsWith("https://")) throw Exception("Catbox said: ${link.take(120)}")
        return link
    }

    fun validWebhook(url: String?) =
        url != null && Regex("^https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+").containsMatchIn(url.trim())

    /** Posts to a Discord channel; attaches the file when it's small enough. Returns the attachment link if any. */
    fun discord(ctx: Context, webhook: String, content: String, file: Uri? = null, name: String? = null, size: Long = 0, embed: JSONObject? = null): String? {
        if (!validWebhook(webhook)) throw Exception("the Discord webhook link isn't valid")
        val payload = JSONObject().put("content", content.take(1900)).put("username", "Staff Toolkit")
            .put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        if (embed != null) payload.put("embeds", JSONArray().put(embed))
        val url = webhook.trim() + (if (webhook.contains("?")) "&" else "?") + "wait=true"
        val parts = mutableListOf(Part("payload_json", payload.toString(), type = "application/json"))
        if (file != null && name != null && size in 1..DISCORD_LIMIT) parts.add(Part("files[0]", uri = file, fileName = name, type = mime(name)))
        val (code, body) = multipart(ctx, url, parts)
        if (code !in 200..299) throw Exception("Discord said $code: ${body.take(120)}")
        return try { JSONObject(body).optJSONArray("attachments")?.optJSONObject(0)?.optString("url") } catch (_: Exception) { null }
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Link", text))
    }

    /**
     * Uploads a capture the way the settings say (Catbox, Discord or both) and copies the link.
     * Returns the link, or null if it was only posted to Discord. Call from a background thread.
     */
    fun send(ctx: Context, cap: Store.Capture, flag: Store.Flag? = null): String? {
        val p = Store.prefs(ctx)
        val to = p.getString("uploadTo", "catbox") ?: "catbox"
        val uri = Uri.parse(cap.uri)
        var link: String? = null
        if (to == "catbox" || to == "both" || to == "none") link = catbox(ctx, uri, cap.name, cap.size)
        if (to == "discord" || to == "both") {
            val hook = p.getString("webhook", "") ?: ""
            val label = when (cap.kind) { "screenshot" -> "Screenshot"; "clip" -> "Clip"; else -> "Recording" }
            val head = if (flag != null) "**${flag.player ?: "?"}**: ${flag.message} (${flag.reasons.joinToString()})\n" else "$label from Staff Toolkit\n"
            val tooBig = cap.size > DISCORD_LIMIT
            if (tooBig && link == null) link = catbox(ctx, uri, cap.name, cap.size)   // too big for Discord: post a link instead
            val att = discord(ctx, hook, head + (link ?: ""), if (tooBig) null else uri, cap.name, cap.size)
            link = link ?: att
        }
        Store.updateCapture(ctx, cap.id) { it.link = link }
        if (link != null) copy(ctx, link)
        return link
    }
}
