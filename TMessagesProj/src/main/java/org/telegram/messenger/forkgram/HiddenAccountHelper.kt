package org.telegram.messenger.forkgram

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64

import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager

import java.nio.charset.StandardCharsets

object HiddenAccountHelper {

    const val VALIDATE_CODE_OK = 0
    const val VALIDATE_CODE_INVALID = 1
    const val VALIDATE_CODE_DUPLICATE = 2
    const val VALIDATE_CODE_APP_PASSCODE = 3

    private const val PREFS_NAME = "mainconfig"
    private const val KEY_STEALTH_MODE = "fg_hiddenAccountsStealthMode"
    private const val KEY_SETTINGS_ONLY_WHEN_HIDDEN = "fg_hiddenAccountsSettingsOnlyWhenHidden"
    private const val KEY_HASH_PREFIX = "fg_hiddenAccountHash_"
    private const val KEY_SALT_PREFIX = "fg_hiddenAccountSalt_"

    private const val SEARCH_UNLOCK_MAX_FREE_TRIES = 3
    private const val SEARCH_UNLOCK_RETRY_STEP_MS = 5000L
    private const val SEARCH_UNLOCK_MAX_RETRY_MS = 30000L

    private val UNLOCK_CODE_PATTERN = Regex("\\d{4}")

    private var pendingUnlockAccount = -1
    private var unlockedHiddenAccount = -1
    private var searchUnlockBadTries = 0
    private var searchUnlockRetryUntil = 0L

    private val preferences: SharedPreferences
        get() = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getHashKey(account: Int): String {
        return KEY_HASH_PREFIX + account
    }

    private fun getSaltKey(account: Int): String {
        return KEY_SALT_PREFIX + account
    }

    @JvmStatic
    fun canUseHiddenAccounts(): Boolean {
        return SharedConfig.passcodeHash.isNotEmpty() || isStealthModeEnabled()
    }

    @JvmStatic
    fun isStealthModeEnabled(): Boolean {
        if (SharedConfig.passcodeHash.isNotEmpty()) {
            val settings = preferences
            if (settings.getBoolean(KEY_STEALTH_MODE, false)) {
                settings.edit().putBoolean(KEY_STEALTH_MODE, false).apply()
            }
            return false
        }
        return preferences.getBoolean(KEY_STEALTH_MODE, false)
    }

    @JvmStatic
    fun setStealthModeEnabled(enabled: Boolean) {
        var value = enabled
        if (value && SharedConfig.passcodeHash.isNotEmpty()) {
            value = false
        }
        preferences.edit().putBoolean(KEY_STEALTH_MODE, value).apply()
    }

    @JvmStatic
    fun isSettingsVisibleOnlyWhenSignedInAsHiddenAccount(): Boolean {
        return preferences.getBoolean(KEY_SETTINGS_ONLY_WHEN_HIDDEN, false)
    }

    @JvmStatic
    fun setSettingsVisibleOnlyWhenSignedInAsHiddenAccount(enabled: Boolean) {
        var value = enabled
        if (value && !hasAnyHiddenAccounts()) {
            value = false
        }
        preferences.edit().putBoolean(KEY_SETTINGS_ONLY_WHEN_HIDDEN, value).apply()
    }

    @JvmStatic
    fun shouldShowSettingsEntry(currentAccount: Int): Boolean {
        return !isSettingsVisibleOnlyWhenSignedInAsHiddenAccount() || isAccountHidden(currentAccount)
    }

    @JvmStatic
    fun hasHiddenAccount(account: Int): Boolean {
        val settings = preferences
        return settings.contains(getHashKey(account)) && settings.contains(getSaltKey(account))
    }

    @JvmStatic
    fun isAccountHidden(account: Int): Boolean {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && hasHiddenAccount(account)
    }

    @JvmStatic
    fun isVisibleActivatedAccount(account: Int): Boolean {
        return UserConfig.isValidAccount(account) && UserConfig.getInstance(account).isClientActivated() && !isAccountHidden(account)
    }

