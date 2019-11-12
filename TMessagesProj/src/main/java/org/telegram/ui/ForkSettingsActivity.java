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
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
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

    public static final int ID_HIDE_BOTTOM_BUTTON = 11;
    public static final int ID_SQUARE_AVATARS = 14;

    public static final int ID_SYNC_PINS = 20;
    public static final int ID_UNMUTED_ON_TOP = 21;

    public static final int ID_REPLACE_FORWARD = 30;
    public static final int ID_MENTION_BY_NAME = 31;

    public static final int ID_INAPP_CAMERA = 50;
    public static final int ID_SYSTEM_CAMERA = 51;
    public static final int ID_PHOTO_HAS_STICKER = 52;
    public static final int ID_REAR_VIDEO_MESSAGES = 55;

    private static final int MENU_SEARCH = 100;

    private UniversalRecyclerView listView;
    private ActionBarMenuItem searchItem;
    private String searchQuery = "";
    private int highlightItemId;

    public ForkSettingsActivity highlight(int itemId) {
        highlightItemId = itemId;
        return this;
    }

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    private static boolean pref(String option, boolean byDefault) {
        return prefs().getBoolean(option, byDefault);
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
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ForkSectionAppearance)));
        items.add(UItem.asButtonCheck(ID_SQUARE_AVATARS, LocaleController.getString(R.string.SquareAvatars), LocaleController.getString(R.string.ForkRestartRequired))
            .setChecked(pref("squareAvatars", false)).setMultiline(true));
        if (SharedConfig.isUserOwner()) {
            items.add(UItem.asButtonCheck(ID_HIDE_BOTTOM_BUTTON, LocaleController.getString(R.string.HideBottomButton), LocaleController.getString(R.string.HideBottomButtonInfo))
                .setChecked(pref("hideBottomButton", false)).setMultiline(true));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ChatList)));
        items.add(UItem.asButtonCheck(ID_SYNC_PINS, LocaleController.getString(R.string.SyncPins), LocaleController.getString(R.string.SyncPinsInfo))
            .setChecked(pref("syncPins", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_UNMUTED_ON_TOP, LocaleController.getString(R.string.UnmutedOnTop), LocaleController.getString(R.string.UnmutedOnTopInfo))
            .setChecked(pref("unmutedOnTop", false)).setMultiline(true));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.FilterChats)));
        items.add(UItem.asButtonCheck(ID_REPLACE_FORWARD, LocaleController.getString(R.string.ReplaceForward), LocaleController.getString(R.string.ReplaceForwardInfo))
            .setChecked(pref("replaceForward", true)).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_MENTION_BY_NAME, LocaleController.getString(R.string.MentionByName), LocaleController.getString(R.string.MentionByNameInfo))
            .setChecked(pref("mentionByName", false)).setMultiline(true));
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
        items.add(UItem.asButtonCheck(ID_REAR_VIDEO_MESSAGES, LocaleController.getString(R.string.RearVideoMessages), LocaleController.getString(R.string.RearVideoMessagesInfo))
            .setChecked(pref("rearVideoMessages", false)).setMultiline(true));
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
        } else if (id == ID_SQUARE_AVATARS) {
            toggle("squareAvatars", item, view);
        } else if (id == ID_HIDE_BOTTOM_BUTTON) {
            toggle("hideBottomButton", item, view);
        } else if (id == ID_SYNC_PINS) {
            toggle("syncPins", item, view);
        } else if (id == ID_UNMUTED_ON_TOP) {
            toggle("unmutedOnTop", item, view);
            MessagesController.getInstance(currentAccount).sortDialogs(null);
        } else if (id == ID_REPLACE_FORWARD) {
            toggle("replaceForward", item, view);
        } else if (id == ID_MENTION_BY_NAME) {
            toggle("mentionByName", item, view);
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
        } else if (id == ID_REAR_VIDEO_MESSAGES) {
            toggle("rearVideoMessages", item, view);
        }
    }

    private void showRadioDialog(CharSequence title, String[] options, int selectedIndex, Utilities.Callback<Integer> onSelected) {
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

        builder.setView(linearLayout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    @Override

}
