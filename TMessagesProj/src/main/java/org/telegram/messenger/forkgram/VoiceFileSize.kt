package org.telegram.messenger.forkgram

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController

object VoiceFileSize {

    const val PREF_ENABLED = "voiceShowFileSize"

    @JvmStatic
    fun isEnabled(): Boolean {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_ENABLED, false)
    }

    @JvmStatic
    fun label(size: Long): String {
        if (!isEnabled()) {
            return ""
        }
        return ", " + AndroidUtilities.formatFileSize(size)
    }
}
