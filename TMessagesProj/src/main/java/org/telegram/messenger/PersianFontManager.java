/*
 * This is the source code of Forkgram for Android.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PersianFontManager {

    public static final String FONT_DEFAULT = "default";
    public static final String FONT_VAZIRMATN = "vazirmatn";
    public static final String FONT_FAR_NAZANIN = "far_nazanin";
    public static final String FONT_FAR_TITR = "far_titr";

    public static final String PREF_KEY = "persian_ui_font";

    public static class FontItem {
        public final String key;
        public final String name;
        public final String subtitle;
        public final String regularPath;
        public final String mediumPath;
        public final String boldPath;

        public FontItem(String key, String name, String subtitle, String regularPath, String mediumPath, String boldPath) {
            this.key = key;
            this.name = name;
            this.subtitle = subtitle;
            this.regularPath = regularPath;
            this.mediumPath = mediumPath;
            this.boldPath = boldPath;
        }
    }

    private static final List<FontItem> FONTS;
    static {
        List<FontItem> list = new ArrayList<>();
        list.add(new FontItem(FONT_DEFAULT, "پیش‌فرض تلگرام", "Telegram Default (Roboto)", null, "fonts/rmedium.ttf", "fonts/rextrabold.ttf"));
        list.add(new FontItem(FONT_VAZIRMATN, "وزیرمتن", "Vazirmatn", "fonts/vazirmatn_regular.ttf", "fonts/vazirmatn_medium.ttf", "fonts/vazirmatn_bold.ttf"));
        list.add(new FontItem(FONT_FAR_NAZANIN, "ایران نازنین", "Far Nazanin", "fonts/far_nazanin.ttf", "fonts/far_nazanin.ttf", "fonts/far_nazanin.ttf"));
        list.add(new FontItem(FONT_FAR_TITR, "تیتر", "Far Titr (Bold)", "fonts/far_titr_bold.ttf", "fonts/far_titr_bold.ttf", "fonts/far_titr_bold.ttf"));
        FONTS = Collections.unmodifiableList(list);
    }

    public static List<FontItem> getFonts() {
        return FONTS;
    }

    public static FontItem getFont(String key) {
        if (key == null) {
            return FONTS.get(0);
        }
        for (FontItem item : FONTS) {
            if (item.key.equals(key)) {
                return item;
            }
        }
        return FONTS.get(0);
    }

    public static String getCurrentFontKey() {
        try {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            return preferences.getString(PREF_KEY, FONT_DEFAULT);
        } catch (Throwable ignore) {
            return FONT_DEFAULT;
        }
    }

    public static FontItem getCurrentFont() {
        return getFont(getCurrentFontKey());
    }

    public static void setCurrentFont(String fontKey) {
        try {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            preferences.edit().putString(PREF_KEY, fontKey).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Protected fonts should NEVER be replaced by the Persian font selector.
     * num.otf: numbers in clocks, badges, counters
     * mw_bold.ttf, mw_bolditalic.ttf: instant view serif headers
     * rcondensedbold.ttf: condensed UI labels, badges, audio titles
     * ritalic.ttf, rmediumitalic.ttf: italic message spans
     * rmono.ttf, monospace: code blocks, monospace spans
     */
    public static boolean isProtectedFont(String assetPath) {
        if (assetPath == null) {
            return true;
        }
        String path = assetPath.toLowerCase();
        return path.contains("num.otf") ||
               path.contains("mw_bold.ttf") ||
               path.contains("mw_bolditalic.ttf") ||
               path.contains("rcondensedbold.ttf") ||
               path.contains("ritalic.ttf") ||
               path.contains("rmediumitalic.ttf") ||
               path.contains("rmono.ttf") ||
               path.contains("mono");
    }

    /**
     * Maps an asset path (e.g. fonts/rmedium.ttf) to the corresponding asset path
     * of the currently selected Persian UI font.
     */
    public static String getMappedAssetPath(String assetPath) {
        if (assetPath == null || isProtectedFont(assetPath)) {
            return assetPath;
        }
        String currentKey = getCurrentFontKey();
        if (FONT_DEFAULT.equals(currentKey)) {
            return assetPath;
        }
        FontItem font = getFont(currentKey);
        if (font == null) {
            return assetPath;
        }

        String path = assetPath.toLowerCase();
        if (path.contains("rextrabold") || path.contains("bold")) {
            return font.boldPath != null ? font.boldPath : (font.mediumPath != null ? font.mediumPath : assetPath);
        } else if (path.contains("rmedium") || path.contains("medium")) {
            return font.mediumPath != null ? font.mediumPath : (font.regularPath != null ? font.regularPath : assetPath);
        } else if (path.contains("rregular") || path.contains("regular")) {
            return font.regularPath != null ? font.regularPath : assetPath;
        }
        return assetPath;
    }

    /**
     * Direct Typeface retriever for font preview in UI selectors.
     */
    public static Typeface getTypefaceForFont(String fontKey, boolean isBold) {
        FontItem item = getFont(fontKey);
        if (item == null || FONT_DEFAULT.equals(item.key)) {
            return isBold ? AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM) : Typeface.DEFAULT;
        }
        String targetPath = isBold ? item.boldPath : item.regularPath;
        if (targetPath == null) {
            targetPath = item.mediumPath;
        }
        if (targetPath != null && ApplicationLoader.applicationContext != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    Typeface.Builder builder = new Typeface.Builder(ApplicationLoader.applicationContext.getAssets(), targetPath);
                    if (isBold) {
                        builder.setWeight(700);
                    }
                    return builder.build();
                } else {
                    return Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), targetPath);
                }
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(e);
                }
            }
        }
        return isBold ? AndroidUtilities.bold() : Typeface.DEFAULT;
    }

    /**
     * Applies the chosen font immediately without requiring an application restart.
     */
    public static void applyFont(String fontKey) {
        setCurrentFont(fontKey);
        AndroidUtilities.clearTypefaceCache();

        try {
            if (ApplicationLoader.applicationContext != null) {
                Theme.reloadAllResources(ApplicationLoader.applicationContext);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }

        try {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didApplyNewTheme);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, Theme.getActiveTheme(), false, null, -1);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
