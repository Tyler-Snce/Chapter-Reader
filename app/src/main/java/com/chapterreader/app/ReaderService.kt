package com.chapterreader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.CookieManager
import android.widget.Toast
import org.jsoup.Jsoup
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Reads the current chapter aloud with the phone's voice engine. Runs as a
 * foreground service so it keeps going with the screen off or in other apps,
 * and fetches the next chapter by itself when one ends.
 */
class ReaderService : Service(), TextToSpeech.OnInitListener {

    interface Listener {
        fun onStateChanged()
        fun onParagraph(index: Int)
        fun onChapterChanged(url: String)
        fun onLoadFailed(url: String)
        /** The word being spoken: character range [start, end) within paragraph [para]. */
        fun onWord(para: Int, start: Int, end: Int) {}
        /** New manga pages or speech bubbles are ready to show. */
        fun onMangaUpdate() {}
    }

    private data class Item(val p: Int, val text: String, val offset: Int)

    companion object {
        const val ACTION_LOAD = "com.chapterreader.LOAD"
        const val ACTION_TOGGLE = "com.chapterreader.TOGGLE"
        const val ACTION_NEXT_CH = "com.chapterreader.NEXT_CH"
        const val ACTION_PREV_CH = "com.chapterreader.PREV_CH"
        const val ACTION_STOP = "com.chapterreader.STOP"
        const val ACTION_LOAD_URL = "com.chapterreader.LOAD_URL"
        const val ACTION_IDLE = "com.chapterreader.IDLE"
        const val ACTION_RESUME = "com.chapterreader.RESUME"
        const val ACTION_SHOW_BUBBLE = "com.chapterreader.SHOW_BUBBLE"
        const val EXTRA_URL = "url"
        const val EXTRA_PLAY = "play"

        private const val CHANNEL = "reader"
        private const val NOTIF_ID = 1

        var instance: ReaderService? = null
            private set
        var listener: Listener? = null
        var viewListener: Listener? = null
        var appVisible = false
        var viewVisible = false

        /** Chapter handed over by the app before starting the service. */
        var pending: Pair<Chapter, Int>? = null

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val audioIo = Executors.newSingleThreadExecutor()
    private val syncIo = Executors.newSingleThreadExecutor()
    private val ocrIo = Executors.newSingleThreadExecutor()
    private lateinit var tts: TextToSpeech
    private lateinit var session: MediaSession
    private lateinit var prefs: SharedPreferences
    private var ttsReady = false
    private var engineName: String? = null
    private var playWhenReady = false
    private var token = 0
    private var errors = 0
    private val items = ArrayList<Item>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var bubble: Bubble? = null
    private var bubbleHidden = false

    private fun each(f: (Listener) -> Unit) {
        listener?.let(f)
        viewListener?.let(f)
    }

    var chapter: Chapter? = null
        private set
    var idx = 0
        private set
    var playing = false
        private set
    var loading = false
        private set
    var status = ""
        private set
    var rate = 1f
        private set
    var autoNext = true
        private set

    val chapterName: String
        get() {
            val c = chapter ?: return "Chapter Reader"
            return c.heading.ifEmpty { c.title.ifEmpty { c.url } }
        }

    val currentPara: Int
        get() = items.getOrNull(idx)?.p ?: -1

    /** How far through the chapter, from 0 to 1. */
    val progress: Float
        get() = if (items.isEmpty()) 0f else idx.coerceAtMost(items.size).toFloat() / items.size

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = prefs(this)
        rate = prefs.getFloat("rate", 1f)
        autoNext = prefs.getBoolean("auto", true)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )

