package org.telegram.messenger.forkgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ColoredImageSpan;

import java.util.HashMap;

public class FolderIcons {

    public static final int FOLDER_TABS_STYLE_TEXT = 0;
    public static final int FOLDER_TABS_STYLE_ICON_TEXT = 1;
    public static final int FOLDER_TABS_STYLE_ICON = 2;

    public static final int FLAG_EMOTICON = 1 << 25;

    public static final String PRIVATE = "👤";
    public static final String GROUP = "👥";
    public static final String CHANNELS = "📢";
    public static final String BOTS = "🤖";
    public static final String UNREAD = "✅";
    public static final String UNMUTED = "🔔";
    public static final String ALL = "💬";
    public static final String CUSTOM = "📁";

    public static final String[] EMOTICONS = {
            ALL,
            CUSTOM,
            PRIVATE,
            GROUP,
            CHANNELS,
            BOTS,
            "⭐",         // favorite
            UNREAD,
            UNMUTED,
            "🐱",   // cat
            "📕",   // book
            "💰",   // money
            "🎮",   // game
            "💡",   // light
            "👌",   // like
            "🎵",   // note
            "🎨",   // palette
            "✈️",   // travel
            "⚽️",   // sport
            "🎓",   // study
            "🛫",   // airplane
            "👑",   // crown
            "🌹",   // flower
            "🏠",   // home
            "❤",         // love
            "🎭",   // mask
            "🍸",   // party
            "📈",   // trade
            "💼",   // work
            "📋",   // setup
    };

    private static final int[] ICON_RES = {
            R.drawable.folder_all,
            R.drawable.folder_custom,
            R.drawable.folder_private,
            R.drawable.folder_group,
            R.drawable.folder_channels,
            R.drawable.folder_bots,
            R.drawable.folder_favorite,
            R.drawable.folder_unread,
            R.drawable.folder_unmuted,
            R.drawable.folder_cat,
            R.drawable.folder_book,
            R.drawable.folder_money,
            R.drawable.folder_game,
            R.drawable.folder_light,
            R.drawable.folder_like,
            R.drawable.folder_note,
            R.drawable.folder_palette,
            R.drawable.folder_travel,
            R.drawable.folder_sport,
            R.drawable.folder_study,
            R.drawable.folder_airplane,
            R.drawable.folder_crown,
            R.drawable.folder_flower,
            R.drawable.folder_home,
            R.drawable.folder_love,
            R.drawable.folder_mask,
            R.drawable.folder_party,
            R.drawable.folder_trade,
            R.drawable.folder_work,
            R.drawable.folder_setup,
    };

    private static final HashMap<String, Integer> byEmoticon = new HashMap<>();

    static {
        for (int a = 0; a < EMOTICONS.length; a++) {
            byEmoticon.put(normalize(EMOTICONS[a]), ICON_RES[a]);
        }
    }

    public static int folderTabsStyle() {
        return MessagesController.getGlobalMainSettings().getInt("folderTabsStyle", FOLDER_TABS_STYLE_TEXT);
    }

    @Nullable
    public static String wireEmoticon(TLRPC.DialogFilter newFilter) {
        return (newFilter.flags & FLAG_EMOTICON) != 0 ? newFilter.emoticon : null;
    }

    private static String normalize(String emoticon) {
        if (emoticon == null) {
            return "";
        }
        return emoticon.replace("\uFE0F", "").replace("\uFE0E", "");
    }

    @DrawableRes
    public static int getIconResByEmoticon(@Nullable String emoticon) {
        if (emoticon == null || emoticon.isEmpty()) {
            return 0;
        }
        Integer res = byEmoticon.get(normalize(emoticon));
        return res == null ? 0 : res;
    }

    public static String computeDefaultEmoticon(MessagesController.DialogFilter filter) {
        if (filter == null || filter.isDefault()) {
            return ALL;
        }
        return computeDefaultEmoticon(filter.flags, !filter.alwaysShow.isEmpty(), !filter.neverShow.isEmpty());
    }

    public static String computeDefaultEmoticon(int flags, boolean hasAlwaysShow, boolean hasNeverShow) {
        final int all = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        if (hasAlwaysShow || hasNeverShow || (flags & all) == 0) {
            return CUSTOM;
        }
        final int typed = flags & all;
        if (typed == MessagesController.DIALOG_FILTER_FLAG_CONTACTS
                || typed == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
                || typed == (MessagesController.DIALOG_FILTER_FLAG_CONTACTS | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS)) {
            return PRIVATE;
        } else if (typed == MessagesController.DIALOG_FILTER_FLAG_GROUPS) {
            return GROUP;
        } else if (typed == MessagesController.DIALOG_FILTER_FLAG_CHANNELS) {
            return CHANNELS;
        } else if (typed == MessagesController.DIALOG_FILTER_FLAG_BOTS) {
            return BOTS;
        }
        final int removed = flags & (MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED);
        if (removed == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) {
            return UNREAD;
        } else if (removed == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) {
            return UNMUTED;
        }
        return CUSTOM;
    }

    @DrawableRes
    public static int getIconRes(MessagesController.DialogFilter filter) {
        if (filter == null) {
            return 0;
        }
        int res = getIconResByEmoticon(filter.emoticon);
        if (res != 0) {
            return res;
        }
        return getIconResByEmoticon(computeDefaultEmoticon(filter));
    }

    public static CharSequence applyIcon(CharSequence name, int iconRes) {
        final int style = folderTabsStyle();
        if (iconRes == 0 || style == FOLDER_TABS_STYLE_TEXT) {
            return name;
        }
        SpannableStringBuilder result = new SpannableStringBuilder(" ");
        ColoredImageSpan span = new ColoredImageSpan(iconRes, ColoredImageSpan.ALIGN_CENTER);
        span.setSize(dp(18));
        result.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (style == FOLDER_TABS_STYLE_ICON_TEXT && !TextUtils.isEmpty(name)) {
            result.append("  ");
            result.append(name);
        }
        return result;
    }
}
