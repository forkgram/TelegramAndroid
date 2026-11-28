package org.telegram.messenger.forkgram

import android.util.Xml
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastEpisodeDescriptionTest {
    @Test
    fun rssDescriptionWinsAndHtmlBecomesPlainText() {
        val description = description("""
            <description><![CDATA[<p>A &amp; B</p><p>Next <a href="https://example.org">line</a>.</p><img src="https://example.org/cover.jpg"><script>ignore me</script><style>hide me</style>]]></description>
            <content:encoded>Fallback content</content:encoded>
            <itunes:summary>Fallback summary</itunes:summary>
        """.trimIndent())

        assertEquals("A & B\nNext line.", description)
    }

    @Test
    fun blankItemDescriptionFallsBackToContentOrSummary() {
        assertEquals("Written notes", description("""
            <description><![CDATA[<p> &nbsp; </p>]]></description>
            <content:encoded>&lt;b&gt;Written notes&lt;/b&gt;</content:encoded>
        """.trimIndent()))
        assertEquals("iTunes summary", description("<itunes:summary>iTunes summary</itunes:summary>"))
        assertNull(description("<description>  </description>"))
        assertNull(description(""))
    }

    @Test
    fun parsedDescriptionHasABoundedSize() {
        val description = requireNotNull(description("<description>${"x".repeat(20_000)}</description>"))
        assertTrue(description.length <= 6_000)
        assertTrue(description.endsWith('…'))
        assertFalse(description.endsWith("x"))
    }

    private fun description(fields: String): String? {
        val xml = """
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Show</title>
                <description>Not an episode description</description>
                <item>
                  <title>Episode</title>
                  <guid>episode</guid>
                  <enclosure url="https://example.org/episode.mp3" type="audio/mpeg" />
                  $fields
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val uri = URI("https://example.org/feed.xml")
        // PodcastFeeds is internal to the main app module.
        val parserClass = Class.forName("org.telegram.messenger.forkgram.PodcastFeeds")
        val parse = parserClass.methods.single {
            it.name.substringBefore('$') == "parse" && it.parameterTypes.size == 4
        }
        val show = xml.byteInputStream().use {
            parse.invoke(parserClass.getField("INSTANCE").get(null), it, uri, uri, Xml.newPullParser())
        }
        val episode = (show.javaClass.getMethod("getEpisodes").invoke(show) as List<*>).single()!!
        return episode.javaClass.getMethod("getDescription").invoke(episode) as String?
    }
}
