package org.telegram.messenger.forkgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class AccountSelector {

    public static boolean hasAccountsToSwitch(int currentAccount) {
        final ArrayList<Integer> accounts = new ArrayList<>();
        HiddenAccountHelper.collectVisibleAccountNumbers(accounts, currentAccount);
        return !accounts.isEmpty();
    }

    public static ActionBarMenuItem addToMenu(BaseFragment fragment, ActionBarMenu menu, int id, Utilities.Callback<Integer> onSelected) {
        final int currentAccount = fragment.getCurrentAccount();
        if (menu == null || !hasAccountsToSwitch(currentAccount)) {
            return null;
        }

        final ActionBarMenuItem item = menu.addItemWithWidth(id, 0, dp(56));

        final TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setTextSize(dp(12));
        avatarDrawable.setInfo(currentAccount, user);

        final BackupImageView imageView = new BackupImageView(menu.getContext());
        imageView.setRoundRadius(dp(18));
        imageView.getImageReceiver().setCurrentAccount(currentAccount);
        final Drawable thumb = user != null && user.photo != null && user.photo.strippedBitmap != null ? user.photo.strippedBitmap : avatarDrawable;
        imageView.setImage(ImageLocation.getForUserOrChat(currentAccount, user, ImageLocation.TYPE_SMALL), "50_50", ImageLocation.getForUserOrChat(user, ImageLocation.TYPE_STRIPPED), "50_50", thumb, user);
        item.addView(imageView, LayoutHelper.createFrame(36, 36, Gravity.CENTER));

        final View.OnTouchListener[] dragOpen = new View.OnTouchListener[1];
        final Runnable rearm = () -> AndroidUtilities.runOnUIThread(() -> item.setOnTouchListener(dragOpen[0]));
        dragOpen[0] = (view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getY() > view.getHeight()) {
                view.cancelLongPress();
                showPopup(fragment, view, onSelected, rearm);
                return true;
            }
            return false;
        };
        item.setOnTouchListener(dragOpen[0]);
        item.setOnClickListener(view -> showPopup(fragment, view, onSelected, rearm));
        item.setOnLongClickListener(view -> {
            showPopup(fragment, view, onSelected, rearm);
            return true;
        });
        return item;
    }

    public static void showPopup(BaseFragment fragment, View anchor, Utilities.Callback<Integer> onSelected) {
        showPopup(fragment, anchor, onSelected, null);
    }

    private static void showPopup(BaseFragment fragment, View anchor, Utilities.Callback<Integer> onSelected, Runnable onDismiss) {
        final int currentAccount = fragment.getCurrentAccount();

        final ArrayList<Integer> accounts = new ArrayList<>();
        HiddenAccountHelper.collectVisibleAccountNumbers(accounts);
        if (!accounts.contains(currentAccount)) {
            accounts.add(0, currentAccount);
        }

        final ItemOptions options = ItemOptions.makeOptions(fragment, anchor);
        options.setMinWidth(230);
        for (int account : accounts) {
            final int selected = account;
            options.addAccount(selected, selected == currentAccount, () -> {
                options.dismiss();
                if (selected != currentAccount) {
                    onSelected.run(selected);
                }
            });
        }

        final ShapeDrawable background = Theme.createRoundRectDrawable(dp(24), fragment.getThemedColor(Theme.key_windowBackgroundWhite));
        background.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        options.setViewAdditionalOffsets(-dp(4), -dp(4), -dp(4), -dp(4));
        options.setScrimViewBackground(background);
        options.translate(0, -dp(4));
        options.setGravity(Gravity.RIGHT);
        if (onDismiss != null) {
            options.setOnDismiss(onDismiss);
        }
        options.show();
    }
}
