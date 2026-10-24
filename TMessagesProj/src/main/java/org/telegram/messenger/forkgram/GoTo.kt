package org.telegram.messenger.forkgram

import android.os.Bundle
import org.telegram.messenger.ChatObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import java.util.Locale

/**
 * Opens dialogs marked with a #tag in their title, e.g. "GoTo Forum #goto".
 * Invoked via the tg://goto deep link from LaunchActivity:
 *   tg://goto                     - bulletin with all available tags
 *   tg://goto?type=forum          - open the forum tagged #goto (default tag)
 *   tg://goto?tag=x&type=channel  - explicit tag and entity type
 */
object GoTo {

    const val DEFAULT_TAG = "goto"

    private const val TYPE_USER = "user"
    private const val TYPE_BASIC = "basic"
    private const val TYPE_SUPERGROUP = "supergroup"
    private const val TYPE_FORUM = "forum"
    private const val TYPE_CHANNEL = "channel"

    private val TAG_REGEX = Regex("#[A-Za-z0-9_]+")

    private class Candidate(val dialog: TLRPC.Dialog, val type: String)

    @JvmStatic
    fun openByTag(account: Int, tagParam: String?, typeParam: String?): Boolean {
        val tag = tagParam?.trim()?.removePrefix("#")?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val type = typeParam?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }

        if (tag == null && type == null) {
            showTagsBulletin(account)
            return false
        }
        val wantedTag = tag ?: DEFAULT_TAG

        val candidates = ArrayList<Candidate>()
        for (dialog in MessagesController.getInstance(account).getAllDialogs()) {
            val title = titleOf(account, dialog) ?: continue
            if (TAG_REGEX.findAll(title).none { it.value.substring(1).lowercase(Locale.US) == wantedTag }) {
                continue
            }
            val entityType = entityTypeOf(account, dialog) ?: continue
            candidates.add(Candidate(dialog, entityType))
        }

        val matched = if (type == null) candidates else candidates.filter { it.type == type }
        if (matched.isEmpty()) {
            showErrorBulletin(format(R.string.GoToNotFound, wantedTag, type ?: "any"))
            return false
        }
        if (type == null && matched.size > 1) {
            val types = matched.joinToString(", ") { it.type }
            showErrorBulletin(format(R.string.GoToAmbiguous, wantedTag, types))
            return false
        }
        return open(matched[0].dialog, wantedTag, type)
    }

    private fun open(dialog: TLRPC.Dialog, tag: String, type: String?): Boolean {
        val did = dialog.id
        val args = Bundle()
        if (did > 0) {
            args.putLong("user_id", did)
        } else {
            args.putLong("chat_id", -did)
        }
        FileLog.d("GoTo: opening #$tag ${type ?: "*"} -> dialog $did")

        val chatActivity = ChatActivity(args)
        val fragment = LaunchActivity.getSafeLastFragment()
        if (fragment != null) {
            return fragment.presentFragment(chatActivity)
        }
        val activity = LaunchActivity.instance ?: run {
            FileLog.w("GoTo: no activity to open #$tag")
            return false
        }
        activity.presentFragment(chatActivity)
        return true
    }

    private fun titleOf(account: Int, dialog: TLRPC.Dialog): String? {
        val controller = MessagesController.getInstance(account)
        return if (dialog.id > 0) {
            controller.getUser(dialog.id)?.let { UserObject.getUserName(it) }
        } else {
            controller.getChat(-dialog.id)?.title
        }
    }

    private fun entityTypeOf(account: Int, dialog: TLRPC.Dialog): String? {
        if (dialog.id > 0) {
            return TYPE_USER
        }
        val chat = MessagesController.getInstance(account).getChat(-dialog.id) ?: return null
        return when {
            ChatObject.isForum(chat) -> TYPE_FORUM
            chat.megagroup -> TYPE_SUPERGROUP
            !ChatObject.isChannel(chat) -> TYPE_BASIC
            else -> TYPE_CHANNEL
        }
    }

    private fun showTagsBulletin(account: Int) {
        val tags = LinkedHashMap<String, LinkedHashSet<String>>()
        for (dialog in MessagesController.getInstance(account).getAllDialogs()) {
            val title = titleOf(account, dialog) ?: continue
            val type = entityTypeOf(account, dialog) ?: continue
            for (match in TAG_REGEX.findAll(title)) {
                val t = match.value.substring(1).lowercase(Locale.US)
                tags.getOrPut(t) { LinkedHashSet() }.add(type)
            }
        }
        if (tags.isEmpty()) {
            FileLog.w("GoTo: no tagged dialogs found")
            return
        }
        val summary = tags.entries.joinToString("; ") { (t, types) ->
            "#$t: ${types.sorted().joinToString(", ")}"
        }
        val fragment = LaunchActivity.getSafeLastFragment() ?: run {
            FileLog.d("GoTo: $summary")
            return
        }
        if (BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment)
                .createSimpleBulletin(R.raw.chats_infotip, format(R.string.GoToTags, summary))
                .show()
        }
    }

    private fun showErrorBulletin(text: String) {
        val fragment = LaunchActivity.getSafeLastFragment() ?: run {
            FileLog.w("GoTo: $text (no fragment to show bulletin)")
            return
        }
        BulletinFactory.of(fragment).createErrorBulletin(text).show()
    }

    private fun format(res: Int, vararg args: Any?): String {
        return LocaleController.formatString(res, *args)
    }
}
