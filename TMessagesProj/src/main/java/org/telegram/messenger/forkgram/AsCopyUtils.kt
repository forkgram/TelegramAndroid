package org.telegram.messenger.forkgram

import android.widget.Toast
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileRefController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.TLRPC.InputMedia
import org.telegram.tgnet.TLRPC.TL_error
import org.telegram.tgnet.TLRPC.TL_inputDocument
import org.telegram.tgnet.TLRPC.TL_inputMediaDocument
import org.telegram.tgnet.TLRPC.TL_inputMediaEmpty
import org.telegram.tgnet.TLRPC.TL_inputMediaPhoto
import org.telegram.tgnet.TLRPC.TL_inputPhoto
import org.telegram.tgnet.TLRPC.TL_inputSingleMedia
import org.telegram.tgnet.TLRPC.TL_messageMediaEmpty
import org.telegram.tgnet.TLRPC.TL_messageMediaGame
import org.telegram.tgnet.TLRPC.TL_messageMediaInvoice
import org.telegram.tgnet.TLRPC.TL_messageMediaWebPage
import org.telegram.tgnet.TLRPC.TL_messages_sendMultiMedia
import org.telegram.tgnet.TLRPC.Updates
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.AlertsCreator
import kotlin.math.min

object ForkUtils {

    @JvmStatic
    fun hasPhotoOrDocument(messageObject: MessageObject): Boolean {
        return hasPhoto(messageObject) || hasDocument(messageObject)
    }

    @JvmStatic
    fun hasPhoto(messageObject: MessageObject): Boolean {
        val media = messageObject.messageOwner.media
        return media?.photo is TLRPC.TL_photo
    }

    @JvmStatic
    fun hasDocument(messageObject: MessageObject): Boolean {
        val media = messageObject.messageOwner.media
        return media?.document is TLRPC.TL_document
    }

}

object AsCopy {

    @JvmStatic
    fun takeReplyToDraft(
        key: Long,
        // Take a look for forum topic if there is no draft.
        keyTopic: TLRPC.TL_forumTopic?,
        currentAccount: Int,
        cleanDraft: Boolean
    ): Int {
        val accountInstance = AccountInstance.getInstance(currentAccount)
        val draft = accountInstance.mediaDataController.getDraft(key, 0)
        if (draft?.reply_to != null) {
            val copyId = draft.reply_to.reply_to_msg_id
            if (cleanDraft) {
                accountInstance.mediaDataController.cleanDraft(key, 0, true)
            }
            return copyId
        }
        return keyTopic?.id ?: 0
    }

    @JvmStatic
    fun takeReplyInputToDraft(key: Long, currentAccount: Int): TLRPC.InputReplyTo? {
        val draft = AccountInstance.getInstance(currentAccount).mediaDataController.getDraft(key, 0)
        val replyTo = draft?.reply_to
        return if (replyTo != null && replyTo.reply_to_msg_id != 0 && !replyTo.quote_text.isNullOrEmpty()) replyTo else null
    }

