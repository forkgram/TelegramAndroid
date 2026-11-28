package org.telegram.messenger.forkgram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

internal class PodcastStreamDataSource : BaseDataSource(true) {
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = PodcastStreamDataSource()
    }

    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val start = PodcastFeeds.url(dataSpec.uri.toString()) ?: throw IOException("Invalid podcast URL")
        val position = dataSpec.position
        val length = dataSpec.length
        val range = if (position != 0L || length != C.LENGTH_UNSET.toLong()) {
            if (length == C.LENGTH_UNSET.toLong()) "bytes=$position-"
            else {
                if (length < 1 || position > Long.MAX_VALUE - (length - 1)) throw IOException("Invalid podcast byte range")
                "bytes=$position-${position + (length - 1)}"
            }
        } else null
        val response = PodcastFeeds.connect(start, "audio/*, application/octet-stream, */*", range)
        val http = response.first
        connection = http
        uri = Uri.parse(response.second.toString())
        try {
            val status = http.responseCode
            if (status == 416 && position == http.getHeaderField("Content-Range")?.let {
                    END_RANGE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull()
                }) {
                remaining = 0
                opened = true
                transferStarted(dataSpec)
                return 0
            }
            if (status != 200 && status != 206) throw IOException("Podcast HTTP status $status")
            var available = http.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            if (status == 206) {
                val match = CONTENT_RANGE.matchEntire(http.getHeaderField("Content-Range").orEmpty())
                    ?: throw IOException("Invalid podcast Content-Range")
                val first = match.groupValues[1].toLongOrNull()
                val last = match.groupValues[2].toLongOrNull()
                val total = match.groupValues[3].toLongOrNull()
                if (first != position || last == null || last < position || last == Long.MAX_VALUE ||
                    total != null && last >= total) throw IOException("Invalid podcast byte range")
                val span = last - position + 1
                if (available != null && available != span) throw IOException("Invalid podcast range length")
                available = span
            }
            val skip = if (status == 200) position else 0L
            if (skip > MAX_LINEAR_SKIP || available != null && skip > available) {
                throw IOException("Podcast server does not support seeking to this position")
            }
            val stream = http.inputStream
            input = stream
            var toSkip = skip
            val discard = if (toSkip > 0) ByteArray(8192) else null
            while (toSkip > 0) {
                val n = stream.read(discard!!, 0, minOf(toSkip, discard.size.toLong()).toInt())
                if (n < 0) throw EOFException()
                toSkip -= n
            }
            remaining = available?.minus(skip) ?: C.LENGTH_UNSET.toLong()
            if (length != C.LENGTH_UNSET.toLong()) {
                remaining = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length)
            }
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (failure: Exception) {
            try {
                close()
            } catch (_: IOException) {
            }
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val n = checkNotNull(input).read(buffer, offset, count)
        if (n == -1) {
            if (remaining != C.LENGTH_UNSET.toLong()) throw EOFException("Incomplete podcast response")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
        } finally {
            input = null
            connection?.disconnect()
            connection = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val MAX_LINEAR_SKIP = 1024 * 1024L
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
        private val END_RANGE = Regex("bytes \\*/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