        session = MediaSession(this, "ChapterReader")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { play() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { nextChapter() }
            override fun onSkipToPrevious() { prevChapter() }
            override fun onFastForward() { jumpPara(1) }
            override fun onRewind() { jumpPara(-1) }
            override fun onStop() { shutdown() }
        })
        session.isActive = true

        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }

        startEngine()
        syncNow()
    }

    private fun systemEngine(): String? =
        try { Settings.Secure.getString(contentResolver, "tts_default_synth") } catch (_: Exception) { null }

    /** Connects to the phone's chosen voice engine (SherpaTTS, Google, Samsung...). */
    private fun startEngine() {
        if (::tts.isInitialized) clearRecordings()
        engineName = systemEngine()
        engineReportsWords = false
        charsPerSecond = 0f
        ttsReady = false
        tts = TextToSpeech(this, this)
    }

    /** If you've picked a different voice engine in Settings, switch to it. */
    private fun checkEngineChanged(): Boolean {
        val now = systemEngine()
        if (now == null || now == engineName) return false
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        startEngine()
        return true
    }

    override fun onInit(result: Int) {
        ttsReady = result == TextToSpeech.SUCCESS
        if (!ttsReady) {
            status = "No text-to-speech voice found on this phone"
            notifyState()
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                handler.post { onPieceStart(utteranceId) }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                handler.post {
                    // This engine reports exact words, so no need to estimate
                    if (!engineReportsWords) {
                        engineReportsWords = true
                        handler.removeCallbacks(wordTicker)
                    }
                    if (!playing) return@post
                    val i = pieceIndex(utteranceId) ?: return@post
                    val item = items.getOrNull(i) ?: return@post
                    if (item.p < 0 || item.offset < 0) return@post
                    each { it.onWord(item.p, item.offset + start, item.offset + end) }
                }
            }

            override fun onDone(utteranceId: String?) {
                handler.post { if (isRenderId(utteranceId)) onRendered(utteranceId, true) else onPieceDone(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { if (isRenderId(utteranceId)) onRendered(utteranceId, false) else onSpeechError(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { if (isRenderId(utteranceId)) onRendered(utteranceId, false) else onSpeechError(utteranceId) }
            }
        })
        if (playWhenReady) {
            playWhenReady = false
            play()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> {
                val p = pending
                pending = null
                if (p != null) {
                    setChapter(p.first, p.second)
                    if (intent.getBooleanExtra(EXTRA_PLAY, false)) play()
                }
            }
            ACTION_LOAD_URL -> loadChapter(intent.getStringExtra(EXTRA_URL), true)
            ACTION_IDLE -> notifyState()
            ACTION_RESUME -> resume()
            ACTION_SHOW_BUBBLE -> showBubble()
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT_CH -> nextChapter()
            ACTION_PREV_CH -> prevChapter()
            ACTION_STOP -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        token++
        try { clearRecordings() } catch (_: Exception) {}
        audioIo.shutdownNow()
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        releaseWake()
        bubble?.hide()
        bubble = null
        session.release()
        io.shutdownNow()
        mangaGen++
        ocrIo.shutdownNow()
        try { ocr?.close() } catch (_: Exception) {}
        instance = null
        each { it.onStateChanged() }
        super.onDestroy()
    }

    fun shutdown() {
        pause()
        bubble?.hide()
        bubble = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- chapter ----------

    private fun setChapter(ch: Chapter, startPara: Int) {
        clearRecordings()
        items.clear()
        if (ch.heading.isNotEmpty() && ch.heading != ch.paras.firstOrNull()) {
            Extractor.chunks(ch.heading).forEach { items.add(Item(-1, it, -1)) }
        }
        ch.paras.forEachIndexed { pi, t ->
            var cursor = 0
            Extractor.chunks(t).forEach { c ->
                // Where this piece starts in the paragraph, for word highlighting
                var at = t.indexOf(c, cursor)
                if (at < 0) at = t.indexOf(c.take(20), cursor)
                items.add(Item(pi, c, at))
                if (at >= 0) cursor = at + c.length
            }
        }
        idx = if (startPara > 0) items.indexOfFirst { it.p >= startPara }.coerceAtLeast(0) else 0
        chapter = ch
        if (ch.isManga) {
            pendingStart = if (startPara > 0) startPara else -1
            startManga(ch)
        } else {
            mangaGen++
            bubbles.clear(); bubbleTexts.clear(); mangaFiles.clear()
            mangaDone = true; waitingText = false; pendingStart = -1
        }
        status = ""
        prefs.edit().putString("lastUrl", ch.url).apply()
        each { it.onChapterChanged(ch.url) }
        notifyState()
    }

    fun loadChapter(url: String?, autoplay: Boolean, startPara: Int = 0) {
        if (url == null || loading) return
        val wasPlaying = playing || autoplay
        if (playing) {
            playing = false
            token++
            tts.stop()
        }
        loading = true
        status = "Loading chapter…"
        notifyState()

        val ua = prefs.getString("ua", null)
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val cookies = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }

        io.execute {
            val ch = try {
                val conn = Jsoup.connect(url)
                    .userAgent(ua)
                    .timeout(25000)
                    .maxBodySize(0)
                    .followRedirects(true)
                if (!cookies.isNullOrEmpty()) conn.header("Cookie", cookies)
                val res = conn.execute()
                Extractor.parse(res.body(), res.url().toString())
            } catch (_: Exception) {
                null
            }
            handler.post {
                loading = false
                if (ch == null) {
                    status = "Couldn't read that page"
                    releaseWake()
                    notifyState()
                    val l = listener
                    if (l != null) {
                        l.onLoadFailed(url)
                    } else {
                        Toast.makeText(this, "Couldn't read that page directly, opening it in Chapter Reader", Toast.LENGTH_LONG).show()
                        try {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .setAction(Intent.ACTION_VIEW)
                                    .setData(Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                    }
                    return@post
                }
                setChapter(ch, startPara)
                if (wasPlaying) play() else notifyState()
            }
        }
    }

    fun nextChapter() = loadChapter(chapter?.next, false)
    fun prevChapter() = loadChapter(chapter?.prev, false)

    // ---------- speech ----------

    // ---------- manga / webtoon ----------
    // Page images are downloaded and read one by one in the background. Speech
    // bubbles become the "paragraphs", so reading can start while later pages load.

    private var ocr: MangaOcr? = null
    private var mangaGen = 0
    private var waitingText = false
    private var pendingStart = -1
    val bubbles = ArrayList<Bubble>()
    private val bubbleTexts = ArrayList<String>()
    /** Local copy of each page image, or null while it's still downloading. */
    val mangaFiles = ArrayList<String?>()
    var mangaDone = true
        private set
    val isManga: Boolean get() = chapter?.isManga == true

    private fun startManga(ch: Chapter) {
        val myGen = ++mangaGen
        bubbles.clear(); bubbleTexts.clear(); mangaFiles.clear()
        repeat(ch.images.size) { mangaFiles.add(null) }
        mangaDone = false
        waitingText = false
        val dir = File(cacheDir, "manga").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val ua = prefs.getString("ua", null)
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        ocrIo.execute {
            val reader = ocr ?: MangaOcr(ua).also { ocr = it }
            ch.images.forEachIndexed { i, url ->
                if (myGen != mangaGen) return@execute
                val f = File(dir, "$myGen-$i")
                val ok = reader.download(url, ch.url, f)
                if (myGen != mangaGen) return@execute
                if (ok) handler.post {
                    if (myGen == mangaGen) { mangaFiles[i] = f.path; each { it.onMangaUpdate() } }
                }
                val found = if (ok) {
                    try { reader.bubbles(f, i) } catch (_: Throwable) { emptyList() }
                } else emptyList()
                handler.post { if (myGen == mangaGen) addBubbles(found) }
            }
            handler.post {
                if (myGen != mangaGen) return@post
                mangaDone = true
                if (pendingStart >= 0) { pendingStart = -1 }
                if (bubbles.isEmpty()) status = "No text found in these pictures"
                each { it.onMangaUpdate() }
                if (waitingText) { waitingText = false; status = if (bubbles.isEmpty()) status else ""; if (playing) speakCurrent() }
                notifyState()
            }
        }
    }

    private fun addBubbles(found: List<Bubble>) {
        for (b in found) {
            val pi = bubbleTexts.size
            bubbles.add(b)
            bubbleTexts.add(b.text)
            var cursor = 0
            for (c in Extractor.chunks(b.text)) {
                var at = b.text.indexOf(c, cursor)
                if (at < 0) at = b.text.indexOf(c.take(20), cursor)
                items.add(Item(pi, c, at))
                if (at >= 0) cursor = at + c.length
            }
        }
        if (pendingStart >= 0) {
            val i = items.indexOfFirst { it.p >= pendingStart }
            if (i >= 0) { idx = i; pendingStart = -1 }
        }
        each { it.onMangaUpdate() }
        if (waitingText && pendingStart < 0 && idx < items.size) {
            waitingText = false
            status = ""
            if (playing) speakCurrent()
            notifyState()
        }
    }

    /** Text of speech bubble [i] (manga), for showing what's being read. */
    fun bubbleText(i: Int): String? = bubbleTexts.getOrNull(i)

    // ---------- resume where you left off ----------

    private fun saveProgress() {
        val c = chapter ?: return
        val p = (items.getOrNull(idx)?.p ?: 0).coerceAtLeast(0)
        prefs.edit()
            .putString("resumeUrl", c.url)
            .putInt("resumePara", p)
            .putString("resumeTitle", chapterName)
            .putLong("resumeUpdated", System.currentTimeMillis())
            .apply()
        // Upload a few seconds later, so it isn't sent for every single paragraph
        handler.removeCallbacks(uploadNow)
        handler.postDelayed(uploadNow, 4000)
    }

    private val uploadNow = Runnable { syncIo.execute { Sync.push(prefs) } }

    /** Checks online for newer progress (e.g. from your PC). */
    fun syncNow() {
        syncIo.execute { if (Sync.pull(prefs)) handler.post { notifyState() } }
    }

    private fun paraChanged(p: Int) {
        each { it.onParagraph(p) }
        saveProgress()
    }

    /** True when nothing is loaded but there's a saved place to continue from. */
    val hasResume: Boolean
        get() = chapter == null && prefs.getString("resumeUrl", null) != null

    val resumeTitle: String
        get() = prefs.getString("resumeTitle", null) ?: "your last chapter"

    fun resume() {
        status = "Finding where you left off\u2026"
        notifyState()
        // Use whichever is newer: this phone's place or the one saved online
        syncIo.execute {
            Sync.pull(prefs)
            handler.post {
                status = ""
                val url = prefs.getString("resumeUrl", null)
                if (url == null) { notifyState(); return@post }
                loadChapter(url, true, prefs.getInt("resumePara", 0))
            }
        }
    }

    fun play() {
        if (chapter == null && !loading) { resume(); return }
        if (chapter == null || loading) return
        if (items.isEmpty() && !isManga) return
        if (checkEngineChanged() || !ttsReady) { playWhenReady = true; return }
        if (idx >= items.size && (!isManga || mangaDone)) idx = 0
        playing = true
        errors = 0
        acquireWake()
        speakCurrent()
        notifyState()
    }

    fun pause() {
        if (chapter != null) {
            handler.removeCallbacks(uploadNow)
            syncIo.execute { Sync.push(prefs) }
        }
        handler.removeCallbacks(wordTicker)
        lastStartIdx = -1
        playing = false
        playWhenReady = false
        token++
        if (recorded) stopPlayers() else if (ttsReady) tts.stop()
        releaseWake()
        notifyState()
    }

    fun toggle() { if (playing) pause() else play() }

    // The next few pieces are handed to the voice engine in advance, so it can
    // prepare them while the current one plays. This removes the gap between sentences.
    private var queuedUpTo = -1
    private val queueAhead = 3

    private fun pieceId(i: Int) = "$token:$i"

    private fun pieceIndex(id: String?): Int? {
        val parts = id?.split(':') ?: return null
        if (parts.size != 2 || parts[0] != token.toString()) return null
        return parts[1].toIntOrNull()
    }

    private fun speakCurrent() {
        token++
        if (idx >= items.size) { chapterFinished(); return }
        if (recorded) { playRecorded(); return }
        tts.setSpeechRate(rate)
        queuedUpTo = idx - 1
        queueMore(flush = true)
        val p = items[idx].p
        if (p >= 0) paraChanged(p)
    }

    private fun queueMore(flush: Boolean) {
        var first = flush
        while (queuedUpTo < items.size - 1 && queuedUpTo < idx + queueAhead) {
            queuedUpTo++
            tts.speak(
                items[queuedUpTo].text,
                if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                Bundle(), pieceId(queuedUpTo)
            )
            first = false
        }
    }

    private fun moveTo(i: Int) {
        if (i <= idx && i != 0) return
        val oldP = items.getOrNull(idx)?.p
        idx = i
        val p = items.getOrNull(i)?.p ?: return
        if (p >= 0 && p != oldP) paraChanged(p)
    }

    // ---------- recorded playback (for engines like SherpaTTS) ----------
    // The voice records each piece to a file ahead of time; the app trims long
    // pauses and plays the files back to back. This gives smooth reading, a word
    // highlight that matches the audio, and speed control without a pitch change.

    private val recorded: Boolean
        get() = engineName?.contains("google", ignoreCase = true) != true
    private var gen = 0
    private val files = HashMap<Int, File>()
    private val recording = HashSet<Int>()
    private val failed = HashSet<Int>()
    private var player: MediaPlayer? = null
    private var playerIdx = -1
    private var nextPlayer: MediaPlayer? = null
    private var nextIdx = -1
    private var waiting = -1

    private fun recDir(): File = File(cacheDir, "voice").apply { mkdirs() }
    private fun isRenderId(id: String?) = id?.startsWith("r:") == true

    private fun clearRecordings() {
        gen++
        stopPlayers()
        files.clear(); recording.clear(); failed.clear()
        try { if (ttsReady) tts.stop() } catch (_: Exception) {}
        recDir().listFiles()?.forEach { it.delete() }
    }

    private fun stopPlayers() {
        handler.removeCallbacks(positionTicker)
        handler.removeCallbacks(stallWatch)
        try { player?.release() } catch (_: Exception) {}
        try { nextPlayer?.release() } catch (_: Exception) {}
        player = null; nextPlayer = null
        playerIdx = -1; nextIdx = -1; waiting = -1
    }

    private fun record(i: Int) {
        if (i < 0 || i >= items.size || files.containsKey(i) || recording.contains(i) || failed.contains(i)) return
        recording.add(i)
        tts.setSpeechRate(rate)
        val f = File(recDir(), "$gen-$i.wav")
        if (tts.synthesizeToFile(items[i].text, Bundle(), f, "r:$gen:$i") != TextToSpeech.SUCCESS) {
            recording.remove(i); failed.add(i)
        }
    }

    private fun rerecordAhead() {
        gen++
        val keep = playerIdx
        files.keys.filter { it != keep }.forEach { files.remove(it)?.delete() }
        recording.clear(); failed.clear()
        try { nextPlayer?.release() } catch (_: Exception) {}
        nextPlayer = null; nextIdx = -1
        try { if (ttsReady) tts.stop() } catch (_: Exception) {}
        if (playing) {
            if (waiting >= 0) { record(waiting); setWaiting(waiting) }
            recordAhead()
        }
    }

    private fun recordAhead() { for (k in 0..4) record(idx + k) }

    private fun onRendered(id: String?, ok: Boolean) {
        val parts = id?.split(':') ?: return
        if (parts.size != 3) return
        val g = parts[1]
        val i = parts[2].toIntOrNull() ?: return
        if (g != gen.toString()) return
        recording.remove(i)
        val f = File(recDir(), "$g-$i.wav")
        if (!ok || !f.exists() || f.length() <= 44) { failed.add(i); fileReady(i); return }
        audioIo.execute {
            Silence.tighten(f)
            handler.post {
                if (g != gen.toString()) return@post
                files[i] = f
                fileReady(i)
            }
        }
    }

    private fun fileReady(i: Int) {
        if (!playing) return
        if (i == waiting) startPlayer(i)
        else if (i == playerIdx + 1 && nextPlayer == null) prepareNext(i)
    }

    private fun makePlayer(f: File): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(f.path)
            prepare()
        }
    } catch (_: Exception) { null }

    private fun prepareNext(i: Int) {
        val f = files[i] ?: return
        try { nextPlayer?.release() } catch (_: Exception) {}
        nextPlayer = makePlayer(f)
        nextIdx = if (nextPlayer != null) i else -1
    }

    /** Starts reading from [idx] using recorded pieces. */
    private fun playRecorded() {
        stopPlayers()
        val p = items[idx].p
        if (p >= 0) paraChanged(p)
        recordAhead()
        if (files.containsKey(idx)) startPlayer(idx) else if (failed.contains(idx)) skipFrom(idx) else setWaiting(idx)
    }

    private fun skipFrom(i: Int) {
        idx = i + 1
        if (idx >= items.size) { chapterFinished(); return }
        playRecorded()
    }

    private fun startPlayer(i: Int, ready: MediaPlayer? = null) {
        waiting = -1
        handler.removeCallbacks(stallWatch)
        stallRetries = 0
        val mp = ready ?: files[i]?.let { makePlayer(it) }
        if (mp == null) { skipFrom(i); return }
        try { player?.release() } catch (_: Exception) {}
        player = mp
        playerIdx = i
        val oldP = items.getOrNull(idx)?.p
        idx = i
        mp.setOnCompletionListener { done -> onPlayerDone(done) }
        mp.start()
        errors = 0
        val p = items[i].p
        if (p >= 0 && p != oldP) paraChanged(p)
        handler.removeCallbacks(positionTicker)
        handler.post(positionTicker)
        if (files.containsKey(i + 1)) prepareNext(i + 1)
        recordAhead()
    }

    private fun onPlayerDone(mp: MediaPlayer) {
        if (mp !== player || !playing) return
        val i = playerIdx + 1
        if (i >= items.size) {
            stopPlayers()
            idx = items.size
            chapterFinished()
            return
        }
        val n = nextPlayer
        if (n != null && nextIdx == i) {
            nextPlayer = null; nextIdx = -1
            startPlayer(i, n)
        } else if (failed.contains(i)) {
            skipFrom(i)
        } else {
            try { player?.release() } catch (_: Exception) {}
            player = null
            idx = i
            setWaiting(i)
            if (files.containsKey(i)) startPlayer(i) else { record(i); noteStall() }
            recordAhead()
        }
    }

    private var stallRetries = 0

    private fun setWaiting(i: Int) {
        waiting = i
        handler.removeCallbacks(stallWatch)
        handler.postDelayed(stallWatch, 8000)
    }

    /**
     * If a recording never arrives (the phone may have closed the voice app in
     * the background), reconnect to the voice and try again; skip it if it keeps failing.
     */
    private val stallWatch = Runnable {
        val i = waiting
        if (!playing || i < 0) return@Runnable
        stallRetries++
        if (stallRetries > 2) {
            stallRetries = 0
            failed.add(i)
            skipFrom(i)
            return@Runnable
        }
        gen++
        recording.clear()
        try { tts.shutdown() } catch (_: Exception) {}
        idx = i
        waiting = -1
        playWhenReady = true
        engineName = systemEngine()
        ttsReady = false
        tts = TextToSpeech(this, this)
    }

    private var stalls = 0
    private var stallTipShown = false

    /** The voice couldn't record the next piece in time. Suggest a fix once. */
    private fun noteStall() {
        stalls++
        if (stalls >= 3 && !stallTipShown) {
            stallTipShown = true
            Toast.makeText(
                this,
                "Your voice can't record fast enough, which causes pauses. In SherpaTTS, tick \"Apply system settings\" and set its speed to 1.0, or choose a \"medium\" voice",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Moves the word highlight along with the audio actually playing. */
    private val positionTicker = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (!playing) return
            try {
                val item = items.getOrNull(playerIdx)
                val dur = mp.duration
                if (item != null && item.p >= 0 && item.offset >= 0 && dur > 0) {
                    val t = item.text
                    val frac = (mp.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
                    val pos = (frac * t.length).toInt().coerceIn(0, t.length - 1)
                    var start = t.lastIndexOf(' ', pos) + 1
                    if (start > pos) start = pos
                    var end = t.indexOf(' ', pos)
                    if (end < 0) end = t.length
                    if (end > start) each { it.onWord(item.p, item.offset + start, item.offset + end) }
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, 70)
        }
    }

    // ---------- estimated word highlight (for engines like SherpaTTS) ----------

    private var engineReportsWords = false
    private var lastStartIdx = -1
    private var lastStartAt = 0L
    private var charsPerSecond = 0f

    private val wordTicker = object : Runnable {
        override fun run() {
            if (!playing || engineReportsWords) return
            estimateWord()
            handler.postDelayed(this, 90)
        }
    }

    /** Learns how fast the voice is actually talking, from sentence start times. */
    private fun learnSpeed(i: Int, now: Long) {
        if (lastStartIdx == i - 1 && lastStartIdx >= 0 && lastStartAt > 0) {
            val len = items.getOrNull(i - 1)?.text?.length ?: 0
            val secs = (now - lastStartAt) / 1000f
            if (secs > 0.4f && len > 10) {
                val measured = len / secs
                charsPerSecond = if (charsPerSecond == 0f) measured else charsPerSecond * 0.7f + measured * 0.3f
            }
        }
        lastStartIdx = i
        lastStartAt = now
    }

    private fun estimateWord() {
        val item = items.getOrNull(idx) ?: return
        if (item.p < 0 || item.offset < 0 || item.text.isEmpty()) return
        val speed = if (charsPerSecond > 0f) charsPerSecond else 15f * rate
        val elapsed = (android.os.SystemClock.uptimeMillis() - lastStartAt) / 1000f
        val pos = (elapsed * speed).toInt().coerceIn(0, item.text.length - 1)
        val t = item.text
        var start = t.lastIndexOf(' ', pos) + 1
        if (start > pos) start = pos
        var end = t.indexOf(' ', pos)
        if (end < 0) end = t.length
        if (end <= start) return
        each { it.onWord(item.p, item.offset + start, item.offset + end) }
    }

    private fun onPieceStart(id: String?) {
        if (!playing) return
        val i = pieceIndex(id) ?: return
        learnSpeed(i, android.os.SystemClock.uptimeMillis())
        if (!engineReportsWords) {
            handler.removeCallbacks(wordTicker)
            handler.post(wordTicker)
        }
        errors = 0
        if (i != idx) moveTo(i)
        queueMore(flush = false)
    }

    private fun onPieceDone(id: String?) {
        if (!playing) return
        val i = pieceIndex(id) ?: return
        if (i >= items.size - 1) {
            idx = items.size
            chapterFinished()
            return
        }
        if (idx <= i) moveTo(i + 1)
        queueMore(flush = false)
    }

    private fun onSpeechError(utteranceId: String?) {
        if (!playing) return
        val i = pieceIndex(utteranceId) ?: return
        errors++
        if (errors > 3) {
            pause()
            status = "The voice engine stopped working. Try again."
            notifyState()
            return
        }
        idx = i + 1
        speakCurrent()
    }

    private fun chapterFinished() {
        // Manga: the pictures are still being read, so wait for more speech bubbles
        if (isManga && (!mangaDone || pendingStart >= 0)) {
            waitingText = true
            status = "Reading the pictures\u2026"
            acquireWake()
            notifyState()
            return
        }
        val next = chapter?.next
        if (autoNext && next != null) {
            loadChapter(next, true)
        } else {
            playing = false
            idx = 0
            status = "End of chapter"
            releaseWake()
            notifyState()
        }
    }

    fun jumpPara(delta: Int) {
        if (items.isEmpty()) return
        val cur = items.getOrNull(idx)?.p ?: 0
        val target = (cur + delta).coerceAtLeast(0)
        val i = items.indexOfFirst { it.p >= target }
        if (i < 0) return
        idx = i
        if (playing) speakCurrent() else { val p = items[idx].p; paraChanged(p) }
    }

    fun startAtPara(pi: Int) {
        val i = items.indexOfFirst { it.p >= pi }
        if (i < 0) return
        idx = i
        if (playing) speakCurrent() else play()
    }

    fun setRate(r: Float) {
        rate = Math.round(r.coerceIn(0.5f, 4f) * 10f) / 10f
        prefs.edit().putFloat("rate", rate).apply()
        if (recorded) {
            // Upcoming sentences are re-recorded at the new speed
            rerecordAhead()
        } else if (playing) speakCurrent()
        notifyState()
    }

    fun setAuto(on: Boolean) {
        autoNext = on
        prefs.edit().putBoolean("auto", on).apply()
        notifyState()
    }

    // ---------- wake lock ----------

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChapterReader:reading").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // ---------- bubble ----------

    fun updateBubble() {
        val want = prefs.getBoolean("bubble", true) && !appVisible && !viewVisible &&
            !bubbleHidden && Settings.canDrawOverlays(this)
        if (want && bubble == null) {
            bubble = Bubble(this).also { it.show() }
        } else if (!want && bubble != null) {
            bubble?.hide()
            bubble = null
        }
        bubble?.update()
    }

    fun hideBubbleForNow() {
        bubbleHidden = true
        Toast.makeText(
            this, "Bubble hidden. Bring it back from the notification or your accessibility button.",
            Toast.LENGTH_LONG
        ).show()
        notifyState()
    }

    fun showBubble() {
        bubbleHidden = false
        prefs.edit().putBoolean("bubble", true).apply()
        notifyState()
    }

    val isBubbleHidden: Boolean
        get() = bubbleHidden

    fun openReadingView() {
        try {
            startActivity(
                Intent(this, ReadingViewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Exception) {}
    }

    /** Reads whatever page your browser is showing. */
    fun readFromBrowser() {
        val w = BrowserWatcher.instance
        if (w == null) {
            Toast.makeText(this, "Turn on the Chapter Reader bubble in the app first", Toast.LENGTH_LONG).show()
            openApp()
            return
        }
        if (loading) return
        status = "Finding your page\u2026"
        notifyState()
        w.currentUrl { url ->
            handler.post {
                if (url == null) {
                    status = ""
                    notifyState()
                    Toast.makeText(this, "Open a chapter in your browser first, then tap \uD83D\uDCD6", Toast.LENGTH_LONG).show()
                } else {
                    status = ""
                    loadChapter(url, true)
                }
            }
        }
    }

    fun openInBrowser() {
        val url = chapter?.url ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
    }

    // ---------- notification & lock screen ----------

    private fun notifyState() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f
                )
                .build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, chapterName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, siteName())
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        updateBubble()
        each { it.onStateChanged() }
    }

    private fun siteName(): String {
        val u = chapter?.url ?: return "Chapter Reader"
        return try { Uri.parse(u).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
    }

    @Suppress("DEPRECATION")
    private fun action(icon: Int, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, ReaderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(icon, title, pi).build()
    }

    private fun buildNotification(): Notification {
        val target = if (chapter != null) ReadingViewActivity::class.java else MainActivity::class.java
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            status.isNotEmpty() -> status
            chapter == null && hasResume -> "Tap \u25B6 to continue: $resumeTitle"
            chapter == null -> "Tap the bubble to read the page you're on"
            else -> String.format(Locale.UK, "%s · %.1f×", siteName(), rate)
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_book)
            .setContentTitle(chapterName)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous chapter", ACTION_PREV_CH, 1))
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play", ACTION_TOGGLE, 2
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next chapter", ACTION_NEXT_CH, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", ACTION_STOP, 4))
        if (bubbleHidden) b.addAction(action(android.R.drawable.ic_menu_view, "Show bubble", ACTION_SHOW_BUBBLE, 5))
        return b
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
