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
    }

    private data class Item(val p: Int, val text: String)

    companion object {
        const val ACTION_LOAD = "com.chapterreader.LOAD"
        const val ACTION_TOGGLE = "com.chapterreader.TOGGLE"
        const val ACTION_NEXT_CH = "com.chapterreader.NEXT_CH"
        const val ACTION_PREV_CH = "com.chapterreader.PREV_CH"
        const val ACTION_STOP = "com.chapterreader.STOP"
        const val ACTION_LOAD_URL = "com.chapterreader.LOAD_URL"
        const val EXTRA_URL = "url"
        const val EXTRA_PLAY = "play"

        private const val CHANNEL = "reader"
        private const val NOTIF_ID = 1

        var instance: ReaderService? = null
            private set
        var listener: Listener? = null
        var appVisible = false

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
    private var playWhenReady = false
    private var token = 0
    private var errors = 0
    private val items = ArrayList<Item>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var bubble: Bubble? = null

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

        tts = TextToSpeech(this, this)
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
                handler.post { if (utteranceId == token.toString()) errors = 0 }
            }

            override fun onDone(utteranceId: String?) {
                handler.post {
                    if (utteranceId == token.toString() && playing) {
                        idx++
                        speakCurrent()
                    }
                }
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
        listener?.onStateChanged()
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
            Extractor.chunks(ch.heading).forEach { items.add(Item(-1, it)) }
        }
        ch.paras.forEachIndexed { pi, t -> Extractor.chunks(t).forEach { items.add(Item(pi, it)) } }
        idx = if (startPara > 0) items.indexOfFirst { it.p >= startPara }.coerceAtLeast(0) else 0
        chapter = ch
        status = ""
        prefs.edit().putString("lastUrl", ch.url).apply()
        listener?.onChapterChanged(ch.url)
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
        if (!ttsReady) { playWhenReady = true; return }
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

    private fun speakCurrent() {
        val my = ++token
        if (idx >= items.size) { chapterFinished(); return }
        val item = items[idx]
        if (item.p >= 0) listener?.onParagraph(item.p)
        tts.setSpeechRate(rate)
        tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, Bundle(), my.toString())
    }

    private fun onSpeechError(utteranceId: String?) {
        if (utteranceId != token.toString() || !playing) return
        errors++
        if (errors > 3) {
            pause()
            status = "The voice engine stopped working. Try again."
            notifyState()
            return
        }
        idx++
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
        if (playing) speakCurrent() else listener?.onParagraph(items[idx].p)
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
        val want = prefs.getBoolean("bubble", true) && !appVisible &&
            Settings.canDrawOverlays(this) && chapter != null
        if (want && bubble == null) {
            bubble = Bubble(this).also { it.show() }
        } else if (!want && bubble != null) {
            bubble?.hide()
            bubble = null
        }
        bubble?.update()
    }

    fun hideBubbleForNow() {
        prefs.edit().putBoolean("bubble", false).apply()
        updateBubble()
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
        listener?.onStateChanged()
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
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            status.isNotEmpty() -> status
            else -> String.format(Locale.UK, "%s · %.1f×", siteName(), rate)
        }
        return Notification.Builder(this, CHANNEL)
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
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
