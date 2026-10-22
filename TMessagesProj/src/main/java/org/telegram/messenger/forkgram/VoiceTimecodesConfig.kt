package org.telegram.messenger.forkgram

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object VoiceTimecodesConfig {

    const val PREF_ENABLED = "voice_timecodes_enabled"
    const val PREF_STT_URL = "voice_timecodes_stt_url"
    const val PREF_STT_TOKEN = "voice_timecodes_stt_token"
    const val PREF_LLM_URL = "voice_timecodes_llm_url"
    const val PREF_LLM_TOKEN = "voice_timecodes_llm_token"
    const val PREF_LLM_MODEL = "voice_timecodes_llm_model"
    const val PREF_MIN_DURATION = "voice_timecodes_min_duration"
    const val PREF_PROMPT = "voice_timecodes_prompt"

    private const val CODE_PREFIX = "FVT1:"
    private const val MAX_DECODED_SIZE = 256 * 1024
    private val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private val PREF_KEYS = setOf(
        PREF_ENABLED,
        PREF_STT_URL,
        PREF_STT_TOKEN,
        PREF_LLM_URL,
        PREF_LLM_TOKEN,
        PREF_LLM_MODEL,
        PREF_MIN_DURATION,
        PREF_PROMPT
    )

    const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
    const val DEFAULT_MIN_DURATION = 10

    const val DEFAULT_PROMPT =
        "Below is a transcript of a voice message with timestamps.\n" +
        "Divide it into meaningful topics. For each topic, provide the start timestamp and a concise title.\n" +
        "Response format — only the list of timecodes, no extra text:\n" +
        "00:00 — Topic name\n" +
        "05:30 — Another topic\n" +
        "...\n\n" +
        "Transcript:\n{transcript}"

    @JvmStatic
    fun prefs(): SharedPreferences {
        return MessagesController.getGlobalMainSettings()
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        return prefs().getBoolean(PREF_ENABLED, false)
    }

    @JvmStatic
    fun setEnabled(value: Boolean) {
        prefs().edit().putBoolean(PREF_ENABLED, value).apply()
    }

    @JvmStatic
    fun sttUrl(): String {
        return prefs().getString(PREF_STT_URL, "")?.trim() ?: ""
    }

    @JvmStatic
    fun sttToken(): String {
        return prefs().getString(PREF_STT_TOKEN, "")?.trim() ?: ""
    }

    @JvmStatic
    fun llmUrl(): String {
        return prefs().getString(PREF_LLM_URL, "")?.trim() ?: ""
    }

    @JvmStatic
    fun llmToken(): String {
        return prefs().getString(PREF_LLM_TOKEN, "")?.trim() ?: ""
    }

    @JvmStatic
    fun llmModel(): String {
        val m = prefs().getString(PREF_LLM_MODEL, DEFAULT_LLM_MODEL)?.trim() ?: ""
        return if (m.isEmpty()) DEFAULT_LLM_MODEL else m
    }

    @JvmStatic
    fun minDuration(): Int {
        val d = prefs().getInt(PREF_MIN_DURATION, DEFAULT_MIN_DURATION)
        return if (d <= 0) DEFAULT_MIN_DURATION else d
    }

    @JvmStatic
    fun promptTemplate(): String {
        val p = prefs().getString(PREF_PROMPT, DEFAULT_PROMPT)
        return if (p.isNullOrBlank()) DEFAULT_PROMPT else p
    }

    @JvmStatic
    fun maxCaptionLimit(account: Int): Int {
        return if (UserConfig.getInstance(account).isPremium) 4096 else 1024
    }

    @JvmStatic
    fun isConfigured(): Boolean {
        return isEnabled() && sttUrl().isNotEmpty() && llmUrl().isNotEmpty()
    }

    @JvmStatic
    fun saveValues(
        enabled: Boolean,
        sttUrl: String,
        sttToken: String,
        llmUrl: String,
        llmToken: String,
        llmModel: String,
        minDuration: Int,
        prompt: String
    ) {
        prefs().edit()
            .putBoolean(PREF_ENABLED, enabled)
            .putString(PREF_STT_URL, sttUrl.trim())
            .putString(PREF_STT_TOKEN, sttToken.trim())
            .putString(PREF_LLM_URL, llmUrl.trim())
            .putString(PREF_LLM_TOKEN, llmToken.trim())
            .putString(PREF_LLM_MODEL, llmModel.trim())
            .putInt(PREF_MIN_DURATION, if (minDuration <= 0) DEFAULT_MIN_DURATION else minDuration)
            .putString(PREF_PROMPT, if (prompt.isBlank()) DEFAULT_PROMPT else prompt)
            .apply()
    }

    @JvmStatic
    fun exportToString(): String {
        return exportToString(
            isEnabled(), sttUrl(), sttToken(), llmUrl(), llmToken(), llmModel(), minDuration(), promptTemplate()
        )
    }

    @JvmStatic
    fun exportToString(
        enabled: Boolean,
        sttUrl: String,
        sttToken: String,
        llmUrl: String,
        llmToken: String,
        llmModel: String,
        minDuration: Int,
        prompt: String
    ): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("enabled", enabled)
        root.put("stt_url", sttUrl.trim())
        root.put("stt_token", sttToken.trim())
        root.put("llm_url", llmUrl.trim())
        root.put("llm_token", llmToken.trim())
        root.put("llm_model", llmModel.trim())
        root.put("min_duration", if (minDuration <= 0) DEFAULT_MIN_DURATION else minDuration)
        root.put("prompt", if (prompt.isBlank()) DEFAULT_PROMPT else prompt)
        val encoded = Base64.encodeToString(compress(root.toString().toByteArray(Charsets.UTF_8)), BASE64_FLAGS)
        return CODE_PREFIX + encoded
    }

    @JvmStatic
    fun importFromString(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        val prefixIndex = trimmed.indexOf(CODE_PREFIX, ignoreCase = true)
        if (prefixIndex >= 0) {
            return try {
                val payload = trimmed.substring(prefixIndex + CODE_PREFIX.length)
                    .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                val json = String(inflate(Base64.decode(payload, BASE64_FLAGS)), Charsets.UTF_8)
                importFromJson(json)
            } catch (e: Exception) {
                FileLog.e("VoiceTimecodesConfig code import error: $e")
                false
            }
        }
        return importFromJson(trimmed)
    }

    @JvmStatic
    fun importFromJson(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            val editor = prefs().edit()
            var updated = false

            val mainConfig = root.optJSONObject("prefs")?.optJSONObject("mainconfig")
            if (mainConfig != null) {
                val keys = mainConfig.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in PREF_KEYS) {
                        continue
                    }
                    val item = mainConfig.optJSONObject(key) ?: continue
                    when (item.optString("t")) {
                        "b" -> {
                            editor.putBoolean(key, item.optBoolean("v"))
                            updated = true
                        }
                        "s" -> {
                            editor.putString(key, item.optString("v"))
                            updated = true
                        }
                        "i" -> {
                            editor.putInt(key, item.optInt("v"))
                            updated = true
                        }
                    }
                }
            } else {
                if (root.has("enabled")) {
                    editor.putBoolean(PREF_ENABLED, root.optBoolean("enabled"))
                    updated = true
                }
                if (root.has("stt_url")) {
                    editor.putString(PREF_STT_URL, root.optString("stt_url"))
                    updated = true
                }
                if (root.has("stt_token")) {
                    editor.putString(PREF_STT_TOKEN, root.optString("stt_token"))
                    updated = true
                }
                if (root.has("llm_url")) {
                    editor.putString(PREF_LLM_URL, root.optString("llm_url"))
                    updated = true
                }
                if (root.has("llm_token")) {
                    editor.putString(PREF_LLM_TOKEN, root.optString("llm_token"))
                    updated = true
                }
                if (root.has("llm_model")) {
                    editor.putString(PREF_LLM_MODEL, root.optString("llm_model"))
                    updated = true
                }
                if (root.has("min_duration")) {
                    editor.putInt(PREF_MIN_DURATION, root.optInt("min_duration", DEFAULT_MIN_DURATION))
                    updated = true
                }
                if (root.has("prompt")) {
                    editor.putString(PREF_PROMPT, root.optString("prompt"))
                    updated = true
                }
            }

            if (updated) {
                editor.apply()
            }
            updated
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            deflater.setInput(data)
            deflater.finish()
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            inflater.setInput(data)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    throw IllegalArgumentException("Truncated settings code")
                }
                out.write(buffer, 0, count)
                if (out.size() > MAX_DECODED_SIZE) {
                    throw IllegalArgumentException("Settings code is too large")
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
