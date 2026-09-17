package org.telegram.messenger.forkgram

import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import java.util.regex.Pattern
import org.telegram.messenger.MessagesController
import org.telegram.ui.Components.URLSpanReplacement

object UrlTracking {
    const val PASTE_SHARE_KEY = "stripTrackingOnPasteShare"
    const val OPEN_KEY = "stripTrackingOnOpen"
    private val webUrl = Pattern.compile("""https?://[^\s<>"\p{Cc}]+""", Pattern.CASE_INSENSITIVE)

    @JvmStatic
    fun isPasteShareEnabled() = MessagesController.getGlobalMainSettings().getBoolean(PASTE_SHARE_KEY, false)

    @JvmStatic
    fun isOpenEnabled() = MessagesController.getGlobalMainSettings().getBoolean(OPEN_KEY, false)

    @JvmStatic
    fun cleanOpened(url: Uri): Uri {
        if (!isOpenEnabled()) return url
        val original = url.toString()
        val cleaned = TrackingParameters.clean(original)
        return if (cleaned == original) url else Uri.parse(cleaned)
    }

    fun cleanPasted(text: SpannableStringBuilder): Boolean {
        if (text.length > 10000 || !isPasteShareEnabled()) return false
        val edits = ArrayList<Triple<Int, Int, String>>()
        val matcher = webUrl.matcher(text)
        while (matcher.find()) {
            val match = matcher.group()
            val url = match.trimEnd('.', ',', '!', ')', ']', '}')
            val cleaned = TrackingParameters.clean(url)
            if (cleaned != url) edits.add(Triple(matcher.start(), matcher.start() + url.length, cleaned))
        }
        for ((start, end, cleaned) in edits.asReversed()) {
            text.replace(start, end, cleaned)
        }
        var changed = edits.isNotEmpty()
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val url = span.url ?: continue
            val cleaned = TrackingParameters.clean(url)
            if (cleaned == url) continue
            val replacement = when (span.javaClass) {
                URLSpanReplacement::class.java -> URLSpanReplacement(cleaned)
                URLSpan::class.java -> URLSpan(cleaned)
                else -> continue
            }
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            text.setSpan(replacement, start, end, flags)
            changed = true
        }
        return changed
    }

    @JvmStatic
    fun cleanPastedUrl(url: String): String = if (isPasteShareEnabled()) TrackingParameters.clean(url) else url
}
