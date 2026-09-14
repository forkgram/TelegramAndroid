package org.telegram.messenger.forkgram

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities

object ForkSettingsLock {

    private const val PREFS_NAME = "mainconfig"
    private const val KEY_HASH = "fg_forkSettingsLockHash"
    private const val KEY_SALT = "fg_forkSettingsLockSalt"
    private const val SALT_SIZE = 16

    private val CODE_PATTERN = Regex("\\d{4}")

    @JvmStatic
    fun hasCode(): Boolean {
        val preferences = preferences()
        return preferences.contains(KEY_HASH) && preferences.contains(KEY_SALT)
    }

    @JvmStatic
    fun isValidCode(code: String?): Boolean {
        return code != null && CODE_PATTERN.matches(code)
    }

    @JvmStatic
    fun checkCode(code: String?): Boolean {
        if (code == null || !isValidCode(code)) {
            return false
        }
        val preferences = preferences()
        val hash = preferences.getString(KEY_HASH, "").orEmpty()
        val saltString = preferences.getString(KEY_SALT, "").orEmpty()
        if (hash.isEmpty() || saltString.isEmpty()) {
            return false
        }
        return try {
            val salt = Base64.decode(saltString, Base64.DEFAULT)
            hash == computeHash(code, salt)
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    @JvmStatic
    fun setCode(code: String?) {
        if (code == null) {
            return
        }
        try {
            val salt = ByteArray(SALT_SIZE)
            Utilities.random.nextBytes(salt)
            val editor = preferences().edit()
            editor.putString(KEY_HASH, computeHash(code, salt))
            editor.putString(KEY_SALT, Base64.encodeToString(salt, Base64.DEFAULT))
            editor.commit()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun removeCode() {
        val editor = preferences().edit()
        editor.remove(KEY_HASH)
        editor.remove(KEY_SALT)
        editor.commit()
    }

    private fun preferences(): SharedPreferences {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun computeHash(code: String, salt: ByteArray): String {
        val codeBytes = code.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(SALT_SIZE * 2 + codeBytes.size)
        System.arraycopy(salt, 0, bytes, 0, SALT_SIZE)
        System.arraycopy(codeBytes, 0, bytes, SALT_SIZE, codeBytes.size)
        System.arraycopy(salt, 0, bytes, codeBytes.size + SALT_SIZE, SALT_SIZE)
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.size.toLong()))
    }
}