    @JvmStatic
    fun hasAnyHiddenAccounts(): Boolean {
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a)) {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun getHiddenAccountsCount(): Int {
        var count = 0
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a)) {
                count++
            }
        }
        return count
    }

    @JvmStatic
    fun getVisibleAccountsCount(): Int {
        var count = 0
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (isVisibleActivatedAccount(a)) {
                count++
            }
        }
        return count
    }

    @JvmStatic
    fun getVisibleAccountsCountExcluding(account: Int): Int {
        var count = 0
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a != account && isVisibleActivatedAccount(a)) {
                count++
            }
        }
        return count
    }

    @JvmStatic
    @JvmOverloads
    fun collectVisibleAccountNumbers(out: ArrayList<Int>, excludedAccount: Int = -1, testBackend: Boolean? = null) {
        out.clear()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a == excludedAccount || !isVisibleActivatedAccount(a)) {
                continue
            }
            if (testBackend != null && ConnectionsManager.getInstance(a).isTestBackend() != testBackend) {
                continue
            }
            out.add(a)
        }
        AccountOrder.sort(out)
    }

    @JvmStatic
    fun canHideAccount(account: Int): Boolean {
        return isAccountHidden(account) || getVisibleAccountsCountExcluding(account) > 0
    }

    @JvmStatic
    fun getFallbackVisibleAccount(excludedAccount: Int): Int {
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a != excludedAccount && isVisibleActivatedAccount(a)) {
                return a
            }
        }
        return -1
    }

    @JvmStatic
    fun setUnlockedHiddenAccount(account: Int) {
        synchronized(this) {
            unlockedHiddenAccount = if (isAccountHidden(account)) account else -1
            pendingUnlockAccount = -1
        }
    }

    @JvmStatic
    fun isUnlockedHiddenAccount(account: Int): Boolean = synchronized(this) {
        unlockedHiddenAccount == account && isAccountHidden(account)
    }

    @JvmStatic
    fun clearUnlockedHiddenAccount() {
        synchronized(this) {
            unlockedHiddenAccount = -1
            pendingUnlockAccount = -1
        }
    }

    @JvmStatic
    fun queuePendingUnlock(account: Int) {
        synchronized(this) {
            pendingUnlockAccount = if (isAccountHidden(account)) account else -1
        }
    }

    @JvmStatic
    fun consumePendingUnlockAccount(): Int = synchronized(this) {
        val account = pendingUnlockAccount
        pendingUnlockAccount = -1
        account
    }

    @JvmStatic
    fun validateUnlockCode(account: Int, code: String?): Int {
        if (code == null || !code.matches(UNLOCK_CODE_PATTERN)) {
            return VALIDATE_CODE_INVALID
        }
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a != account && isAccountHidden(a) && checkUnlockCode(a, code)) {
                return VALIDATE_CODE_DUPLICATE
            }
        }
        if (SharedConfig.passcodeHash.isNotEmpty() && SharedConfig.checkPasscode(code)) {
            return VALIDATE_CODE_APP_PASSCODE
        }
        return VALIDATE_CODE_OK
    }

    @JvmStatic
    fun verifyUnlockCode(account: Int, code: String?): Boolean {
        return isAccountHidden(account) && code != null && code.matches(UNLOCK_CODE_PATTERN) && checkUnlockCode(account, code)
    }

    @JvmStatic
    fun setHiddenAccountCode(account: Int, code: String) {
        try {
            val salt = ByteArray(16)
            Utilities.random.nextBytes(salt)
            val editor = preferences.edit()
            editor.putString(getHashKey(account), hashUnlockCode(code, salt))
            editor.putString(getSaltKey(account), Base64.encodeToString(salt, Base64.DEFAULT))
            editor.apply()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    @JvmStatic
    fun removeHiddenAccount(account: Int) {
        var hasOtherHiddenAccounts = false
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (a != account && UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a)) {
                hasOtherHiddenAccounts = true
                break
            }
        }
        val editor = preferences.edit()
        editor.remove(getHashKey(account))
        editor.remove(getSaltKey(account))
        if (!hasOtherHiddenAccounts) {
            editor.putBoolean(KEY_SETTINGS_ONLY_WHEN_HIDDEN, false)
        }
        editor.apply()
        synchronized(this) {
            if (pendingUnlockAccount == account) {
                pendingUnlockAccount = -1
            }
            if (unlockedHiddenAccount == account) {
                unlockedHiddenAccount = -1
            }
        }
    }

    @JvmStatic
    fun clearAccount(account: Int) {
        removeHiddenAccount(account)
    }

    @JvmStatic
    fun findHiddenAccountByCode(code: String?): Int {
        if (code.isNullOrEmpty()) {
            return -1
        }
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a) && checkUnlockCode(a, code)) {
                return a
            }
        }
        return -1
    }

    @JvmStatic
    fun prepareHiddenUnlockFromPasscode(code: String?): Int {
        val account = findHiddenAccountByCode(code)
        if (account >= 0) {
            queuePendingUnlock(account)
        }
        return account
    }

    @JvmStatic
    fun tryUnlockFromSearch(code: String?): Int {
        if (!shouldUseSearchUnlock()) {
            return -1
        }
        val now = SystemClock.elapsedRealtime()
        if (searchUnlockRetryUntil > now) {
            return -1
        }
        val account = findHiddenAccountByCode(code)
        if (account >= 0) {
            searchUnlockBadTries = 0
            searchUnlockRetryUntil = 0L
            setUnlockedHiddenAccount(account)
            return account
        }
        if (code != null && code.matches(UNLOCK_CODE_PATTERN)) {
            searchUnlockBadTries++
            if (searchUnlockBadTries >= SEARCH_UNLOCK_MAX_FREE_TRIES) {
                val retryMs = minOf((searchUnlockBadTries - SEARCH_UNLOCK_MAX_FREE_TRIES + 1L) * SEARCH_UNLOCK_RETRY_STEP_MS, SEARCH_UNLOCK_MAX_RETRY_MS)
                searchUnlockRetryUntil = now + retryMs
            }
        }
        return -1
    }

    @JvmStatic
    fun shouldUseSearchUnlock(): Boolean {
        return SharedConfig.passcodeHash.isEmpty() && isStealthModeEnabled() && hasAnyHiddenAccounts()
    }

    private fun checkUnlockCode(account: Int, code: String): Boolean {
        val settings = preferences
        val hash = settings.getString(getHashKey(account), "").orEmpty()
        val saltString = settings.getString(getSaltKey(account), "").orEmpty()
        if (hash.isEmpty() || saltString.isEmpty()) {
            return false
        }
        return try {
            val salt = Base64.decode(saltString, Base64.DEFAULT)
            hash == hashUnlockCode(code, salt)
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    private fun hashUnlockCode(code: String, salt: ByteArray): String {
        val codeBytes = code.toByteArray(StandardCharsets.UTF_8)
        val bytes = ByteArray(32 + codeBytes.size)
        System.arraycopy(salt, 0, bytes, 0, 16)
        System.arraycopy(codeBytes, 0, bytes, 16, codeBytes.size)
        System.arraycopy(salt, 0, bytes, codeBytes.size + 16, 16)
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.size.toLong()))
    }
}
