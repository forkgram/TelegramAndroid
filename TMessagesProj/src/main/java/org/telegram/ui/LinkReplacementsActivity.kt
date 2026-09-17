package org.telegram.ui

import android.content.Context
import android.content.DialogInterface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BotWebViewVibrationEffect
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.forkgram.LinkReplacements
import org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalRecyclerView

class LinkReplacementsActivity : BaseFragment() {

    private val rules = ArrayList<LinkReplacements.Rule>()
    private lateinit var listView: UniversalRecyclerView

    override fun onFragmentCreate(): Boolean {
        rules.addAll(LinkReplacements.load())
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(LocaleController.getString(R.string.ReplaceLinksOnPaste))
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false)
        }
        actionBar.setActionBarMenuOnItemClick(object : ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                } else if (id == MENU_RESET) {
                    resetToDefault()
                }
            }
        })

        actionBar.createMenu().addItem(MENU_RESET, R.drawable.msg_reset)
            .setContentDescription(LocaleController.getString(R.string.Reset))

        val frameLayout = FrameLayout(context)
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        fragmentView = frameLayout

        listView = UniversalRecyclerView(
            this,
            { items, adapter -> fillItems(items, adapter) },
            { item, view, position, x, y -> onClick(item, view, position, x, y) },
            { item, view, position, x, y -> onLongClick(item, view, position, x, y) }
        )
        frameLayout.addView(
            listView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP or Gravity.LEFT)
        )

        return frameLayout
    }

    private fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButtonCheck(
                ID_ENABLED,
                LocaleController.getString(R.string.ReplaceLinksOnPaste),
                LocaleController.getString(R.string.ReplaceLinksOnPasteInfo)
            ).setChecked(LinkReplacements.isEnabled()).setMultiline(true)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.LinkReplacementRules)))
        for (a in rules.indices) {
            val rule = rules[a]
            items.add(
                UItem.asButtonCheck(RULE_ID_OFFSET + a, rule.pattern, getRuleValue(rule))
                    .setChecked(rule.enabled)
            )
        }
        items.add(
            UItem.asButton(ID_ADD, R.drawable.msg_add, LocaleController.getString(R.string.LinkReplacementAdd))
                .accent()
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.LinkReplacementRulesInfo)))
    }

    private fun getRuleValue(rule: LinkReplacements.Rule): CharSequence {
        if (rule.valid) {
            return rule.replacement
        }
        val invalid = SpannableString(LocaleController.getString(R.string.LinkReplacementInvalid))
        invalid.setSpan(
            ForegroundColorSpan(Theme.getColor(Theme.key_text_RedRegular)),
            0,
            invalid.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return invalid
    }

    private fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == ID_ENABLED) {
            toggleEnabled(item, view)
            return
        }
        if (item.id == ID_ADD) {
            showRuleDialog(-1)
            return
        }
        val index = item.id - RULE_ID_OFFSET
        if (index < 0 || index >= rules.size) {
            return
        }
        val checkClick = if (LocaleController.isRTL) {
            x <= AndroidUtilities.dp(76f)
        } else {
            x >= view.measuredWidth - AndroidUtilities.dp(76f)
        }
        if (checkClick) {
            toggleRule(index, item, view)
        } else {
            showRuleDialog(index)
        }
    }

    private fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val index = item.id - RULE_ID_OFFSET
        if (index < 0 || index >= rules.size) {
            return false
        }
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) { deleteRule(index) }
            .setScrimViewBackground(listView.getClipBackground(view))
            .show()
        return true
    }

    private fun toggleEnabled(item: UItem, view: View) {
        val value = !item.checked
        item.checked = value
        MessagesController.getGlobalMainSettings().edit()
            .putBoolean(LinkReplacements.ENABLED_KEY, value)
            .commit()
        setCellChecked(view, value)
    }

    private fun toggleRule(index: Int, item: UItem, view: View) {
        val value = !rules[index].enabled
        rules[index] = rules[index].with(value)
        LinkReplacements.save(rules)
        item.checked = value
        setCellChecked(view, value)
    }

    private fun deleteRule(index: Int) {
        if (index < 0 || index >= rules.size) {
            return
        }
        rules.removeAt(index)
        LinkReplacements.save(rules)
        listView.adapter.update(true)
    }

    private fun resetToDefault() {
        LinkReplacements.reset()
        rules.clear()
        rules.addAll(LinkReplacements.load())
        listView.adapter.update(true)
    }

    private fun setCellChecked(view: View, value: Boolean) {
        if (view is NotificationsCheckCell) {
            view.setChecked(value)
        }
    }

    private fun showRuleDialog(index: Int) {
        val context = parentActivity ?: return
        val rule = if (index >= 0 && index < rules.size) rules[index] else null

        val builder = AlertDialog.Builder(context)
        builder.setTitle(
            LocaleController.getString(
                if (rule == null) R.string.LinkReplacementAdd else R.string.LinkReplacementEdit
            )
        )
        builder.setCustomViewOffset(0)

        val linearLayout = LinearLayout(context)
        linearLayout.orientation = LinearLayout.VERTICAL

        val patternField = createField(
            context,
            rule?.pattern.orEmpty(),
            LocaleController.getString(R.string.LinkReplacementFind),
            EditorInfo.IME_ACTION_NEXT
        )
        linearLayout.addView(patternField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0))

        val replacementField = createField(
            context,
            rule?.replacement.orEmpty(),
            LocaleController.getString(R.string.LinkReplacementReplaceWith),
            EditorInfo.IME_ACTION_DONE
        )
        linearLayout.addView(replacementField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0))

        builder.setView(linearLayout)
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null)

        val dialog = builder.create()
        showDialog(dialog)
        patternField.requestFocus()

        val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        button?.setOnClickListener {
            val pattern = patternField.text.toString().trim()
            if (!LinkReplacements.isValidPattern(pattern)) {
                patternField.setErrorText(LocaleController.getString(R.string.LinkReplacementInvalid))
                AndroidUtilities.shakeViewSpring(patternField, -6f)
                BotWebViewVibrationEffect.APP_ERROR.vibrate()
                return@setOnClickListener
            }
            val replacement = replacementField.text.toString()
            val saved = LinkReplacements.Rule(pattern, replacement, rule?.enabled ?: true)
            if (rule == null) {
                rules.add(saved)
            } else {
                rules[index] = saved
            }
            LinkReplacements.save(rules)
            listView.adapter.update(true)
            dialog.dismiss()
        }
    }

    private fun createField(context: Context, value: String, hint: CharSequence, imeOptions: Int): EditTextBoldCursor {
        val editText = object : EditTextBoldCursor(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64f), View.MeasureSpec.EXACTLY)
                )
            }
        }
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        editText.setText(value)
        editText.setHintText(hint)
        editText.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        editText.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
        editText.isSingleLine = true
        editText.isFocusable = true
        editText.setTransformHintToHeader(true)
        editText.setLineColors(
            Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
            Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
            Theme.getColor(Theme.key_text_RedRegular)
        )
        editText.imeOptions = imeOptions
        editText.background = null
        editText.setPadding(0, 0, 0, 0)
        return editText
    }

    companion object {
        private const val MENU_RESET = 1
        private const val ID_ENABLED = 1
        private const val ID_ADD = 2
        private const val RULE_ID_OFFSET = 100
    }
}
