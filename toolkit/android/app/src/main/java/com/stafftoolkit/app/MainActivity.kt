package com.stafftoolkit.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** The Staff Toolkit screen: start/stop, settings, chat rules, flagged messages and captures. */
class MainActivity : Activity() {
    private val BG = 0xFF111317.toInt()
    private val CARD = 0xFF20242C.toInt()
    private val BTN = 0xFF2C313B.toInt()
    private val ACCENT = 0xFF3FB68B.toInt()
    private val REC = 0xFFFF3B4E.toInt()
    private val FG = 0xFFF3F5F9.toInt()
    private val MUTED = 0xFF8A92A2.toInt()
    private val prefs by lazy { Store.prefs(this) }
    private lateinit var startBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var flagBox: LinearLayout
    private lateinit var capBox: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), px(14), px(14), px(30)) }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Staff Toolkit"; textSize = 22f; setTextColor(FG); typeface = Typeface.DEFAULT_BOLD })
        statusText = text("", MUTED, 13f)
        root.addView(statusText)
        startBtn = button("Start", ACCENT) { toggleRunning() }.apply { textSize = 17f; minHeight = px(56) }
        root.addView(startBtn, lp(top = 10))
        root.addView(text("Starting asks to share your screen. Choose \"Entire screen\" so it works in Minecraft. A floating button then gives you screenshot, record and save-clip.", MUTED, 12.5f), lp(top = 6))

        // Quick buttons
        val quick = row()
        quick.addView(button("📷 Screenshot") { send(CaptureService.ACTION_SHOT) }, weight())
        quick.addView(button("⏺ Record") { send(CaptureService.ACTION_RECORD) }, weight())
        quick.addView(button("⏪ Save clip") { send(CaptureService.ACTION_CLIP) }, weight())
        root.addView(quick, lp(top = 10))

        // ----- Watching and capture -----
        root.addView(heading("Chat watcher (Bedrock)"))
        val c1 = card()
        c1.addView(check("Read chat on screen and flag rule-breaking messages", "watch", true) { CaptureService.instance?.refreshWatcher() })
        c1.addView(check("Notify me about flagged messages", "notifyFlags", true))
        c1.addView(check("Read the whole screen (not just the chat area on the left)", "chatArea_all", false) {
            prefs.edit().putString("chatArea", if (it) "all" else "left").apply()
        })
        c1.addView(text("It reads text from the screen every 1–2 seconds, so it uses some extra battery.", MUTED, 12.5f))
        root.addView(c1, lp())

        root.addView(heading("Capture"))
        val c2 = card()
        c2.addView(check("Replay buffer: keep the last seconds ready to save", "replayOn", false) { refresh() })
        c2.addView(choice("Replay length", "replaySeconds", listOf("15" to "15 s", "30" to "30 s", "60" to "1 min", "120" to "2 min"), "30", int = true) { refresh() })
        c2.addView(choice("Size", "size", listOf("720" to "720p", "1080" to "1080p", "1440" to "1440p", "native" to "Full"), "1080"))
        c2.addView(choice("FPS", "fps", listOf("30" to "30", "60" to "60", "90" to "90", "120" to "120"), "60", int = true))
        c2.addView(choice("Quality", "quality", listOf("standard" to "Standard", "high" to "High", "max" to "Best"), "high"))
        c2.addView(check("Floating button over games", "bubble", true) { if (it) askOverlay(); refresh() })
        c2.addView(check("Save a clip automatically when a message is flagged", "autoClip", false))
        c2.addView(text("Size, FPS, quality and sound changes apply the next time you press Start.", MUTED, 12.5f))
        root.addView(c2, lp())

        root.addView(heading("Sound"))
        val c3 = card()
        c3.addView(check("Game and phone sound", "gameSound", true) { if (it) askMic() })
        c3.addView(slider("Game volume", "gameVolume"))
        c3.addView(check("Microphone", "mic", false) { if (it) askMic() })
        c3.addView(slider("Mic volume", "micVolume"))
        c3.addView(check("Mute everything", "muted", false))
        c3.addView(text("Sound needs the microphone permission (Android asks for it even for game sound).", MUTED, 12.5f))
        root.addView(c3, lp())

        // ----- Uploads -----
        root.addView(heading("After a capture"))
        val c4 = card()
        c4.addView(choice("Then", "after", listOf("upload" to "Upload + copy link", "save" to "Just save"), "upload"))
        c4.addView(choice("Upload to", "uploadTo", listOf("catbox" to "Catbox", "discord" to "Discord", "both" to "Both", "none" to "Nowhere"), "catbox"))
        c4.addView(text("Everything is also saved in your gallery (Pictures / Movies → Staff Toolkit).", MUTED, 12.5f))
        val hook = edit("Discord webhook link", prefs.getString("webhook", "") ?: "", false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        c4.addView(hook, lp(top = 8))
        val hr = row()
        hr.addView(button("Save") { prefs.edit().putString("webhook", hook.text.toString().trim()).apply(); toast("Saved") }, weight())
        hr.addView(button("Test") {
            prefs.edit().putString("webhook", hook.text.toString().trim()).apply()
            Thread {
                val msg = try { Uploader.discord(this, hook.text.toString(), "Staff Toolkit (phone) is connected to this channel."); "Test sent – check the channel" } catch (e: Exception) { e.message ?: "Failed" }
                runOnUiThread { toast(msg) }
            }.start()
        }, weight())
        c4.addView(hr, lp(top = 6))
        c4.addView(check("Post flagged messages to Discord", "flagsToDiscord", false))
        c4.addView(text("Discord: channel settings → Integrations → Webhooks → New webhook → Copy URL.", MUTED, 12.5f))
        root.addView(c4, lp())

        // ----- Rules -----
        root.addView(heading("Chat rules"))
        val c5 = card()
        c5.addView(check("Advertising (server addresses, IPs, Discord invites)", "r_advertising", true))
        c5.addView(check("Blocked words", "r_words", true))
        c5.addView(check("Spam (repeating, very fast messages)", "r_spam", true))
        c5.addView(check("Excessive caps", "r_caps", true))
        c5.addView(check("Character / word flooding", "r_flood", true))
        val words = edit("Blocked words, one per line", prefs.getString("blockedWords", "") ?: "", true)
        val allow = edit("Allowed websites, one per line", prefs.getString("allowDomains", "youtube.com\nyoutu.be\ntwitch.tv\nimgur.com") ?: "", true)
        val watch = edit("Watch list (players), one per line", prefs.getString("watchPlayers", "") ?: "", true)
        val ignore = edit("Ignore (staff, bots), one per line", prefs.getString("ignorePlayers", "") ?: "", true)
        for (e in listOf(words, allow, watch, ignore)) c5.addView(e, lp(top = 8))
        c5.addView(button("Save rules", ACCENT) {
            prefs.edit().putString("blockedWords", words.text.toString()).putString("allowDomains", allow.text.toString())
                .putString("watchPlayers", watch.text.toString()).putString("ignorePlayers", ignore.text.toString()).apply()
            CaptureService.instance?.refreshWatcher()
            toast("Rules saved")
        }, lp(top = 8))
        root.addView(c5, lp())

        // ----- Lists -----
        root.addView(heading("Flagged messages"))
        flagBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(flagBox)
        root.addView(heading("Your captures"))
        capBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(capBox)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        CaptureService.onChange = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        super.onPause()
        CaptureService.onChange = null
    }

    // ---------- start / stop ----------

    private fun toggleRunning() {
        val s = CaptureService.instance
        if (s?.running == true) { send(CaptureService.ACTION_STOP); return }
        if ((prefs.getBoolean("gameSound", true) || prefs.getBoolean("mic", false)) && !prefs.getBoolean("muted", false) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) { askMic(); return }
        if (prefs.getBoolean("bubble", true) && !Settings.canDrawOverlays(this)) { askOverlay(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data))
            statusText.postDelayed({ render() }, 800)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("Without it, recordings will have no sound")
            prefs.edit().putBoolean("muted", true).apply()
        }
    }

    private fun askMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        }
    }

    private fun askOverlay() {
        if (Settings.canDrawOverlays(this)) return
        toast("Allow \"Display over other apps\" for the floating button")
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun send(action: String, extra: (Intent) -> Unit = {}) {
        if (CaptureService.instance?.running != true && action != CaptureService.ACTION_STOP) { toast("Press Start first"); return }
        startService(Intent(this, CaptureService::class.java).setAction(action).also(extra))
    }

    private fun refresh() { if (CaptureService.instance?.running == true) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH)) }

    // ---------- lists ----------

    private fun render() {
        val s = CaptureService.instance
        val on = s?.running == true
        startBtn.text = if (on) "Stop Staff Toolkit" else "Start"
        (startBtn.background as GradientDrawable).setColor(if (on) BTN else ACCENT)
        statusText.text = if (!on) "Not running" else buildString {
            append(if (s!!.recording) "● Recording" else "Running")
            if (s.replayOn) append(" · replay on")
            if (prefs.getBoolean("watch", true)) append(" · watching chat")
        }
        statusText.setTextColor(if (s?.recording == true) REC else MUTED)
        renderFlags()
        renderCaptures()
    }

    private fun renderFlags() {
        flagBox.removeAllViews()
        val list = Store.flags(this).filter { !it.done }.take(50)
        if (list.isEmpty()) { flagBox.addView(text("No flagged messages.", MUTED, 13f)); return }
        for (f in list) {
            val c = card()
            c.addView(text(f.reasons.joinToString(), 0xFFFFC9CD.toInt(), 12.5f))
            c.addView(text((f.player?.let { "$it: " } ?: "") + f.message, FG, 14.5f))
            c.addView(text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(f.time) +
                (f.evidence?.let { "  ·  Evidence: $it" } ?: ""), MUTED, 12f))
            val r = row()
            r.addView(button("Copy") {
                Uploader.copy(this, (f.player?.let { "$it: " } ?: "") + f.message + (f.evidence?.let { "\nEvidence: $it" } ?: ""))
                toast("Copied")
            }, weight())
            r.addView(button("Handled", ACCENT) { Store.updateFlag(this, f.id) { it.done = true }; renderFlags() }, weight())
            c.addView(r, lp(top = 6))
            flagBox.addView(c, lp(top = 6))
        }
    }

    private fun renderCaptures() {
        capBox.removeAllViews()
        val list = Store.captures(this).take(50)
        if (list.isEmpty()) { capBox.addView(text("Screenshots, clips and recordings show up here.", MUTED, 13f)); return }
        for (cap in list) {
            val c = card()
            val size = if (cap.size > 1e6) String.format("%.1f MB", cap.size / 1e6) else "${cap.size / 1000} KB"
            c.addView(text(cap.name, FG, 14f))
            c.addView(text(size + (cap.link?.let { "  ·  $it" } ?: ""), MUTED, 12f))
            val r = row()
            if (cap.link != null) r.addView(button("Copy link", ACCENT) { Uploader.copy(this, cap.link!!); toast("Link copied") }, weight())
            else r.addView(button("Upload") {
                val svc = CaptureService.instance
                if (svc != null) { svc.upload(cap); toast("Uploading…") }
                else Thread {
                    val msg = try { Uploader.send(this, cap)?.let { "Link copied" } ?: "Posted to Discord" } catch (e: Exception) { "Upload failed: ${e.message}" }
                    runOnUiThread { toast(msg); renderCaptures() }
                }.start()
            }, weight())
            r.addView(button("Share") {
                val share = Intent(Intent.ACTION_SEND).setType(if (cap.kind == "screenshot") "image/png" else "video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(cap.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(share, "Share"))
            }, weight())
            r.addView(button("Open") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(cap.uri), if (cap.kind == "screenshot") "image/png" else "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { toast("No app to open it") }
            }, weight())
            r.addView(button("✕") {
                Media.delete(this, Uri.parse(cap.uri))
                Store.saveCaptures(this, Store.captures(this).filter { it.id != cap.id })
                renderCaptures()
            }, LinearLayout.LayoutParams(px(48), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(4) })
            c.addView(r, lp(top = 6))
            c.addView(text("To trim or add music: Open → Edit in your Gallery app.", MUTED, 11.5f))
            capBox.addView(c, lp(top = 6))
        }
    }

    // ---------- small UI helpers ----------

    private fun rounded(color: Int, r: Int = 12) = GradientDrawable().apply { cornerRadius = r * dp; setColor(color) }
    private fun text(t: String, color: Int, size: Float) = TextView(this).apply { text = t; setTextColor(color); textSize = size }
    private fun heading(t: String) = TextView(this).apply {
        text = t; setTextColor(FG); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(18), 0, px(6))
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(CARD); setPadding(px(12), px(10), px(12), px(10)) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(3); marginEnd = px(3) }

    private fun button(label: String, color: Int = BTN, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(if (color == ACCENT) 0xFF06140E.toInt() else FG); gravity = Gravity.CENTER
        background = rounded(color, 10); minHeight = px(44); setPadding(px(6), px(8), px(6), px(8))
        isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun check(label: String, key: String, def: Boolean, onChange: (Boolean) -> Unit = {}) = CheckBox(this).apply {
        text = label; setTextColor(FG); textSize = 14f
        buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply(); onChange(v) }
    }

    /** A row of options to pick one from. */
    private fun choice(label: String, key: String, options: List<Pair<String, String>>, def: String, int: Boolean = false, onChange: () -> Unit = {}): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6), 0, px(2)) }
        box.addView(text(label, MUTED, 12.5f))
        val r = row()
        val current = if (int) prefs.getInt(key, def.toInt()).toString() else prefs.getString(key, def) ?: def
        val btns = ArrayList<TextView>()
        for ((value, name) in options) {
            val b = button(name) {}
            b.setOnClickListener {
                if (int) prefs.edit().putInt(key, value.toInt()).apply() else prefs.edit().putString(key, value).apply()
                btns.forEachIndexed { i, x -> (x.background as GradientDrawable).setColor(if (options[i].first == value) ACCENT else BTN); x.setTextColor(if (options[i].first == value) 0xFF06140E.toInt() else FG) }
                onChange()
            }
            if (value == current) { (b.background as GradientDrawable).setColor(ACCENT); b.setTextColor(0xFF06140E.toInt()) }
            btns.add(b)
            r.addView(b, weight())
        }
        box.addView(r)
        return box
    }

    private fun slider(label: String, key: String): View {
        val box = row()
        val v = text("", MUTED, 12.5f)
        val bar = SeekBar(this).apply {
            max = 200
            progress = (prefs.getFloat(key, 1f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { v.text = "$label $p%"; if (fromUser) prefs.edit().putFloat(key, p / 100f).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        v.text = "$label ${bar.progress}%"
        box.addView(v, LinearLayout.LayoutParams(px(130), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return box
    }

    private fun edit(hint: String, value: String, multi: Boolean) = EditText(this).apply {
        this.hint = hint; setText(value); setTextColor(FG); setHintTextColor(MUTED); textSize = 14f
        background = rounded(0xFF12151A.toInt(), 8); setPadding(px(10), px(8), px(10), px(8))
        if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; minLines = 3; gravity = Gravity.TOP }
        else setSingleLine()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
