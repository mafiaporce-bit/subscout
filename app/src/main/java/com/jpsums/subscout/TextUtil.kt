package com.jpsums.subscout

/** Plain-text helpers with no Android in them, so they can be tested on a computer. */
object TextUtil {
    private val DROP = Regex("(?is)<(style|script|head|title)\\b.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BREAK = Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6]|table|section|blockquote)\\s*>")
    private val CELL = Regex("(?i)</(td|th)\\s*>")
    private val TAG = Regex("(?s)<[^>]{0,2000}>")
    private val ENTITY = Regex("&(#[xX]?[0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val SPACES = Regex("[ \\u00a0\\u200b\\u200c\\u200d\\ufeff]+")
    private val TABS = Regex(" ?\\t[ \\t]*")
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "pound" to "£", "euro" to "€", "yen" to "¥", "copy" to "©", "reg" to "®", "trade" to "™",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "bull" to "•", "middot" to "·", "zwnj" to "", "zwj" to "", "shy" to "")

    /** Turns an HTML email into readable lines: table rows become lines, cells are split by tabs. */
    fun htmlToText(html: String): String {
        var s = if (html.length > 400_000) html.substring(0, 400_000) else html
        s = DROP.replace(s, " ")
        s = COMMENT.replace(s, " ")
        s = s.replace("\r", "").replace("\n", " ")
        s = BREAK.replace(s, "\n")
        s = CELL.replace(s, "\t")
        s = TAG.replace(s, "")
        s = ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> codePoint(e.substring(2).toIntOrNull(16))
                e.startsWith("#") -> codePoint(e.substring(1).toIntOrNull())
                else -> NAMED[e.lowercase()] ?: m.value
            }
        }
        return tidy(s)
    }

    private fun codePoint(n: Int?): String =
        if (n == null || n <= 0 || n > 0x10FFFF) "" else if (n == 160) " " else String(Character.toChars(n))

    /** Squeezes spaces and drops empty lines. */
    fun tidy(text: String): String {
        val sb = StringBuilder()
        for (line in text.replace("\r", "").split('\n')) {
            val l = TABS.replace(SPACES.replace(line, " "), "\t").trim(' ', '\t')
            if (l.isNotEmpty()) sb.append(l).append('\n')
        }
        return sb.toString().trim()
    }

    fun clip(s: String, max: Int): String = if (s.length > max) s.substring(0, max) else s

    /** Which mail server goes with an email address. */
    fun hostFor(email: String): String {
        val d = email.substringAfter('@').lowercase()
        return when {
            d == "gmail.com" || d == "googlemail.com" -> "imap.gmail.com"
            d.startsWith("yahoo.") || d == "ymail.com" || d == "rocketmail.com" -> "imap.mail.yahoo.com"
            d == "icloud.com" || d == "me.com" || d == "mac.com" -> "imap.mail.me.com"
            d == "aol.com" -> "imap.aol.com"
            d.startsWith("zoho.") -> "imap.zoho.com"
            else -> "imap.$d"
        }
    }
}
