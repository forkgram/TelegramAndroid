package org.telegram.ui;

import android.content.SharedPreferences;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;

public enum ForkSetting {
    HIDE_SENSITIVE_DATA("hideSensitiveData", false),
    SQUARE_AVATARS("squareAvatars", false),
    PHOTO_HAS_STICKER("photoHasSticker", true),
    SHOW_NOTIFICATION_CONTENT("showNotificationContent", true),
    UNMUTED_ON_TOP("unmutedOnTop", false),
    REAR_VIDEO_MESSAGES("rearVideoMessages", false),
    REPLACE_FORWARD("replaceForward", false),
    MENTION_BY_NAME("mentionByName", false),
    OPEN_ARCHIVE_ON_PULL("openArchiveOnPull", false),
    HIDE_BOTTOM_BUTTON("hideBottomButton", false),
    DISABLE_FLIP_PHOTOS("disableFlipPhotos", false),
    FORMAT_WITH_SECONDS("formatWithSeconds", false),
    DISABLE_THUMBS_IN_DIALOG_LIST("disableThumbsInDialogList", false),
    DISABLE_GLOBAL_SEARCH("disableGlobalSearch", false),
    CUSTOM_TITLE("customTitle", false),
    FULL_RECENT_STICKERS("fullRecentStickers", false),
    HIDE_SEND_AS("hideSendAs", false),
    DISABLE_QUICK_REACTION("disableQuickReaction", false),
    DISABLE_LOCKED_ANIMATED_EMOJI("disableLockedAnimatedEmoji", false),
    DISABLE_PARAMETERS_FROM_BOT_LINKS("disableParametersFromBotLinks", false),
    LOCK_PREMIUM("lockPremium", false),
    ADD_ITEM_TO_DELETE_ALL_UNPINNED_MESSAGES("addItemToDeleteAllUnpinnedMessages", false),
    LARGE_PHOTO("largePhoto", false),
    DISABLE_SLIDE_TO_NEXT_CHANNEL("disableSlideToNextChannel", false),
    DISABLE_RECENT_FILES_ATTACHMENT("disableRecentFilesAttachment", false),
    BOT_SKIP_SHARE("botSkipShare", false),
    BOT_SKIP_FULLSCREEN("botSkipFullscreen", false),
    DISABLE_DEFAULT_IN_APP_BROWSER("disableDefaultInAppBrowser", false),
    SYNC_PINS("syncPins", true),
    INAPP_CAMERA("inappCamera", true),
    SYSTEM_CAMERA("systemCamera", false);

    public final String key;
    public final boolean defaultValue;

    ForkSetting(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public boolean get() {
        return MessagesController.getGlobalMainSettings().getBoolean(key, defaultValue);
    }

    public boolean toggle() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        boolean newValue = !get();
        editor.putBoolean(key, newValue);
        editor.apply();
        return newValue;
    }

    public boolean isVisible() {
        switch (this) {
            case HIDE_SENSITIVE_DATA:
            case HIDE_BOTTOM_BUTTON:
                return !SharedConfig.isUserOwner();
            default:
                return true;
        }
    }
}