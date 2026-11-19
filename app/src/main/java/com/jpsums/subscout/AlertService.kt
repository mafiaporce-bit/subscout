package com.jpsums.subscout

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Notices card-charge alerts as they arrive (bank SMS, Google / Samsung Wallet) and keeps a copy on
 * this phone, so SubScout can spot charges that repeat. Anything that does not look like a card
 * charge is ignored, and so are chat apps, email apps and one-time codes. Nothing is sent anywhere.
 */
class AlertService : NotificationListenerService() {

    companion object {
        private val MONEY = Regex("(?i)(\\b(AED|USD|EUR|GBP|PHP|SAR|QAR|KWD|BHD|OMR|INR|Dhs?)\\.?\\s?\\d|\\d\\s?(AED|USD|EUR|GBP|PHP|SAR)\\b|[$€£₱₹]\\s?\\d)")
        private val SPEND = Regex("(?i)\\b(purchase|purchased|spent|debited|charged|payment|paid|trx|txn|transaction|used|using|deducted|pos)\\b")
        private val CODES = Regex("(?i)\\b(otp|one[- ]time|verification code|do not share|never share|activation code)\\b")
        private val WALLETS = setOf("com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.google.android.apps.nbu.paisa.user")
        private val IGNORED = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "com.facebook.mlite", "com.facebook.katana",
            "org.telegram.messenger", "com.instagram.android", "com.snapchat.android", "com.viber.voip",
            "im.thebot.messenger", "com.discord", "com.twitter.android", "com.zhiliaoapp.musically",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
            "com.jpsums.zenix")
        private const val KEEP = 3000
        private val lock = Any()
        private var seen: HashSet<Int>? = null

        fun isOn(c: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
        private fun file(c: Context) = File(c.filesDir, "alerts.jsonl")

        /** Everything saved so far, as a JSON array of {t, pkg, title, text}. */
        fun read(c: Context): String {
            val out = JSONArray()
            synchronized(lock) {
                val f = file(c)
                if (f.exists()) f.forEachLine { line -> if (line.isNotBlank()) try { out.put(JSONObject(line)) } catch (_: Exception) { } }
            }
            return out.toString()
        }

        /** How many are saved, and when the first one arrived. */
        fun stats(c: Context): Pair<Int, Long> {
            var n = 0; var first = 0L
            synchronized(lock) {
                val f = file(c)
                if (f.exists()) f.forEachLine { line ->
                    if (line.isNotBlank()) { if (n == 0) first = try { JSONObject(line).optLong("t") } catch (_: Exception) { 0L }; n++ }
                }
            }
            return Pair(n, first)
        }

        private fun save(c: Context, time: Long, pkg: String, title: String, text: String) {
            synchronized(lock) {
                val f = file(c)
                val known = seen ?: HashSet<Int>().also { h ->
                    if (f.exists()) f.forEachLine { line -> try { val o = JSONObject(line); h.add((o.optString("title") + "|" + o.optString("text")).hashCode()) } catch (_: Exception) { } }
                    seen = h
                }
                if (!known.add("$title|$text".hashCode())) return        // the same alert shown again
                f.appendText(JSONObject().put("t", time).put("pkg", pkg).put("title", title).put("text", text).toString() + "\n")
                if (known.size > KEEP + 500) {                           // keep the file small
                    val lines = f.readLines().filter { it.isNotBlank() }.takeLast(KEEP)
                    f.writeText(lines.joinToString("\n") + "\n")
                    seen = null
                }
            }
        }
    }

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { take(it) } } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) try { take(sbn) } catch (_: Exception) { }
    }

    private fun take(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg in IGNORED) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val texts = ArrayList<Pair<String, Long>>()
        val styled = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (styled != null && styled.isNotEmpty()) {
            for (p in styled) {
                val b = p as? Bundle ?: continue
                val t = b.getCharSequence("text")?.toString()?.trim() ?: continue
                if (t.isNotEmpty()) texts.add(Pair(t, b.getLong("time", sbn.postTime)))
            }
        } else {
            val t = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""
            if (t.isNotEmpty()) texts.add(Pair(t, sbn.postTime))
        }
        val wallet = pkg in WALLETS
        for ((text, time) in texts) {
            val full = "$title $text"
            if (!MONEY.containsMatchIn(full) || CODES.containsMatchIn(full)) continue
            if (!wallet && !SPEND.containsMatchIn(full)) continue
            save(this, time, pkg, title.take(80), text.take(600))
        }
    }
}
