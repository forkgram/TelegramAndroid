package org.telegram.messenger.forkgram

import android.text.SpannableStringBuilder
import android.widget.EditText

import org.json.JSONArray
import org.json.JSONObject

import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

import java.util.regex.Matcher
import java.util.regex.Pattern

object LinkReplacements {

    const val ENABLED_KEY = "replaceLinksOnPaste"

    private const val RULES_KEY = "linkReplacementRules"
    private const val MAX_INPUT_LENGTH = 10000
    private const val MAX_RULES = 100

    class Rule(val pattern: String, val replacement: String, val enabled: Boolean) {

        val compiled: Pattern? = compile(pattern)

        val valid: Boolean
            get() = compiled != null

        fun with(enabled: Boolean) = Rule(pattern, replacement, enabled)
    }

    private fun compile(pattern: String?): Pattern? {
        if (pattern.isNullOrEmpty()) {
            return null
        }
        return try {
            Pattern.compile(pattern)
        } catch (e: Exception) {
            null
        }
    }

    private fun prefs() = MessagesController.getGlobalMainSettings()

    @JvmStatic
    fun isEnabled() = prefs().getBoolean(ENABLED_KEY, false)

    @JvmStatic
    fun isValidPattern(pattern: String?) = compile(pattern) != null

    @JvmStatic
    fun defaults(): ArrayList<Rule> = arrayListOf(
        Rule("https?://(?:www\\.)?(?:twitter|x)\\.com/", "https://fixupx.com/", true),
        Rule("https?://(?:www\\.)?instagram\\.com/", "https://kkinstagram.com/", true),
        Rule("https?://(?:www\\.|vm\\.|vt\\.)?tiktok\\.com/", "https://vxtiktok.com/", true),
        Rule("https?://(?:www\\.|old\\.)?reddit\\.com/", "https://rxddit.com/", true),
        Rule("https?://(?:www\\.)?bsky\\.app/", "https://fxbsky.app/", true),
        Rule("https?://(?:www\\.)?pixiv\\.net/(?:en/)?artworks/", "https://phixiv.net/artworks/", true)
    )

    @JvmStatic
    fun load(): ArrayList<Rule> {
        val stored = prefs().getString(RULES_KEY, null) ?: return defaults()
        val rules = ArrayList<Rule>()
        try {
            val array = JSONArray(stored)
            for (a in 0 until array.length()) {
                val item = array.optJSONObject(a) ?: continue
                val pattern = item.optString("p")
                if (pattern.isEmpty()) {
                    continue
                }
                rules.add(Rule(pattern, item.optString("r"), item.optBoolean("e", true)))
            }
        } catch (e: Exception) {
            FileLog.e(e)
            return defaults()
        }
        return rules
    }

    @JvmStatic
    fun save(rules: List<Rule>) {
        val array = JSONArray()
        for (rule in rules.take(MAX_RULES)) {
            val item = JSONObject()
            item.put("p", rule.pattern)
            item.put("r", rule.replacement)
            item.put("e", rule.enabled)
            array.put(item)
        }
        prefs().edit().putString(RULES_KEY, array.toString()).commit()
    }

    @JvmStatic
    fun reset() {
        prefs().edit().remove(RULES_KEY).commit()
    }

    @JvmStatic
    fun enabledCount(): Int = load().count { it.enabled && it.valid }

    @JvmStatic
    fun apply(text: SpannableStringBuilder): Boolean {
        if (text.length > MAX_INPUT_LENGTH) {
            return false
        }
        var changed = false
        for (rule in load()) {
            val compiled = rule.compiled
            if (!rule.enabled || compiled == null) {
                continue
            }
            try {
                val matcher = compiled.matcher(text)
                val starts = ArrayList<Int>()
                val ends = ArrayList<Int>()
                val replacements = ArrayList<String>()
                while (matcher.find()) {
                    if (matcher.end() == matcher.start()) {
                        continue
                    }
                    starts.add(matcher.start())
                    ends.add(matcher.end())
                    replacements.add(expand(matcher, rule.replacement))
                }
                for (a in starts.indices.reversed()) {
                    text.replace(starts[a], ends[a], replacements[a])
                    changed = true
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
        return changed
    }

    private fun expand(matcher: Matcher, replacement: String): String {
        val builder = StringBuilder()
        var a = 0
        while (a < replacement.length) {
            val c = replacement[a]
            if (c == '\\' && a + 1 < replacement.length) {
                builder.append(replacement[a + 1])
                a += 2
            } else if (c == '$' && a + 1 < replacement.length && replacement[a + 1].isDigit()) {
                val group = replacement[a + 1] - '0'
                if (group <= matcher.groupCount()) {
                    builder.append(matcher.group(group).orEmpty())
                }
                a += 2
            } else {
                builder.append(c)
                a++
            }
        }
        return builder.toString()
    }

    @JvmStatic
    fun pasteWithReplacements(editText: EditText, pasted: CharSequence?): Boolean {
        if (!isEnabled() || pasted.isNullOrEmpty()) {
            return false
        }
        val editable = editText.text ?: return false
        val replaced = SpannableStringBuilder(pasted)
        if (!apply(replaced)) {
            return false
        }
        val original = SpannableStringBuilder(pasted)
        val start = Math.max(0, Math.min(editText.selectionStart, editText.selectionEnd))
        val end = Math.min(editable.length, Math.max(editText.selectionStart, editText.selectionEnd))
        if (start > end) {
            return false
        }
        editText.setText(editable.replace(start, end, replaced))
        editText.setSelection(Math.min(editText.length(), start + replaced.length))
        showUndo(editText, start, replaced.length, original)
        return true
    }

    private fun showUndo(editText: EditText, start: Int, length: Int, original: CharSequence) {
        val fragment = LaunchActivity.getSafeLastFragment() ?: return
        BulletinFactory.of(fragment).createSimpleBulletin(
            R.raw.chats_infotip,
            LocaleController.getString(R.string.LinkReplacedOnPaste),
            LocaleController.getString(R.string.Undo)
        ) {
            val editable = editText.text ?: return@createSimpleBulletin
            if (start + length > editable.length) {
                return@createSimpleBulletin
            }
            editText.setText(editable.replace(start, start + length, original))
            editText.setSelection(Math.min(editText.length(), start + original.length))
        }.show()
    }
}
