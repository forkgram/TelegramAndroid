package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.forkgram.QuickAttachConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class QuickAttachSettingsActivity extends BaseFragment {

    private static final int ID_ENABLED = 1;
    private static final int ID_SEND_IMMEDIATELY = 2;
    private static final int ID_NEWEST_FIRST = 3;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.QuickAttach));
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

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setGlowColor(Theme.getColor(Theme.key_avatar_backgroundActionBarBlue));
        ((FrameLayout) fragmentView).addView(listView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = QuickAttachConfig.isEnabled();
        items.add(UItem.asButtonCheck(ID_ENABLED,
                LocaleController.getString(R.string.QuickAttach),
                LocaleController.getString(R.string.QuickAttachInfo))
            .setChecked(enabled)
            .setMultiline(true));
        items.add(UItem.asButtonCheck(ID_SEND_IMMEDIATELY,
                LocaleController.getString(R.string.QuickAttachSendImmediately),
                LocaleController.getString(R.string.QuickAttachSendImmediatelyInfo))
            .setChecked(QuickAttachConfig.sendImmediately())
            .setEnabled(enabled)
            .setMultiline(true));
        items.add(UItem.asButtonCheck(ID_NEWEST_FIRST,
                LocaleController.getString(R.string.QuickAttachNewestFirst),
                LocaleController.getString(R.string.QuickAttachNewestFirstInfo))
            .setChecked(QuickAttachConfig.newestFirst())
            .setEnabled(enabled)
            .setMultiline(true));
        items.add(UItem.asShadow(null));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        final int id = item.id;
        if (id == ID_ENABLED) {
            toggle(QuickAttachConfig.PREF_ENABLED, item, view);
            listView.adapter.update(true);
        } else if (id == ID_SEND_IMMEDIATELY) {
            if (QuickAttachConfig.isEnabled()) {
                toggle(QuickAttachConfig.PREF_SEND_IMMEDIATELY, item, view);
            }
        } else if (id == ID_NEWEST_FIRST) {
            if (QuickAttachConfig.isEnabled()) {
                toggle(QuickAttachConfig.PREF_NEWEST_FIRST, item, view);
            }
        }
    }

    private void toggle(String option, UItem item, View view) {
        final boolean value = !item.checked;
        item.checked = value;
        QuickAttachConfig.prefs().edit().putBoolean(option, value).commit();
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        } else if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(value);
        }
    }
}
