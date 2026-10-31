package com.stafftoolkit.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Runs while Staff Toolkit is on: holds the screen capture, reads Bedrock chat off the screen,
 * records, keeps the replay buffer, takes screenshots, and shows the floating button.
 */
class CaptureService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SHOT = "shot"
        const val ACTION_RECORD = "record"
        const val ACTION_CLIP = "clip"
        const val ACTION_REFRESH = "refresh"   // settings changed
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_FLAG = "flag"
        private const val CHANNEL = "running"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTE_ID = 1

        @Volatile var instance: CaptureService? = null
        var onChange: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val ocrWork = Executors.newSingleThreadExecutor()
    private val uploads = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences
    private var projection: MediaProjection? = null
    private var pipeline: ScreenPipeline? = null
    private var display: VirtualDisplay? = null
    private var recorder: Recorder? = null
    var recordingSince = 0L
        private set
    val recording: Boolean get() = recorder?.recording == true
    val replayOn: Boolean get() = recorder != null && prefs.getBoolean("replayOn", false)
    val running: Boolean get() = projection != null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Store.prefs(this)
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Staff Toolkit running", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Flagged messages and uploads", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_SHOT -> work.execute { screenshot() }
            ACTION_RECORD -> work.execute { toggleRecording() }
            ACTION_CLIP -> work.execute { saveClip(intent.getStringExtra(EXTRA_FLAG)) }
            ACTION_REFRESH -> work.execute { applySettings() }
        }
        return START_NOT_STICKY
    }

    // ---------- starting and stopping ----------

    private fun start(intent: Intent) {
        val needMic = prefs.getBoolean("mic", false) && !prefs.getBoolean("muted", false) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if (needMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTE_ID, note(), type)
        if (projection != null) return
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: run { stopSelf(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (_: Exception) { null } ?: run { stopSelf(); return }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopEverything() } }
        }, main)

        // Capture in landscape (games), at the size chosen in settings
        val m = resources.displayMetrics
        val (bw, bh) = if (android.os.Build.VERSION.SDK_INT >= 30) {
            getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { Pair(it.width(), it.height()) }
        } else Pair(m.widthPixels, m.heightPixels)
        var w = max(bw, bh)
        var h = minOf(bw, bh)
        val target = when (prefs.getString("size", "1080")) { "720" -> 720; "1080" -> 1080; "1440" -> 1440; else -> h }
        if (h > target) { w = w * target / h; h = target }
        w = w / 16 * 16; h = h / 16 * 16
        val pipe = ScreenPipeline(w, h)
        pipeline = pipe
        display = p.createVirtualDisplay("StaffToolkit", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, pipe.surface, null, null)
        work.execute { applySettings() }
        startWatcher()
        if (prefs.getBoolean("bubble", true)) main.post { showBubble() }
        changed()
    }

    private fun stopEverything() {
        stopWatcher()
        work.execute {
            recorder?.let { r -> r.stopRecording()?.let { captured(it, "recording", null) }; r.stop() }
            recorder = null
            main.post {
                hideBubble()
                try { display?.release() } catch (_: Exception) {}
                try { projection?.stop() } catch (_: Exception) {}
                pipeline?.release()
                display = null; projection = null; pipeline = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                changed()
            }
        }
    }

    override fun onDestroy() {
        instance = null
        stopWatcher()
        hideBubble()
        super.onDestroy()
    }

    private fun changed() {
        main.post {
            getSystemService(NotificationManager::class.java).notify(NOTE_ID, note())
            updateBubble()
            onChange?.invoke()
        }
    }

    // ---------- recording and replay ----------

    /** Starts or stops the encoder depending on whether the replay buffer or a recording needs it. */
    private fun applySettings() {
        val pipe = pipeline ?: return
        val wantReplay = prefs.getBoolean("replayOn", false)
        if (recorder == null && wantReplay) recorder = makeRecorder(pipe)
        if (recorder != null && !wantReplay && !recording) { recorder?.stop(); recorder = null }
        recorder?.replaySeconds = prefs.getInt("replaySeconds", 30)
        if (prefs.getBoolean("bubble", true)) main.post { showBubble() } else main.post { hideBubble() }
        changed()
    }

    private fun makeRecorder(pipe: ScreenPipeline): Recorder? {
        val p = projection ?: return null
        val fps = prefs.getInt("fps", 60)
        val q = when (prefs.getString("quality", "high")) { "standard" -> 0.08f; "max" -> 0.25f; else -> 0.14f }
        val bitrate = (pipe.width * pipe.height * fps * q).toInt().coerceIn(2_000_000, 80_000_000)
        val muted = prefs.getBoolean("muted", false)
        val micOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return try {
            Recorder(this, pipe, p, fps, bitrate,
                gameSound = !muted && micOk && prefs.getBoolean("gameSound", true),
                gameVolume = prefs.getFloat("gameVolume", 1f),
                mic = !muted && micOk && prefs.getBoolean("mic", false),
                micVolume = prefs.getFloat("micVolume", 1f),
            ).also { it.replaySeconds = prefs.getInt("replaySeconds", 30); it.start() }
        } catch (e: Exception) {
            toast("Couldn't start recording: ${e.message}")
            null
        }
    }

    private fun toggleRecording() {
        val pipe = pipeline ?: run { toast("Start Staff Toolkit first"); return }
        if (recording) {
            val uri = recorder?.stopRecording()
            recordingSince = 0
            if (!prefs.getBoolean("replayOn", false)) { recorder?.stop(); recorder = null }
            changed()
            if (uri != null) captured(uri, "recording", null) else toast("The recording couldn't be saved")
            return
        }
        if (recorder == null) recorder = makeRecorder(pipe)
        if (recorder?.startRecording() == true) {
            recordingSince = System.currentTimeMillis()
            toast("Recording")
        } else toast("Couldn't start recording")
        changed()
    }

    private fun saveClip(flagId: String?) {
        val r = recorder
        if (r == null || !prefs.getBoolean("replayOn", false)) { toast("Turn on the replay buffer first"); return }
        val secs = prefs.getInt("replaySeconds", 30)
        toast("Saving the last $secs seconds…")
        val uri = r.saveClip(secs)
        if (uri == null) { toast("Nothing to save yet"); return }
        captured(uri, "clip", flagId)
    }

    private fun screenshot() {
        val pipe = pipeline ?: run { toast("Start Staff Toolkit first"); return }
        // Hide the floating button for a moment so it isn't in the picture
        main.post { bubble?.visibility = View.INVISIBLE }
        Thread.sleep(250)
        val bmp = pipe.grab(1f)
        main.post { bubble?.visibility = View.VISIBLE }
        if (bmp == null) { toast("Nothing to capture yet"); return }
        val uri = Media.newImage(this, "Screenshot ${Media.stamp()}.png") ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        Media.finish(this, uri)
        captured(uri, "screenshot", null)
    }

    /** A capture finished: list it, and upload it if that's turned on. */
    private fun captured(uri: Uri, kind: String, flagId: String?) {
        val name = when (kind) { "screenshot" -> "Screenshot"; "clip" -> "Clip"; else -> "Recording" } + " " + Media.stamp() + if (kind == "screenshot") ".png" else ".mp4"
        val cap = Store.Capture(Store.newId(), uri.toString(), name, kind, System.currentTimeMillis(), Media.size(this, uri), flagId = flagId)
        Store.addCapture(this, cap)
        changed()
        if (prefs.getString("after", "upload") == "upload" && prefs.getString("uploadTo", "catbox") != "none") upload(cap)
        else alert(cap.id.hashCode(), "Saved to your gallery", name, null)
    }

    fun upload(cap: Store.Capture) {
        uploads.execute {
            try {
                val flag = cap.flagId?.let { id -> Store.flags(this).find { it.id == id } }
                val link = Uploader.send(this, cap, flag)
                if (flag != null && link != null) Store.updateFlag(this, flag.id) { it.evidence = link }
                if (link != null) alert(cap.id.hashCode(), "Uploaded – link copied", link, Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                else alert(cap.id.hashCode(), "Posted to Discord", cap.name, null)
            } catch (e: Exception) {
                alert(cap.id.hashCode(), "Upload failed – still saved in your gallery", e.message ?: "", null)
            }
            changed()
        }
    }

    // ---------- Bedrock chat watcher (reads chat off the screen) ----------

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var rules = ChatRules()
    private val seen = HashMap<String, Long>()
    private var seeded = false
    private var watching = false
    private val watchTick = object : Runnable {
        override fun run() {
            if (!watching) return
            ocrWork.execute { readChat() }
            main.postDelayed(this, 1500)
        }
    }

    private fun startWatcher() {
        if (!prefs.getBoolean("watch", true) || watching) return
        rules = Store.rules(this)
        seen.clear(); seeded = false
        watching = true
        main.postDelayed(watchTick, 1500)
    }

    private fun stopWatcher() {
        watching = false
        main.removeCallbacks(watchTick)
    }

    fun refreshWatcher() {
        rules = Store.rules(this)
        if (prefs.getBoolean("watch", true)) startWatcher() else stopWatcher()
    }

    private fun readChat() {
        val pipe = pipeline ?: return
        val full = pipe.grab(minOf(1f, 1280f / pipe.width)) ?: return
        // Bedrock shows chat on the left side of the screen
        val region = prefs.getString("chatArea", "left") ?: "left"
        val x0 = 0; val x1 = if (region == "all") full.width else (full.width * 0.62f).toInt()
        val y0 = (full.height * 0.04f).toInt(); val y1 = (full.height * 0.92f).toInt()
        val crop = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
        full.recycle()
        val text = try { Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 10, TimeUnit.SECONDS) } catch (_: Exception) { null }
        crop.recycle()
        text ?: return
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > 180_000 }
        for (block in text.textBlocks) for (line in block.lines) {
            val t = line.text.trim()
            if (t.length < 3) continue
            val key = t.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            if (key.length < 3) continue
            if (seen.containsKey(key) || seen.keys.any { abs(it.length - key.length) <= 2 && similar(it, key) }) { seen[key] = now; continue }
            seen[key] = now
            // The first look only learns what's already on screen (scoreboards, menus)
            if (!seeded) continue
            val hit = rules.check(t, now) ?: continue
            flag(hit)
        }
        seeded = true
    }

    /** Text recognition can misread a letter between looks; treat near-identical lines as the same. */
    private fun similar(a: String, b: String): Boolean {
        if (a.length < 8 || b.length < 8) return false
        val n = minOf(a.length, b.length)
        var same = 0
        for (i in 0 until n) if (a[i] == b[i]) same++
        return same >= n - 2
    }

    private fun flag(hit: ChatRules.Hit) {
        val f = Store.Flag(Store.newId(), System.currentTimeMillis(), hit.player, hit.message, hit.reasons)
        Store.addFlag(this, f)
        changed()
        if (prefs.getBoolean("notifyFlags", true)) {
            alert(f.id.hashCode(), hit.reasons.joinToString(), (hit.player?.let { "$it: " } ?: "") + hit.message,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (prefs.getBoolean("flagsToDiscord", false)) {
            val hook = Store.activeHook(this)?.url ?: ""
            if (Uploader.validWebhook(hook)) uploads.execute {
                try {
                    Uploader.discord(this, hook, "", embed = JSONObject()
                        .put("title", hit.reasons.joinToString().take(250))
                        .put("description", ("**${hit.player ?: "Unknown"}**: ${hit.message}").take(3900))
                        .put("color", 0xE5484D)
                        .put("footer", JSONObject().put("text", "Bedrock · Staff Toolkit (phone)")))
                } catch (_: Exception) {}
            }
        }
        if (prefs.getBoolean("autoClip", false) && recorder != null) work.execute { saveClip(f.id) }
    }

    // ---------- floating button ----------

    private var bubble: LinearLayout? = null
    private var bubbleMenu: LinearLayout? = null
    private var bubbleMain: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this) || projection == null) return
        val wm = getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.getInt("bubbleX", 0); y = prefs.getInt("bubbleY", dp(160)) }

        fun round(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        fun btn(label: String, desc: String, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; gravity = Gravity.CENTER; contentDescription = desc
            background = round(0xEE20242C.toInt()); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(6) }
            setOnClickListener { onTap() }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mainBtn = TextView(this).apply {
            text = "🛡"; textSize = 20f; gravity = Gravity.CENTER; contentDescription = "Staff Toolkit"
            background = round(0xEE3FB68B.toInt()); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        menu.addView(btn("📷", "Screenshot") { menu.visibility = View.GONE; work.execute { screenshot() } })
        menu.addView(btn("⏺", "Start or stop recording") { menu.visibility = View.GONE; work.execute { toggleRecording() } })
        menu.addView(btn("⏪", "Save the last seconds") { menu.visibility = View.GONE; work.execute { saveClip(null) } })
        menu.addView(btn("⚙", "Open Staff Toolkit") {
            menu.visibility = View.GONE
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        root.addView(mainBtn)
        root.addView(menu)

        // Drag to move; tap to open the menu
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        mainBtn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); try { wm.updateViewLayout(root, lp) } catch (_: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("bubbleX", lp.x).putInt("bubbleY", lp.y).apply()
                    else menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    true
                }
                else -> false
            }
        }
        try { wm.addView(root, lp) } catch (_: Exception) { return }
        bubble = root; bubbleMenu = menu; bubbleMain = mainBtn
        updateBubble()
    }

    private fun updateBubble() {
        val b = bubbleMain ?: return
        (b.background as? GradientDrawable)?.setColor(if (recording) 0xEEFF3B4E.toInt() else 0xEE3FB68B.toInt())
        b.text = if (recording) "⏺" else "🛡"
        (bubbleMenu?.getChildAt(1) as? TextView)?.text = if (recording) "⏹" else "⏺"
        (bubbleMenu?.getChildAt(2) as? TextView)?.alpha = if (recorder != null && prefs.getBoolean("replayOn", false)) 1f else 0.4f
    }

    private fun hideBubble() {
        bubble?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        bubble = null; bubbleMenu = null; bubbleMain = null
    }

    // ---------- notifications ----------

    private fun pending(action: String, req: Int) = PendingIntent.getService(this, req, Intent(this, CaptureService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun note(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = buildString {
            append(if (recording) "Recording" else "Ready")
            if (prefs.getBoolean("replayOn", false)) append(" · replay on")
            if (watching) append(" · watching chat")
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Staff Toolkit")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Screenshot", pending(ACTION_SHOT, 1)).build())
            .addAction(Notification.Action.Builder(null, if (recording) "Stop recording" else "Record", pending(ACTION_RECORD, 2)).build())
            .addAction(Notification.Action.Builder(null, "Stop", pending(ACTION_STOP, 3)).build())
            .build()
    }

    private fun alert(id: Int, title: String, text: String, tap: Intent?) {
        val n = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (tap != null) n.setContentIntent(PendingIntent.getActivity(this, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        try { getSystemService(NotificationManager::class.java).notify(id, n.build()) } catch (_: SecurityException) {}
    }

    private fun toast(msg: String) { main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
