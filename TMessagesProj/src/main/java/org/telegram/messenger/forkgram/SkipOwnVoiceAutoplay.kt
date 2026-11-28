package org.telegram.messenger.forkgram

import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController

object SkipOwnVoiceAutoplay {

    const val PREF_ENABLED = "skipOwnVoiceAutoplay"

    @JvmStatic
    fun isEnabled(): Boolean {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_ENABLED, false)
    }

    @JvmStatic
    fun nextIndex(playlist: List<MessageObject>, from: Int, finished: MessageObject): Int {
        var index = from
        if (isOwn(finished) || !isEnabled()) {
            return index
        }
        while (index < playlist.size && isOwn(playlist[index])) {
            index++
        }
        return index
    }

    private fun isOwn(message: MessageObject): Boolean {
        return message.isOut && message.messageOwner?.fwd_from == null
    }
}
