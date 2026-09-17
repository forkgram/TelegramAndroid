package org.telegram.messenger.forkgram

import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

object MediaSpoiler {

    @JvmStatic
    fun canToggle(messageObject: MessageObject?): Boolean {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false
        }
        if (messageObject.id <= 0 || messageObject.isSecretMedia || messageObject.needDrawBluredPreview() || messageObject.isSensitive) {
            return false
        }
        if (messageObject.type != MessageObject.TYPE_PHOTO && messageObject.type != MessageObject.TYPE_VIDEO && messageObject.type != MessageObject.TYPE_GIF) {
            return false
        }
        val media = MessageObject.getMedia(messageObject.messageOwner)
        if (media is TLRPC.TL_messageMediaPhoto) {
            return media.photo != null && media.photo.access_hash != 0L
        }
        if (media is TLRPC.TL_messageMediaDocument) {
            return media.document != null && media.document.access_hash != 0L
        }
        return false
    }

    @JvmStatic
    fun isSpoilered(messageObject: MessageObject?): Boolean {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false
        }
        val media = MessageObject.getMedia(messageObject.messageOwner)
        return media != null && media.spoiler
    }

    @JvmStatic
    fun inputMedia(messageObject: MessageObject?, spoiler: Boolean): TLRPC.InputMedia? {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null
        }
        val media = MessageObject.getMedia(messageObject.messageOwner)
        if (media is TLRPC.TL_messageMediaPhoto && media.photo != null) {
            val inputMedia = TLRPC.TL_inputMediaPhoto()
            inputMedia.id = inputPhoto(media.photo)
            inputMedia.spoiler = spoiler
            if (media.ttl_seconds != 0) {
                inputMedia.ttl_seconds = media.ttl_seconds
                inputMedia.flags = inputMedia.flags or TLObject.FLAG_0
            }
            if (media.live_photo && media.document != null) {
                inputMedia.video = inputDocument(media.document)
            }
            return inputMedia
        }
        if (media is TLRPC.TL_messageMediaDocument && media.document != null) {
            val inputMedia = TLRPC.TL_inputMediaDocument()
            inputMedia.id = inputDocument(media.document)
            inputMedia.spoiler = spoiler
            if (media.ttl_seconds != 0) {
                inputMedia.ttl_seconds = media.ttl_seconds
                inputMedia.flags = inputMedia.flags or TLObject.FLAG_0
            }
            if (media.video_cover != null) {
                inputMedia.video_cover = inputPhoto(media.video_cover)
                inputMedia.flags = inputMedia.flags or TLObject.FLAG_3
            }
            if (media.video_timestamp != 0) {
                inputMedia.video_timestamp = media.video_timestamp
                inputMedia.flags = inputMedia.flags or TLObject.FLAG_4
            }
            return inputMedia
        }
        return null
    }

    private fun inputPhoto(photo: TLRPC.Photo): TLRPC.TL_inputPhoto {
        val input = TLRPC.TL_inputPhoto()
        input.id = photo.id
        input.access_hash = photo.access_hash
        input.file_reference = photo.file_reference ?: ByteArray(0)
        return input
    }

    private fun inputDocument(document: TLRPC.Document): TLRPC.TL_inputDocument {
        val input = TLRPC.TL_inputDocument()
        input.id = document.id
        input.access_hash = document.access_hash
        input.file_reference = document.file_reference ?: ByteArray(0)
        return input
    }
}
