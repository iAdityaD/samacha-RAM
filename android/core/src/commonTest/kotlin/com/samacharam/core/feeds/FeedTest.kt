package com.samacharam.core.feeds

import com.samacharam.core.ContentType
import com.samacharam.core.parseFeedDate
import com.samacharam.core.parseIsoInstant
import com.samacharam.core.parseRfc822
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiniXmlTest {
    @Test fun parsesNamespacesCdataAndEntities() {
        val root = MiniXml.parse(
            """<?xml version="1.0"?><rss xmlns:dc="http://purl.org/dc/elements/1.1/"><!-- c --><channel>
               <dc:creator a='1 &amp; 2'>Ada &amp; Alan &#8211; &#x1F600;</dc:creator><d><![CDATA[<b>raw</b> & more]]></d><e/></channel></rss>""",
        )
        val channel = root.child("channel")!!
        assertEquals("Ada & Alan – 😀", channel.child("creator")!!.text)
        assertEquals("1 & 2", channel.child("creator")!!.attributes["a"])
        assertEquals("<b>raw</b> & more", channel.child("d")!!.text)
        assertTrue(channel.child("e") != null)
    }

    @Test fun rejectsDtdsAndEntityDeclarations() {
        assertFailsWith<XmlException> { MiniXml.parse("""<!DOCTYPE x [<!ENTITY a "aaaa">]><x>&a;</x>""") }
        assertFailsWith<XmlException> { MiniXml.parse("""<x><!ENTITY b SYSTEM "file:///etc/passwd"></x>""") }
    }

    @Test fun rejectsMalformedAndAbusiveDocuments() {
        assertFailsWith<XmlException> { MiniXml.parse("<a><b></a></b>") }
        assertFailsWith<XmlException> { MiniXml.parse("<a>") }
        assertFailsWith<XmlException> { MiniXml.parse("<a/><b/>") }
        assertFailsWith<XmlException> { MiniXml.parse("<x>".repeat(100) + "</x>".repeat(100)) }
    }

    @Test fun unknownNamedEntitiesAreNotExpanded() = assertEquals("&custom;", MiniXml.parse("<a>&custom;</a>").text)
}

class HtmlTextTest {
    @Test fun dropsScriptsAndKeepsReadableText() {
        val text = HtmlText.toText("<p>Hello&nbsp;<b>world</b> &amp; friends</p><script>alert('x')</script><style>p{}</style><ul><li>One</li><li>Two</li></ul>")
        assertEquals("Hello world & friends\nOne\nTwo", text)
    }
}

class FeedDateTest {
    @Test fun rfc822Variants() {
        val expected = parseIsoInstant("2002-10-02T13:00:00Z")
        assertEquals(expected, parseRfc822("Wed, 02 Oct 2002 13:00:00 GMT"))
        assertEquals(expected, parseRfc822("Wed, 02 Oct 2002 08:00:00 -0500"))
        assertEquals(expected, parseRfc822("2 Oct 02 09:00:00 EDT"))
        assertEquals(expected?.minus(0), parseRfc822("Wed, 2 Oct 2002 13:00 +0000"))
        assertEquals(null, parseRfc822("Wed, 02 Oct 2002 13:00:00 XYZT"))
        assertEquals(expected, parseFeedDate("2002-10-02T13:00:00Z"))
    }
}

class FeedParserTest {
    private val arxiv = """<?xml version='1.0' encoding='UTF-8'?>
<rss xmlns:arxiv="http://arxiv.org/schemas/atom" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:atom="http://www.w3.org/2005/Atom" version="2.0">
  <channel>
    <title>cs.CL updates on arXiv.org</title>
    <link>http://rss.arxiv.org/rss/cs.CL</link>
    <item>
      <title>Low-Latency Speech Recognition on Phones</title>
      <link>https://arxiv.org/abs/2611.01234</link>
      <description>arXiv:2611.01234v1 Announce Type: new
Abstract: We present a streaming encoder &lt;i&gt;quantized&lt;/i&gt; to 4 bits.</description>
      <guid isPermaLink="false">oai:arXiv.org:2611.01234v1</guid>
      <category>cs.CL</category>
      <pubDate>Fri, 06 Nov 2026 00:00:00 -0500</pubDate>
      <arxiv:announce_type>new</arxiv:announce_type>
      <dc:creator>A. Researcher, B. Scientist</dc:creator>
    </item>
    <item><title>No link here</title><description>Skipped</description></item>
  </channel>
</rss>"""

