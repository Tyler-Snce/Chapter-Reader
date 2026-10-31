package com.chapterreader.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Dark full-screen view of the chapter being read. Highlights the paragraph
 * being spoken, scrolls along with it and shows how far through you are.
 */
class ReadingViewActivity : Activity(), ReaderService.Listener {

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var progressText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var playBtn: TextView
    private lateinit var rateText: TextView
    private lateinit var manga: WebView
    private lateinit var caption: TextView
    private lateinit var paceBtn: TextView
    private val paces = floatArrayOf(0f, 0.3f, 0.6f, 1f, 1.5f, 2.5f, 4f)
    private var mangaFor: String? = null
    private var mangaReady = false
    private val glideTimes = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile private var mangaTouch = 0L

    private val paraViews = ArrayList<TextView>()
    private var builtFor: String? = null
    private var current = -1
    private var lastTouch = 0L
    private var textSize = 19f

    private val prefs by lazy { ReaderService.prefs(this) }
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = PANEL
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        textSize = prefs.getFloat("viewText", 19f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Header: chapter name and progress
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(12), px(18), px(10))
            setBackgroundColor(PANEL)
        }
        titleText = TextView(this).apply {
            textSize = 16f
            setTextColor(TEXT_ON)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        progressText = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, px(4), 0, px(6))
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3545.toInt())
        }
        header.addView(titleText)
        header.addView(progressText)
        header.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(4)))
        root.addView(header)

        // Chapter text
        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(20), px(18), px(260))
        }
        scroll.addView(list)
        scroll.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                lastTouch = SystemClock.uptimeMillis()
            }
            false
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Manga / webtoon pages, with the speech bubble being read outlined
        manga = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(BG)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(MangaBridge(), "CRBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    mangaReady = true
                    view.evaluateJavascript("pace=${pace()}", null)
                    pushManga()
                }
            }
        }
        root.addView(manga, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        caption = TextView(this).apply {
            visibility = View.GONE
            textSize = 15f
            setTextColor(TEXT_ON)
            setBackgroundColor(HIGHLIGHT)
            setPadding(px(16), px(10), px(16), px(10))
            maxLines = 4
        }
        root.addView(caption)

        // Controls
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(px(8), px(8), px(8), px(10))
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(button("⏮\nChapter") { svc()?.prevChapter() }, weighted())
        row1.addView(button("⏪\nPara") { svc()?.jumpPara(-1) }, weighted())
        playBtn = button("▶", ACCENT) { svc()?.toggle() }.apply { textSize = 22f }
        row1.addView(playBtn, weighted())
        row1.addView(button("⏩\nPara") { svc()?.jumpPara(1) }, weighted())
        row1.addView(button("⏭\nChapter") { svc()?.nextChapter() }, weighted())
        controls.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, 0)
        }
        row2.addView(button("−") { svc()?.let { it.setRate(it.rate - 0.1f) } }, small())
        rateText = TextView(this).apply {
            textSize = 14f
            setTextColor(TEXT_ON)
            gravity = Gravity.CENTER
        }
        row2.addView(rateText, small())
        row2.addView(button("+") { svc()?.let { it.setRate(it.rate + 0.1f) } }, small())
        row2.addView(button("A−") { setText(textSize - 1f) }, small())
        row2.addView(button("A+") { setText(textSize + 1f) }, small())
        // Manga only: how quickly to scroll through the artwork between speech bubbles
        paceBtn = button("") { nextPace() }.apply { visibility = View.GONE }
        row2.addView(paceBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f).apply { setMargins(px(3), 0, px(3), 0) })
        row2.addView(button("◎ Follow") {
            lastTouch = 0
            if (svc()?.isManga == true) manga.evaluateJavascript("follow()", null) else scrollToCurrent(true)
        },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f).apply { setMargins(px(3), 0, px(3), 0) })
        controls.addView(row2)
        root.addView(controls)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ReaderService.viewListener = this
        ReaderService.viewVisible = true
        svc()?.updateBubble()
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        ReaderService.viewListener = null
        ReaderService.viewVisible = false
        svc()?.updateBubble()
    }

    private fun svc(): ReaderService? = ReaderService.instance

    // ---------- content ----------

    private fun rebuild() {
        val s = svc()
        val ch = s?.chapter
        if (s == null || ch == null) {
            list.removeAllViews()
            paraViews.clear()
            builtFor = null
            list.addView(TextView(this).apply {
                text = "Nothing is being read yet.\n\nOpen a chapter in your browser and tap the bubble, then come back here."
                textSize = 17f
                setTextColor(MUTED)
            })
            refresh()
            return
        }
        if (ch.isManga) {
            showManga(true)
            if (mangaFor != ch.url) {
                mangaFor = ch.url
                mangaReady = false
                manga.loadDataWithBaseURL("file:///", MANGA_HTML, "text/html", "utf-8", null)
            } else pushManga()
            builtFor = null
            refresh()
            return
        }
        showManga(false)
        if (ch.url != builtFor) {
            list.removeAllViews()
            paraViews.clear()
            current = -1
            if (ch.heading.isNotEmpty()) {
                list.addView(TextView(this).apply {
                    text = ch.heading
                    textSize = this@ReadingViewActivity.textSize + 4f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(TEXT_ON)
                    setPadding(px(10), 0, px(10), px(16))
                })
            }
            ch.paras.forEachIndexed { i, t ->
                val tv = TextView(this).apply {
                    text = t
                    textSize = this@ReadingViewActivity.textSize
                    setTextColor(TEXT)
                    setLineSpacing(0f, 1.35f)
                    setPadding(px(10), px(8), px(10), px(8))
                    setOnClickListener { svc()?.startAtPara(i) }
                }
                list.addView(tv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(4) })
                paraViews.add(tv)
            }
            builtFor = ch.url
            scroll.scrollTo(0, 0)
        }
        highlight(s.currentPara, smooth = false)
        refresh()
    }

    private fun pace(): Float = prefs.getFloat("artPace", 1f)

    private fun showPace() {
        val p = pace()
        paceBtn.text = if (p == 0f) "Art\nOff" else String.format(Locale.UK, "Art\n%.1fs", p)
        if (mangaReady) manga.evaluateJavascript("pace=$p", null)
    }

    /** Cycles the artwork pace: off, then from very fast to slow. */
    private fun nextPace() {
        val cur = pace()
        val i = paces.indexOfFirst { it > cur + 0.01f }
        val next = if (i < 0) paces[0] else paces[i]
        prefs.edit().putFloat("artPace", next).apply()
        glideTimes.clear()
        showPace()
        Toast.makeText(
            this,
            if (next == 0f) "Artwork: off (jump to each bubble)" else String.format(Locale.UK, "Artwork: %.1f seconds per screen", next),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showManga(on: Boolean) {
        paceBtn.visibility = if (on) View.VISIBLE else View.GONE
        if (on) showPace()
        manga.visibility = if (on) View.VISIBLE else View.GONE
        caption.visibility = if (on) View.VISIBLE else View.GONE
        scroll.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) mangaFor = null
    }

    /** Sends the downloaded pages and found speech bubbles to the manga view. */
    private fun pushManga() {
        val s = svc() ?: return
        if (!mangaReady || !s.isManga) return
        val pages = JSONArray()
        s.mangaFiles.forEach { pages.put(it ?: JSONObject.NULL) }
        val bubbles = JSONArray()
        s.bubbles.forEach { b ->
            bubbles.put(JSONArray().put(b.img).put(b.x.toDouble()).put(b.y.toDouble()).put(b.w.toDouble()).put(b.h.toDouble()))
        }
        manga.evaluateJavascript("setData($pages,$bubbles,${s.currentPara})", null)
        caption.text = s.bubbleText(s.currentPara) ?: if (s.mangaDone) "" else "Reading the pictures\u2026"
    }

    inner class MangaBridge {
        @JavascriptInterface
        fun tap(i: Int) { runOnUiThread { svc()?.startAtPara(i) } }

        /** How long gliding to bubble [i] will take, worked out by the page. */
        @JavascriptInterface
        fun glideTime(i: Int, ms: Int) { glideTimes[i] = ms }

        @JavascriptInterface
        fun touched() { mangaTouch = SystemClock.uptimeMillis() }
    }

    private fun highlight(i: Int, smooth: Boolean) {
        if (svc()?.isManga == true) {
            current = i
            if (mangaReady) manga.evaluateJavascript("setCur($i,${glideTimes[i] ?: if (smooth) 400 else 0})", null)
            caption.text = svc()?.bubbleText(i) ?: ""
            return
        }
        if (i != current) {
            paraViews.getOrNull(current)?.apply {
                background = null
                setTextColor(TEXT)
                text = text.toString()
            }
            current = i
            paraViews.getOrNull(i)?.apply {
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(HIGHLIGHT)
                }
                setTextColor(TEXT_ON)
            }
        }
        scrollToCurrent(smooth)
    }

    private fun scrollToCurrent(smooth: Boolean) {
        // Don't fight you if you've just scrolled by hand
        if (SystemClock.uptimeMillis() - lastTouch < 5000) return
        val v = paraViews.getOrNull(current) ?: return
        scroll.post {
            val y = (v.top - scroll.height / 4).coerceAtLeast(0)
            if (smooth) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y)
        }
    }

    private fun setText(size: Float) {
        textSize = size.coerceIn(13f, 32f)
        prefs.edit().putFloat("viewText", textSize).apply()
        paraViews.forEach { it.textSize = textSize }
        scrollToCurrent(false)
    }

    private fun refresh() {
        val s = svc()
        titleText.text = when {
            s == null -> "Chapter Reader"
            s.status.isNotEmpty() -> s.status
            else -> s.chapterName
        }
        val total = paraViews.size
        val p = s?.progress ?: 0f
        progressText.text = if (s?.isManga == true) {
            val n = s.bubbles.size
            val pages = s.mangaFiles.count { it != null }
            (if (n > 0) String.format(Locale.UK, "Bubble %d of %d", (s.currentPara + 1).coerceIn(1, n), n) else "No speech bubbles yet") +
                (if (s.mangaDone) "" else " \u00b7 reading page $pages of ${s.mangaFiles.size}")
        } else if (total > 0) {
            String.format(
                Locale.UK, "Paragraph %d of %d · %d%%",
                (current + 1).coerceIn(1, total), total, (p * 100).toInt()
            )
        } else ""
        bar.progress = (p * 1000).toInt()
        playBtn.text = when {
            s?.loading == true -> "…"
            s?.playing == true -> "⏸"
            else -> "▶"
        }
        rateText.text = String.format(Locale.UK, "%.1f×", s?.rate ?: prefs.getFloat("rate", 1f))
    }

    // ---------- service callbacks ----------

    override fun onStateChanged() = refresh()

    override fun onParagraph(index: Int) {
        highlight(index, smooth = true)
        refresh()
    }

    override fun onChapterChanged(url: String) = rebuild()

    override fun artPause(bubble: Int): Long {
        if (svc()?.isManga != true || !mangaReady) return 0L
        if (SystemClock.uptimeMillis() - mangaTouch < 5000) return 0L
        return (glideTimes[bubble] ?: 0).toLong()
    }

    override fun onMangaUpdate() {
        pushManga()
        refresh()
    }

    override fun onWord(para: Int, start: Int, end: Int) {
        if (svc()?.isManga == true) return
        if (para != current) highlight(para, smooth = true)
        val tv = paraViews.getOrNull(para) ?: return
        val plain = tv.text.toString()
        if (start < 0 || end > plain.length || start >= end) return
        val span = SpannableString(plain)
        span.setSpan(BackgroundColorSpan(WORD_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(WORD_FG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = span
        keepWordVisible(tv, start)
    }

    /** In long paragraphs, keeps the line being read on screen. */
    private fun keepWordVisible(tv: TextView, offset: Int) {
        if (SystemClock.uptimeMillis() - lastTouch < 5000) return
        val layout = tv.layout ?: return
        val line = layout.getLineForOffset(offset)
        val lineY = tv.top + tv.paddingTop + layout.getLineTop(line)
        val visTop = scroll.scrollY
        val visBottom = visTop + scroll.height
        if (lineY < visTop + scroll.height / 6 || lineY > visBottom - scroll.height / 3) {
            scroll.smoothScrollTo(0, (lineY - scroll.height / 3).coerceAtLeast(0))
        }
    }

    override fun onLoadFailed(url: String) = refresh()

    // ---------- helpers ----------

    private fun button(label: String, color: Int = SURFACE, onTap: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(TEXT_ON)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 10 * dp
            setColor(color)
        }
        isClickable = true
        minHeight = px(44)
        setPadding(px(2), px(6), px(2), px(6))
        setOnClickListener { onTap() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(px(3), 0, px(3), 0)
    }

    private fun small() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(px(3), 0, px(3), 0)
    }

    companion object {
        /** The manga page: images stacked full width, an outline over the bubble being read. */
        private val MANGA_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;background:#121016;color:#8e879c;font:15px sans-serif}
.pg{position:relative;width:100%}
.pg img{display:block;width:100%;height:auto}
.wait{height:70vh;display:flex;align-items:center;justify-content:center}
.b{position:absolute;border-radius:12px}
.b.cur{outline:3px solid #7b5cff;outline-offset:4px;background:rgba(123,92,255,.10)}
</style></head><body><div id="pages"></div>
<script>
var nb=0,cur=-1,lastTouch=0;
function setData(p,b,c){
  var box=document.getElementById('pages');
  while(box.children.length<p.length){var d=document.createElement('div');d.className='pg';d.innerHTML='<div class="wait">Loading page\u2026</div>';box.appendChild(d);}
  for(var i=0;i<p.length;i++){var d=box.children[i];if(p[i]&&!d.dataset.src){d.dataset.src=p[i];var w=d.querySelector('.wait');if(w)w.remove();var im=document.createElement('img');im.src='file://'+p[i];d.insertBefore(im,d.firstChild);}}
  for(var j=nb;j<b.length;j++){var q=b[j],d=box.children[q[0]];if(!d)continue;var o=document.createElement('div');o.className='b';
    o.style.left=(q[1]*100)+'%';o.style.top=(q[2]*100)+'%';o.style.width=(q[3]*100)+'%';o.style.height=(q[4]*100)+'%';
    o.dataset.i=j;o.onclick=function(){CRBridge.tap(+this.dataset.i)};d.appendChild(o);}
  nb=b.length;
  if(c!==cur)setCur(c,0);
}
var pace=1,anim=0;
function bub(i){return document.querySelector('.b[data-i="'+i+'"]');}
function targetY(o){return window.scrollY+o.getBoundingClientRect().top-window.innerHeight/3;}
function glide(target,ms){
  cancelAnimationFrame(anim);
  var start=window.scrollY,d=target-start;
  if(Math.abs(d)<4)return;
  if(!ms){window.scrollTo(0,target);return;}
  var t0=performance.now();
  function step(now){
    if(Date.now()-lastTouch<5000)return;
    var k=Math.min(1,(now-t0)/ms);
    var e=ms>1000?k:(k<.5?2*k*k:1-Math.pow(-2*k+2,2)/2);
    window.scrollTo(0,start+d*e);
    if(k<1)anim=requestAnimationFrame(step);
  }
  anim=requestAnimationFrame(step);
}
function setCur(i,ms){
  var old=document.querySelector('.b.cur');if(old)old.classList.remove('cur');
  cur=i;var o=bub(i);if(!o)return;o.classList.add('cur');
  if(Date.now()-lastTouch>5000)glide(targetY(o),ms);
  // Tell the app how long the glide to the next bubble will take, so it can wait for it
  var n=bub(i+1);
  if(n){var gap=targetY(n)-targetY(o);var t=!pace?0:(gap>window.innerHeight*0.5?Math.min(120000,gap/window.innerHeight*pace*1000):(gap>4?400:0));CRBridge.glideTime(i+1,Math.round(t));}
}
function follow(){lastTouch=0;var o=bub(cur);if(o)glide(targetY(o),500);}
['touchstart','touchmove'].forEach(function(e){addEventListener(e,function(){lastTouch=Date.now();CRBridge.touched();},{passive:true});});
</script></body></html>
"""
        private val BG = 0xFF121016.toInt()
        private val PANEL = 0xFF1D1B22.toInt()
        private val SURFACE = 0xFF34303D.toInt()
        private val ACCENT = 0xFF7B5CFF.toInt()
        private val HIGHLIGHT = 0xFF2E2754.toInt()
        private val TEXT = 0xFFB9B3C4.toInt()
        private val TEXT_ON = 0xFFF4F1F8.toInt()
        private val MUTED = 0xFF8E879C.toInt()
        private val WORD_BG = 0xFF7B5CFF.toInt()
        private val WORD_FG = 0xFFFFFFFF.toInt()
    }
}