    @JvmStatic
    fun performForwardFromMyName(
        key: Long,
        keyTopic: TLRPC.TL_forumTopic?,
        text: String?,
        sendingMessageObjects: ArrayList<MessageObject>,
        currentAccount: Int,
        parentFragment: BaseFragment?,
        notify: Boolean,
        monoForumPeerId: Long = 0L
    ) {

        val queue = ArrayList<() -> Unit>()
        val saveOriginalCaptions = (text == null)
        var replaceText = text
        val reply = takeReplyToDraft(key, keyTopic, currentAccount, false)
        val replyInput = takeReplyInputToDraft(key, currentAccount)
        val topicId = if (monoForumPeerId != 0L) 0 else (keyTopic?.id ?: 0)
        fun currentReplaceText(): String? {
            val temp = replaceText
            replaceText = ""
            return if (saveOriginalCaptions) null else temp
        }
        var groupedMsgs = ArrayList<MessageObject>()

        val deque = {
            if (queue.isNotEmpty()) {
                val copyLambda = queue[0]
                queue.removeAt(0)
                copyLambda()
            } else {
                val accountInstance = AccountInstance.getInstance(currentAccount)
                accountInstance.mediaDataController.cleanDraft(key, 0, true)
            }
        }

        fun sendAsAlbum() {
            val copyGrouped = ArrayList<MessageObject>(groupedMsgs)
            groupedMsgs = ArrayList()
            val copyText = currentReplaceText()
            queue.add {
                sendItemsAsAlbum(
                    currentAccount,
                    copyGrouped,
                    key,
                    if (reply == 0) topicId else reply,
                    parentFragment,
                    copyText,
                    notify,
                    monoForumPeerId,
                    deque,
                    replyInput)
            }
        }

        for (msg in sendingMessageObjects) {
            if (msg.groupId != 0L) {
                if (groupedMsgs.isNotEmpty()) {
                    if (groupedMsgs[0].groupId != msg.groupId) {
                        sendAsAlbum()
                    }
                }
                groupedMsgs.add(msg)
                continue
            }
            if (groupedMsgs.isNotEmpty()) {
                sendAsAlbum()
            }
            val copyMsg = msg
            val copyText = currentReplaceText()
            queue.add {
                val instance = SendMessagesHelper.getInstance(currentAccount)
                instance.processForwardFromMyName(copyMsg, key, 0, monoForumPeerId, null, copyText, notify, topicId)
                deque()
            }
        }
        if (groupedMsgs.isNotEmpty()) {
            sendAsAlbum()
        }
        deque()
    }

    @JvmStatic
    fun groupItemsIntoAlbum(
        key: Long,
        reply: Int,
        text: String?,
        sendingMessageObjects: ArrayList<MessageObject>,
        currentAccount: Int,
        parentFragment: BaseFragment?,
        notify: Boolean,
        monoForumPeerId: Long = 0L,
        replyInput: TLRPC.InputReplyTo? = null
    ) {
        if (sendingMessageObjects.isEmpty()) {
            return
        }

        val sub = { from: Int, to: Int ->
            ArrayList<MessageObject>(sendingMessageObjects.subList(from, to))
        }

        val objectsToSend = sub(0, min(10, sendingMessageObjects.size))
        val objectsToDelay = sub(objectsToSend.size, sendingMessageObjects.size)

        val finish = {
            groupItemsIntoAlbum(key, reply, text, objectsToDelay, currentAccount, parentFragment, notify, monoForumPeerId, replyInput)
        }

        sendItemsAsAlbum(
            currentAccount,
            objectsToSend,
            key,
            reply,
            parentFragment,
            text,
            notify,
            monoForumPeerId,
            finish,
            replyInput)
    }

    fun inputMediaFromMessageObject(m: MessageObject): InputMedia {
        val sourceMedia = m.messageOwner.media
        if (sourceMedia == null
            || sourceMedia is TL_messageMediaEmpty
            || sourceMedia is TL_messageMediaWebPage
            || sourceMedia is TL_messageMediaGame
            || sourceMedia is TL_messageMediaInvoice) {
            return TL_inputMediaEmpty()
        }
        if (ForkUtils.hasDocument(m)) {
            val document = sourceMedia.document
            val media = TL_inputMediaDocument()
            media.id = TL_inputDocument()
            media.id.id = document.id
            media.id.access_hash = document.access_hash
            media.id.file_reference = document.file_reference
            if (media.id.file_reference == null) {
                media.id.file_reference = ByteArray(0)
            }
            return media
        }
        if (ForkUtils.hasPhoto(m)) {
            val photo = sourceMedia.photo
            val media = TL_inputMediaPhoto()
            media.id = TL_inputPhoto()
            media.id.id = photo.id
            media.id.access_hash = photo.access_hash
            media.id.file_reference = photo.file_reference
            if (media.id.file_reference == null) {
                media.id.file_reference = ByteArray(0)
            }
            return media
        }
        return TL_inputMediaEmpty()
    }

