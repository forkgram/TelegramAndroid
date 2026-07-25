package org.telegram.messenger.forkgram

import android.text.TextUtils

import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig

object AccountOrder {

    private const val KEY_ORDER = "fg_accountsOrder"
    private const val SEPARATOR = ","

    @JvmStatic
    fun sort(accounts: ArrayList<Int>) {
        val order = load()
        if (order.isEmpty()) {
            accounts.sortWith { account1, account2 -> compareByLoginTime(account1, account2) }
            return
        }
        accounts.sortWith { account1, account2 ->
            val index1 = order.indexOf(UserConfig.getInstance(account1).getClientUserId())
            val index2 = order.indexOf(UserConfig.getInstance(account2).getClientUserId())
            when {
                index1 >= 0 && index2 >= 0 -> index1.compareTo(index2)
                index1 == index2 -> compareByLoginTime(account1, account2)
                index1 < 0 -> 1
                else -> -1
            }
        }
    }

    @JvmStatic
    fun save(reordered: List<Int>) {
        val accounts = ArrayList<Int>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a)
            }
        }
        sort(accounts)
        var index = 0
        var i = 0
        while (i < accounts.size && index < reordered.size) {
            if (accounts[i] in reordered) {
                accounts[i] = reordered[index++]
            }
            i++
        }
        val ids = ArrayList<Long>()
        for (account in accounts) {
            ids.add(UserConfig.getInstance(account).getClientUserId())
        }
        MessagesController.getGlobalMainSettings().edit()
            .putString(KEY_ORDER, TextUtils.join(SEPARATOR, ids))
            .apply()
    }

    private fun compareByLoginTime(account1: Int, account2: Int): Int {
        return UserConfig.getInstance(account1).loginTime.compareTo(UserConfig.getInstance(account2).loginTime)
    }

    private fun load(): ArrayList<Long> {
        val ids = ArrayList<Long>()
        val value: String? = MessagesController.getGlobalMainSettings().getString(KEY_ORDER, null)
        if (value.isNullOrEmpty()) {
            return ids
        }
        for (part in value.split(SEPARATOR)) {
            val id = part.toLongOrNull() ?: continue
            if (id !in ids) {
                ids.add(id)
            }
        }
        return ids
    }
}
