package org.telegram.messenger.forkgram

import android.util.Xml
import androidx.core.text.HtmlCompat
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal object PodcastFeeds {
    const val MAX_FEED_BYTES = 4 * 1024 * 1024
    private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
    const val MAX_EPISODES = 300
    private const val MAX_DESCRIPTION_MARKUP = 16_384
    private const val MAX_DESCRIPTION_TEXT = 6_000
    private val hiddenHtml = Regex("""(?is)<(script|style|template)\b[^>]*>.*?(?:</\1\s*>|\z)""")
    private val descriptionSpacing = Regex("""[\t\r\p{Zs}]+""")
    private val descriptionControls = Regex("""[\p{Cc}&&[^\n\t\r]]""")
    private val excessiveLineBreaks = Regex("""\n{3,}""")
    private val metadataTags = setOf("title", "guid", "duration")
    private val literalHtmlTags = setOf("br", "p", "div", "li", "ul", "ol", "blockquote", "h1", "h2", "h3", "script", "style", "template")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "mp4", "ogg", "opus", "flac", "wav")
    private val hex = "0123456789abcdef"

    data class Episode @JvmOverloads constructor(
        val key: String,
        val title: String,
        val url: String,
        val type: String,
        val duration: Int,
        val artworkUrl: String? = null,
        val description: String? = null
    )
    data class Show @JvmOverloads constructor(
        val url: String,
        val title: String,
        val episodes: List<Episode>,
        val artworkUrl: String? = null
    )

    private class Artwork {
        var itunes: String? = null
        var media: String? = null
        var rss: String? = null

        fun pick(): String? = itunes ?: media ?: rss
    }

    fun url(value: String, base: URI? = null): URI? {
        if (value.isBlank() || value.length > 4096) return null
        return runCatching {
            val raw = URI(value.trim())
            val uri = (base?.resolve(raw) ?: raw).normalize()
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
            if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.fragment != null ||
                (uri.port != -1 && uri.port != 443) || !host.contains('.') || host.endsWith(".local") ||
                host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".onion") ||
                host.all { it.isDigit() || it == '.' } || host.contains(':')) return null
            URI("https://" + host + (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: ""))
        }.getOrNull()
    }

    fun key(value: String): String = buildString(64) {
        for (byte in MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))) {
            val n = byte.toInt() and 255
            append(hex[n shr 4])
            append(hex[n and 15])
        }
    }

    fun fetch(raw: String): Show {
        val start = requireNotNull(url(raw))
        return request(start, "application/rss+xml, application/xml, text/xml, */*") { stream, finalUri, length ->
            if (length > MAX_FEED_BYTES) throw IOException("Feed is too large")
            val bytes = bounded(stream, MAX_FEED_BYTES)
            ByteArrayInputStream(bytes).use { parse(it, start, finalUri, Xml.newPullParser()) }
        }
    }

    fun fetchArtwork(raw: String): ByteArray {
        val start = url(raw) ?: throw IOException("Invalid artwork URL")
        return request(start, "image/*, application/octet-stream") { stream, _, length ->
            if (length > MAX_ARTWORK_BYTES) throw IOException("Artwork is too large")
            bounded(stream, MAX_ARTWORK_BYTES)
        }
    }

    fun bounded(stream: InputStream, max: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        copy(stream, bytes, max.toLong(), 30_000)
        return bytes.toByteArray()
    }

    private fun copy(stream: InputStream, out: OutputStream, max: Long, maxMillis: Long) {
        val buffer = ByteArray(32 * 1024)
        val deadline = System.nanoTime() + maxMillis * 1_000_000
        var total = 0L
        while (true) {
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted) throw IOException("Read timed out")
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Response is too large")
            out.write(buffer, 0, n)
        }
    }

    private fun <T> request(start: URI, accept: String, handle: (InputStream, URI, Long) -> T): T {
        val (connection, endpoint) = connect(start, accept)
        try {
            val status = connection.responseCode
            if (status != 200) throw IOException("HTTP $status")
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            return connection.inputStream.use { handle(it, endpoint, length) }
        } finally {
            connection.disconnect()
        }
    }

    internal fun connect(start: URI, accept: String, range: String? = null): Pair<HttpURLConnection, URI> {
        var endpoint = start
        repeat(6) {
            if (!InetAddress.getAllByName(endpoint.host).all { isPublic(it) }) throw IOException("Non-public host")
            val connection = endpoint.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            var keep = false
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Forkgram/Podcasts")
                if (range != null) connection.setRequestProperty("Range", range)
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> endpoint = url(connection.getHeaderField("Location") ?: "", endpoint)
                        ?: throw IOException("Invalid redirect")
                    else -> {
                        keep = true
                        return connection to endpoint
                    }
                }
            } finally {
                if (!keep) connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    internal fun isPublic(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return false
        val b = ip.address.map { it.toInt() and 255 }
        if (b.size == 4) return b[0] in 1..223 &&
            !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 198 && b[1] in 18..19) &&
            !(b[0] == 192 && b[1] == 0) && !(b[0] == 198 && b[1] == 51 && b[2] == 100) &&
            !(b[0] == 203 && b[1] == 0 && b[2] == 113)
        return b.size == 16 && (b[0] and 224) == 32 && !(b[0] == 32 && b[1] == 1 && b[2] == 13 && b[3] == 184)
    }

    internal fun parse(source: InputStream, canonical: URI, base: URI, parser: XmlPullParser): Show {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        }
        parser.setInput(source, null)
        var channel = -1
        var itemDepth = -1
        var showTitle = ""
        val showArtwork = Artwork()
        var episodeArtwork = Artwork()
        val descriptions = LinkedHashMap<String, String>(3)
        var rssImageDepth = -1
        var mediaGroupDepth = -1
        var episodeTitle = ""
        var episodeGuid = ""
        var episodeDuration = 0
        var enclosure: Pair<URI, String>? = null
        val entries = LinkedHashMap<String, Episode>()
        var capture: String? = null
        var captureDepth = -1
        val value = StringBuilder()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.DOCDECL -> throw IOException("DOCTYPE is not supported")
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    if (parser.depth > 48) throw IOException("Feed nesting is too deep")
                    val tag = parser.name.lowercase(Locale.ROOT)
                    val depth = parser.depth
                    val namespace = parser.namespace.orEmpty().lowercase(Locale.ROOT)
                        .removePrefix("https://").removePrefix("http://").trimEnd('/')
                    val media = namespace == "search.yahoo.com/mrss"
                    val itunes = namespace == "www.itunes.com/dtds/podcast-1.0.dtd"
                    if (tag == "channel" && parser.depth == 2) channel = parser.depth
                    if (channel > 0 && tag == "item" && parser.depth == channel + 1) {
                        itemDepth = parser.depth
                        episodeTitle = ""
                        episodeGuid = ""
                        episodeDuration = 0
                        episodeArtwork = Artwork()
                        descriptions.clear()
                        enclosure = null
                    } else if (channel > 0 && depth == (if (itemDepth > 0) itemDepth else channel) + 1 &&
                        namespace.isEmpty() && tag == "image"
                    ) {
                        rssImageDepth = depth
                    } else if (channel > 0 && depth == (if (itemDepth > 0) itemDepth else channel) + 1 && media && tag == "group") {
                        mediaGroupDepth = depth
                    } else if (rssImageDepth > 0 && depth == rssImageDepth + 1 && namespace.isEmpty() && tag == "url" && capture == null) {
                        capture = "artwork"
                        captureDepth = depth
                        value.setLength(0)
                    } else if (channel > 0 && (depth == (if (itemDepth > 0) itemDepth else channel) + 1 ||
                            media && mediaGroupDepth > 0 && depth == mediaGroupDepth + 1)
                    ) {
                        val artwork = if (itemDepth > 0) episodeArtwork else showArtwork
                        if (itunes && tag == "image" && artwork.itunes == null) {
                            artwork.itunes = url(parser.getAttributeValue(null, "href").orEmpty(), base)?.toString()
                        } else if (media && artwork.media == null && (tag == "thumbnail" || tag == "content" &&
                                (parser.getAttributeValue(null, "medium").equals("image", true) ||
                                    parser.getAttributeValue(null, "type").orEmpty().startsWith("image/", true)))
                        ) {
                            artwork.media = url(parser.getAttributeValue(null, "url").orEmpty(), base)?.toString()
                        }
                    }
                    if (capture?.startsWith("description-") == true && tag in literalHtmlTags && value.length < MAX_DESCRIPTION_MARKUP) {
                        value.append("<").append(tag).append(">")
                    }
                    if (itemDepth > 0 && parser.depth == itemDepth + 1 && tag == "enclosure" && enclosure == null) {
                        val href = url(parser.getAttributeValue(null, "url") ?: "", base)
                        val type = parser.getAttributeValue(null, "type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
                        val ext = href?.path?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
                        if (href != null && (type.startsWith("audio/") || (type.isEmpty() || type == "application/octet-stream") && ext in audioExtensions)) {
                            enclosure = href to type
                        }
                    } else if (capture == null) {
                        val field = when {
                            itemDepth > 0 && depth == itemDepth + 1 -> when {
                                tag in metadataTags -> tag
                                namespace.isEmpty() && tag == "description" -> "description-rss"
                                namespace == "purl.org/rss/1.0/modules/content" && tag == "encoded" -> "description-encoded"
                                itunes && tag == "summary" -> "description-itunes"
                                else -> null
                            }
                            itemDepth < 0 && channel > 0 && depth == channel + 1 && tag == "title" -> tag
                            else -> null
                        }
                        if (field != null && field !in descriptions) {
                            capture = field
                            captureDepth = depth
                            value.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val field = capture
                    if (field != null) {
                        val limit = if (field.startsWith("description-")) MAX_DESCRIPTION_MARKUP else 4096
                        if (value.length < limit) {
                            val text = parser.text.orEmpty()
                            value.append(text, 0, text.length.coerceAtMost(limit - value.length))
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val field = capture
                    if (field != null && parser.depth == captureDepth) {
                        if (field.startsWith("description-")) {
                            if (value.isNotBlank()) descriptions[field] = value.toString()
                        } else {
                            val text = value.toString().replace(Regex("\\s+"), " ").trim()
                            when (field) {
                                "title" -> if (itemDepth > 0) episodeTitle = text.take(500) else showTitle = text.take(300)
                                "guid" -> episodeGuid = text
                                "duration" -> episodeDuration = duration(text)
                                "artwork" -> {
                                    val artwork = if (itemDepth > 0) episodeArtwork else showArtwork
                                    if (artwork.rss == null) artwork.rss = url(value.toString().trim(), base)?.toString()
                                }
                            }
                        }
                        capture = null
                    } else if (field?.startsWith("description-") == true && parser.depth > captureDepth && value.length < MAX_DESCRIPTION_MARKUP) {
                        val tag = parser.name.lowercase(Locale.ROOT)
                        if (tag in literalHtmlTags && tag != "br") value.append("</").append(tag).append(">")
                    }
                    if (parser.depth == rssImageDepth) rssImageDepth = -1
                    if (parser.depth == mediaGroupDepth) mediaGroupDepth = -1
                    if (itemDepth > 0 && parser.depth == itemDepth && parser.name.equals("item", true)) {
                        val audio = enclosure
                        if (audio != null && episodeTitle.isNotBlank() && entries.size < MAX_EPISODES) {
                            val id = key(canonical.toString() + "\n" + episodeGuid.ifEmpty { audio.first.toString() })
                            if (id !in entries) {
                                val description = sequenceOf("description-rss", "description-encoded", "description-itunes")
                                    .mapNotNull { descriptions[it]?.let(::plainDescription) }
                                    .firstOrNull()
                                entries[id] = Episode(id, episodeTitle, audio.first.toString(), audio.second, episodeDuration, episodeArtwork.pick(), description)
                            }
                        }
                        itemDepth = -1
                    }
                }
            }
        }
        if (channel < 0) throw IOException("Unsupported feed")
        return Show(canonical.toString(), showTitle.ifEmpty { canonical.host }, entries.values.toList(), showArtwork.pick())
    }

    private fun plainDescription(raw: String): String? {
        // Only the characters are used; no URL spans, images, or interactive HTML are retained.
        val markup = hiddenHtml.replace(raw, "")
        val plain = runCatching { HtmlCompat.fromHtml(markup, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() }.getOrNull() ?: return null
        val text = plain.replace("\uFFFC", "").replace(descriptionControls, "")
            .replace(descriptionSpacing, " ")
            .lineSequence().map { it.trim() }.joinToString("\n")
            .replace(excessiveLineBreaks, "\n\n").trim()
        if (text.isEmpty()) return null
        if (text.length <= MAX_DESCRIPTION_TEXT) return text
        val limit = if (text[MAX_DESCRIPTION_TEXT - 2].isHighSurrogate()) MAX_DESCRIPTION_TEXT - 2 else MAX_DESCRIPTION_TEXT - 1
        return text.substring(0, limit).trimEnd() + '…'
    }

    private fun duration(text: String): Int {
        val parts = text.split(':')
        if (parts.size !in 1..3) return 0
        var value = 0L
        for (part in parts) {
            val n = part.toLongOrNull()?.takeIf { it in 0..604800 } ?: return 0
            value = value * 60 + n
        }
        return value.takeIf { it in 1..604800 }?.toInt() ?: 0
    }
}
