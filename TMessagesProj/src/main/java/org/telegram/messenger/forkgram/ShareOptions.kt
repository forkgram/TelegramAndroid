package org.telegram.messenger.forkgram

import android.view.Gravity
import android.view.View
import android.view.ViewGroup

import androidx.collection.LongSparseArray

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ItemOptions

import java.util.function.IntConsumer

class ShareOptions(
    private val currentAccount: Int,
    messages: ArrayList<MessageObject>?,
    fragment: ChatActivity?
) {

    private val chatMode = fragment?.chatMode ?: ChatActivity.MODE_DEFAULT
    private val topicId = fragment?.topicId?.toInt() ?: 0
    private val deletableMessages = ArrayList<MessageObject>()
    private val hasMessages = !messages.isNullOrEmpty()

    private var sendWithoutSound = false
    private var deleteOriginals = false

    init {
        messages?.forEach { message ->
            if (canDelete(message)) {
                deletableMessages.add(message)
            }
        }
    }

    fun isEmpty(): Boolean = !hasMessages

    fun isSendWithoutSound(): Boolean = sendWithoutSound

    fun isDeleteEnabled(): Boolean = deleteOriginals && deletableMessages.isNotEmpty()

    fun showMenu(container: ViewGroup, resourcesProvider: Theme.ResourcesProvider?, anchor: View) {
        val options = ItemOptions.makeOptions(container, resourcesProvider, anchor)
        options.setDismissWithButtons(false)
        options.setGravity(Gravity.RIGHT)

        val soundItem = addCheckbox(options, LocaleController.getString(R.string.SendWithoutSound), sendWithoutSound)
        soundItem.setOnClickListener {
            sendWithoutSound = !sendWithoutSound
            soundItem.setChecked(sendWithoutSound)
        }

        if (deletableMessages.isNotEmpty()) {
            val deleteItem = addCheckbox(options, LocaleController.getString(R.string.ShareDeleteOriginals), deleteOriginals)
            deleteItem.setOnClickListener {
                deleteOriginals = !deleteOriginals
                deleteItem.setChecked(deleteOriginals)
            }
        }

        options.show()
    }

    private fun addCheckbox(options: ItemOptions, text: CharSequence, checked: Boolean): ActionBarMenuSubItem {
        val item = options.addChecked()
        item.setText(text)
        item.checkView?.apply {
            setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck)
            setDrawUnchecked(true)
            setDrawBackgroundAsArc(10)
        }
        item.setChecked(checked)
        return item
    }

    fun deleteOriginalsWhenSent(targetDialogIds: ArrayList<Long>, expectedMessagesPerDialog: Int, onDeleted: IntConsumer?) {
        if (!isDeleteEnabled() || targetDialogIds.isEmpty() || expectedMessagesPerDialog <= 0) {
            return
        }
        SentWatcher(targetDialogIds, expectedMessagesPerDialog * targetDialogIds.size, onDeleted).arm()
    }

    private inner class SentWatcher(
        targetDialogIds: ArrayList<Long>,
        private var pending: Int,
        private val onDeleted: IntConsumer?
    ) : NotificationCenter.NotificationCenterDelegate {

        private val dialogIds = HashSet(targetDialogIds)
        private val giveUp = Runnable { disarm() }

        fun arm() {
            val center = NotificationCenter.getInstance(currentAccount)
            center.addObserver(this, NotificationCenter.messageReceivedByServer)
            center.addObserver(this, NotificationCenter.messageSendError)
            AndroidUtilities.runOnUIThread(giveUp, SENT_TIMEOUT)
        }

        private fun disarm() {
            AndroidUtilities.cancelRunOnUIThread(giveUp)
            val center = NotificationCenter.getInstance(currentAccount)
            center.removeObserver(this, NotificationCenter.messageReceivedByServer)
            center.removeObserver(this, NotificationCenter.messageSendError)
        }

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (id == NotificationCenter.messageSendError) {
                disarm()
                return
            }
            if (id != NotificationCenter.messageReceivedByServer || args.size < 7) {
                return
            }
            val dialogId = args[3]
            if (args[6] == true || dialogId !is Long || dialogId !in dialogIds) {
                return
            }
            pending--
            if (pending > 0) {
                return
            }
            disarm()
            val deleted = deleteNow()
            if (deleted > 0 && onDeleted != null) {
                onDeleted.accept(deleted)
            }
        }
    }

    private fun deleteNow(): Int {
        val controller = MessagesController.getInstance(currentAccount)
        val revokable = LongSparseArray<ArrayList<MessageObject>>()
        val localOnly = LongSparseArray<ArrayList<MessageObject>>()
        var count = 0
        for (message in deletableMessages) {
            val dialogId = message.dialogId
            if (message.isEphemeral) {
                controller.deleteEphemeralMessage(dialogId, topicId, message)
                count++
                continue
            }
            val target = if (canRevoke(message)) revokable else localOnly
            val group = target.get(dialogId) ?: ArrayList<MessageObject>().also { target.put(dialogId, it) }
            group.add(message)
            count++
        }
        deleteGroups(revokable, true)
        deleteGroups(localOnly, false)
        deletableMessages.clear()
        return count
    }

    private fun deleteGroups(groups: LongSparseArray<ArrayList<MessageObject>>, forAll: Boolean) {
        val controller = MessagesController.getInstance(currentAccount)
        for (a in 0 until groups.size()) {
            val dialogId = groups.keyAt(a)
            val encryptedChat = if (DialogObject.isEncryptedDialog(dialogId)) {
                controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId))
            } else {
                null
            }
            val ids = ArrayList<Int>()
            var randomIds: ArrayList<Long>? = null
            for (message in groups.valueAt(a)) {
                ids.add(message.id)
                if (encryptedChat != null && message.messageOwner.random_id != 0L && message.type != MessageObject.TYPE_DATE) {
                    if (randomIds == null) {
                        randomIds = ArrayList()
                    }
                    randomIds.add(message.messageOwner.random_id)
                }
            }
            controller.deleteMessages(ids, randomIds, encryptedChat, dialogId, topicId, forAll, chatMode)
        }
    }

    private fun canDelete(message: MessageObject?): Boolean {
        return message != null &&
            message.canDeleteMessage(chatMode == ChatActivity.MODE_SCHEDULED, chatOf(message))
    }

    private fun canRevoke(message: MessageObject): Boolean {
        if (chatMode != ChatActivity.MODE_DEFAULT) {
            return false
        }
        val dialogId = message.dialogId
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return true
        }
        val chat = chatOf(message)
        if (ChatObject.isChannel(chat)) {
            return true
        }
        val controller = MessagesController.getInstance(currentAccount)
        val age = ConnectionsManager.getInstance(currentAccount).currentTime - message.messageOwner.date
        if (chat != null) {
            return (message.isOut || ChatObject.canBlockUsers(chat)) && age <= controller.revokeTimeLimit
        }
        val user = controller.getUser(dialogId)
        if (user == null || UserObject.isUserSelf(user) || UserObject.isDeleted(user) || user.bot && !user.support) {
            return false
        }
        return (message.isOut || controller.canRevokePmInbox) && age <= controller.revokeTimePmLimit
    }

    private fun chatOf(message: MessageObject): TLRPC.Chat? {
        val dialogId = message.dialogId
        return if (DialogObject.isChatDialog(dialogId)) {
            MessagesController.getInstance(currentAccount).getChat(-dialogId)
        } else {
            null
        }
    }

    companion object {

        private const val SENT_TIMEOUT = 60000L
    }
}