    @JvmStatic
    fun sendItemsAsAlbum(
        currentAccount: Int,
        messages: ArrayList<MessageObject>,
        peer: Long,
        reply: Int,
        fragment: BaseFragment?,
        replaceText: String?,
        notify: Boolean,
        monoForumPeerId: Long = 0L,
        finish: () -> Unit,
        replyInput: TLRPC.InputReplyTo? = null
    ) {
        if (peer == 0L || messages.size > 10 || messages.isEmpty()) {
            return
        }
        val accountInstance = AccountInstance.getInstance(currentAccount)
        val lowerId = if (monoForumPeerId != 0L) monoForumPeerId else peer
        val sendToPeer = accountInstance.messagesController.getInputPeer(lowerId) ?: return
        val request = TL_messages_sendMultiMedia()
        request.peer = sendToPeer
        request.silent = !notify
        request.reply_to = replyInput ?: SendMessagesHelper.getInstance(currentAccount).createReplyInput(reply)
        if (replyInput != null || reply != 0) {
            request.flags += 1
        }

        for (i in messages.indices) {
            val m = messages[i]
            val media: InputMedia = inputMediaFromMessageObject(m)
            if (media is TL_inputMediaEmpty) {
                continue
            }
            val inputSingleMedia = TL_inputSingleMedia()
            inputSingleMedia.random_id = Utilities.random.nextLong()
            inputSingleMedia.media = media
            if (replaceText == null) {
                inputSingleMedia.message = m.messageOwner.message
                val entities = m.messageOwner.entities
                if (entities != null && entities.isNotEmpty()) {
                    inputSingleMedia.entities = entities
                    inputSingleMedia.flags = inputSingleMedia.flags or 1
                }
            } else {
                inputSingleMedia.message = if (request.multi_media.isEmpty()) replaceText else ""
            }
            request.multi_media.add(inputSingleMedia)
        }

        fun showToast(msg: String) {
            AndroidUtilities.runOnUIThread {
                Toast.makeText(
                    ApplicationLoader.applicationContext,
                    msg,
                    Toast.LENGTH_LONG).show()
            }
        }

        fun sendAlbum(response: TLObject?, error: TL_error?) {
            if (error == null) {
                accountInstance.messagesController.processUpdates(response as Updates, false)
                AndroidUtilities.runOnUIThread { finish() }
                return
            }
            if (!FileRefController.isFileRefError(error.text)) {
                showToast("It seems that you want to group incompatible file types.")
                AndroidUtilities.runOnUIThread {
                    AlertsCreator.processError(
                        currentAccount,
                        error,
                        fragment,
                        request)
                }
                return
            }
            // FileRefError.

            // Request messages, update file references and resend.
            fun handleMessages(cloudMessages: ArrayList<TLRPC.Message>, msgErr: TL_error?) {
                if (cloudMessages.isEmpty()) {
                    return
                }
                var atLeastOneFileRefUpdated = false
                for (i in cloudMessages.indices) {
                    val cloudMedia = cloudMessages[i].media
                    val localMedia = messages[i].messageOwner.media
                    if (cloudMedia == null) {
                        continue
                    }
                    if (cloudMedia.document != null && ForkUtils.hasDocument(messages[i])) {
                        atLeastOneFileRefUpdated = true
                        localMedia.document.file_reference = cloudMedia.document.file_reference
                    }
                    if (cloudMedia.photo != null && ForkUtils.hasPhoto(messages[i])) {
                        atLeastOneFileRefUpdated = true
                        localMedia.photo.file_reference = cloudMedia.photo.file_reference
                    }
                }
                if (!atLeastOneFileRefUpdated) {
                    showToast("Sorry, something went wrong.")
                    return
                }
                sendItemsAsAlbum(currentAccount, messages, peer, reply, fragment, replaceText, notify, monoForumPeerId, finish, replyInput)
            }

            ForkApi.tlrpcMessages(currentAccount, messages, ::handleMessages)
        }
        accountInstance.connectionsManager.sendRequest(request, ::sendAlbum)
    }

}
