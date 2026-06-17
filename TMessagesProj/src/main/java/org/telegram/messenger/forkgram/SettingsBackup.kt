package org.telegram.messenger.forkgram

import android.content.Context

import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.BuildVars

object SettingsBackup {

    private const val MARKER = "forkgram_settings_export"

    private val ALLOWED_PREFS = listOf(
        "mainconfig",
        "themeconfig",
        "lastfm",
        "langconfig",
        "playback_speed",
        "camera",
        "voippipconfig"
    )

    private fun isAllowed(name: String?) = ALLOWED_PREFS.any { it == name }

    @JvmStatic
    @Throws(Exception::class)
    fun export(context: Context): String {
        val root = JSONObject()
        root.put(MARKER, 1)
        root.put("appVersion", BuildVars.BUILD_VERSION_STRING)
        val prefsObject = JSONObject()
        for (name in ALLOWED_PREFS) {
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) {
                continue
            }
            val fileObject = JSONObject()
            for (entry in all.entries) {
                val value = entry.value
                val typed = JSONObject()
                when (value) {
                    is Boolean -> {
                        typed.put("t", "b")
                        typed.put("v", value)
                    }
                    is Int -> {
                        typed.put("t", "i")
                        typed.put("v", value)
                    }
                    is Long -> {
                        typed.put("t", "l")
                        typed.put("v", value)
                    }
                    is Float -> {
                        typed.put("t", "f")
                        typed.put("v", value.toDouble())
                    }
                    is String -> {
                        typed.put("t", "s")
                        typed.put("v", value)
                    }
                    is Set<*> -> {
                        typed.put("t", "ss")
                        val array = JSONArray()
                        for (item in value) {
                            array.put(item.toString())
                        }
                        typed.put("v", array)
                    }
                    else -> continue
                }
                fileObject.put(entry.key, typed)
            }
            prefsObject.put(name, fileObject)
        }
        root.put("prefs", prefsObject)
        return root.toString(2)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun restore(context: Context, json: String): Boolean {
        val root = JSONObject(json)
        if (!root.has(MARKER)) {
            return false
        }
        val prefsObject = root.optJSONObject("prefs") ?: return false
        val fileNames = prefsObject.keys()
        while (fileNames.hasNext()) {
            val name = fileNames.next()
            if (!isAllowed(name)) {
                continue
            }
            val fileObject = prefsObject.getJSONObject(name)
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            val keys = fileObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val typed = fileObject.getJSONObject(key)
                when (typed.getString("t")) {
                    "b" -> editor.putBoolean(key, typed.getBoolean("v"))
                    "i" -> editor.putInt(key, typed.getInt("v"))
                    "l" -> editor.putLong(key, typed.getLong("v"))
                    "f" -> editor.putFloat(key, typed.getDouble("v").toFloat())
                    "s" -> editor.putString(key, typed.getString("v"))
                    "ss" -> {
                        val array = typed.getJSONArray("v")
                        val set = HashSet<String>()
                        for (i in 0 until array.length()) {
                            set.add(array.getString(i))
                        }
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.apply()
        }
        return true
    }
}
