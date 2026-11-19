package com.jpsums.subscout

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * What the screen (index.html) can ask the phone to do. Slow jobs answer later through
 * window.SSNative('done' | 'error' | 'progress', id, message); big answers are picked up with take(id).
 */
class Bridge(private val a: MainActivity) {
    private val results = ConcurrentHashMap<String, String>()

    private fun done(id: String, json: String) { results[id] = json; a.emit("done", id, "") }
    private fun fail(id: String, msg: String) = a.emit("error", id, msg)
    private fun open(i: Intent) { a.runOnUiThread { try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { } } }

    @JavascriptInterface
    fun info(): String {
        val st = AlertService.stats(a)
        return JSONObject()
            .put("version", try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Exception) { "" })
            .put("notif", AlertService.isOn(a))
            .put("alerts", st.first).put("alertsSince", st.second)
            .put("accounts", try { Accounts.listPublic(a) } catch (_: Exception) { JSONArray() })
            .toString()
    }

    @JavascriptInterface
    fun take(id: String): String = results.remove(id) ?: "null"

    @JavascriptInterface
    fun alerts(): String = try { AlertService.read(a) } catch (_: Exception) { "[]" }

    @JavascriptInterface
    fun openNotifAccess() = open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    @JavascriptInterface
    fun openAppSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))

    @JavascriptInterface
    fun openUrl(url: String) { if (url.startsWith("https://")) open(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    @JavascriptInterface
    fun addAccount(id: String, json: String) {
        thread {
            var host = ""
            try {
                val o = JSONObject(json)
                val email = o.getString("email").trim()
                val pass = o.getString("pass").replace(" ", "")
                host = o.optString("host").trim().ifEmpty { TextUtil.hostFor(email) }
                Mail.check(Accounts.Account("", email, host, 993, pass))
                Accounts.add(a, email, host, 993, pass)
                done(id, "{\"ok\":true}")
            } catch (e: Throwable) { fail(id, Mail.explain(e, host)) }
        }
    }

    @JavascriptInterface
    fun removeAccount(accId: String) { try { Accounts.remove(a, accId) } catch (_: Exception) { } }

    @JavascriptInterface
    fun mailHeaders(id: String, accId: String, months: Int) {
        thread {
            var host = ""
            try {
                val acc = Accounts.get(a, accId) ?: throw IllegalStateException("That mailbox is no longer connected.")
                host = acc.host
                done(id, Mail.headers(acc, months))
            } catch (e: Throwable) { fail(id, Mail.explain(e, host)) }
        }
    }

    @JavascriptInterface
    fun mailBodies(id: String, accId: String, uidsJson: String) {
        thread {
            var host = ""
            try {
                val acc = Accounts.get(a, accId) ?: throw IllegalStateException("That mailbox is no longer connected.")
                host = acc.host
                val arr = JSONArray(uidsJson)
                val uids = LongArray(arr.length()) { arr.getLong(it) }
                done(id, Mail.bodies(acc, uids) { n, total -> a.emit("progress", id, "$n/$total") })
            } catch (e: Throwable) { fail(id, Mail.explain(e, host)) }
        }
    }
}
