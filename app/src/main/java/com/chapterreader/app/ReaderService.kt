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
    }

    private fun systemEngine(): String? =
        try { Settings.Secure.getString(contentResolver, "tts_default_synth") } catch (_: Exception) { null }

    /** Connects to the phone's chosen voice engine (SherpaTTS, Google, Samsung...). */
    private fun startEngine() {
        engineName = systemEngine()
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
                    if (!playing) return@post
                    val i = pieceIndex(utteranceId) ?: return@post
                    val item = items.getOrNull(i) ?: return@post
                    if (item.p < 0 || item.offset < 0) return@post
                    each { it.onWord(item.p, item.offset + start, item.offset + end) }
                }
            }

            override fun onDone(utteranceId: String?) {
                handler.post { onPieceDone(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { onSpeechError(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handler.post { onSpeechError(utteranceId) }
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
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        releaseWake()
        bubble?.hide()
        bubble = null
        session.release()
        io.shutdownNow()
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
        status = ""
        prefs.edit().putString("lastUrl", ch.url).apply()
        each { it.onChapterChanged(ch.url) }
        notifyState()
    }

    fun loadChapter(url: String?, autoplay: Boolean) {
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
                setChapter(ch, 0)
                if (wasPlaying) play() else notifyState()
            }
        }
    }

    fun nextChapter() = loadChapter(chapter?.next, false)
    fun prevChapter() = loadChapter(chapter?.prev, false)

    // ---------- speech ----------

    fun play() {
        if (chapter == null || items.isEmpty() || loading) return
        if (checkEngineChanged() || !ttsReady) { playWhenReady = true; return }
        if (idx >= items.size) idx = 0
        playing = true
        errors = 0
        acquireWake()
        speakCurrent()
        notifyState()
    }

    fun pause() {
        playing = false
        playWhenReady = false
        token++
        if (ttsReady) tts.stop()
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
        tts.setSpeechRate(rate)
        queuedUpTo = idx - 1
        queueMore(flush = true)
        val p = items[idx].p
        if (p >= 0) each { it.onParagraph(p) }
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
        if (p >= 0 && p != oldP) each { it.onParagraph(p) }
    }

    private fun onPieceStart(id: String?) {
        if (!playing) return
        val i = pieceIndex(id) ?: return
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
        if (playing) speakCurrent() else { val p = items[idx].p; each { it.onParagraph(p) } }
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
        if (playing) speakCurrent()
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
