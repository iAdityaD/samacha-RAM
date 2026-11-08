package com.samacharam.core.feeds

/** A parsed XML element. Names are local names: namespace prefixes are dropped (`dc:creator` → `creator`). */
class XmlElement(val name: String, val attributes: Map<String, String>) {
    val children = ArrayList<XmlElement>()
    private val textParts = StringBuilder()
    val text: String get() = textParts.toString()

    internal fun appendText(value: String) { if (textParts.length < MiniXml.MAX_TEXT) textParts.append(value) }

    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    fun all(name: String): List<XmlElement> = children.filter { it.name == name }
    fun descendants(name: String): Sequence<XmlElement> = sequence {
        for (c in children) { if (c.name == name) yield(c); yieldAll(c.descendants(name)) }
    }
    /** Text of this element and all descendants, for content given as inline XHTML. */
    fun deepText(): String = buildString { append(text); children.forEach { append(' ').append(it.deepText()) } }
}

class XmlException(message: String) : IllegalArgumentException(message)

/**
 * Small, strict XML reader for feeds. It never resolves external entities and rejects DTDs outright,
 * so XXE and entity-expansion attacks are impossible by construction. Depth and node counts are bounded.
 */
object MiniXml {
    const val MAX_DEPTH = 64
    const val MAX_NODES = 100_000
    const val MAX_TEXT = 1_000_000

    fun parse(xml: String): XmlElement {
        var i = 0
        val stack = ArrayList<XmlElement>()
        var root: XmlElement? = null
        var nodes = 0

        fun fail(message: String): Nothing = throw XmlException(message)

        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) { if (xml.substring(i).isNotBlank() && stack.isNotEmpty()) stack.last().appendText(decode(xml.substring(i))); break }
            if (lt > i && stack.isNotEmpty()) stack.last().appendText(decode(xml.substring(i, lt)))
            when {
                xml.startsWith("<!--", lt) -> i = (xml.indexOf("-->", lt + 4).takeIf { it >= 0 } ?: fail("Unclosed comment.")) + 3
                xml.startsWith("<![CDATA[", lt) -> {
                    val end = xml.indexOf("]]>", lt + 9).takeIf { it >= 0 } ?: fail("Unclosed CDATA section.")
                    stack.lastOrNull()?.appendText(xml.substring(lt + 9, end))
                    i = end + 3
                }
                xml.startsWith("<!", lt) -> fail("DTD and entity declarations are not accepted.")
                xml.startsWith("<?", lt) -> i = (xml.indexOf("?>", lt + 2).takeIf { it >= 0 } ?: fail("Unclosed processing instruction.")) + 2
                xml.startsWith("</", lt) -> {
                    val end = xml.indexOf('>', lt).takeIf { it >= 0 } ?: fail("Unclosed end tag.")
                    val name = localName(xml.substring(lt + 2, end).trim())
                    val open = stack.removeLastOrNull() ?: fail("Unexpected end tag </$name>.")
                    if (open.name != name) fail("Mismatched tags <${open.name}> and </$name>.")
                    i = end + 1
                }
                else -> {
                    val end = tagEnd(xml, lt + 1).takeIf { it >= 0 } ?: fail("Unclosed tag.")
                    val selfClosing = xml[end - 1] == '/'
                    val body = xml.substring(lt + 1, if (selfClosing) end - 1 else end)
                    val element = startTag(body)
                    if (++nodes > MAX_NODES) fail("Document has too many elements.")
                    val parent = stack.lastOrNull()
                    if (parent != null) parent.children += element else if (root == null) root = element else fail("Document has more than one root element.")
                    if (!selfClosing) {
                        stack += element
                        if (stack.size > MAX_DEPTH) fail("Document is nested too deeply.")
                    }
                    i = end + 1
                }
            }
        }
        if (stack.isNotEmpty()) fail("Unclosed element <${stack.last().name}>.")
        return root ?: fail("Document has no root element.")
    }

    /** Finds the '>' that ends a start tag, skipping quoted attribute values. */
    private fun tagEnd(xml: String, from: Int): Int {
        var quote: Char? = null
        for (j in from until xml.length) {
            val c = xml[j]
            if (quote != null) { if (c == quote) quote = null } else if (c == '"' || c == '\'') quote = c else if (c == '>') return j
        }
        return -1
    }

    private val ATTRIBUTE = Regex("""([^\s=/]+)\s*=\s*("([^"]*)"|'([^']*)')""")

    private fun startTag(body: String): XmlElement {
        val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
        val name = body.substring(0, nameEnd)
        if (name.isEmpty()) throw XmlException("Element without a name.")
        val attributes = LinkedHashMap<String, String>()
        for (m in ATTRIBUTE.findAll(body.substring(nameEnd))) {
            val key = m.groupValues[1]
            if (key == "xmlns" || key.startsWith("xmlns:")) continue
            attributes[localName(key)] = decode(m.groupValues[3].ifEmpty { m.groupValues[4] })
        }
        return XmlElement(localName(name), attributes)
    }

    private fun localName(name: String) = name.substringAfter(':')

    private val ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")

    /** Decodes the five XML entities and numeric references. Unknown named entities are left as written. */
    fun decode(value: String): String {
        if ('&' !in value) return value
        return ENTITY.replace(value) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> codePoint(e.substring(2).toInt(16)) ?: m.value
                e.startsWith("#") -> codePoint(e.substring(1).toInt()) ?: m.value
                else -> when (e) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; else -> m.value }
            }
        }
    }

    internal fun codePoint(cp: Int): String? = when {
        cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> { val v = cp - 0x10000; charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString() }
    }
}
