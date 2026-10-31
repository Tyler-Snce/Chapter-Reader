package com.chapterreader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.webkit.CookieManager
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * New chapter alerts: every few hours, checks each series in your library for a chapter
 * newer than the last one seen, and sends a notification when one comes out.
 */
object ChapterAlerts {
    private const val JOB_ID = 4242
    private const val CHANNEL = "alerts"
    private const val EVERY_MS = 3L * 60 * 60 * 1000
    private const val MAX_HOPS = 6

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean("alertsOn", true)

    fun setEnabled(ctx: Context, prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean("alertsOn", on).apply()
        schedule(ctx, prefs)
    }

    /** Sets up (or cancels) the regular background check. */
    fun schedule(ctx: Context, prefs: SharedPreferences) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(prefs)) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AlertJob::class.java))
            .setPeriodic(EVERY_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        try { js.schedule(job) } catch (_: Exception) {}
    }

    private class Page(val url: String, val title: String, val next: String?)

    private fun fetch(url: String, ua: String): Page? = try {
        val cookies = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        val conn = Jsoup.connect(url).userAgent(ua).timeout(20000).maxBodySize(0).followRedirects(true)
        if (!cookies.isNullOrEmpty()) conn.header("Cookie", cookies)
        val res = conn.execute()
        val ch = Extractor.parse(res.body(), res.url().toString())
        if (ch == null) null
        else Page(ch.url, ch.heading.ifEmpty { ch.title }, ch.next)
    } catch (_: Exception) {
        null
    }

    /**
     * Checks every series once. The first check of a series only notes its newest chapter;
     * later checks send a notification when a newer one appears. Returns how many series have
     * new chapters. Call from a background thread.
     */
    @Synchronized
    fun check(ctx: Context, prefs: SharedPreferences): Int {
        val ua = prefs.getString("ua", null)
            ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        val state = try { JSONObject(prefs.getString("alertState", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        var found = 0
        for (e in Library.list(prefs).take(40)) {
            val known = state.optJSONObject(e.id)
            var url = known?.optString("latest")?.takeIf { it.isNotEmpty() } ?: e.url
            var page = fetch(url, ua) ?: continue
            var title = page.title
            var hops = 0
            // Follow "next chapter" links until there isn't one: that's the newest chapter
            while (hops < MAX_HOPS) {
                val next = page.next ?: break
                if (next == url || next.substringBefore('#') == url.substringBefore('#')) break
                val np = fetch(next, ua) ?: break
                // A "next" link to the series' main page isn't a new chapter
                if (Library.seriesOf(np.url, np.title).first != e.id) break
                page = np; url = np.url; title = np.title; hops++
            }
            val first = known == null
            state.put(e.id, JSONObject().put("latest", url).put("title", title).put("checked", System.currentTimeMillis()))
            if (!first && hops > 0) {
                found++
                notify(ctx, e, title, hops)
            }
        }
        prefs.edit().putString("alertState", state.toString()).putLong("alertsChecked", System.currentTimeMillis()).apply()
        return found
    }

    private fun notify(ctx: Context, e: Library.Entry, title: String, count: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New chapters", NotificationManager.IMPORTANCE_DEFAULT))
        // Tapping it carries on reading the series from where you left off
        val open = Intent(ctx, ReaderService::class.java)
            .setAction(ReaderService.ACTION_LOAD_URL)
            .putExtra(ReaderService.EXTRA_URL, e.url)
            .putExtra(ReaderService.EXTRA_PARA, e.para)
        val pi = PendingIntent.getForegroundService(ctx, e.id.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (count > 1) "$count new chapters, up to $title" else "New: $title"
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_book)
            .setContentTitle(e.series)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\nTap to carry on from where you left off."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(e.id.hashCode(), n) } catch (_: SecurityException) {}
    }
}

/** Runs the new chapter check in the background, even when the app is closed. */
class AlertJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val prefs = getSharedPreferences("reader", MODE_PRIVATE)
            try {
                Sync.pull(prefs)   // include series started on the PC
                ChapterAlerts.check(this, prefs)
            } catch (_: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
