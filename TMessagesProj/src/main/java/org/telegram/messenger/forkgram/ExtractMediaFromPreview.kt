package org.telegram.messenger.forkgram

import android.text.Editable
import android.text.Spanned
import android.text.TextUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.URLSpanReplacement

object ExtractMediaFromPreview {

    @JvmStatic
    fun hasMedia(webPage: TLRPC.WebPage?): Boolean {
        return webPage != null && (webPage.photo is TLRPC.TL_photo || webPage.document is TLRPC.TL_document)
    }

    @JvmStatic
    fun isDocument(webPage: TLRPC.WebPage?): Boolean {
        return webPage != null && webPage.document is TLRPC.TL_document
    }

    @JvmStatic
    fun isPhotoOnly(webPage: TLRPC.WebPage?): Boolean {
        if (webPage == null || webPage.photo !is TLRPC.TL_photo || webPage.document is TLRPC.TL_document) {
            return false
        }
        return webPage.site_name.isNullOrEmpty() &&
            webPage.title.isNullOrEmpty() &&
            webPage.description.isNullOrEmpty()
    }

    @JvmStatic
    fun removeLink(editable: Editable?, webPage: TLRPC.WebPage?): Boolean {
        if (editable.isNullOrEmpty()) {
            return false
        }
        val range = linkRange(editable, webPage) ?: return false
        var start = range[0]
        var end = range[1]
        while (end < editable.length && (editable[end] == ' ' || editable[end] == '\t')) {
            end++
        }
        if (end == range[1]) {
            while (start > 0 && editable[start - 1].isWhitespace()) {
                start--
            }
        }
        editable.delete(start, end)
        if (editable.isNotEmpty() && editable.isBlank()) {
            editable.clear()
        }
        return true
    }

    private fun linkRange(text: CharSequence, webPage: TLRPC.WebPage?): IntArray? {
        for (candidate in arrayOf(webPage?.url, webPage?.display_url)) {
            if (candidate.isNullOrEmpty()) {
                continue
            }
            val index = TextUtils.indexOf(text, candidate)
            if (index >= 0) {
                return intArrayOf(index, index + candidate.length)
            }
        }
        val target = normalizedUrl(webPage?.url)
        if (target.isNotEmpty() && text is Spanned) {
            for (span in text.getSpans(0, text.length, URLSpanReplacement::class.java)) {
                if (normalizedUrl(span.url) != target) {
                    continue
                }
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                if (start >= 0 && end > start) {
                    return intArrayOf(start, end)
                }
            }
        }
        val matcher = (AndroidUtilities.WEB_URL ?: return null).matcher(text)
        var firstUrl: IntArray? = null
        var count = 0
        while (matcher.find()) {
            count++
            if (target.isNotEmpty() && normalizedUrl(text.subSequence(matcher.start(), matcher.end())) == target) {
                return intArrayOf(matcher.start(), matcher.end())
            }
            if (count == 1) {
                firstUrl = intArrayOf(matcher.start(), matcher.end())
            }
        }
        return if (count == 1) firstUrl else null
    }

    private fun normalizedUrl(url: CharSequence?): String {
        if (url.isNullOrEmpty()) {
            return ""
        }
        return url.toString().trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    @JvmStatic
    fun send(
        currentAccount: Int,
        peer: Long,
        webPage: TLRPC.WebPage,
        caption: String?,
        entities: ArrayList<TLRPC.MessageEntity>?,
        replyToMsg: MessageObject?,
        replyToTopMsg: MessageObject?,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        payStars: Long
    ): Boolean {
        val params: SendMessagesHelper.SendMessageParams = when {
            webPage.document is TLRPC.TL_document -> SendMessagesHelper.SendMessageParams.of(
                webPage.document as TLRPC.TL_document, null, null, peer,
                replyToMsg, replyToTopMsg, caption, entities, null, null,
                notify, scheduleDate, scheduleRepeatPeriod, 0, webPage, null, false
            )
            webPage.photo is TLRPC.TL_photo -> SendMessagesHelper.SendMessageParams.of(
                webPage.photo as TLRPC.TL_photo, null, peer,
                replyToMsg, replyToTopMsg, caption, entities, null, null,
                notify, scheduleDate, scheduleRepeatPeriod, 0, webPage, false
            )
            else -> return false
        }
        params.payStars = payStars
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params)
        return true
    }
}
