package org.telegram.messenger.forkgram

import android.net.Uri
import android.text.TextUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.ForkSettingsActivity
import org.telegram.ui.LaunchActivity

/**
 * Programmatic control of Fork Settings, invoked via the tg://forksettings
 * deep link from LaunchActivity (the agent-testing sibling of [GoTo]):
 *   tg://forksettings                      - open Fork Settings
 *   tg://forksettings?item=hideInAppHints  - open and highlight one option
 *   tg://forksettings?set=hideInAppHints   - flip a boolean option headlessly
 *   tg://forksettings?set=syncPins&value=false - set an option to a value
 *   tg://forksettings?get=syncPins         - dump one option to the dump file
 *   tg://forksettings?list=1               - dump every option to the dump file
 * Machine-readable answers go to the dump file rewritten by every get/list:
 *   adb shell cat /sdcard/Android/data/<applicationId>/files/fork_settings.txt
 * (the test build's external files dir, readable by adb shell without root).
 * Open/highlight works on any build; set/get/list only on the test builds
 * (applicationId containing ".test" - the forkTest ones the automation uses),
 * so a tapped web link can never silently rewrite settings in the daily build.
 * The side effects of the toggles live in [applyEffect], which the
 * Fork Settings UI calls too - one source of truth for both paths.
 */
object ForkSettings {

    private const val DUMP_FILE_NAME = "fork_settings.txt"

    private val TEST_BUILD: Boolean
        get() = ApplicationLoader.applicationContext?.packageName?.contains(".test") == true

    private fun prefs() = MessagesController.getGlobalMainSettings()

    private fun parseBool(raw: String): Boolean? = when (raw.lowercase()) {
        "true", "1", "on" -> true
        "false", "0", "off" -> false
        else -> null
    }

    private abstract class Option(val key: String, val itemId: Int) {
        abstract fun read(): Any
        abstract fun write(account: Int, raw: String): Any
        open fun apply(account: Int, value: Boolean): Any =
            throw IllegalArgumentException("boolean value required")
    }

    private class BoolOption(
        key: String,
        itemId: Int,
        val default: Boolean,
        val readFn: (() -> Boolean)? = null,
        val writeFn: ((Boolean) -> Unit)? = null,
        val guard: ((Boolean) -> String?)? = null,
    ) : Option(key, itemId) {
        override fun read(): Boolean = readFn?.invoke() ?: prefs().getBoolean(key, default)
        override fun write(account: Int, raw: String): Any {
            val value = parseBool(raw) ?: throw IllegalArgumentException("expected true/false, got \"$raw\"")
            return apply(account, value)
        }

        override fun apply(account: Int, value: Boolean): Any {
            guard?.invoke(value)?.let { reason -> throw IllegalStateException(reason) }
            if (writeFn != null) {
                writeFn(value)
            } else {
                prefs().edit().putBoolean(key, value).commit()
            }
            return value
        }
    }

    private class IntOption(
        key: String,
        itemId: Int,
        val default: Int,
        val readFn: (() -> Int)? = null,
        val writeFn: ((Int) -> Unit)? = null,
        val range: IntRange? = null,
    ) : Option(key, itemId) {
        override fun read(): Int = readFn?.invoke() ?: prefs().getInt(key, default)
        override fun write(account: Int, raw: String): Any {
            val value = raw.toIntOrNull() ?: throw IllegalArgumentException("expected an integer, got \"$raw\"")
            if (range != null && value !in range) {
                throw IllegalArgumentException("expected $range, got $value")
            }
            if (writeFn != null) {
                writeFn(value)
            } else {
                prefs().edit().putInt(key, value).commit()
            }
            return value
        }
    }

    private class FloatOption(
        key: String,
        itemId: Int,
        val default: Float,
        val readFn: (() -> Float)? = null,
    ) : Option(key, itemId) {
        override fun read(): Float = readFn?.invoke() ?: prefs().getFloat(key, default)
        override fun write(account: Int, raw: String): Any {
            val value = raw.toFloatOrNull() ?: throw IllegalArgumentException("expected a number, got \"$raw\"")
            prefs().edit().putFloat(key, value).commit()
            return value
        }
    }

