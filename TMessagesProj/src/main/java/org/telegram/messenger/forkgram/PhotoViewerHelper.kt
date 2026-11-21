package org.telegram.messenger.forkgram

import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController

object PhotoViewerHelper {

    const val PREF_SEEKBAR_FOR_GIFS = "seekbarForGifs"

    @JvmStatic
    fun isGifSeekbarEnabled(): Boolean =
        MessagesController.getGlobalMainSettings().getBoolean(PREF_SEEKBAR_FOR_GIFS, false)

    @JvmStatic
    fun gifAsVideo(msg: MessageObject?): Boolean =
        msg != null && msg.isNewGif && isGifSeekbarEnabled()
}
