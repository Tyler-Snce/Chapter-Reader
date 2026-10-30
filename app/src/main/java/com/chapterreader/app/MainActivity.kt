package com.chapterreader.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity(), ReaderService.Listener {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var statusText: TextView
    private lateinit var playBtn: TextView
    private lateinit var rateText: TextView
    private lateinit var autoBox: CheckBox
    private lateinit var bubbleBox: CheckBox
    private val prefs by lazy { ReaderService.prefs(this) }
    private val dp by lazy { resources.displayMetrics.density }

    private fun px(v: Int) = (v * dp).toInt()

    // ---------- setup ----------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Address bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(6), px(8), px(6))
        }
        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "Type a web address"
            textSize = 14f
            setTextColor(FG)
            setHintTextColor(MUTED)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(SURFACE, 10)
            setPadding(px(10), px(8), px(10), px(8))
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)
                ) { go(text.toString()); true } else false
            }
        }
        top.addView(urlBar, LinearLayout.LayoutParams(0, px(42), 1f))
        top.addView(button("Read", ACCENT) { readThisPage() }, LinearLayout.LayoutParams(px(72), px(42)).apply {
            marginStart = px(6)
        })
        root.addView(top)

        // Browser
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Status line
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(px(12), px(6), px(12), px(2))
        }
        root.addView(statusText)

        // Main controls
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(4), px(6), px(2))
        }
        row1.addView(button("⏮\nChapter") { svc()?.prevChapter() ?: needRead() }, weighted())
        row1.addView(button("⏪\nPara") { svc()?.jumpPara(-1) ?: needRead() }, weighted())
        playBtn = button("▶", ACCENT) { playPressed() }.apply { textSize = 22f }
        row1.addView(playBtn, weighted())
        row1.addView(button("⏩\nPara") { svc()?.jumpPara(1) ?: needRead() }, weighted())
        row1.addView(button("⏭\nChapter") { svc()?.nextChapter() ?: needRead() }, weighted())
        root.addView(row1)

        // Speed and options
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(2), px(6), px(8))
        }
        row2.addView(button("−") { changeRate(-0.1f) }, LinearLayout.LayoutParams(px(48), px(42)))
        rateText = TextView(this).apply {
            textSize = 15f
            setTextColor(FG)
            gravity = Gravity.CENTER
        }
        row2.addView(rateText, LinearLayout.LayoutParams(px(54), px(42)))
        row2.addView(button("+") { changeRate(0.1f) }, LinearLayout.LayoutParams(px(48), px(42)))
        autoBox = checkbox("Auto next") { on ->
            prefs.edit().putBoolean("auto", on).apply()
            svc()?.setAuto(on)
        }
        row2.addView(autoBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = px(8)
        })
        bubbleBox = checkbox("Bubble") { on -> setBubble(on) }
        row2.addView(bubbleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        setContentView(root)

        // Browser settings
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.addJavascriptInterface(Bridge(), "CRBridge")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
                prefs.edit().putString("lastUrl", url).apply()
                val s = svc()
                if (s != null && sameUrl(url, s.chapter?.url)) {
                    view.evaluateJavascript(Scripts.INSTALL, null)
                    val p = s.currentPara
                    if (p >= 0) view.evaluateJavascript(Scripts.highlight(p), null)
                }
            }
        }
        prefs.edit().putString("ua", web.settings.userAgentString).apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val shared = sharedUrl(intent)
        val start = shared ?: svc()?.chapter?.url ?: prefs.getString("lastUrl", null) ?: "https://www.google.com"
        web.loadUrl(start)
        urlBar.setText(start)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUrl(intent)?.let { web.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        ReaderService.listener = this
        ReaderService.appVisible = true
        svc()?.updateBubble()
        refresh()
        val s = svc()
        if (s != null && s.playing && s.chapter != null && !sameUrl(web.url, s.chapter?.url)) web.loadUrl(s.chapter!!.url)
    }

    override fun onPause() {
        super.onPause()
        ReaderService.listener = null
        ReaderService.appVisible = false
        svc()?.updateBubble()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    // ---------- actions ----------

    private fun svc(): ReaderService? = ReaderService.instance

    private fun needRead() {
        Toast.makeText(this, "Open a chapter and tap Read first", Toast.LENGTH_SHORT).show()
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        web.loadUrl(url)
        urlBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun playPressed() {
        val s = svc()
        if (s?.chapter != null && sameUrl(web.url, s.chapter?.url)) s.toggle() else readThisPage()
    }

    private fun readThisPage() {
        web.evaluateJavascript(Scripts.EXTRACT) { result ->
            val ch = try { parseChapter(result) } catch (_: Exception) { null }
            if (ch == null) {
                Toast.makeText(this, "Couldn't find story text on this page", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            maybeAskOverlay()
            ReaderService.pending = Pair(ch.first, ch.second)
            val i = Intent(this, ReaderService::class.java)
                .setAction(ReaderService.ACTION_LOAD)
                .putExtra(ReaderService.EXTRA_PLAY, true)
            startForegroundService(i)
        }
    }

    private fun parseChapter(json: String?): Pair<Chapter, Int>? {
        if (json.isNullOrEmpty() || json == "null") return null
        val o = JSONObject(json)
        if (!o.optBoolean("ok")) return null
        val arr = o.getJSONArray("paras")
        val paras = ArrayList<String>()
        for (i in 0 until arr.length()) paras.add(arr.getString(i))
        if (paras.isEmpty()) return null
        fun str(k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
        val ch = Chapter(
            url = o.optString("url", web.url ?: ""),
            title = o.optString("title"),
            heading = o.optString("heading"),
            paras = paras,
            next = str("next"),
            prev = str("prev"),
        )
        return Pair(ch, o.optInt("start", 0))
    }

    private fun changeRate(delta: Float) {
        val s = svc()
        if (s != null) {
            s.setRate(s.rate + delta)
        } else {
            val r = Math.round((prefs.getFloat("rate", 1f) + delta).coerceIn(0.5f, 4f) * 10f) / 10f
            prefs.edit().putFloat("rate", r).apply()
            refresh()
        }
    }

    private fun setBubble(on: Boolean) {
        prefs.edit().putBoolean("bubble", on).apply()
        if (on && !Settings.canDrawOverlays(this)) openOverlaySettings()
        svc()?.updateBubble()
    }

    private fun maybeAskOverlay() {
        if (prefs.getBoolean("bubble", true) && !Settings.canDrawOverlays(this) &&
            !prefs.getBoolean("askedOverlay", false)
        ) {
            prefs.edit().putBoolean("askedOverlay", true).apply()
            openOverlaySettings()
        }
    }

    private fun openOverlaySettings() {
        Toast.makeText(
            this, "Turn on \"Allow display over other apps\" for Chapter Reader, then come back",
            Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun sharedUrl(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_VIEW) return intent.dataString
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("https?://\\S+").find(text)?.value
    }

    private fun sameUrl(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        fun norm(u: String) = u.substringBefore('#').trimEnd('/')
        return norm(a) == norm(b)
    }

    private fun refresh() {
        val s = svc()
        val rate = s?.rate ?: prefs.getFloat("rate", 1f)
        rateText.text = String.format(Locale.UK, "%.1f×", rate)
        playBtn.text = when {
            s?.loading == true -> "…"
            s?.playing == true -> "⏸"
            else -> "▶"
        }
        statusText.text = when {
            s == null -> "Tip: in your own browser tap Share → Chapter Reader"
            s.status.isNotEmpty() -> s.status
            else -> s.chapterName
        }
        autoBox.isChecked = s?.autoNext ?: prefs.getBoolean("auto", true)
        bubbleBox.isChecked = prefs.getBoolean("bubble", true) && Settings.canDrawOverlays(this)
    }

    // ---------- service callbacks ----------

    override fun onStateChanged() = refresh()

    override fun onParagraph(index: Int) {
        if (sameUrl(web.url, svc()?.chapter?.url)) web.evaluateJavascript(Scripts.highlight(index), null)
    }

    override fun onChapterChanged(url: String) {
        if (!sameUrl(web.url, url)) web.loadUrl(url)
        refresh()
    }

    override fun onLoadFailed(url: String) {
        web.loadUrl(url)
        Toast.makeText(this, "Opened the next chapter here. Tap Read to carry on.", Toast.LENGTH_LONG).show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun tap(index: Int) {
            runOnUiThread {
                val s = svc() ?: return@runOnUiThread
                if (sameUrl(web.url, s.chapter?.url)) s.startAtPara(index)
            }
        }
    }

    // ---------- small UI helpers ----------

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(color)
    }

    private fun button(label: String, color: Int = SURFACE, onTap: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(FG)
            gravity = Gravity.CENTER
            background = rounded(color, 10)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }

    private fun weighted() = LinearLayout.LayoutParams(0, px(54), 1f).apply {
        setMargins(px(3), 0, px(3), 0)
    }

    private fun checkbox(label: String, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        textSize = 13f
        setTextColor(FG)
        setOnClickListener { onChange(isChecked) }
    }

    companion object {
        private val BG = 0xFF1D1B22.toInt()
        private val SURFACE = 0xFF34303D.toInt()
        private val ACCENT = 0xFF6B4FD8.toInt()
        private val FG = 0xFFF2F0F5.toInt()
        private val MUTED = 0xFFA8A2B3.toInt()
    }
}
