package com.jpsums.subscout

import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.protocol.IMAPResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

/**
 * Reads receipts from a mailbox over IMAP. Read-only: the folder is opened read-only and bodies are
 * fetched with PEEK, so nothing is deleted, moved or marked as read.
 */
object Mail {
    private const val MAX_HEADERS = 1500
    private val WORDS = arrayOf("receipt", "invoice", "subscription", "renew", "payment", "billing", "trial", "membership", "charged", "order", "purchase", "plan")

    private fun connect(a: Accounts.Account): Store {
        val p = Properties()
        p["mail.store.protocol"] = "imaps"
        p["mail.imaps.host"] = a.host
        p["mail.imaps.port"] = a.port.toString()
        p["mail.imaps.ssl.enable"] = "true"
        p["mail.imaps.connectiontimeout"] = "20000"
        p["mail.imaps.timeout"] = "45000"
        p["mail.imaps.peek"] = "true"
        p["mail.imaps.partialfetch"] = "true"
        p["mail.imaps.fetchsize"] = "65536"
        p["mail.mime.address.strict"] = "false"
        val store = Session.getInstance(p).getStore("imaps")
        store.connect(a.host, a.port, a.email, a.pass)
        return store
    }

    /** Signs in once to prove the address and app password work. Throws when they don't. */
    fun check(a: Accounts.Account) {
        val store = connect(a)
        try { store.getFolder("INBOX").exists() } finally { try { store.close() } catch (_: Exception) { } }
    }

    /** A message people can act on. */
    fun explain(e: Throwable, host: String): String {
        var t: Throwable? = e
        while (t != null) {
            if (t is AuthenticationFailedException) {
                return if (host.contains("gmail")) "Gmail did not accept that app password. Check that you pasted all 16 letters and that 2-Step Verification is on."
                else "The mail server did not accept that address and app password."
            }
            if (t is java.net.UnknownHostException) return "Could not find the mail server $host. Check the internet, or the server name."
            if (t is java.net.SocketTimeoutException || t is java.net.ConnectException) return "Could not reach $host. Check the internet and try again."
            t = t.cause
        }
        return "Mail problem: " + (e.message ?: e.javaClass.simpleName)
    }

    /** Gmail keeps everything in "All Mail" (its name changes with the account language, so look for the flag). */
    private fun openBest(store: Store): Folder {
        var best: Folder? = null
        try {
            for (f in store.defaultFolder.list("*")) {
                val attrs = (f as? IMAPFolder)?.attributes ?: continue
                if (attrs.any { it.equals("\\All", true) }) { best = f; break }
            }
        } catch (_: Exception) { }
        val folder = best ?: store.getFolder("INBOX")
        folder.open(Folder.READ_ONLY)
        return folder
    }

    /** Gmail's own search (same as the search box in the Gmail app). Returns message numbers. */
    private fun gmailSearch(folder: IMAPFolder, query: String): IntArray {
        val found = folder.doCommand(IMAPFolder.ProtocolCommand { p ->
            val args = Argument()
            args.writeAtom("X-GM-RAW")
            args.writeString(query)
            val r = p.command("SEARCH", args)
            val last = r[r.size - 1]
            val nums = ArrayList<Int>()
            if (last.isOK) {
                for (i in r.indices) {
                    val ir = r[i] as? IMAPResponse ?: continue
                    if (ir.keyEquals("SEARCH")) {
                        while (true) { val n = ir.readNumber(); if (n == -1) break; nums.add(n) }
                    }
                }
            }
            p.notifyResponseHandlers(r)
            p.handleResult(last)
            nums
        })
        @Suppress("UNCHECKED_CAST")
        return (found as ArrayList<Int>).toIntArray()
    }

    private fun search(folder: Folder, gmail: Boolean, months: Int): Array<Message> {
        if (gmail && folder is IMAPFolder) {
            try {
                val q = "newer_than:${months}m -category:promotions -category:social -in:chats -in:spam -in:trash " +
                    "subject:(receipt OR invoice OR subscription OR renew OR renewal OR renews OR renewed OR payment OR billing OR trial OR membership OR charged OR order OR purchase OR plan)"
                val nums = gmailSearch(folder, q)
                if (nums.isEmpty()) return emptyArray()
                nums.sort()
                return folder.getMessages(nums.takeLast(MAX_HEADERS).toIntArray())
            } catch (_: Exception) { /* fall through to the standard search */ }
        }
        val since = Calendar.getInstance().apply { add(Calendar.MONTH, -months) }.time
        val words = Array<SearchTerm>(WORDS.size) { SubjectTerm(WORDS[it]) }
        val hits = folder.search(AndTerm(ReceivedDateTerm(ComparisonTerm.GE, since), OrTerm(words)))
        hits.sortBy { it.messageNumber }
        return if (hits.size > MAX_HEADERS) hits.copyOfRange(hits.size - MAX_HEADERS, hits.size) else hits
    }

