/*
 * Copyright 23rd, 2019.
 */

package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.forkgram.ForkOfflineTranscribe;
import org.telegram.messenger.forkgram.ForkOfflineTranslate;
import org.telegram.messenger.forkgram.HiddenAccountHelper;
import org.telegram.messenger.forkgram.SettingsBackup;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.TextHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ForkSettingsActivity extends BaseFragment {

    public static final int ID_HIDE_SENSITIVE_DATA = 1;
    public static final int ID_FORCE_BLOCK_SCREENSHOTS = 2;
    public static final int ID_SHOW_NOTIFICATION_CONTENT = 3;
    public static final int ID_DROP_SCREENSHOT_CAPTION = 4;
    public static final int ID_HIDDEN_ACCOUNTS = 5;

    public static final int ID_HIDE_IN_APP_HINTS = 10;
    public static final int ID_HIDE_BOTTOM_BUTTON = 11;
    public static final int ID_CUSTOM_TITLE = 12;
    public static final int ID_AVATAR_CORNERS = 13;

    public static final int ID_SYNC_PINS = 20;
    public static final int ID_UNMUTED_ON_TOP = 21;
    public static final int ID_OPEN_ARCHIVE_ON_PULL = 22;
    public static final int ID_HIDE_STORIES_IN_ARCHIVE = 23;
    public static final int ID_DISABLE_THUMBS_IN_DIALOG_LIST = 24;
    public static final int ID_DISABLE_GLOBAL_SEARCH = 25;
    public static final int ID_HIDE_CONTACTS_IN_DIALOGS = 26;
    public static final int ID_ENABLE_LAST_SEEN_DOTS = 27;
    public static final int ID_HIDE_ALL_CHATS_TAB = 28;
    public static final int ID_DEFAULT_FOLDER = 29;

    public static final int ID_REPLACE_FORWARD = 30;
    public static final int ID_MENTION_BY_NAME = 31;
    public static final int ID_HIDE_SEND_AS = 32;
    public static final int ID_DISABLE_LINK_PREVIEW_BY_DEFAULT = 33;
    public static final int ID_DELETE_ALL_UNPINNED = 34;
    public static final int ID_DISABLE_SLIDE_TO_NEXT_CHANNEL = 35;
    public static final int ID_FORMAT_WITH_SECONDS = 36;
    public static final int ID_HIDE_AI_EDITOR = 37;
    public static final int ID_FORMATTING_MENU = 38;

    public static final int ID_DISABLE_QUICK_REACTION = 40;
    public static final int ID_HIDE_MESSAGE_REACTIONS = 41;
    public static final int ID_HIDE_SAVED_MESSAGES_TAGS = 42;
    public static final int ID_DISABLE_LOCKED_ANIMATED_EMOJI = 43;
    public static final int ID_FULL_RECENT_STICKERS = 44;
    public static final int ID_SHOW_ARCHIVED_STICKERS = 45;
    public static final int ID_STICKER_SIZE = 46;

    public static final int ID_INAPP_CAMERA = 50;
    public static final int ID_SYSTEM_CAMERA = 51;
    public static final int ID_PHOTO_HAS_STICKER = 52;
    public static final int ID_DISABLE_MOTION_PHOTO = 53;
    public static final int ID_DISABLE_FLIP_PHOTOS = 54;
    public static final int ID_REAR_VIDEO_MESSAGES = 55;
    public static final int ID_DISABLE_PLAY_VISIBLE_VIDEO_ON_VOLUME = 56;
    public static final int ID_DISABLE_RECENT_FILES_ATTACHMENT = 57;

    public static final int ID_VOICE_QUALITY = 60;
    public static final int ID_DISABLE_AUTOPLAY_NEXT_VOICE = 61;
    public static final int ID_OFFLINE_STT = 62;
    public static final int ID_CLOUDFLARE_ENABLE_STT = 63;
    public static final int ID_CLOUDFLARE_CREDENTIALS = 64;
    public static final int ID_TRANSLATION_PROVIDER = 65;

    public static final int ID_BOT_SKIP_SHARE = 70;
    public static final int ID_BOT_SKIP_FULLSCREEN = 71;
    public static final int ID_DISABLE_PARAMETERS_FROM_BOT_LINKS = 72;
    public static final int ID_DISABLE_DEFAULT_IN_APP_BROWSER = 73;

    public static final int ID_DISABLE_UNIFIED_PUSH = 80;
    public static final int ID_UPDATE_CHECK_INTERVAL = 81;
    public static final int ID_DISABLE_TABLET_MODE = 82;
    public static final int ID_LOCK_PREMIUM = 83;

    public static final int ID_LASTFM_LOGIN = 90;

    public static final int ID_EXPORT_SETTINGS = 95;
    public static final int ID_IMPORT_SETTINGS = 96;

    private static final int MENU_SEARCH = 100;

    private static final int REQUEST_EXPORT_SETTINGS = 7311;
    private static final int REQUEST_IMPORT_SETTINGS = 7312;

    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private String searchQuery = "";
    private int highlightItemId;

    public ForkSettingsActivity highlight(int itemId) {
        highlightItemId = itemId;
        return this;
    }

    private class StickerSizeCell extends FrameLayout {

        private final SeekBarView sizeBar;
        private final TextPaint textPaint;

        private final int startStickerSize = 2;
        private final int endStickerSize = (int) ChatMessageCell.MAX_STICKER_SIZE;
        private final String option = "stickerSize";

        private float diff() {
            return (float) (endStickerSize - startStickerSize);
        }

        private float stickerSize() {
            return MessagesController.getGlobalMainSettings().getFloat(option, endStickerSize);
        }

        private void setStickerSize(float size) {
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putFloat(option, size);
            editor.commit();
        }

        public StickerSizeCell(Context context) {
            super(context);

            setWillNotDraw(false);

            textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(AndroidUtilities.dp(16));

            sizeBar = new SeekBarView(context);
            sizeBar.setReportChanges(true);
            sizeBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
                    setStickerSize(startStickerSize + diff() * progress);
                    invalidate();
                }

                @Override
                public void onSeekBarPressed(boolean pressed) {

                }
            });
            addView(
                sizeBar,
                LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT,
                    38,
                    Gravity.LEFT | Gravity.TOP,
                    9,
                    5,
                    43,
                    11));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            canvas.drawText(
                "" + Math.round(stickerSize()),
                getMeasuredWidth() - AndroidUtilities.dp(39),
                AndroidUtilities.dp(28),
                textPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            sizeBar.setProgress((stickerSize() - startStickerSize) / diff());
        }

        @Override
        public void invalidate() {
            super.invalidate();
            sizeBar.invalidate();
        }
    }

    private StickerSizeCell stickerSizeCell;

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    private static boolean pref(String option, boolean byDefault) {
        return prefs().getBoolean(option, byDefault);
    }

    private String getUpdateIntervalText() {
        long interval = prefs().getLong("updateForkCheckInterval", 30 * 60 * 1000);
        if (interval == 0) {
            return LocaleController.getString(R.string.Disable);
        } else if (interval < 60 * 1000) {
            return LocaleController.formatPluralString("Seconds", (int) (interval / 1000));
        } else if (interval < 60 * 60 * 1000) {
            return LocaleController.formatPluralString("Minutes", (int) (interval / (60 * 1000)));
        } else if (interval < 24 * 60 * 60 * 1000) {
            return LocaleController.formatPluralString("Hours", (int) (interval / (60 * 60 * 1000)));
        } else {
            return LocaleController.formatPluralString("Days", (int) (interval / (24 * 60 * 60 * 1000)));
        }
    }

    private static String getVoiceQualityText() {
        int bitrate = prefs().getInt("voiceQualityBitrate", -1);
        if (bitrate <= 0) return LocaleController.getString(R.string.VoiceQualityMax);
        if (bitrate <= 16000) return LocaleController.getString(R.string.VoiceQualityLow);
        if (bitrate <= 32000) return LocaleController.getString(R.string.VoiceQualityMedium);
        if (bitrate <= 64000) return LocaleController.getString(R.string.VoiceQualityHigh);
        return LocaleController.getString(R.string.VoiceQualityMax);
    }

    public static String getOfflineTranscriberText() {
        String label = org.telegram.messenger.forkgram.ForkOfflineTranscribe.selectedProviderLabel();
        if (label != null) {
            return label;
        }
        if (org.telegram.messenger.forkgram.ForkOfflineTranscribe.isEnabled()) {
            return LocaleController.getString(R.string.OfflineTranscriptionMissing);
        }
        return LocaleController.getString(R.string.Disable);
    }

    public static String getTranslationProviderText() {
        switch (ForkOfflineTranslate.provider()) {
            case ForkOfflineTranslate.PROVIDER_ALTERNATIVE:
                return "DuckDuckGo";
            case ForkOfflineTranslate.PROVIDER_OFFLINE:
                return LocaleController.getString(R.string.TranslationEngineOffline);
            default:
                return LocaleController.getString(R.string.TranslationEngineDefault);
        }
    }

    private String getHiddenAccountsText() {
        int hiddenCount = HiddenAccountHelper.getHiddenAccountsCount();
        return hiddenCount > 0 ? Integer.toString(hiddenCount) : LocaleController.getString(R.string.PasswordOff);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.ForkSettingsTitle));
        actionBar.setAllowOverlayTitle(true);

        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search)
            .setIsSearchField(true)
            .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                @Override
                public void onSearchCollapse() {
                    searchQuery = "";
                    if (listView != null) {
                        listView.adapter.update(false);
                    }
                }

                @Override
                public void onSearchExpand() {
                    searchQuery = "";
                    if (listView != null) {
                        listView.adapter.update(false);
                    }
                }

                @Override
                public void onTextChanged(EditText editText) {
                    searchQuery = editText.getText().toString();
                    if (listView != null) {
                        listView.adapter.update(false);
                    }
                }
            });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        searchItem.setContentDescription(LocaleController.getString(R.string.Search));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setGlowColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        if (highlightItemId != 0) {
            final int itemId = highlightItemId;
            highlightItemId = 0;
            listView.highlightRow(() -> {
                int position = listView.findPositionByItemId(itemId);
                if (position >= 0) {
                    listView.layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60));
                }
                return position;
            });
        }

        return fragmentView;
    }

    private boolean isSearching() {
        return searchItem != null && searchItem.isSearchFieldVisible2() && !TextUtils.isEmpty(searchQuery.trim());
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (isSearching()) {
            fillSearchResults(items);
        } else {
            fillSettings(items);
        }
    }

    private void fillSearchResults(ArrayList<UItem> items) {
        final ArrayList<UItem> all = new ArrayList<>();
        fillSettings(all);

        final String[] tokens = searchQuery.trim().toLowerCase().split("\\s+");

        UItem pendingHeader = null;
        boolean anyFound = false;
        for (int i = 0; i < all.size(); ++i) {
            final UItem item = all.get(i);
            if (isHeader(item)) {
                pendingHeader = item;
                continue;
            }
            if (isShadow(item) || item.id <= 0) {
                continue;
            }
            if (!matches(item, tokens)) {
                continue;
            }
            if (pendingHeader != null) {
                if (anyFound) {
                    items.add(UItem.asShadow(null));
                }
                items.add(pendingHeader);
                pendingHeader = null;
            }
            items.add(item);
            anyFound = true;
        }
        items.add(UItem.asShadow(null));
    }

    private static UItem searchable(UItem item, int titleRes) {
        item.text = LocaleController.getString(titleRes);
        return item;
    }

    private static boolean isHeader(UItem item) {
        return item.viewType == UniversalAdapter.VIEW_TYPE_HEADER;
    }

    private static boolean isShadow(UItem item) {
        return item.viewType == UniversalAdapter.VIEW_TYPE_SHADOW;
    }

    private static boolean matches(UItem item, String[] tokens) {
        final StringBuilder sb = new StringBuilder();
        if (item.text != null) sb.append(item.text).append(' ');
        if (item.subtext != null) sb.append(item.subtext).append(' ');
        if (item.textValue != null) sb.append(item.textValue);

        final String haystack = sb.toString().toLowerCase();
        final String translit = LocaleController.getInstance().getTranslitString(haystack);
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (haystack.contains(token)) continue;
            if (translit != null && translit.contains(token)) continue;
            return false;
        }
        return true;
    }

    private void fillSettings(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacySettings)));
        if (!SharedConfig.isUserOwner()) {
            items.add(UItem.asButtonCheck(ID_HIDE_SENSITIVE_DATA, LocaleController.getString(R.string.HideSensitiveData), LocaleController.getString(R.string.ForkRestartRequired))
                .setChecked(pref("hideSensitiveData", false)).setMultiline(true));
        }
        items.add(UItem.asButtonCheck(ID_FORCE_BLOCK_SCREENSHOTS, LocaleController.getString(R.string.ForceBlockScreenshots), LocaleController.getString(R.string.ForceBlockScreenshotsInfo))
            .setChecked(pref("forceBlockScreenshots", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_SHOW_NOTIFICATION_CONTENT, LocaleController.getString(R.string.ShowNotificationContent), LocaleController.getString(R.string.ShowNotificationContentInfo))
            .setChecked(pref("showNotificationContent", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DROP_SCREENSHOT_CAPTION, LocaleController.getString(R.string.DropScreenshotCaption), LocaleController.getString(R.string.DropScreenshotCaptionInfo))
            .setChecked(pref("dropScreenshotCaption", true)).setMultiline(true));
        if (HiddenAccountHelper.shouldShowSettingsEntry(currentAccount)) {
            items.add(UItem.asSettingsCell(ID_HIDDEN_ACCOUNTS, LocaleController.getString(R.string.HiddenAccounts), getHiddenAccountsText()));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkSectionAppearance)));
        items.add(UItem.asButtonCheck(ID_HIDE_IN_APP_HINTS, LocaleController.getString(R.string.HideInAppHints), LocaleController.getString(R.string.HideInAppHintsInfo))
            .setChecked(pref("hideInAppHints", false)).setMultiline(true));
        if (SharedConfig.isUserOwner()) {
            items.add(UItem.asButtonCheck(ID_HIDE_BOTTOM_BUTTON, LocaleController.getString(R.string.HideBottomButton), LocaleController.getString(R.string.HideBottomButtonInfo))
                .setChecked(pref("hideBottomButton", false)).setMultiline(true));
        }
        items.add(UItem.asSettingsCell(ID_CUSTOM_TITLE, LocaleController.getString(R.string.EditAdminRank), prefs().getString("forkCustomTitle", "Fork Client")));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.AvatarShape)));
        items.add(searchable(UItem.asSlideView(
            new String[]{
                LocaleController.getString(R.string.AvatarShapeRound),
                LocaleController.getString(R.string.AvatarShapeRounded),
                LocaleController.getString(R.string.AvatarShapeSquare)
            },
            AndroidUtilities.avatarCornersType(),
            index -> {
                SharedPreferences.Editor editor = prefs().edit();
                editor.putInt("avatarCorners", index);
                editor.commit();
            }).setId(ID_AVATAR_CORNERS), R.string.AvatarShape));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ForkRestartRequired)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ChatList)));
        items.add(UItem.asButtonCheck(ID_SYNC_PINS, LocaleController.getString(R.string.SyncPins), LocaleController.getString(R.string.SyncPinsInfo))
            .setChecked(pref("syncPins", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_UNMUTED_ON_TOP, LocaleController.getString(R.string.UnmutedOnTop), LocaleController.getString(R.string.UnmutedOnTopInfo))
            .setChecked(pref("unmutedOnTop", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_OPEN_ARCHIVE_ON_PULL, LocaleController.getString(R.string.OpenArchiveOnPull), LocaleController.getString(R.string.OpenArchiveOnPullInfo))
            .setChecked(pref("openArchiveOnPull", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_HIDE_STORIES_IN_ARCHIVE, LocaleController.getString(R.string.HideStoriesInArchive), LocaleController.getString(R.string.HideStoriesInArchiveInfo))
            .setChecked(pref("hideStoriesInArchive", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_THUMBS_IN_DIALOG_LIST, LocaleController.getString(R.string.DisableThumbsInDialogList), LocaleController.getString(R.string.DisableThumbsInDialogListInfo))
            .setChecked(pref("disableThumbsInDialogList", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_GLOBAL_SEARCH, LocaleController.getString(R.string.DisableGlobalSearch), LocaleController.getString(R.string.DisableGlobalSearchInfo))
            .setChecked(pref("disableGlobalSearch", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_HIDE_CONTACTS_IN_DIALOGS, LocaleController.getString(R.string.HideContactsInDialogs), LocaleController.getString(R.string.HideContactsInDialogsInfo))
            .setChecked(pref("hideContactsInDialogs", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_ENABLE_LAST_SEEN_DOTS, LocaleController.getString(R.string.EnableLastSeenDots), LocaleController.getString(R.string.EnableLastSeenDotsInfo))
            .setChecked(pref("enableLastSeenDots", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_HIDE_ALL_CHATS_TAB, LocaleController.getString(R.string.HideAllChatsTab), LocaleController.getString(R.string.HideAllChatsTabInfo))
            .setChecked(pref("hideAllChatsTab", false)).setMultiline(true));
        items.add(UItem.asSettingsCell(ID_DEFAULT_FOLDER, LocaleController.getString(R.string.DefaultFolder), getDefaultFolderText()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.FilterChats)));
        items.add(UItem.asButtonCheck(ID_REPLACE_FORWARD, LocaleController.getString(R.string.ReplaceForward), LocaleController.getString(R.string.ReplaceForwardInfo))
            .setChecked(pref("replaceForward", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_MENTION_BY_NAME, LocaleController.getString(R.string.MentionByName), LocaleController.getString(R.string.MentionByNameInfo))
            .setChecked(pref("mentionByName", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_HIDE_SEND_AS, LocaleController.getString(R.string.HideSendAs), LocaleController.getString(R.string.HideSendAsInfo))
            .setChecked(pref("hideSendAs", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_LINK_PREVIEW_BY_DEFAULT, LocaleController.getString(R.string.DisableLinkPreviewByDefault), LocaleController.getString(R.string.DisableLinkPreviewByDefaultInfo))
            .setChecked(pref("disableLinkPreviewByDefault", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DELETE_ALL_UNPINNED, LocaleController.getString(R.string.AddDeleteAllUnpinnedMessages), LocaleController.getString(R.string.AddDeleteAllUnpinnedMessagesInfo))
            .setChecked(pref("addItemToDeleteAllUnpinnedMessages", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_SLIDE_TO_NEXT_CHANNEL, LocaleController.getString(R.string.DisableSlideToNextChannel), LocaleController.getString(R.string.DisableSlideToNextChannelInfo))
            .setChecked(pref("disableSlideToNextChannel", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_FORMAT_WITH_SECONDS, LocaleController.getString(R.string.FormatWithSeconds), LocaleController.getString(R.string.FormatWithSecondsInfo))
            .setChecked(pref("formatWithSeconds", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_HIDE_AI_EDITOR, LocaleController.getString(R.string.HideAiEditor), LocaleController.getString(R.string.HideAiEditorInfo))
            .setChecked(pref("hideAiEditor", false)).setMultiline(true));
        items.add(UItem.asSettingsCell(ID_FORMATTING_MENU, LocaleController.getString(R.string.FormattingMenu), null));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Reactions)));
        items.add(UItem.asButtonCheck(ID_DISABLE_QUICK_REACTION, LocaleController.getString(R.string.DisableQuickReaction), LocaleController.getString(R.string.DisableQuickReactionInfo))
            .setChecked(pref("disableQuickReaction", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_HIDE_MESSAGE_REACTIONS, LocaleController.getString(R.string.HideMessageReactions), LocaleController.getString(R.string.HideMessageReactionsInfo))
            .setChecked(pref("hideMessageReactions", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_HIDE_SAVED_MESSAGES_TAGS, LocaleController.getString(R.string.HideSavedMessagesTags), LocaleController.getString(R.string.HideSavedMessagesTagsInfo))
            .setChecked(pref("hideSavedMessagesTags", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_LOCKED_ANIMATED_EMOJI, LocaleController.getString(R.string.DisableLockedAnimatedEmoji), LocaleController.getString(R.string.DisableLockedAnimatedEmojiInfo))
            .setChecked(pref("disableLockedAnimatedEmoji", false)).setMultiline(true));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.StickersName)));
        items.add(UItem.asButtonCheck(ID_FULL_RECENT_STICKERS, LocaleController.getString(R.string.FullRecentStickers), LocaleController.getString(R.string.FullRecentStickersInfo))
            .setChecked(pref("fullRecentStickers", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_SHOW_ARCHIVED_STICKERS, LocaleController.getString(R.string.ShowArchivedStickers), LocaleController.getString(R.string.ShowArchivedStickersInfo))
            .setChecked(pref("showArchivedStickers", false)).setMultiline(true));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.StickerSize)));
        if (stickerSizeCell == null) {
            stickerSizeCell = new StickerSizeCell(getContext());
        }
        items.add(searchable(UItem.asCustom(ID_STICKER_SIZE, stickerSizeCell), R.string.StickerSize));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkSectionMedia)));
        items.add(UItem.asButtonCheck(ID_INAPP_CAMERA, LocaleController.getString(R.string.InAppCamera), LocaleController.getString(R.string.InAppCameraInfo))
            .setChecked(pref("inappCamera", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_SYSTEM_CAMERA, LocaleController.getString(R.string.SystemCamera), LocaleController.getString(R.string.SystemCameraInfo))
            .setChecked(pref("systemCamera", false))
            .setEnabled(SharedConfig.inappCamera)
            .setMultiline(true));
        items.add(UItem.asButtonCheck(ID_PHOTO_HAS_STICKER, LocaleController.getString(R.string.PhotoHasSticker), LocaleController.getString(R.string.PhotoHasStickerInfo))
            .setChecked(pref("photoHasSticker", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_MOTION_PHOTO, LocaleController.getString(R.string.DisableMotionPhoto), LocaleController.getString(R.string.DisableMotionPhotoInfo))
            .setChecked(pref("disableMotionPhoto", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_FLIP_PHOTOS, LocaleController.getString(R.string.DisableFlipPhotos), LocaleController.getString(R.string.DisableFlipPhotosInfo))
            .setChecked(pref("disableFlipPhotos", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_REAR_VIDEO_MESSAGES, LocaleController.getString(R.string.RearVideoMessages), LocaleController.getString(R.string.RearVideoMessagesInfo))
            .setChecked(pref("rearVideoMessages", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_PLAY_VISIBLE_VIDEO_ON_VOLUME, LocaleController.getString(R.string.DisablePlayVisibleVideoOnVolume), LocaleController.getString(R.string.DisablePlayVisibleVideoOnVolumeInfo))
            .setChecked(pref("disablePlayVisibleVideoOnVolume", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_RECENT_FILES_ATTACHMENT, LocaleController.getString(R.string.DisableRecentFilesAttachment), LocaleController.getString(R.string.DisableRecentFilesAttachmentInfo))
            .setChecked(pref("disableRecentFilesAttachment", false)).setMultiline(true));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkSectionVoice)));
        items.add(UItem.asSettingsCell(ID_VOICE_QUALITY, LocaleController.getString(R.string.VoiceMessageQuality), getVoiceQualityText()));
        items.add(UItem.asButtonCheck(ID_DISABLE_AUTOPLAY_NEXT_VOICE, LocaleController.getString(R.string.DisableAutoplayNextVoice), LocaleController.getString(R.string.DisableAutoplayNextVoiceInfo))
            .setChecked(pref("disableAutoplayNextVoice", false)).setMultiline(true));
        items.add(UItem.asSettingsCell(ID_OFFLINE_STT, LocaleController.getString(R.string.OfflineTranscription), getOfflineTranscriberText()));
        items.add(UItem.asCheck(ID_CLOUDFLARE_ENABLE_STT, LocaleController.getString(R.string.CloudflareEnableSTT))
            .setChecked(SharedConfig.cfEnableStt));
        items.add(UItem.asSettingsCell(ID_CLOUDFLARE_CREDENTIALS, LocaleController.getString(R.string.CloudflareCredentials), ""));
        items.add(UItem.asSettingsCell(ID_TRANSLATION_PROVIDER, LocaleController.getString(R.string.TranslationEngine), getTranslationProviderText()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkSectionBots)));
        items.add(UItem.asButtonCheck(ID_BOT_SKIP_SHARE, LocaleController.getString(R.string.BotSkipShare), LocaleController.getString(R.string.BotSkipShareInfo))
            .setChecked(pref("botSkipShare", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_BOT_SKIP_FULLSCREEN, LocaleController.getString(R.string.BotSkipFullscreen), LocaleController.getString(R.string.BotSkipFullscreenInfo))
            .setChecked(pref("botSkipFullscreen", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_PARAMETERS_FROM_BOT_LINKS, LocaleController.getString(R.string.DisableParametersFromBotLinks), LocaleController.getString(R.string.DisableParametersFromBotLinksInfo))
            .setChecked(pref("disableParametersFromBotLinks", false)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_DISABLE_DEFAULT_IN_APP_BROWSER, LocaleController.getString(R.string.DisableDefaultInAppBrowser), LocaleController.getString(R.string.DisableDefaultInAppBrowserInfo))
            .setChecked(pref("disableDefaultInAppBrowser", org.telegram.messenger.BuildConfig.SKIP_INTERNAL_BROWSER_BY_DEFAULT)).setMultiline(true));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkSectionSystem)));
        items.add(UItem.asButtonCheck(ID_DISABLE_UNIFIED_PUSH, LocaleController.getString(R.string.DisableUnifiedPush), LocaleController.getString(R.string.DisableUnifiedPushInfo))
            .setChecked(pref("disableUnifiedPush", false)).setMultiline(true));
        items.add(UItem.asSettingsCell(ID_UPDATE_CHECK_INTERVAL, LocaleController.getString(R.string.UpdateCheckInterval), getUpdateIntervalText()));
        if (AndroidUtilities.isTabletInternal()) {
            items.add(UItem.asButtonCheck(ID_DISABLE_TABLET_MODE, LocaleController.getString(R.string.DisableTabletMode), LocaleController.getString(R.string.DisableTabletModeInfo))
                .setChecked(SharedConfig.forceDisableTabletMode)
                .setMultiline(true));
        }
        items.add(UItem.asButtonCheck(ID_LOCK_PREMIUM, LocaleController.getString(R.string.LockPremium), LocaleController.getString(R.string.LockPremiumInfo))
            .setChecked(pref("lockPremium", false)).setMultiline(true));
        items.add(UItem.asShadow(null));

        if (BuildVars.LASTFM_API_KEY != null && BuildVars.LASTFM_API_KEY.length() > 2 &&
            BuildVars.LASTFM_API_SECRET != null && BuildVars.LASTFM_API_SECRET.length() > 2) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.ThirdParty)));
            items.add(UItem.asSettingsCell(ID_LASTFM_LOGIN, R.drawable.ic_lastfm, "Last.fm"));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkSectionBackup)));
        items.add(UItem.asSettingsCell(ID_EXPORT_SETTINGS, LocaleController.getString(R.string.ExportSettings), ""));
        items.add(UItem.asSettingsCell(ID_IMPORT_SETTINGS, LocaleController.getString(R.string.ImportSettings), ""));
        items.add(UItem.asShadow(null));
    }

    private boolean toggle(String option, UItem item, View view) {
        final boolean value = !item.checked;
        item.checked = value;
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(option, value);
        editor.commit();
        setCellChecked(view, value);
        return value;
    }

    private static void setCellChecked(View view, boolean value) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        } else if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(value);
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        final int id = item.id;

        if (id == ID_HIDE_SENSITIVE_DATA) {
            toggle("hideSensitiveData", item, view);
        } else if (id == ID_FORCE_BLOCK_SCREENSHOTS) {
            toggle("forceBlockScreenshots", item, view);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetPasscode, false);
        } else if (id == ID_SHOW_NOTIFICATION_CONTENT) {
            toggle("showNotificationContent", item, view);
        } else if (id == ID_DROP_SCREENSHOT_CAPTION) {
            toggle("dropScreenshotCaption", item, view);
        } else if (id == ID_HIDDEN_ACCOUNTS) {
            presentFragment(new HiddenAccountsActivity());

        } else if (id == ID_HIDE_IN_APP_HINTS) {
            toggle("hideInAppHints", item, view);
        } else if (id == ID_HIDE_BOTTOM_BUTTON) {
            toggle("hideBottomButton", item, view);
        } else if (id == ID_CUSTOM_TITLE) {
            showCustomTitleDialog(view);

        } else if (id == ID_SYNC_PINS) {
            toggle("syncPins", item, view);
        } else if (id == ID_UNMUTED_ON_TOP) {
            toggle("unmutedOnTop", item, view);
            MessagesController.getInstance(currentAccount).sortDialogs(null);
        } else if (id == ID_OPEN_ARCHIVE_ON_PULL) {
            toggle("openArchiveOnPull", item, view);
        } else if (id == ID_HIDE_STORIES_IN_ARCHIVE) {
            toggle("hideStoriesInArchive", item, view);
        } else if (id == ID_DISABLE_THUMBS_IN_DIALOG_LIST) {
            toggle("disableThumbsInDialogList", item, view);
        } else if (id == ID_DISABLE_GLOBAL_SEARCH) {
            toggle("disableGlobalSearch", item, view);
        } else if (id == ID_HIDE_CONTACTS_IN_DIALOGS) {
            toggle("hideContactsInDialogs", item, view);
        } else if (id == ID_ENABLE_LAST_SEEN_DOTS) {
            toggle("enableLastSeenDots", item, view);
        } else if (id == ID_HIDE_ALL_CHATS_TAB) {
            toggle("hideAllChatsTab", item, view);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
        } else if (id == ID_DEFAULT_FOLDER) {
            showDefaultFolderDialog();

        } else if (id == ID_REPLACE_FORWARD) {
            toggle("replaceForward", item, view);
        } else if (id == ID_MENTION_BY_NAME) {
            toggle("mentionByName", item, view);
        } else if (id == ID_HIDE_SEND_AS) {
            toggle("hideSendAs", item, view);
        } else if (id == ID_DISABLE_LINK_PREVIEW_BY_DEFAULT) {
            toggle("disableLinkPreviewByDefault", item, view);
        } else if (id == ID_DELETE_ALL_UNPINNED) {
            toggle("addItemToDeleteAllUnpinnedMessages", item, view);
        } else if (id == ID_DISABLE_SLIDE_TO_NEXT_CHANNEL) {
            toggle("disableSlideToNextChannel", item, view);
        } else if (id == ID_FORMAT_WITH_SECONDS) {
            toggle("formatWithSeconds", item, view);
        } else if (id == ID_HIDE_AI_EDITOR) {
            toggle("hideAiEditor", item, view);
        } else if (id == ID_FORMATTING_MENU) {
            presentFragment(new FormattingMenuActivity());

        } else if (id == ID_DISABLE_QUICK_REACTION) {
            toggle("disableQuickReaction", item, view);
        } else if (id == ID_HIDE_MESSAGE_REACTIONS) {
            toggle("hideMessageReactions", item, view);
        } else if (id == ID_HIDE_SAVED_MESSAGES_TAGS) {
            toggle("hideSavedMessagesTags", item, view);
        } else if (id == ID_DISABLE_LOCKED_ANIMATED_EMOJI) {
            toggle("disableLockedAnimatedEmoji", item, view);
        } else if (id == ID_FULL_RECENT_STICKERS) {
            toggle("fullRecentStickers", item, view);
        } else if (id == ID_SHOW_ARCHIVED_STICKERS) {
            if (toggle("showArchivedStickers", item, view)) {
                MediaDataController.getInstance(currentAccount).loadArchivedStickerSets();
            }

        } else if (id == ID_INAPP_CAMERA) {
            SharedConfig.toggleInappCamera();
            setCellChecked(view, SharedConfig.inappCamera);
            listView.adapter.update(true);
        } else if (id == ID_SYSTEM_CAMERA) {
            if (SharedConfig.inappCamera) {
                toggle("systemCamera", item, view);
            }
        } else if (id == ID_PHOTO_HAS_STICKER) {
            toggle("photoHasSticker", item, view);
        } else if (id == ID_DISABLE_MOTION_PHOTO) {
            toggle("disableMotionPhoto", item, view);
        } else if (id == ID_DISABLE_FLIP_PHOTOS) {
            toggle("disableFlipPhotos", item, view);
        } else if (id == ID_REAR_VIDEO_MESSAGES) {
            toggle("rearVideoMessages", item, view);
        } else if (id == ID_DISABLE_PLAY_VISIBLE_VIDEO_ON_VOLUME) {
            toggle("disablePlayVisibleVideoOnVolume", item, view);
        } else if (id == ID_DISABLE_RECENT_FILES_ATTACHMENT) {
            toggle("disableRecentFilesAttachment", item, view);

        } else if (id == ID_VOICE_QUALITY) {
            showVoiceQualityDialog();
        } else if (id == ID_DISABLE_AUTOPLAY_NEXT_VOICE) {
            toggle("disableAutoplayNextVoice", item, view);
        } else if (id == ID_OFFLINE_STT) {
            showOfflineTranscriberDialog();
        } else if (id == ID_CLOUDFLARE_ENABLE_STT) {
            if (!SharedConfig.cfEnableStt && (android.text.TextUtils.isEmpty(SharedConfig.cfAccountID) || android.text.TextUtils.isEmpty(SharedConfig.cfApiToken))) {
                showCfCredentialsDialog();
                return;
            }
            SharedConfig.cfEnableStt = !SharedConfig.cfEnableStt;
            SharedConfig.saveConfig();
            setCellChecked(view, SharedConfig.cfEnableStt);
        } else if (id == ID_CLOUDFLARE_CREDENTIALS) {
            showCfCredentialsDialog();
        } else if (id == ID_TRANSLATION_PROVIDER) {
            showTranslationProviderDialog();

        } else if (id == ID_BOT_SKIP_SHARE) {
            toggle("botSkipShare", item, view);
        } else if (id == ID_BOT_SKIP_FULLSCREEN) {
            toggle("botSkipFullscreen", item, view);
        } else if (id == ID_DISABLE_PARAMETERS_FROM_BOT_LINKS) {
            toggle("disableParametersFromBotLinks", item, view);
        } else if (id == ID_DISABLE_DEFAULT_IN_APP_BROWSER) {
            toggle("disableDefaultInAppBrowser", item, view);

        } else if (id == ID_DISABLE_UNIFIED_PUSH) {
            toggle("disableUnifiedPush", item, view);
        } else if (id == ID_UPDATE_CHECK_INTERVAL) {
            showUpdateIntervalDialog();
        } else if (id == ID_DISABLE_TABLET_MODE) {
            SharedConfig.toggleForceDisableTabletMode();
            setCellChecked(view, SharedConfig.forceDisableTabletMode);
            Activity activity = getParentActivity();
            if (activity != null) {
                final android.content.pm.PackageManager pm = activity.getPackageManager();
                final Intent intent = pm.getLaunchIntentForPackage(activity.getPackageName());
                activity.finishAffinity();
                activity.startActivity(intent);
            }
            System.exit(0);
        } else if (id == ID_LOCK_PREMIUM) {
            toggle("lockPremium", item, view);

        } else if (id == ID_LASTFM_LOGIN) {
            presentFragment(new LastFmLoginActivity());

        } else if (id == ID_EXPORT_SETTINGS) {
            exportSettings();
        } else if (id == ID_IMPORT_SETTINGS) {
            importSettings();
        }
    }

    private void showCustomTitleDialog(View view) {
        final String defaultValue = "Fork Client";
        org.telegram.messenger.forkgram.ForkDialogs.CreateFieldAlert(
            getContext(),
            LocaleController.getString(R.string.EditAdminRank),
            prefs().getString("forkCustomTitle", defaultValue),
            (result) -> {
                if (result.isEmpty()) {
                    result = defaultValue;
                }
                SharedPreferences.Editor editor = prefs().edit();
                editor.putString("forkCustomTitle", result);
                editor.commit();
                listView.adapter.update(false);

                BaseFragment previousFragment = parentLayout.getFragmentStack().size() > 2
                    ? parentLayout.getFragmentStack().get(parentLayout.getFragmentStack().size() - 3)
                    : null;
                if (previousFragment instanceof DialogsActivity) {
                    ((DialogsActivity) previousFragment).getActionBar().setTitle(result);
                }
                return null;
            });
    }

    private void showCfCredentialsDialog() {
        var context = getParentActivity();
        if (context == null) {
            return;
        }
        var builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.CloudflareCredentials));
        builder.setMessage(LocaleController.getString(R.string.CloudflareCredentialsDialog));
        builder.setCustomViewOffset(0);

        var ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);

        var editTextAccountId = new EditTextBoldCursor(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64), MeasureSpec.EXACTLY));
            }
        };
        editTextAccountId.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editTextAccountId.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editTextAccountId.setText(SharedConfig.cfAccountID);
        editTextAccountId.setHintText(LocaleController.getString(R.string.CloudflareAccountID));
        editTextAccountId.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editTextAccountId.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        editTextAccountId.setSingleLine(true);
        editTextAccountId.setFocusable(true);
        editTextAccountId.setTransformHintToHeader(true);
        editTextAccountId.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        editTextAccountId.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        editTextAccountId.setBackground(null);
        editTextAccountId.requestFocus();
        editTextAccountId.setPadding(0, 0, 0, 0);
        ll.addView(editTextAccountId, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0));

        var editTextApiToken = new EditTextBoldCursor(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64), MeasureSpec.EXACTLY));
            }
        };
        editTextApiToken.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editTextApiToken.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editTextApiToken.setText(SharedConfig.cfApiToken);
        editTextApiToken.setHintText(LocaleController.getString(R.string.CloudflareAPIToken));
        editTextApiToken.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editTextApiToken.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        editTextApiToken.setSingleLine(true);
        editTextApiToken.setFocusable(true);
        editTextApiToken.setTransformHintToHeader(true);
        editTextApiToken.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        editTextApiToken.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editTextApiToken.setBackground(null);
        editTextApiToken.requestFocus();
        editTextApiToken.setPadding(0, 0, 0, 0);
        ll.addView(editTextApiToken, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0));

        builder.setView(ll);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        var dialog = builder.create();
        showDialog(dialog);
        var button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setOnClickListener(v -> {
                var accountId = editTextAccountId.getText();
                if (!android.text.TextUtils.isEmpty(accountId) && accountId.length() != 32) {
                    AndroidUtilities.shakeViewSpring(editTextAccountId, -6);
                    BotWebViewVibrationEffect.APP_ERROR.vibrate();
                    return;
                }
                var apiToken = editTextApiToken.getText();
                if (!android.text.TextUtils.isEmpty(apiToken) && apiToken.length() < 40) {
                    AndroidUtilities.shakeViewSpring(editTextApiToken, -6);
                    BotWebViewVibrationEffect.APP_ERROR.vibrate();
                    return;
                }
                SharedConfig.cfAccountID = accountId == null ? "" : accountId.toString();
                SharedConfig.cfApiToken = apiToken == null ? "" : apiToken.toString();
                if (!android.text.TextUtils.isEmpty(SharedConfig.cfAccountID) && !android.text.TextUtils.isEmpty(SharedConfig.cfApiToken)) {
                    SharedConfig.cfEnableStt = true;
                }
                SharedConfig.saveConfig();
                listView.adapter.update(false);
                dialog.dismiss();
            });
        }
    }

    private void showRadioDialog(CharSequence title, String[] options, int selectedIndex, Utilities.Callback<Integer> onSelected) {
        showRadioDialog(title, options, selectedIndex, null, onSelected);
    }

    private void showRadioDialog(CharSequence title, String[] options, int selectedIndex, View footer, Utilities.Callback<Integer> onSelected) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);

        for (int i = 0; i < options.length; i++) {
            RadioColorCell cell = new RadioColorCell(activity);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setTag(i);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(options[i], selectedIndex == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);

            cell.setOnClickListener(v -> {
                onSelected.run((Integer) v.getTag());
                builder.getDismissRunnable().run();
            });
        }

        if (footer != null) {
            linearLayout.addView(footer);
        }

        builder.setView(linearLayout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private String getDefaultFolderText() {
        final int id = prefs().getInt("defaultFolderId", -1);
        if (id != -1) {
            ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
            for (int a = 0; a < filters.size(); a++) {
                MessagesController.DialogFilter filter = filters.get(a);
                if (!filter.isDefault() && filter.localId == id) {
                    return filter.name;
                }
            }
        }
        return LocaleController.getString(R.string.FilterAllChats);
    }

    private void showDefaultFolderDialog() {
        ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        for (int a = 0; a < filters.size(); a++) {
            MessagesController.DialogFilter filter = filters.get(a);
            if (filter.isDefault()) {
                names.add(LocaleController.getString(R.string.FilterAllChats));
                ids.add(-1);
            } else {
                names.add(filter.name);
                ids.add(filter.localId);
            }
        }
        if (names.isEmpty()) {
            names.add(LocaleController.getString(R.string.FilterAllChats));
            ids.add(-1);
        }
        final int currentId = prefs().getInt("defaultFolderId", -1);
        int selectedIndex = 0;
        for (int a = 0; a < ids.size(); a++) {
            if (ids.get(a) == currentId) {
                selectedIndex = a;
                break;
            }
        }
        final int[] idsArr = new int[ids.size()];
        for (int a = 0; a < ids.size(); a++) {
            idsArr[a] = ids.get(a);
        }
        showRadioDialog(LocaleController.getString(R.string.DefaultFolder), names.toArray(new String[0]), selectedIndex, index -> {
            SharedPreferences.Editor editor = prefs().edit();
            editor.putInt("defaultFolderId", idsArr[index]);
            editor.commit();
            listView.adapter.update(false);
        });
    }

    private void showVoiceQualityDialog() {
        final String[] options = {
            LocaleController.getString(R.string.VoiceQualityLow),
            LocaleController.getString(R.string.VoiceQualityMedium),
            LocaleController.getString(R.string.VoiceQualityHigh),
            LocaleController.getString(R.string.VoiceQualityMax)
        };
        final int[] bitrates = {16000, 32000, 64000, -1};

        int currentBitrate = prefs().getInt("voiceQualityBitrate", -1);
        int selectedIndex = bitrates.length - 1;
        for (int i = 0; i < bitrates.length; i++) {
            if (bitrates[i] == currentBitrate) {
                selectedIndex = i;
                break;
            }
        }

        showRadioDialog(LocaleController.getString(R.string.VoiceMessageQuality), options, selectedIndex, index -> {
            SharedPreferences.Editor editor = prefs().edit();
            editor.putInt("voiceQualityBitrate", bitrates[index]);
            editor.commit();
            listView.adapter.update(false);
        });
    }

    private void showUpdateIntervalDialog() {
        final long[] intervals = {
            0,
            5 * 60 * 1000L,
            15 * 60 * 1000L,
            30 * 60 * 1000L,
            60 * 60 * 1000L,
            2 * 60 * 60 * 1000L,
            6 * 60 * 60 * 1000L,
            12 * 60 * 60 * 1000L,
            24 * 60 * 60 * 1000L,
            2 * 24 * 60 * 60 * 1000L,
            7 * 24 * 60 * 60 * 1000L
        };
        final String[] options = new String[intervals.length];
        options[0] = LocaleController.getString(R.string.Disable);
        for (int i = 1; i < intervals.length; i++) {
            long interval = intervals[i];
            if (interval < 60 * 60 * 1000L) {
                options[i] = LocaleController.formatPluralString("Minutes", (int) (interval / (60 * 1000L)));
            } else if (interval < 24 * 60 * 60 * 1000L) {
                options[i] = LocaleController.formatPluralString("Hours", (int) (interval / (60 * 60 * 1000L)));
            } else {
                options[i] = LocaleController.formatPluralString("Days", (int) (interval / (24 * 60 * 60 * 1000L)));
            }
        }

        long currentInterval = prefs().getLong("updateForkCheckInterval", 30 * 60 * 1000);
        int selectedIndex = 3;
        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i] == currentInterval) {
                selectedIndex = i;
                break;
            }
        }

        showRadioDialog(LocaleController.getString(R.string.UpdateCheckInterval), options, selectedIndex, index -> {
            SharedPreferences.Editor editor = prefs().edit();
            editor.putLong("updateForkCheckInterval", intervals[index]);
            editor.commit();
            listView.adapter.update(false);
        });
    }

    private void showTranslationProviderDialog() {
        showTranslationProviderDialog(this, true, () -> listView.adapter.update(false));
    }

    public static void showTranslationProviderDialog(BaseFragment fragment, boolean showLanguageLink, Runnable onChanged) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final String[] options = new String[]{
            LocaleController.getString(R.string.TranslationEngineDefault),
            "DuckDuckGo",
            LocaleController.getString(R.string.TranslationEngineOffline)
        };
        final int[] providers = new int[]{
            ForkOfflineTranslate.PROVIDER_DEFAULT,
            ForkOfflineTranslate.PROVIDER_ALTERNATIVE,
            ForkOfflineTranslate.PROVIDER_OFFLINE
        };

        int current = ForkOfflineTranslate.provider();
        int selectedIndex = 0;
        for (int i = 0; i < providers.length; i++) {
            if (providers[i] == current) {
                selectedIndex = i;
                break;
            }
        }

        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.TranslationEngine));

        for (int i = 0; i < options.length; i++) {
            RadioColorCell cell = new RadioColorCell(activity);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setTag(i);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(options[i], selectedIndex == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);

            cell.setOnClickListener(v -> {
                int index = (Integer) v.getTag();
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putInt("translationProvider", providers[index]);
                editor.commit();

                if (onChanged != null) {
                    onChanged.run();
                }
                builder.getDismissRunnable().run();
            });
        }

        linearLayout.addView(createEngineInfoView(activity, R.string.TranslationEngineOfflineInfo, ForkOfflineTranslate.FDROID_URL));

        builder.setView(linearLayout);
        if (showLanguageLink) {
            builder.setNeutralButton(LocaleController.getString(R.string.Language), (dialog, which) -> fragment.presentFragment(new LanguageSelectActivity()));
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private static View createEngineInfoView(Activity activity, int textResId, String url) {
        LinkSpanDrawable.LinksTextView infoView = TextHelper.makeLinkTextView(activity, 13, Theme.key_dialogTextGray2, false);
        infoView.setText(AndroidUtilities.replaceSingleTag(
            LocaleController.getString(textResId),
            () -> Browser.openUrl(activity, url)
        ));
        infoView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(10), AndroidUtilities.dp(22), AndroidUtilities.dp(6));
        return infoView;
    }

    private void showOfflineTranscriberDialog() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        java.util.List<org.telegram.messenger.forkgram.TranscriberProvider> providers =
            org.telegram.messenger.forkgram.ForkOfflineTranscribe.availableProviders();
        if (providers.isEmpty()) {
            new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.OfflineTranscription))
                .setMessage(AndroidUtilities.replaceSingleTag(
                    LocaleController.getString(R.string.OfflineTranscriptionUnavailable),
                    () -> Browser.openUrl(activity, ForkOfflineTranscribe.SUGGESTED_FDROID_URL)
                ))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
            return;
        }

        String selectedId = org.telegram.messenger.forkgram.ForkOfflineTranscribe.selectedProviderId();
        String[] options = new String[providers.size() + 1];
        options[0] = LocaleController.getString(R.string.Disable);
        int selectedIndex = 0;
        for (int i = 0; i < providers.size(); i++) {
            options[i + 1] = providers.get(i).label;
            if (providers.get(i).id().equals(selectedId)) {
                selectedIndex = i + 1;
            }
        }

        View footer = createEngineInfoView(activity, R.string.OfflineTranscriptionInfo, ForkOfflineTranscribe.SUGGESTED_FDROID_URL);
        showRadioDialog(LocaleController.getString(R.string.OfflineTranscription), options, selectedIndex, footer, index -> {
            org.telegram.messenger.forkgram.TranscriberProvider selected = index == 0 ? null : providers.get(index - 1);
            org.telegram.messenger.forkgram.ForkOfflineTranscribe.setProvider(selected);
            listView.adapter.update(false);
            if (selected != null) {
                warnIfModelMissing(selected);
            }
        });
    }

    private void warnIfModelMissing(org.telegram.messenger.forkgram.TranscriberProvider provider) {
        new Thread(() -> {
            org.opentranscribe.api.TranscriberCapabilities caps =
                org.telegram.messenger.forkgram.ForkOfflineTranscribe.capabilitiesOf(provider);
            if (caps != null && !caps.modelReady) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentActivity() == null) {
                        return;
                    }
                    org.telegram.ui.Components.AlertsCreator.showSimpleAlert(
                        ForkSettingsActivity.this,
                        LocaleController.getString(R.string.OfflineTranscription),
                        LocaleController.formatString(R.string.OfflineTranscriptionNoModel, provider.label)
                    );
                });
            }
        }).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    private void exportSettings() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "forkgram_settings.json");
            startActivityForResult(intent, REQUEST_EXPORT_SETTINGS);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void importSettings() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT_SETTINGS);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void showSettingsBackupInfo(String message) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ForkSettingsTitle));
        builder.setMessage(message);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        Context context = ApplicationLoader.applicationContext;
        if (requestCode == REQUEST_EXPORT_SETTINGS) {
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    out.write(SettingsBackup.export(context).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                showSettingsBackupInfo(LocaleController.getString(R.string.ExportSettingsDone));
            } catch (Exception e) {
                FileLog.e(e);
                showSettingsBackupInfo(LocaleController.getString(R.string.ImportSettingsError));
            }
        } else if (requestCode == REQUEST_IMPORT_SETTINGS) {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while (in != null && (read = in.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                boolean ok = SettingsBackup.restore(context, new String(baos.toByteArray(), StandardCharsets.UTF_8));
                showSettingsBackupInfo(LocaleController.getString(ok ? R.string.ImportSettingsRestart : R.string.ImportSettingsError));
            } catch (Exception e) {
                FileLog.e(e);
                showSettingsBackupInfo(LocaleController.getString(R.string.ImportSettingsError));
            }
        }
    }

    public static String GetBotPlatform(int currentAccount, long botId) {
        return MessagesController.getMainSettings(currentAccount).getString("bot_platform_" + botId, "android");
    }

    public static boolean GetBotCopyLink(int currentAccount, long botId) {
        return MessagesController.getMainSettings(currentAccount).getBoolean("bot_copy_link_" + botId, false);
    }
}
