package org.telegram.messenger.forkgram

import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC

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
