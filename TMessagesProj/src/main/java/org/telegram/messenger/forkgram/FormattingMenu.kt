package org.telegram.messenger.forkgram

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.TypefaceSpan

class FormattingMenu private constructor(
    val order: List<String>,
    val hidden: List<String>
) {

    fun isVisible(key: String) = key !in hidden

    val visibleOrder: List<String>
        get() = order.filter(::isVisible)

    companion object {

        const val QUOTE = "quote"
        const val SPOILER = "spoiler"
        const val BOLD = "bold"
        const val ITALIC = "italic"
        const val MONO = "mono"
        const val STRIKE = "strike"
        const val UNDERLINE = "underline"
        const val LINK = "link"
        const val DATE = "date"
        const val REGULAR = "regular"

        @JvmField
        val DEFAULT_ORDER = listOf(
            QUOTE, SPOILER, BOLD, ITALIC, MONO, STRIKE, UNDERLINE, LINK, DATE, REGULAR
        )

        private const val ORDER_KEY = "formattingMenuOrder"
        private const val HIDDEN_KEY = "formattingMenuHidden"
        private const val SEPARATOR = ","

        @JvmStatic
        fun load(): FormattingMenu {
            val preferences = MessagesController.getGlobalMainSettings()
            return FormattingMenu(
                parseKeys(preferences.getString(ORDER_KEY, null), appendMissing = true),
                parseKeys(preferences.getString(HIDDEN_KEY, null), appendMissing = false)
            )
        }

        @JvmStatic
        fun save(order: List<String>, hidden: List<String>) {
            MessagesController.getGlobalMainSettings().edit()
                .putString(ORDER_KEY, TextUtils.join(SEPARATOR, order))
                .putString(HIDDEN_KEY, TextUtils.join(SEPARATOR, hidden))
                .apply()
        }

        @JvmStatic
        fun reset() {
            MessagesController.getGlobalMainSettings().edit()
                .remove(ORDER_KEY)
                .remove(HIDDEN_KEY)
                .apply()
        }

        @JvmStatic
        fun getMenuId(key: String) = when (key) {
            QUOTE -> R.id.menu_quote
            SPOILER -> R.id.menu_spoiler
            BOLD -> R.id.menu_bold
            ITALIC -> R.id.menu_italic
            MONO -> R.id.menu_mono
            STRIKE -> R.id.menu_strike
            UNDERLINE -> R.id.menu_underline
            LINK -> R.id.menu_link
            DATE -> R.id.menu_date
            REGULAR -> R.id.menu_regular
            else -> 0
        }

        @JvmStatic
        fun getTitle(key: String): CharSequence = when (key) {
            QUOTE -> LocaleController.getString(R.string.Quote)
            SPOILER -> LocaleController.getString(R.string.Spoiler)
            BOLD -> withSpan(R.string.Bold, TypefaceSpan(AndroidUtilities.bold()))
            ITALIC -> withSpan(R.string.Italic, TypefaceSpan(AndroidUtilities.getTypeface("fonts/ritalic.ttf")))
            MONO -> withSpan(R.string.Mono, TypefaceSpan(Typeface.MONOSPACE))
            STRIKE -> withSpan(R.string.Strike, styleSpan(TextStyleSpan.FLAG_STYLE_STRIKE))
            UNDERLINE -> withSpan(R.string.Underline, styleSpan(TextStyleSpan.FLAG_STYLE_UNDERLINE))
            LINK -> LocaleController.getString(R.string.CreateLink)
            DATE -> LocaleController.getString(R.string.FormattedDate)
            REGULAR -> LocaleController.getString(R.string.Regular)
            else -> ""
        }

        private fun parseKeys(value: String?, appendMissing: Boolean): List<String> {
            val keys = value.orEmpty()
                .split(SEPARATOR)
                .filter { it in DEFAULT_ORDER }
                .distinct()
            return if (appendMissing) keys + DEFAULT_ORDER.filterNot { it in keys } else keys
        }

        private fun withSpan(resId: Int, span: Any): CharSequence {
            val stringBuilder = SpannableStringBuilder(LocaleController.getString(resId))
            stringBuilder.setSpan(span, 0, stringBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return stringBuilder
        }

        private fun styleSpan(flag: Int): TextStyleSpan {
            val run = TextStyleSpan.TextStyleRun()
            run.flags = run.flags or flag
            return TextStyleSpan(run)
        }
    }
}