    @Test fun parsesArxivStyleRss() {
        val feed = FeedParser.parse(arxiv, "https://rss.arxiv.org/rss/cs.CL", ContentType.Paper)
        assertEquals("cs.CL updates on arXiv.org", feed.title)
        assertEquals(1, feed.items.size)
        assertEquals(1, feed.skipped)
        val item = feed.items.single()
        assertEquals("https://arxiv.org/abs/2611.01234", item.url)
        assertEquals("A. Researcher, B. Scientist", item.authors)
        assertTrue(item.excerpt.contains("streaming encoder quantized to 4 bits"), item.excerpt)
        assertEquals(parseIsoInstant("2026-11-06T05:00:00Z"), item.published)
        assertEquals(ContentType.Paper, item.type)
    }

    @Test fun parsesAtomWithRelativeLinksAndHtmlContent() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title type="text">Example Blog</title><author><name>Blog Team</name></author>
          <entry><title>Shipping &lt;b&gt;v2&lt;/b&gt;</title><link rel="self" href="/feed/1"/><link rel="alternate" type="text/html" href="/posts/v2/?utm_source=rss"/>
          <updated>2026-11-01T10:00:00Z</updated><content type="html">&lt;p&gt;We shipped.&lt;/p&gt;&lt;script&gt;x()&lt;/script&gt;</content></entry>
          <entry><title>XHTML post</title><link href="https://blog.example.com/x"/><published>2026-11-02T10:00:00+02:00</published>
          <summary type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>Inline <em>markup</em>.</p></div></summary><author><name>Jo</name></author></entry></feed>"""
        val items = FeedParser.parse(atom, "https://blog.example.com/feed.atom", ContentType.Blog).items
        assertEquals("Shipping v2", items[0].title)
        assertEquals("https://blog.example.com/posts/v2/", items[0].url)
        assertEquals("We shipped.", items[0].excerpt)
        assertEquals("Blog Team", items[0].authors)
        assertTrue(items[1].excerpt.contains("Inline") && items[1].excerpt.contains("markup"))
        assertEquals("Jo", items[1].authors)
        assertEquals(parseIsoInstant("2026-11-02T08:00:00Z"), items[1].published)
    }

    @Test fun parsesRss1Rdf() {
        val rdf = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <channel rdf:about="https://x.org/"><title>X</title></channel>
          <item rdf:about="https://x.org/a"><title>A</title><link>https://x.org/a</link><dc:date>2026-11-03</dc:date></item></rdf:RDF>"""
        val item = FeedParser.parse(rdf, "https://x.org/rss", ContentType.News).items.single()
        assertEquals("https://x.org/a", item.url)
        assertEquals(parseIsoInstant("2026-11-03"), item.published)
    }

    @Test fun parsesJsonFeed() {
        val json = """{"version":"https://jsonfeed.org/version/1.1","title":"J","items":[
            {"id":"1","url":"https://j.org/1","title":"First","content_html":"<p>Hello</p>","date_published":"2026-11-04T00:00:00Z","authors":[{"name":"Kim"}]},
            {"id":"2","title":"No url"}]}"""
        val feed = FeedParser.parse("﻿" + json, "https://j.org/feed.json", ContentType.Blog)
        assertEquals("Hello", feed.items.single().excerpt)
        assertEquals("Kim", feed.items.single().authors)
        assertEquals(1, feed.skipped)
    }

    @Test fun rejectsNonFeeds() {
        assertFailsWith<XmlException> { FeedParser.parse("<html><body>Not a feed</body></html>", "https://x.org/", ContentType.Blog) }
        assertFailsWith<XmlException> { FeedParser.parse("<rss><title>No channel</title></rss>", "https://x.org/", ContentType.Blog) }
    }

    @Test fun emptyFeedsAreValid() {
        // arXiv's RSS has no items on weekends; that is not an error.
        val feed = FeedParser.parse("<rss version=\"2.0\"><channel><title>cs.AI updates on arXiv.org</title></channel></rss>", "https://rss.arxiv.org/rss/cs.AI", ContentType.Paper)
        assertEquals(0, feed.items.size)
        assertEquals("cs.AI updates on arXiv.org", feed.title)
        assertEquals(0, FeedParser.parse("""{"version":"https://jsonfeed.org/version/1.1","title":"J","items":[]}""", "https://j.org/f.json", ContentType.Blog).items.size)
    }

    @Test fun resolvesLinks() {
        assertEquals("https://a.org/p", FeedParser.resolve("/p", "https://a.org/feed/rss"))
        assertEquals("https://cdn.org/p", FeedParser.resolve("//cdn.org/p", "https://a.org/feed"))
        assertEquals("https://a.org/feed/p", FeedParser.resolve("p", "https://a.org/feed/rss"))
        assertEquals("https://a.org/p", FeedParser.resolve("p", "https://a.org"))
    }
}