    private class StringOption(
        key: String,
        itemId: Int,
        val default: String,
        val readFn: (() -> String)? = null,
        val writeFn: ((String) -> String)? = null,
    ) : Option(key, itemId) {
        override fun read(): String = readFn?.invoke() ?: prefs().getString(key, default) ?: default
        override fun write(account: Int, raw: String): Any {
            val value = writeFn?.invoke(raw) ?: raw
            prefs().edit().putString(key, value).commit()
            return value
        }
    }

    private val OPTIONS: Map<String, Option> = listOf(
        BoolOption("hideSensitiveData", ForkSettingsActivity.ID_HIDE_SENSITIVE_DATA, false),
        BoolOption("hideSensitivePhone", ForkSettingsActivity.ID_HIDE_SENSITIVE_PHONE, true),
        BoolOption("hideSensitiveUsername", ForkSettingsActivity.ID_HIDE_SENSITIVE_USERNAME, true),
        BoolOption("hideSensitiveBio", ForkSettingsActivity.ID_HIDE_SENSITIVE_BIO, true),
        BoolOption("hideSensitiveId", ForkSettingsActivity.ID_HIDE_SENSITIVE_ID, true),
        BoolOption("forceBlockScreenshots", ForkSettingsActivity.ID_FORCE_BLOCK_SCREENSHOTS, false),
        BoolOption("showNotificationContent", ForkSettingsActivity.ID_SHOW_NOTIFICATION_CONTENT, false),
        BoolOption("dropScreenshotCaption", ForkSettingsActivity.ID_DROP_SCREENSHOT_CAPTION, true),

        BoolOption("hideInAppHints", ForkSettingsActivity.ID_HIDE_IN_APP_HINTS, false),
        BoolOption("hideBottomButton", ForkSettingsActivity.ID_HIDE_BOTTOM_BUTTON, false),
        StringOption(
            "forkCustomTitle", ForkSettingsActivity.ID_CUSTOM_TITLE, "Fork Client",
            writeFn = { raw -> raw.trim().ifEmpty { "Fork Client" } }),
        IntOption("avatarCorners", ForkSettingsActivity.ID_AVATAR_CORNERS, 0, readFn = AndroidUtilities::avatarCornersType),

        BoolOption("syncPins", ForkSettingsActivity.ID_SYNC_PINS, true),
        BoolOption("unmutedOnTop", ForkSettingsActivity.ID_UNMUTED_ON_TOP, false),
        BoolOption("openArchiveOnPull", ForkSettingsActivity.ID_OPEN_ARCHIVE_ON_PULL, true),
        BoolOption("hideStoriesInArchive", ForkSettingsActivity.ID_HIDE_STORIES_IN_ARCHIVE, false),
        BoolOption("disableThumbsInDialogList", ForkSettingsActivity.ID_DISABLE_THUMBS_IN_DIALOG_LIST, false),
        BoolOption("disableGlobalSearch", ForkSettingsActivity.ID_DISABLE_GLOBAL_SEARCH, false),
        BoolOption("hideContactsInDialogs", ForkSettingsActivity.ID_HIDE_CONTACTS_IN_DIALOGS, false),
        BoolOption("enableLastSeenDots", ForkSettingsActivity.ID_ENABLE_LAST_SEEN_DOTS, true),
        BoolOption("hideAllChatsTab", ForkSettingsActivity.ID_HIDE_ALL_CHATS_TAB, false),
        IntOption("folderTabsStyle", ForkSettingsActivity.ID_FOLDER_TABS_STYLE, FolderIcons.FOLDER_TABS_STYLE_TEXT, readFn = FolderIcons::folderTabsStyle),

        BoolOption("replaceForward", ForkSettingsActivity.ID_REPLACE_FORWARD, true),
        BoolOption("mentionByName", ForkSettingsActivity.ID_MENTION_BY_NAME, false),
        BoolOption("hideSendAs", ForkSettingsActivity.ID_HIDE_SEND_AS, false),
        BoolOption("disableLinkPreviewByDefault", ForkSettingsActivity.ID_DISABLE_LINK_PREVIEW_BY_DEFAULT, false),
        BoolOption("addItemToDeleteAllUnpinnedMessages", ForkSettingsActivity.ID_DELETE_ALL_UNPINNED, false),
        BoolOption("disableSlideToNextChannel", ForkSettingsActivity.ID_DISABLE_SLIDE_TO_NEXT_CHANNEL, false),
        BoolOption("formatWithSeconds", ForkSettingsActivity.ID_FORMAT_WITH_SECONDS, false),
        BoolOption("hideAiEditor", ForkSettingsActivity.ID_HIDE_AI_EDITOR, false),

        BoolOption("disableQuickReaction", ForkSettingsActivity.ID_DISABLE_QUICK_REACTION, false),
        BoolOption("hideMessageReactions", ForkSettingsActivity.ID_HIDE_MESSAGE_REACTIONS, false),
        BoolOption("hideSavedMessagesTags", ForkSettingsActivity.ID_HIDE_SAVED_MESSAGES_TAGS, false),
        BoolOption("disableLockedAnimatedEmoji", ForkSettingsActivity.ID_DISABLE_LOCKED_ANIMATED_EMOJI, false),

        BoolOption("fullRecentStickers", ForkSettingsActivity.ID_FULL_RECENT_STICKERS, true),
        BoolOption("showArchivedStickers", ForkSettingsActivity.ID_SHOW_ARCHIVED_STICKERS, false),
        FloatOption("stickerSize", ForkSettingsActivity.ID_STICKER_SIZE, ChatMessageCell.MAX_STICKER_SIZE),

        BoolOption(
            "inappCamera", ForkSettingsActivity.ID_INAPP_CAMERA, true,
            readFn = { SharedConfig.inappCamera },
            writeFn = { value ->
                SharedConfig.inappCamera = value
                prefs().edit().putBoolean("inappCamera", value).commit()
            }),
        BoolOption(
            "systemCamera", ForkSettingsActivity.ID_SYSTEM_CAMERA, false,
            guard = { value ->
                if (value && !SharedConfig.inappCamera) "requires inappCamera" else null
            }),
        BoolOption("photoHasSticker", ForkSettingsActivity.ID_PHOTO_HAS_STICKER, true),
        BoolOption("disableMotionPhoto", ForkSettingsActivity.ID_DISABLE_MOTION_PHOTO, false),
        BoolOption("disableFlipPhotos", ForkSettingsActivity.ID_DISABLE_FLIP_PHOTOS, false),
        BoolOption("rearVideoMessages", ForkSettingsActivity.ID_REAR_VIDEO_MESSAGES, false),
        BoolOption("disablePlayVisibleVideoOnVolume", ForkSettingsActivity.ID_DISABLE_PLAY_VISIBLE_VIDEO_ON_VOLUME, false),
        BoolOption("disableRecentFilesAttachment", ForkSettingsActivity.ID_DISABLE_RECENT_FILES_ATTACHMENT, false),

        BoolOption("disableAutoplayNextVoice", ForkSettingsActivity.ID_DISABLE_AUTOPLAY_NEXT_VOICE, false),
        BoolOption(VoiceFileSize.PREF_ENABLED, ForkSettingsActivity.ID_VOICE_FILE_SIZE, false),
        IntOption("voiceQualityBitrate", ForkSettingsActivity.ID_VOICE_QUALITY, -1),
        IntOption(
            "translationProvider", ForkSettingsActivity.ID_TRANSLATION_PROVIDER, ForkOfflineTranslate.PROVIDER_DEFAULT,
            readFn = ForkOfflineTranslate::provider,
            range = ForkOfflineTranslate.PROVIDER_DEFAULT..ForkOfflineTranslate.PROVIDER_OFFLINE),
        BoolOption(
            "cfEnableStt", ForkSettingsActivity.ID_CLOUDFLARE_ENABLE_STT, false,
            readFn = { SharedConfig.cfEnableStt },
            writeFn = { value ->
                SharedConfig.cfEnableStt = value
                SharedConfig.saveConfig()
            },
            guard = { value ->
                if (value && (TextUtils.isEmpty(SharedConfig.cfAccountID) || TextUtils.isEmpty(SharedConfig.cfApiToken))) {
                    "credentials required"
                } else {
                    null
                }
            }),

        BoolOption("botSkipShare", ForkSettingsActivity.ID_BOT_SKIP_SHARE, false),
        BoolOption("botSkipFullscreen", ForkSettingsActivity.ID_BOT_SKIP_FULLSCREEN, false),
        BoolOption("disableParametersFromBotLinks", ForkSettingsActivity.ID_DISABLE_PARAMETERS_FROM_BOT_LINKS, false),
        BoolOption("disableDefaultInAppBrowser", ForkSettingsActivity.ID_DISABLE_DEFAULT_IN_APP_BROWSER, BuildConfig.SKIP_INTERNAL_BROWSER_BY_DEFAULT),
        BoolOption("quickRepeatButton", ForkSettingsActivity.ID_QUICK_REPEAT, false),

        BoolOption("webSocketTransport", ForkSettingsActivity.ID_WEBSOCKET_TRANSPORT, false),
        StringOption(
            "webSocketDomain", ForkSettingsActivity.ID_WEBSOCKET_DOMAIN, "",
            writeFn = { raw ->
                val domain = ConnectionsManager.normalizeWebSocketDomain(raw)
                if (domain.isEmpty() && raw.trim().isNotEmpty()) {
                    throw IllegalArgumentException("invalid domain \"$raw\"")
                }
                domain
            }),
        BoolOption("satelliteDataSaving", ForkSettingsActivity.ID_SATELLITE_DATA_SAVING, true),
        BoolOption("lockPremium", ForkSettingsActivity.ID_LOCK_PREMIUM, false),
    ).associateBy { it.key }

