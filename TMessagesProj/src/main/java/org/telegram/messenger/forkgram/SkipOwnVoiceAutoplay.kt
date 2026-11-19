package org.telegram.messenger.forkgram

import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController

// Lets auto-play of the next voice or video message skip the ones you sent.
object SkipOwnVoiceAutoplay {

    const val PREF_ENABLED = "skipOwnVoiceAutoplay"

    @JvmStatic
    fun isEnabled(): Boolean {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_ENABLED, false)
    }

    // Index of the first message at or after [from] that auto-play should play after [finished].
    // Nothing is skipped when [finished] was your own, so playing through your own messages
    // (and Saved Messages) works as before. Forwarded messages are never skipped.
    @JvmStatic
    fun nextIndex(playlist: List<MessageObject>, from: Int, finished: MessageObject): Int {
        var index = from
        if (finished.isOut || !isEnabled()) {
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
