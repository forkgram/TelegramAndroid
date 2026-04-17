package org.telegram.messenger.forkgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class HiddenAccountHelper {

    public static final int VALIDATE_CODE_OK = 0;
    public static final int VALIDATE_CODE_INVALID = 1;
    public static final int VALIDATE_CODE_DUPLICATE = 2;
    public static final int VALIDATE_CODE_APP_PASSCODE = 3;

    private static final String PREFS_NAME = "mainconfig";
    private static final String KEY_STEALTH_MODE = "fg_hiddenAccountsStealthMode";
    private static final String KEY_SETTINGS_ONLY_WHEN_HIDDEN = "fg_hiddenAccountsSettingsOnlyWhenHidden";
    private static final String KEY_HASH_PREFIX = "fg_hiddenAccountHash_";
    private static final String KEY_SALT_PREFIX = "fg_hiddenAccountSalt_";

    private static final int SEARCH_UNLOCK_MAX_FREE_TRIES = 3;
    private static final long SEARCH_UNLOCK_RETRY_STEP_MS = 5000L;
    private static final long SEARCH_UNLOCK_MAX_RETRY_MS = 30000L;

    private static int pendingUnlockAccount = -1;
    private static int unlockedHiddenAccount = -1;
    private static int searchUnlockBadTries;
    private static long searchUnlockRetryUntil;

    private HiddenAccountHelper() {
    }

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String getHashKey(int account) {
        return KEY_HASH_PREFIX + account;
    }

    private static String getSaltKey(int account) {
        return KEY_SALT_PREFIX + account;
    }

    public static boolean canUseHiddenAccounts() {
        return !SharedConfig.passcodeHash.isEmpty() || isStealthModeEnabled();
    }

    public static boolean isStealthModeEnabled() {
        if (!SharedConfig.passcodeHash.isEmpty()) {
            SharedPreferences preferences = getPreferences();
            if (preferences.getBoolean(KEY_STEALTH_MODE, false)) {
                preferences.edit().putBoolean(KEY_STEALTH_MODE, false).apply();
            }
            return false;
        }
        return getPreferences().getBoolean(KEY_STEALTH_MODE, false);
    }

    public static void setStealthModeEnabled(boolean enabled) {
        if (enabled && !SharedConfig.passcodeHash.isEmpty()) {
            enabled = false;
        }
        getPreferences().edit().putBoolean(KEY_STEALTH_MODE, enabled).apply();
    }

    public static boolean isSettingsVisibleOnlyWhenSignedInAsHiddenAccount() {
        return getPreferences().getBoolean(KEY_SETTINGS_ONLY_WHEN_HIDDEN, false);
    }

    public static void setSettingsVisibleOnlyWhenSignedInAsHiddenAccount(boolean enabled) {
        if (enabled && !hasAnyHiddenAccounts()) {
            enabled = false;
        }
        getPreferences().edit().putBoolean(KEY_SETTINGS_ONLY_WHEN_HIDDEN, enabled).apply();
    }

    public static boolean shouldShowSettingsEntry(int currentAccount) {
        return !isSettingsVisibleOnlyWhenSignedInAsHiddenAccount() || isAccountHidden(currentAccount);
    }

    public static boolean hasHiddenAccount(int account) {
        SharedPreferences preferences = getPreferences();
        return preferences.contains(getHashKey(account)) && preferences.contains(getSaltKey(account));
    }

    public static boolean isAccountHidden(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && hasHiddenAccount(account);
    }

    public static boolean isVisibleActivatedAccount(int account) {
        return UserConfig.isValidAccount(account) && UserConfig.getInstance(account).isClientActivated() && !isAccountHidden(account);
    }

    public static boolean hasAnyHiddenAccounts() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a)) {
                return true;
            }
        }
        return false;
    }

    public static int getHiddenAccountsCount() {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a)) {
                count++;
            }
        }
        return count;
    }

    public static int getVisibleAccountsCount() {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (isVisibleActivatedAccount(a)) {
                count++;
            }
        }
        return count;
    }

    public static int getVisibleAccountsCountExcluding(int account) {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != account && isVisibleActivatedAccount(a)) {
                count++;
            }
        }
        return count;
    }

    public static void collectVisibleAccountNumbers(ArrayList<Integer> out) {
        collectVisibleAccountNumbers(out, -1, null);
    }

    public static void collectVisibleAccountNumbers(ArrayList<Integer> out, int excludedAccount) {
        collectVisibleAccountNumbers(out, excludedAccount, null);
    }

    public static void collectVisibleAccountNumbers(ArrayList<Integer> out, int excludedAccount, Boolean testBackend) {
        out.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a == excludedAccount || !isVisibleActivatedAccount(a)) {
                continue;
            }
            if (testBackend != null && ConnectionsManager.getInstance(a).isTestBackend() != testBackend) {
                continue;
            }
            out.add(a);
        }
        out.sort((o1, o2) -> Long.compare(UserConfig.getInstance(o1).loginTime, UserConfig.getInstance(o2).loginTime));
    }

    public static boolean canHideAccount(int account) {
        return isAccountHidden(account) || getVisibleAccountsCountExcluding(account) > 0;
    }

    public static int getFallbackVisibleAccount(int excludedAccount) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != excludedAccount && isVisibleActivatedAccount(a)) {
                return a;
            }
        }
        return -1;
    }

    public static synchronized void setUnlockedHiddenAccount(int account) {
        unlockedHiddenAccount = isAccountHidden(account) ? account : -1;
        pendingUnlockAccount = -1;
    }

    public static synchronized boolean isUnlockedHiddenAccount(int account) {
        return unlockedHiddenAccount == account && isAccountHidden(account);
    }

    public static synchronized void clearUnlockedHiddenAccount() {
        unlockedHiddenAccount = -1;
        pendingUnlockAccount = -1;
    }

    public static synchronized void queuePendingUnlock(int account) {
        pendingUnlockAccount = isAccountHidden(account) ? account : -1;
    }

    public static synchronized int consumePendingUnlockAccount() {
        int account = pendingUnlockAccount;
        pendingUnlockAccount = -1;
        return account;
    }

    public static int validateUnlockCode(int account, String code) {
        if (code == null || !code.matches("\\d{4}")) {
            return VALIDATE_CODE_INVALID;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != account && isAccountHidden(a) && checkUnlockCode(a, code)) {
                return VALIDATE_CODE_DUPLICATE;
            }
        }
        if (!SharedConfig.passcodeHash.isEmpty() && SharedConfig.checkPasscode(code)) {
            return VALIDATE_CODE_APP_PASSCODE;
        }
        return VALIDATE_CODE_OK;
    }

    public static boolean verifyUnlockCode(int account, String code) {
        return isAccountHidden(account) && code != null && code.matches("\\d{4}") && checkUnlockCode(account, code);
    }

    public static void setHiddenAccountCode(int account, String code) {
        try {
            byte[] salt = new byte[16];
            Utilities.random.nextBytes(salt);
            SharedPreferences.Editor editor = getPreferences().edit();
            editor.putString(getHashKey(account), hashCode(code, salt));
            editor.putString(getSaltKey(account), Base64.encodeToString(salt, Base64.DEFAULT));
            editor.apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void removeHiddenAccount(int account) {
        boolean hasOtherHiddenAccounts = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != account && UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a)) {
                hasOtherHiddenAccounts = true;
                break;
            }
        }
        SharedPreferences.Editor editor = getPreferences().edit();
        editor.remove(getHashKey(account));
        editor.remove(getSaltKey(account));
        if (!hasOtherHiddenAccounts) {
            editor.putBoolean(KEY_SETTINGS_ONLY_WHEN_HIDDEN, false);
        }
        editor.apply();
        synchronized (HiddenAccountHelper.class) {
            if (pendingUnlockAccount == account) {
                pendingUnlockAccount = -1;
            }
            if (unlockedHiddenAccount == account) {
                unlockedHiddenAccount = -1;
            }
        }
    }

    public static void clearAccount(int account) {
        removeHiddenAccount(account);
    }

    public static int findHiddenAccountByCode(String code) {
        if (code == null || code.isEmpty()) {
            return -1;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && isAccountHidden(a) && checkUnlockCode(a, code)) {
                return a;
            }
        }
        return -1;
    }

    public static int prepareHiddenUnlockFromPasscode(String code) {
        int account = findHiddenAccountByCode(code);
        if (account >= 0) {
            queuePendingUnlock(account);
        }
        return account;
    }

    public static int tryUnlockFromSearch(String code) {
        if (!shouldUseSearchUnlock()) {
            return -1;
        }
        long now = SystemClock.elapsedRealtime();
        if (searchUnlockRetryUntil > now) {
            return -1;
        }
        int account = findHiddenAccountByCode(code);
        if (account >= 0) {
            searchUnlockBadTries = 0;
            searchUnlockRetryUntil = 0L;
            setUnlockedHiddenAccount(account);
            return account;
        }
        if (code != null && code.matches("\\d{4}")) {
            searchUnlockBadTries++;
            if (searchUnlockBadTries >= SEARCH_UNLOCK_MAX_FREE_TRIES) {
                long retryMs = Math.min((searchUnlockBadTries - SEARCH_UNLOCK_MAX_FREE_TRIES + 1L) * SEARCH_UNLOCK_RETRY_STEP_MS, SEARCH_UNLOCK_MAX_RETRY_MS);
                searchUnlockRetryUntil = now + retryMs;
            }
        }
        return -1;
    }

    public static boolean shouldUseSearchUnlock() {
        return SharedConfig.passcodeHash.isEmpty() && isStealthModeEnabled() && hasAnyHiddenAccounts();
    }

    private static boolean checkUnlockCode(int account, String code) {
        SharedPreferences preferences = getPreferences();
        String hash = preferences.getString(getHashKey(account), "");
        String saltString = preferences.getString(getSaltKey(account), "");
        if (hash.isEmpty() || saltString.isEmpty()) {
            return false;
        }
        try {
            byte[] salt = Base64.decode(saltString, Base64.DEFAULT);
            return hash.equals(hashCode(code, salt));
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static String hashCode(String code, byte[] salt) throws Exception {
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[32 + codeBytes.length];
        System.arraycopy(salt, 0, bytes, 0, 16);
        System.arraycopy(codeBytes, 0, bytes, 16, codeBytes.length);
        System.arraycopy(salt, 0, bytes, codeBytes.length + 16, 16);
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
    }
}
