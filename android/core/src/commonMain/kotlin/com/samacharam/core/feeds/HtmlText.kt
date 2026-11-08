package com.samacharam.core.feeds

/**
 * Converts feed HTML to plain text for storage and display. Nothing is ever rendered as HTML,
 * so scripts and markup in feeds cannot run. Script/style contents are dropped.
 */
object HtmlText {
    private val HIDDEN = Regex("(?is)<(script|style|noscript|template)\\b[^>]*>.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BLOCK = Regex("(?i)</?(p|div|br|li|ul|ol|h[1-6]|tr|blockquote|section|article|pre)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "copy" to "©", "reg" to "®", "trade" to "™",
        "deg" to "°", "times" to "×", "middot" to "·", "bull" to "•", "eacute" to "é", "uuml" to "ü", "ouml" to "ö", "auml" to "ä",
    )
    private val ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")

    fun toText(html: String): String {
        var s = HIDDEN.replace(html, " ")
        s = COMMENT.replace(s, " ")
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, "")
        s = ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> MiniXml.codePoint(e.substring(2).toInt(16)) ?: ""
                e.startsWith("#") -> MiniXml.codePoint(e.substring(1).toInt()) ?: ""
                else -> NAMED[e.lowercase()] ?: m.value
            }
        }
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