    private val NAV_ITEMS: Map<String, Int> = mapOf(
        "hiddenAccounts" to ForkSettingsActivity.ID_HIDDEN_ACCOUNTS,
        "forkSettingsLock" to ForkSettingsActivity.ID_FORK_SETTINGS_LOCK,
        "defaultFolder" to ForkSettingsActivity.ID_DEFAULT_FOLDER,
        "formattingMenu" to ForkSettingsActivity.ID_FORMATTING_MENU,
        "linkReplacements" to ForkSettingsActivity.ID_LINK_REPLACEMENTS,
        "quickAttach" to ForkSettingsActivity.ID_QUICK_ATTACH,
        "offlineStt" to ForkSettingsActivity.ID_OFFLINE_STT,
        "cloudflareCredentials" to ForkSettingsActivity.ID_CLOUDFLARE_CREDENTIALS,
        "unifiedPush" to ForkSettingsActivity.ID_UNIFIED_PUSH,
        "updateCheckInterval" to ForkSettingsActivity.ID_UPDATE_CHECK_INTERVAL,
        "disableTabletMode" to ForkSettingsActivity.ID_DISABLE_TABLET_MODE,
        "webPublish" to ForkSettingsActivity.ID_WEB_PUBLISH,
        "voiceTimecodes" to ForkSettingsActivity.ID_VOICE_TIMECODES,
        "lastfm" to ForkSettingsActivity.ID_LASTFM_LOGIN,
        "exportSettings" to ForkSettingsActivity.ID_EXPORT_SETTINGS,
        "importSettings" to ForkSettingsActivity.ID_IMPORT_SETTINGS,
        "resetLauncherShortcuts" to ForkSettingsActivity.ID_RESET_LAUNCHER_SHORTCUTS,
    )

