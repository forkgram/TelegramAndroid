package org.telegram.messenger.forkgram

import android.text.SpannableStringBuilder
import android.text.Spanned

import androidx.annotation.DrawableRes

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.ColoredImageSpan

object FolderIcons {

    const val FOLDER_TABS_STYLE_TEXT = 0
    const val FOLDER_TABS_STYLE_ICON_TEXT = 1
    const val FOLDER_TABS_STYLE_ICON = 2

    const val FLAG_EMOTICON = 1 shl 25

    const val PRIVATE = "👤"
    const val GROUP = "👥"
    const val CHANNELS = "📢"
    const val BOTS = "🤖"
    const val UNREAD = "✅"
    const val UNMUTED = "🔔"
    const val ALL = "💬"
    const val CUSTOM = "📁"

    @JvmField
    val EMOTICONS = arrayOf(
        ALL,
        CUSTOM,
        PRIVATE,
        GROUP,
        CHANNELS,
        BOTS,
        "⭐",
        UNREAD,
        UNMUTED,
        "🐱",
        "📕",
        "💰",
        "🎮",
        "💡",
        "👌",
        "🎵",
        "🎨",
        "✈️",
        "⚽️",
        "🎓",
        "🛫",
        "👑",
        "🌹",
        "🏠",
        "❤",
        "🎭",
        "🍸",
        "📈",
        "💼",
        "📋",
    )

    private val ICON_RES = intArrayOf(
        R.drawable.folder_all,
        R.drawable.folder_custom,
        R.drawable.folder_private,
        R.drawable.folder_group,
        R.drawable.folder_channels,
        R.drawable.folder_bots,
        R.drawable.folder_favorite,
        R.drawable.folder_unread,
        R.drawable.folder_unmuted,
        R.drawable.folder_cat,
        R.drawable.folder_book,
        R.drawable.folder_money,
        R.drawable.folder_game,
        R.drawable.folder_light,
        R.drawable.folder_like,
        R.drawable.folder_note,
        R.drawable.folder_palette,
        R.drawable.folder_travel,
        R.drawable.folder_sport,
        R.drawable.folder_study,
        R.drawable.folder_airplane,
        R.drawable.folder_crown,
        R.drawable.folder_flower,
        R.drawable.folder_home,
        R.drawable.folder_love,
        R.drawable.folder_mask,
        R.drawable.folder_party,
        R.drawable.folder_trade,
        R.drawable.folder_work,
        R.drawable.folder_setup,
    )

    private val byEmoticon = EMOTICONS.indices.associate { normalize(EMOTICONS[it]) to ICON_RES[it] }

    @JvmStatic
    fun folderTabsStyle(): Int {
        return MessagesController.getGlobalMainSettings().getInt("folderTabsStyle", FOLDER_TABS_STYLE_TEXT)
    }

    @JvmStatic
    fun wireEmoticon(newFilter: TLRPC.DialogFilter): String? {
        return if ((newFilter.flags and FLAG_EMOTICON) != 0) newFilter.emoticon else null
    }

    private fun normalize(emoticon: String?): String {
        if (emoticon == null) {
            return ""
        }
        return emoticon.replace("\uFE0F", "").replace("\uFE0E", "")
    }

    @JvmStatic
    @DrawableRes
    fun getIconResByEmoticon(emoticon: String?): Int {
        if (emoticon.isNullOrEmpty()) {
            return 0
        }
        return byEmoticon[normalize(emoticon)] ?: 0
    }

    @JvmStatic
    fun computeDefaultEmoticon(filter: MessagesController.DialogFilter?): String {
        if (filter == null || filter.isDefault) {
            return ALL
        }
        return computeDefaultEmoticon(filter.flags, filter.alwaysShow.isNotEmpty(), filter.neverShow.isNotEmpty())
    }

    @JvmStatic
    fun computeDefaultEmoticon(flags: Int, hasAlwaysShow: Boolean, hasNeverShow: Boolean): String {
        val all = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
        if (hasAlwaysShow || hasNeverShow || (flags and all) == 0) {
            return CUSTOM
        }
        val contacts = MessagesController.DIALOG_FILTER_FLAG_CONTACTS
        val nonContacts = MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
        val byType = when (flags and all) {
            contacts, nonContacts, (contacts or nonContacts) -> PRIVATE
            MessagesController.DIALOG_FILTER_FLAG_GROUPS -> GROUP
            MessagesController.DIALOG_FILTER_FLAG_CHANNELS -> CHANNELS
            MessagesController.DIALOG_FILTER_FLAG_BOTS -> BOTS
            else -> null
        }
        if (byType != null) {
            return byType
        }
        val excludeRead = MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ
        val excludeMuted = MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED
        return when (flags and (excludeRead or excludeMuted)) {
            excludeRead -> UNREAD
            excludeMuted -> UNMUTED
            else -> CUSTOM
        }
    }

    @JvmStatic
    @DrawableRes
    fun getIconRes(filter: MessagesController.DialogFilter?): Int {
        if (filter == null) {
            return 0
        }
        val res = getIconResByEmoticon(filter.emoticon)
        if (res != 0) {
            return res
        }
        return getIconResByEmoticon(computeDefaultEmoticon(filter))
    }

    @JvmStatic
    fun applyIcon(name: CharSequence?, iconRes: Int): CharSequence? {
        val style = folderTabsStyle()
        if (iconRes == 0 || style == FOLDER_TABS_STYLE_TEXT) {
            return name
        }
        val result = SpannableStringBuilder(" ")
        val span = ColoredImageSpan(iconRes, ColoredImageSpan.ALIGN_CENTER)
        span.setSize(AndroidUtilities.dp(18f))
        result.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style == FOLDER_TABS_STYLE_ICON_TEXT && !name.isNullOrEmpty()) {
            result.append("  ")
            result.append(name)
        }
        return result
    }
}
