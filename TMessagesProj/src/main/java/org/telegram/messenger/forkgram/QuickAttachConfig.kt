package org.telegram.messenger.forkgram

import android.content.SharedPreferences
import org.telegram.messenger.MessagesController

object QuickAttachConfig {

    const val PREF_ENABLED = "quickAttach"
    const val PREF_SEND_IMMEDIATELY = "quickAttachSendImmediately"
    const val PREF_NEWEST_FIRST = "quickAttachNewestFirst"

    @JvmStatic
    fun prefs(): SharedPreferences {
        return MessagesController.getGlobalMainSettings()
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        return prefs().getBoolean(PREF_ENABLED, true)
    }

    @JvmStatic
    fun setEnabled(value: Boolean) {
        prefs().edit().putBoolean(PREF_ENABLED, value).apply()
    }

    @JvmStatic
    fun sendImmediately(): Boolean {
        return prefs().getBoolean(PREF_SEND_IMMEDIATELY, true)
    }

    @JvmStatic
    fun setSendImmediately(value: Boolean) {
        prefs().edit().putBoolean(PREF_SEND_IMMEDIATELY, value).apply()
    }

    @JvmStatic
    fun newestFirst(): Boolean {
        return prefs().getBoolean(PREF_NEWEST_FIRST, false)
    }

    @JvmStatic
    fun setNewestFirst(value: Boolean) {
        prefs().edit().putBoolean(PREF_NEWEST_FIRST, value).apply()
    }
}
