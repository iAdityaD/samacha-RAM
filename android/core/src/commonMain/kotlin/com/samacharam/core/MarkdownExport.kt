package com.samacharam.core

import com.samacharam.core.db.SavedItem

/**
 * Exports saved items as readable Markdown files with YAML frontmatter.
 * Export only: the database stays the source of truth. Missing fields are omitted, never invented.
 */
object MarkdownExport {

    /** Returns relative path → file content, e.g. `Library/2026/retrieval-augmented-generation.md`. */
    fun export(items: List<SavedItem>): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        for (item in items) {
            val year = formatDate(item.article.published ?: item.savedAt).take(4)
            val base = "Library/$year/${slug(item.article.title)}"
            var path = "$base.md"
            var n = 2
            while (path in files) path = "$base-${n++}.md"
            files[path] = render(item)
        }
        return files
    }

    fun render(item: SavedItem): String = buildString {
        val a = item.article
        appendLine("---")
        field("title", a.title)
        field("url", a.url)
        field("type", a.type.name.lowercase())
        if (a.authors.isNotBlank()) field("authors", a.authors)
        if (a.sourceKey.isNotBlank()) field("source", a.sourceKey)
        a.published?.let { appendLine("published: ${formatDate(it)}") }
        appendLine("saved: ${formatDate(item.savedAt)}")
        if (a.topics.isNotEmpty()) appendLine("topics: [${a.topics.joinToString(", ") { quote(it) }}]")
        appendLine("evidence: not assessed")
        appendLine("---")
        appendLine()
        appendLine("# ${a.title.replace('\n', ' ')}")
        if (a.excerpt.isNotBlank()) {
            appendLine(); appendLine("## Excerpt"); appendLine(); appendLine(a.excerpt.trim())
        }
        if (item.highlights.isNotEmpty()) {
            appendLine(); appendLine("## Highlights")
            for (h in item.highlights) {
                appendLine()
                h.quote.trim().lines().forEach { appendLine("> $it") }
                val location = listOfNotNull(h.anchor?.let { "passage $it" }, h.docVersion?.let { "version ${it.take(12)}" })
                if (location.isNotEmpty()) appendLine(">\n> — ${location.joinToString(" · ")}")
                if (h.note.isNotBlank()) { appendLine(); appendLine(h.note.trim()) }
            }
        }
        if (item.note.isNotBlank()) {
            appendLine(); appendLine("## Notes"); appendLine(); appendLine(item.note.trim())
        }
    }

    private fun StringBuilder.field(key: String, value: String) = appendLine("$key: ${quote(value)}")

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    fun slug(title: String): String {
        val words = titleTokens(title).ifEmpty { setOf("untitled") }
        val joined = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it in words }.distinct().joinToString("-")
        return joined.ifEmpty { "untitled" }.take(80).trimEnd('-')
    }
}