    @JvmStatic
    fun handleDeepLink(account: Int, uri: Uri): Boolean {
        val setKey = uri.getQueryParameter("set")?.trim()
        val itemKey = uri.getQueryParameter("item")?.trim()
        val getKey = uri.getQueryParameter("get")?.trim()
        val wantsList = uri.getQueryParameter("list") != null
        val openAfterSet = uri.getBooleanQueryParameter("open", false)

        if (setKey != null) {
            setOption(account, setKey, uri.getQueryParameter("value"))
            if (openAfterSet) {
                open(account, setKey)
            }
        }
        if (getKey != null) {
            dump(account, getKey)
        }
        if (wantsList) {
            dump(account, null)
        }
        if (itemKey != null) {
            open(account, itemKey)
        }
        if (setKey == null && getKey == null && !wantsList && itemKey == null) {
            open(account, null)
        }
        return true
    }

    @JvmStatic
    fun open(account: Int, itemKey: String?): Boolean {
        var itemId = 0
        if (itemKey != null) {
            itemId = resolveItemId(itemKey)
            if (itemId == 0) {
                FileLog.w("ForkSettings: open $itemKey failed: unknown option")
                showErrorBulletin("Unknown fork setting: $itemKey")
            }
        }
        FileLog.d("ForkSettings: open${if (itemKey != null && itemId != 0) " item=$itemKey" else ""}")

        val last = LaunchActivity.getSafeLastFragment()
        if (last is ForkSettingsActivity && itemId != 0) {
            last.rehighlight(itemId)
            return true
        }
        val settings = if (itemId != 0) ForkSettingsActivity().highlight(itemId) else ForkSettingsActivity()
        if (last != null) {
            return last.presentFragment(settings)
        }
        val activity = LaunchActivity.instance ?: run {
            FileLog.w("ForkSettings: no activity to open the screen")
            return false
        }
        activity.presentFragment(settings)
        return true
    }