    /** Sender, subject and date of every email that might be a receipt. JSON: [{u,f,s,d}] */
    fun headers(a: Accounts.Account, months: Int): String {
        val store = connect(a)
        try {
            val folder = openBest(store)
            val msgs = search(folder, a.host.contains("gmail"), months.coerceIn(1, 36))
            val fp = FetchProfile()
            fp.add(FetchProfile.Item.ENVELOPE)
            fp.add(UIDFolder.FetchProfileItem.UID)
            folder.fetch(msgs, fp)
            val uf = folder as UIDFolder
            val out = JSONArray()
            for (m in msgs) {
                try {
                    val from = m.from?.firstOrNull()
                    val who = if (from is InternetAddress) ((from.personal ?: "") + " <" + (from.address ?: "") + ">").trim() else (from?.toString() ?: "")
                    val date = (m.receivedDate ?: m.sentDate)?.time ?: continue
                    out.put(JSONObject().put("u", uf.getUID(m)).put("f", who).put("s", m.subject ?: "").put("d", date))
                } catch (_: Exception) { }
            }
            try { folder.close(false) } catch (_: Exception) { }
            return out.toString()
        } finally { try { store.close() } catch (_: Exception) { } }
    }

    /** The readable text of the chosen emails. JSON: {uid: text} */
    fun bodies(a: Accounts.Account, uids: LongArray, progress: (Int, Int) -> Unit): String {
        val store = connect(a)
        try {
            val folder = openBest(store)
            val msgs = (folder as UIDFolder).getMessagesByUID(uids)
            val fp = FetchProfile()
            fp.add(FetchProfile.Item.CONTENT_INFO)
            try { folder.fetch(msgs.filterNotNull().toTypedArray(), fp) } catch (_: Exception) { }
            val out = JSONObject()
            for (i in msgs.indices) {
                val m = msgs[i]
                if (m != null) {
                    try { out.put(uids[i].toString(), TextUtil.clip(TextUtil.tidy(textOf(m, 0)), 9000)) } catch (_: Exception) { }
                }
                if (i % 4 == 3 || i == msgs.size - 1) progress(i + 1, msgs.size)
            }
            try { folder.close(false) } catch (_: Exception) { }
            return out.toString()
        } finally { try { store.close() } catch (_: Exception) { } }
    }

    private fun multipartOf(p: Part): Multipart? {
        try { val c = p.content; if (c is Multipart) return c } catch (_: Exception) { }
        return try { MimeMultipart(p.dataHandler.dataSource) } catch (_: Exception) { null }
    }

    private fun readText(p: Part): String {
        val name = try { ContentType(p.contentType).getParameter("charset") } catch (_: Exception) { null }
        val charset = try { java.nio.charset.Charset.forName(MimeUtility.javaCharset(name ?: "UTF-8")) } catch (_: Exception) { Charsets.UTF_8 }
        val buf = java.io.ByteArrayOutputStream()
        p.inputStream.use { ins ->
            val chunk = ByteArray(16384)
            while (buf.size() < 300_000) { val n = ins.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
        }
        return String(buf.toByteArray(), charset)
    }

    private val PRICE = Regex("\\d[.,]\\d{2}")

    private fun textOf(p: Part, depth: Int): String {
        if (depth > 6) return ""
        try {
            if (p.isMimeType("text/plain")) return readText(p)
            if (p.isMimeType("text/html")) return TextUtil.htmlToText(readText(p))
            if (p.isMimeType("multipart/alternative")) {
                val mp = multipartOf(p) ?: return ""
                var plain = ""; var html = ""
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (bp.isMimeType("text/plain")) plain = textOf(bp, depth + 1)
                    else if (html.isEmpty()) html = textOf(bp, depth + 1)
                }
                // the plain part of a receipt is often a stub ("view in browser"); use it only when it holds a price
                return if (PRICE.containsMatchIn(plain) || html.isBlank()) plain else html
            }
            if (p.isMimeType("multipart/*")) {
                val mp = multipartOf(p) ?: return ""
                val sb = StringBuilder()
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (Part.ATTACHMENT.equals(bp.disposition, true)) continue
                    sb.append(textOf(bp, depth + 1)).append('\n')
                    if (sb.length > 20000) break
                }
                return sb.toString()
            }
        } catch (_: Exception) { }
        return ""
    }
}
