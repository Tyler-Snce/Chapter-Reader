package com.chapterreader.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Microsoft's natural online voices (the ones Edge's Read Aloud uses): turns text into MP3 audio,
 * with the time each word is spoken, for the word highlight. Connection details follow the
 * edge-tts project (github.com/rany2/edge-tts). Call from a background thread.
 */
object EdgeTts {
    /** Voice id to the name shown in the app. "" means the phone's own voice (SherpaTTS, Google...). */
    val VOICES = listOf(
        "en-US-AvaMultilingualNeural" to "Ava",
        "en-US-EmmaMultilingualNeural" to "Emma",
        "en-US-AndrewMultilingualNeural" to "Andrew",
        "en-US-BrianMultilingualNeural" to "Brian",
        "en-AU-WilliamMultilingualNeural" to "William",
        "en-GB-SoniaNeural" to "Sonia",
        "en-GB-RyanNeural" to "Ryan",
    )
    val DESCRIPTIONS = mapOf(
        "en-US-AvaMultilingualNeural" to "expressive, caring (American)",
        "en-US-EmmaMultilingualNeural" to "cheerful, clear (American)",
        "en-US-AndrewMultilingualNeural" to "warm, confident (American)",
        "en-US-BrianMultilingualNeural" to "casual, relaxed (American)",
        "en-AU-WilliamMultilingualNeural" to "friendly (Australian)",
        "en-GB-SoniaNeural" to "British",
        "en-GB-RyanNeural" to "British",
    )
    fun name(id: String?) = VOICES.find { it.first == id }?.second ?: "Phone voice"

    private const val CHROMIUM = "143.0.3650.75"
    private const val BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
    // If the phone's clock is off, Microsoft refuses the token; it's corrected from the server's time
    @Volatile private var skewSeconds = 0.0

    class Word(val ms: Long, val start: Int, val end: Int)
    class Result(val audio: ByteArray, val words: List<Word>)

    private fun stamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun id() = UUID.randomUUID().toString().replace("-", "")

    /** [rate] 1 = normal, 1.5 = 50% faster. Tries once more if Microsoft drops or refuses the connection. */
    fun synth(text: String, voice: String, rate: Float): Result {
        return try { attempt(text, voice, rate) } catch (e: Exception) { Thread.sleep(400); attempt(text, voice, rate) }
    }

    private fun attempt(text: String, voice: String, rate: Float): Result {
        val major = CHROMIUM.substringBefore('.')
        val muid = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        val url = "$BASE?TrustedClientToken=${Gec.TOKEN}&Sec-MS-GEC=${Gec.token(System.currentTimeMillis() / 1000.0 + skewSeconds)}" +
            "&Sec-MS-GEC-Version=2-$CHROMIUM&ConnectionId=${id()}"
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0")
            .header("Cookie", "muid=$muid;")
            .build()

        val audio = ByteArrayOutputStream()
        val marks = ArrayList<Pair<Long, String>>()
        val done = CountDownLatch(1)
        var error: Exception? = null
        var finished = false

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("X-Timestamp:${stamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                val pct = Math.round((rate - 1f) * 100)
                val lang = voice.split('-').take(2).joinToString("-")
                val short = voice.split('-').drop(2).joinToString("-")
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
                    "<voice name='Microsoft Server Speech Text to Speech Voice ($lang, $short)'>" +
                    "<prosody pitch='+0Hz' rate='${if (pct >= 0) "+" else ""}$pct%' volume='+0%'>${esc(text)}</prosody></voice></speak>"
                webSocket.send("X-RequestId:${id()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${stamp()}Z\r\nPath:ssml\r\n\r\n$ssml")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val split = text.indexOf("\r\n\r\n")
                if (split < 0) return
                val head = text.substring(0, split)
                val body = text.substring(split + 4)
                if (head.contains("Path:audio.metadata")) {
                    try {
                        val meta = JSONObject(body).optJSONArray("Metadata") ?: return
                        for (i in 0 until meta.length()) {
                            val m = meta.getJSONObject(i)
                            if (m.optString("Type") != "WordBoundary") continue
                            val d = m.getJSONObject("Data")
                            marks.add(Pair(d.getLong("Offset"), d.getJSONObject("text").getString("Text")))
                        }
                    } catch (_: Exception) {}
                } else if (head.contains("Path:turn.end")) {
                    finished = true
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (2 + headLen > b.size) return
                val head = String(b, 2, headLen, Charsets.UTF_8)
                if (head.contains("Path:audio")) audio.write(b, 2 + headLen, b.size - 2 - headLen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Refused because the clock is off? Use the server's time for the next try
                val date = response?.header("Date")
                if (response?.code == 403 && date != null) {
                    try {
                        val server = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(date)?.time
                        if (server != null) skewSeconds += (server - System.currentTimeMillis()) / 1000.0
                    } catch (_: Exception) {}
                }
                error = Exception("Microsoft voice: " + (response?.code?.let { "refused ($it)" } ?: t.message ?: "connection failed"))
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finished && error == null) error = Exception("Microsoft voice connection closed")
                done.countDown()
            }
        })
        if (!done.await(40, TimeUnit.SECONDS)) { ws.cancel(); throw Exception("Microsoft voice timed out") }
        if (!finished) throw error ?: Exception("Microsoft voice failed")
        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) throw Exception("Microsoft voice returned no audio")
        // Match each spoken word to its place in the text
        val words = ArrayList<Word>()
        var cursor = 0
        for ((ticks, w) in marks) {
            val at = text.indexOf(w, cursor)
            if (at < 0) continue
            words.add(Word(ticks / 10_000, at, at + w.length))
            cursor = at + w.length
        }
        return Result(bytes, words)
    }
}