    @JvmStatic
    fun setOption(account: Int, key: String, rawValue: String?): Boolean {
        if (!TEST_BUILD) {
            FileLog.w("ForkSettings: set $key rejected on a non-test build")
            open(account, null)
            return false
        }
        val option = OPTIONS[key] ?: run {
            FileLog.w("ForkSettings: set $key failed: unknown option")
            showErrorBulletin("Unknown fork setting: $key")
            return false
        }
        val value = try {
            if (rawValue == null && option is BoolOption) {
                option.apply(account, !option.read())
            } else {
                option.write(account, rawValue ?: throw IllegalArgumentException("missing value"))
            }
        } catch (e: Exception) {
            FileLog.w("ForkSettings: set $key=$rawValue failed: ${e.message}")
            showErrorBulletin("Invalid value for fork setting $key: ${e.message ?: "invalid"}")
            return false
        }
        FileLog.d("ForkSettings: set $key=$value")
        applyEffect(account, key, value)
        showDoneBulletin("Fork setting applied: $key = $value")
        return true
    }

    @JvmStatic
    fun dump(account: Int, filter: String?): Boolean {
        if (!TEST_BUILD) {
            FileLog.w("ForkSettings: dump rejected on a non-test build")
            return false
        }
        val single = filter?.let { OPTIONS[it] }
        if (filter != null && single == null) {
            FileLog.w("ForkSettings: get $filter failed: unknown option")
            showErrorBulletin("Unknown fork setting: $filter")
            return false
        }
        val options: Collection<Option> = single?.let { listOf(it) } ?: OPTIONS.values
        val builder = StringBuilder()
        builder.append("# ForkSettings dump, filter=").append(filter ?: "all").append('\n')
        for (option in options) {
            val value = try {
                option.read()
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            builder.append(option.key).append('=').append(value).append('\n')
        }
        val context = ApplicationLoader.applicationContext ?: run {
            FileLog.w("ForkSettings: no context to write the dump")
            return false
        }
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            java.io.File(dir, DUMP_FILE_NAME).writeText(builder.toString())
            FileLog.d("ForkSettings: dumped ${options.size} option(s) to $DUMP_FILE_NAME" +
                (if (filter != null) " (filter=$filter)" else ""))
            true
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    @JvmStatic
    fun applyEffect(account: Int, key: String, value: Any?) {
        when (key) {
            "forceBlockScreenshots" ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetPasscode, false)
            "unmutedOnTop" ->
                MessagesController.getInstance(account).sortDialogs(null)
            "hideAllChatsTab", "folderTabsStyle" ->
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
            "showArchivedStickers" ->
                if (value == true) {
                    MediaDataController.getInstance(account).loadArchivedStickerSets()
                }
            "webSocketTransport", "webSocketDomain" ->
                ConnectionsManager.setWebSocketEnabled(
                    prefs().getBoolean("webSocketTransport", false),
                    prefs().getString("webSocketDomain", ""))
            "satelliteDataSaving" ->
                ApplicationLoader.setSatelliteDataSavingEnabled(value == true)
            "quickRepeatButton" ->
                QuickRepeat.invalidate()
        }
    }

    private fun resolveItemId(key: String): Int {
        return OPTIONS[key]?.itemId ?: NAV_ITEMS[key] ?: 0
    }

    private fun showDoneBulletin(text: String) {
        val fragment = LaunchActivity.getSafeLastFragment() ?: return
        if (BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, text).show()
        }
    }

    private fun showErrorBulletin(text: String) {
        val fragment = LaunchActivity.getSafeLastFragment() ?: return
        BulletinFactory.of(fragment).createErrorBulletin(text).show()
    }
}
