package com.samacharam.core.feeds

import com.samacharam.core.ContentType
import com.samacharam.core.FeedItem
import com.samacharam.core.canonicalUrl
import com.samacharam.core.parseFeedDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ParsedFeed(val title: String, val items: List<FeedItem>, val skipped: Int)

/** RSS 2.0, RSS 1.0 (RDF), Atom, and JSON Feed. Topics are assigned later by the refresher. */
object FeedParser {
    const val MAX_ITEMS = 200
    private const val MAX_EXCERPT = 16_000

    fun parse(body: String, feedUrl: String, type: ContentType): ParsedFeed {
        val text = body.removePrefix("﻿").trimStart()
        return if (text.startsWith("{")) jsonFeed(text, feedUrl, type) else xmlFeed(MiniXml.parse(text), feedUrl, type)
    }

    private fun xmlFeed(root: XmlElement, feedUrl: String, type: ContentType): ParsedFeed = when (root.name) {
        "rss" -> {
            val channel = root.child("channel") ?: throw XmlException("RSS feed has no channel.")
            build(channel.child("title")?.text, channel.all("item"), feedUrl) { rssItem(it, feedUrl, type) }
        }
        "RDF" -> build(root.child("channel")?.child("title")?.text, root.all("item"), feedUrl) { rssItem(it, feedUrl, type) }
        "feed" -> {
            val feedAuthor = root.child("author")?.child("name")?.text
            build(root.child("title")?.text, root.all("entry"), feedUrl) { atomEntry(it, feedUrl, type, feedAuthor) }
        }
        else -> throw XmlException("Not an RSS or Atom feed (root element <${root.name}>).")
    }

    private fun <T> build(title: String?, entries: List<T>, feedUrl: String, map: (T) -> FeedItem?): ParsedFeed {
        if (entries.isEmpty()) throw XmlException("Feed contains no entries.")
        val items = entries.take(MAX_ITEMS).mapNotNull { runCatching { map(it) }.getOrNull() }
        return ParsedFeed(HtmlText.toText(title ?: feedUrl).take(200), items, entries.take(MAX_ITEMS).size - items.size)
    }

    private fun rssItem(e: XmlElement, feedUrl: String, type: ContentType): FeedItem? {
        val guid = e.child("guid")
        val link = e.child("link")?.let { it.text.ifBlank { it.attributes["href"] ?: it.attributes["resource"] ?: "" } }
            ?.takeIf { it.isNotBlank() }
            ?: guid?.takeIf { it.attributes["isPermaLink"] != "false" }?.text
            ?: e.attributes["about"]
        val html = e.child("description")?.text?.takeIf { it.isNotBlank() } ?: e.child("encoded")?.text ?: e.child("summary")?.text ?: ""
        val authors = e.all("creator").joinToString(", ") { it.text.trim() }.ifBlank { e.child("author")?.text ?: "" }
        return item(link, feedUrl, e.child("title")?.text, authors, e.child("pubDate")?.text ?: e.child("date")?.text, HtmlText.toText(html), type)
    }

    private fun atomEntry(e: XmlElement, feedUrl: String, type: ContentType, feedAuthor: String?): FeedItem? {
        val links = e.all("link")
        val link = (links.firstOrNull { (it.attributes["rel"] ?: "alternate") == "alternate" && (it.attributes["type"] ?: "text/html").contains("html") }
            ?: links.firstOrNull { (it.attributes["rel"] ?: "alternate") == "alternate" })?.attributes?.get("href")
        val body = e.child("summary") ?: e.child("content")
        val excerpt = when (body?.attributes?.get("type")) {
            null, "text" -> body?.text?.trim() ?: ""
            "xhtml" -> HtmlText.toText(body.deepText())
            else -> HtmlText.toText(body.text) // "html": escaped markup, already unescaped once by the XML reader
        }
        val authors = e.all("author").mapNotNull { it.child("name")?.text?.trim() }.joinToString(", ").ifBlank { feedAuthor ?: "" }
        return item(link, feedUrl, e.child("title")?.text, authors, e.child("published")?.text ?: e.child("updated")?.text, excerpt, type)
    }

    private fun jsonFeed(text: String, feedUrl: String, type: ContentType): ParsedFeed {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw XmlException("JSON Feed must be an object.")
        val entries = (root["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw XmlException("JSON Feed has no items.")
        return build(root.str("title"), entries, feedUrl) { o ->
            val excerpt = o.str("summary") ?: o.str("content_text") ?: o.str("content_html")?.let(HtmlText::toText) ?: ""
            val authors = (o["authors"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString(", ")
                ?: (o["author"] as? JsonObject)?.str("name") ?: ""
            item(o.str("url") ?: o.str("external_url"), feedUrl, o.str("title"), authors, o.str("date_published") ?: o.str("date_modified"), excerpt, type)
        }
    }

    private fun item(link: String?, feedUrl: String, rawTitle: String?, authors: String, date: String?, excerpt: String, type: ContentType): FeedItem? {
        val title = HtmlText.toText(rawTitle ?: "").replace('\n', ' ').take(500)
        if (title.isBlank()) return null
        val url = canonicalUrl(resolve(link?.trim() ?: return null, feedUrl))
        return FeedItem(url, title, HtmlText.toText(authors).replace('\n', ' ').take(500), parseFeedDate(date?.trim()), excerpt.take(MAX_EXCERPT), type, emptyList())
    }

    /** Resolves root-relative and protocol-relative links against the feed URL. */
    fun resolve(link: String, base: String): String {
        val scheme = base.substringBefore("://")
        val origin = scheme + "://" + base.substringAfter("://").substringBefore('/')
        return when {
            link.startsWith("//") -> "$scheme:$link"
            link.startsWith("/") -> origin + link
            link.startsWith("http://") || link.startsWith("https://") -> link
            else -> (if ('/' in base.substringAfter("://")) base.substringBeforeLast('/') else origin) + "/" + link
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
