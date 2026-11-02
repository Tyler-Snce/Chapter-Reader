package com.chapterreader.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * Loads a page in a hidden browser, for sites protected by Cloudflare's "checking your browser"
 * page. A real browser passes that check by itself; the pass (a cookie) is shared with the app's
 * own browser, so later chapters load straight away. Call from the main thread.
 */
object WebFetcher {
    private val hosts = HashSet<String>()

    /** Sites that turned away a direct fetch, so they're loaded this way straight away next time. */
    fun needsBrowser(url: String) = hosts.contains(host(url))
    fun remember(url: String) { hosts.add(host(url)) }
    private fun host(url: String) = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }

    fun isChallenge(code: Int, body: String, cfHeader: String?) =
        (code == 403 || code == 503 || code == 429) &&
            (cfHeader != null || body.contains("Just a moment", true) || body.contains("challenge-platform") || body.contains("cf-chl", true))

    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(ctx: Context, url: String, done: (html: String?, finalUrl: String?) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val web = WebView(app)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        // Shown as a tiny invisible window so the page runs like a real one (needs "display over other apps")
        val wm = app.getSystemService(WindowManager::class.java)
        var attached = false
        if (Settings.canDrawOverlays(app)) {
            val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
                .apply { gravity = Gravity.TOP or Gravity.START; alpha = 0f }
            try { wm.addView(web, lp); attached = true } catch (_: Exception) {}
        }
        var finished = false
        val started = System.currentTimeMillis()
        fun finish(html: String?, at: String?) {
            if (finished) return
            finished = true
            CookieManager.getInstance().flush()
            if (attached) try { wm.removeView(web) } catch (_: Exception) {}
            web.stopLoading(); web.destroy()
            done(html, at)
        }
        val check = object : Runnable {
            override fun run() {
                if (finished) return
                if (System.currentTimeMillis() - started > 40_000) { finish(null, null); return }
                web.evaluateJavascript("[document.title, document.body ? document.body.innerText.length : 0, location.href]") { r ->
                    val a = try { JSONArray(r) } catch (_: Exception) { null }
                    val title = a?.optString(0) ?: ""
                    val len = a?.optInt(1) ?: 0
                    val challenge = Regex("just a moment|attention required|checking your browser|verify you are human", RegexOption.IGNORE_CASE).containsMatchIn(title)
                    if (!challenge && len > 300) {
                        val at = a?.optString(2)
                        web.evaluateJavascript("document.documentElement.outerHTML") { h ->
                            val html = try { JSONArray("[$h]").getString(0) } catch (_: Exception) { null }
                            finish(html, at)
                        }
                    } else main.postDelayed(this, 1000)
                }
            }
        }
        web.loadUrl(url)
        main.postDelayed(check, 1500)
    }
}
