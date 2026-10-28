package org.telegram.messenger.forkgram

import org.telegram.messenger.MessagesController

object QuickRepeat {
    const val PREF_KEY = "quickRepeatButton"
    private const val DIALOG_PREFIX = "quickRepeatDialog_"

    private val explicit = HashMap<Int, HashMap<Long, Boolean>>()

    @JvmStatic
    fun isDefaultEnabled(): Boolean =
        MessagesController.getGlobalMainSettings().getBoolean(PREF_KEY, false)

    @JvmStatic
    fun isEnabled(account: Int, dialogId: Long): Boolean {
        val map = explicitMap(account)
        synchronized(map) {
            val value = map[dialogId]
            if (value != null) {
                return value
            }
        }
        return MessagesController.getMainSettings(account)
            .getBoolean(DIALOG_PREFIX + dialogId, isDefaultEnabled())
    }

    @JvmStatic
    fun setEnabled(account: Int, dialogId: Long, value: Boolean) {
        MessagesController.getMainSettings(account)
            .edit()
            .putBoolean(DIALOG_PREFIX + dialogId, value)
            .apply()
        val map = explicitMap(account)
        synchronized(map) {
            map[dialogId] = value
        }
    }

    @JvmStatic
    fun toggle(account: Int, dialogId: Long): Boolean {
        val value = !isEnabled(account, dialogId)
        setEnabled(account, dialogId, value)
        return value
    }

    @JvmStatic
    fun invalidate() {
        synchronized(explicit) {
            explicit.clear()
        }
    }

    private fun explicitMap(account: Int): HashMap<Long, Boolean> {
        synchronized(explicit) {
            return explicit.getOrPut(account) { HashMap() }
        }
    }
}
