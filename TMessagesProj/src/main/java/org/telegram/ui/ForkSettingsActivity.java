/*
 * Copyright 23rd, 2019.
 */

package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class ForkSettingsActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private int rowCount;

    // Settings structure
    private static final SettingItem[] SETTINGS = {
        new SettingItem(SettingType.HEADER, "General"),
        new SettingItem(SettingType.SWITCH, "squareAvatars", "Square Avatars", false),
        new SettingItem(SettingType.SWITCH, "photoHasSticker", "Photo Has Sticker", true),
        new SettingItem(SettingType.SWITCH, "showNotificationContent", "Show Notification Content", false),
        new SettingItem(SettingType.SWITCH, "lockPremium", "Lock Premium", false),
        new SettingItem(SettingType.SECTION, null),
        
        new SettingItem(SettingType.HEADER, "Chat List"),
        new SettingItem(SettingType.SWITCH, "syncPins", "Sync Pins", true),
        new SettingItem(SettingType.SWITCH, "unmutedOnTop", "Unmuted On Top", false),
        new SettingItem(SettingType.SWITCH, "openArchiveOnPull", "Open Archive On Pull", false),
        new SettingItem(SettingType.SWITCH, "disableGlobalSearch", "Disable Global Search", false),
        new SettingItem(SettingType.TEXT, "forkCustomTitle", "Custom Title", "Fork Client"),
        new SettingItem(SettingType.SECTION, null),
        
        new SettingItem(SettingType.HEADER, "Filter Chats"),
        new SettingItem(SettingType.SWITCH, "replaceForward", "Replace Forward", true),
        new SettingItem(SettingType.SWITCH, "mentionByName", "Mention By Name", false),
        new SettingItem(SettingType.SWITCH, "rearVideoMessages", "Rear Video Messages", false),
        new SettingItem(SettingType.SWITCH, "fullRecentStickers", "Full Recent Stickers", false),
        new SettingItem(SettingType.SWITCH, "hideSendAs", "Hide Send As", false),
        new SettingItem(SettingType.SECTION, null),
        
        new SettingItem(SettingType.HEADER, "Camera"),
        new SettingItem(SettingType.CAMERA, "inappCamera", "In-App Camera", true),
        new SettingItem(SettingType.SWITCH, "systemCamera", "System Camera", false),
        new SettingItem(SettingType.SECTION, null)
    };

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        rowCount = SETTINGS.length;
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Fork Settings");

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

        listAdapter = new ListAdapter(context);
        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        
        listView.setOnItemClickListener((view, position, x, y) -> {
            SettingItem item = SETTINGS[position];
            if (item.type == SettingType.SWITCH) {
                toggleSetting(item.key, view, item.defaultBoolValue);
            } else if (item.type == SettingType.CAMERA) {
                SharedConfig.toggleInappCamera();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.inappCamera);
                }
                updateSystemCameraState();
            } else if (item.type == SettingType.TEXT) {
                // TODO: Show dialog for text input
            }
            
            // Special handling
            if ("unmutedOnTop".equals(item.key)) {
                MessagesController.getInstance(currentAccount).sortDialogs(null);
            }
        });

        return fragmentView;
    }

    private void toggleSetting(String key, View view, boolean defaultValue) {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        boolean current = prefs.getBoolean(key, defaultValue);
        prefs.edit().putBoolean(key, !current).apply();
        
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(!current);
        }
    }

    private void updateSystemCameraState() {
        for (int i = 0; i < SETTINGS.length; i++) {
            if ("systemCamera".equals(SETTINGS[i].key)) {
                RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(i);
                if (holder != null && holder.itemView instanceof TextCheckCell) {
                    ((TextCheckCell) holder.itemView).setEnabled(SharedConfig.inappCamera);
                }
                break;
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            SettingItem item = SETTINGS[position];
            SharedPreferences prefs = MessagesController.getGlobalMainSettings();
            
            switch (holder.getItemViewType()) {
                case 1: // Header
                    ((HeaderCell) holder.itemView).setText(item.title);
                    break;
                    
                case 2: // TextCheck
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    boolean checked = item.type == SettingType.CAMERA ? 
                        SharedConfig.inappCamera : 
                        prefs.getBoolean(item.key, item.defaultBoolValue);
                    textCheckCell.setTextAndCheck(item.title, checked, position < rowCount - 1);
                    
                    if ("systemCamera".equals(item.key)) {
                        textCheckCell.setEnabled(SharedConfig.inappCamera);
                    }
                    break;
                    
                case 3: // TextSettings
                    TextSettingsCell textSettingsCell = (TextSettingsCell) holder.itemView;
                    String value = prefs.getString(item.key, item.defaultStringValue);
                    textSettingsCell.setTextAndValue(item.title, value, false);
                    break;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            SettingItem item = SETTINGS[position];
            return item.type != SettingType.HEADER && item.type != SettingType.SECTION;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 1:
                    view = new HeaderCell(mContext);
                    break;
                case 2:
                    view = new TextCheckCell(mContext);
                    break;
                case 3:
                    view = new TextSettingsCell(mContext);
                    break;
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) {
            SettingItem item = SETTINGS[position];
            switch (item.type) {
                case HEADER:
                    return 1;
                case SWITCH:
                case CAMERA:
                    return 2;
                case TEXT:
                    return 3;
                case SECTION:
                default:
                    return 0;
            }
        }
    }

    // Helper classes
    private enum SettingType {
        HEADER, SWITCH, TEXT, CAMERA, SECTION
    }

    private static class SettingItem {
        final SettingType type;
        final String key;
        final String title;
        final boolean defaultBoolValue;
        final String defaultStringValue;

        SettingItem(SettingType type, String title) {
            this(type, null, title, false, null);
        }

        SettingItem(SettingType type, String key, String title, boolean defaultValue) {
            this(type, key, title, defaultValue, null);
        }

        SettingItem(SettingType type, String key, String title, String defaultValue) {
            this(type, key, title, false, defaultValue);
        }

        SettingItem(SettingType type, String key, String title, boolean defaultBoolValue, String defaultStringValue) {
            this.type = type;
            this.key = key;
            this.title = title;
            this.defaultBoolValue = defaultBoolValue;
            this.defaultStringValue = defaultStringValue;
        }
    }

    public static String GetBotPlatform(int currentAccount, long botId) {
        return MessagesController.getMainSettings(currentAccount).getString("bot_platform_" + botId, "android");
    }

    public static boolean GetBotCopyLink(int currentAccount, long botId) {
        return MessagesController.getMainSettings(currentAccount).getBoolean("bot_copy_link_" + botId, false);
    }
}