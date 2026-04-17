package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Paint;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.forkgram.HiddenAccountHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class HiddenAccountsActivity extends UniversalFragment {

    private static final int ID_STEALTH_MODE = 1;
    private static final int ID_SETTINGS_ONLY_WHEN_HIDDEN = 2;
    private static final int ACCOUNT_ID_OFFSET = 100;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HiddenAccounts);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!HiddenAccountHelper.shouldShowSettingsEntry(currentAccount)) {
            finishFragment();
            return;
        }

        boolean hasPasscode = !SharedConfig.passcodeHash.isEmpty();
        boolean canUseHiddenAccounts = HiddenAccountHelper.canUseHiddenAccounts();
        boolean hasHiddenAccounts = HiddenAccountHelper.hasAnyHiddenAccounts();

        items.add(UItem.asHeader(LocaleController.getString(R.string.HiddenAccountsOptions)));
        items.add(UItem.asCheck(ID_STEALTH_MODE, LocaleController.getString(R.string.HiddenAccountsStealthMode))
                .setChecked(HiddenAccountHelper.isStealthModeEnabled())
                .setEnabled(!hasPasscode));
        items.add(UItem.asCheck(ID_SETTINGS_ONLY_WHEN_HIDDEN, LocaleController.getString(R.string.HiddenAccountsOnlyShowWhenSignedInAsHiddenAccount))
                .setChecked(HiddenAccountHelper.isSettingsVisibleOnlyWhenSignedInAsHiddenAccount())
                .setEnabled(hasHiddenAccounts));
        if (hasPasscode) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsPasscodeModeInfo)));
        } else if (HiddenAccountHelper.isStealthModeEnabled()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsStealthModeInfo)));
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsNeedsPasscodeOrStealthMode)));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.HiddenAccountsAccounts)));
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setTextSize(AndroidUtilities.dp(16));
        int titleMaxWidth = AndroidUtilities.displaySize.x - AndroidUtilities.dp(160);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user == null) {
                continue;
            }
            String value = LocaleController.getString(HiddenAccountHelper.isAccountHidden(a) ? R.string.HiddenAccountsStateHidden : R.string.HiddenAccountsStateVisible);
            CharSequence name = TextUtils.ellipsize(UserObject.getUserName(user), titlePaint, titleMaxWidth, TextUtils.TruncateAt.END);
            items.add(UItem.asButton(ACCOUNT_ID_OFFSET + a, R.drawable.msg2_secret, name, value).setEnabled(canUseHiddenAccounts || HiddenAccountHelper.isAccountHidden(a)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsAccountsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_STEALTH_MODE) {
            toggleStealthMode();
            return;
        }
        if (item.id == ID_SETTINGS_ONLY_WHEN_HIDDEN) {
            toggleSettingsVisibility();
            return;
        }
        if (item.id >= ACCOUNT_ID_OFFSET) {
            int account = item.id - ACCOUNT_ID_OFFSET;
            if (HiddenAccountHelper.isAccountHidden(account)) {
                showHiddenAccountActions(account);
            } else {
                startHideAccountFlow(account);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!HiddenAccountHelper.shouldShowSettingsEntry(currentAccount)) {
            finishFragment();
            return;
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void toggleStealthMode() {
        boolean enabled = !HiddenAccountHelper.isStealthModeEnabled();
        if (enabled && !SharedConfig.passcodeHash.isEmpty()) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsStealthModeRequiresPasscodeOff));
            return;
        }
        if (!enabled && SharedConfig.passcodeHash.isEmpty() && HiddenAccountHelper.hasAnyHiddenAccounts()) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsDisableStealthNeedsPasscode));
            return;
        }
        HiddenAccountHelper.setStealthModeEnabled(enabled);
        notifySettingsChanged(UserConfig.selectedAccount);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void toggleSettingsVisibility() {
        if (!HiddenAccountHelper.hasAnyHiddenAccounts()) {
            return;
        }
        HiddenAccountHelper.setSettingsVisibleOnlyWhenSignedInAsHiddenAccount(!HiddenAccountHelper.isSettingsVisibleOnlyWhenSignedInAsHiddenAccount());
        notifySettingsChanged(UserConfig.selectedAccount);
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void startHideAccountFlow(int account) {
        if (!HiddenAccountHelper.canUseHiddenAccounts()) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsNeedsPasscodeOrStealthMode));
            return;
        }
        if (!HiddenAccountHelper.canHideAccount(account)) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsNeedVisibleAccount));
            return;
        }
        showSetUnlockCodeDialog(account, false);
    }

    private void showHiddenAccountActions(int account) {
        showCurrentCodeDialog(account, () -> showHiddenAccountActionsVerified(account));
    }

    private void showHiddenAccountActionsVerified(int account) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        CharSequence[] items = new CharSequence[] {
                LocaleController.getString(R.string.HiddenAccountsChangeUnlockCode),
                LocaleController.getString(R.string.HiddenAccountsRemoveHiddenAccount)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser()));
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                showSetUnlockCodeDialog(account, true);
            } else if (which == 1) {
                showRemoveHiddenAccountDialog(account);
            }
        });
        showDialog(builder.create());
    }

    private void showCurrentCodeDialog(int account, Runnable onVerified) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor codeField = createCodeField(context, LocaleController.getString(R.string.HiddenAccountsCurrentUnlockCode));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        layout.addView(codeField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.FILL_HORIZONTAL, 0, 8, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.HiddenAccountsConfirmCurrentUnlockCode));
        builder.setMessage(LocaleController.getString(R.string.HiddenAccountsConfirmCurrentUnlockCodeInfo));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Continue), null);

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(dialog -> {
            codeField.requestFocus();
            AndroidUtilities.showKeyboard(codeField);
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!HiddenAccountHelper.verifyUnlockCode(account, codeField.getText().toString())) {
                    AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsIncorrectCurrentUnlockCode));
                    return;
                }
                alertDialog.dismiss();
                onVerified.run();
            });
        });
        showDialog(alertDialog);
    }

    private void showRemoveHiddenAccountDialog(int account) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog alertDialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.HiddenAccountsRemoveHiddenAccount))
                .setMessage(LocaleController.getString(R.string.HiddenAccountsRemoveHiddenAccountConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.HiddenAccountsShowAccount), (dialog, which) -> {
                    HiddenAccountHelper.removeHiddenAccount(account);
                    if (UserConfig.selectedAccount == account) {
                        HiddenAccountHelper.clearUnlockedHiddenAccount();
                    }
                    notifySettingsChanged(account);
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                })
                .create();
        showDialog(alertDialog);
        TextView button = (TextView) alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void showSetUnlockCodeDialog(int account, boolean replacing) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        EditTextBoldCursor firstField = createCodeField(context, LocaleController.getString(R.string.HiddenAccountsEnterUnlockCode));
        EditTextBoldCursor secondField = createCodeField(context, LocaleController.getString(R.string.HiddenAccountsConfirmUnlockCode));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        layout.addView(firstField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.FILL_HORIZONTAL, 0, 8, 0, 0));
        layout.addView(secondField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.FILL_HORIZONTAL, 0, 12, 0, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(replacing ? R.string.HiddenAccountsChangeUnlockCode : R.string.HiddenAccountsHideAccount));
        builder.setMessage(LocaleController.getString(R.string.HiddenAccountsUnlockCodeInfo));
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(replacing ? R.string.Save : R.string.HiddenAccountsHideAccountAction), null);

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(dialog -> {
            firstField.requestFocus();
            AndroidUtilities.showKeyboard(firstField);
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String firstCode = firstField.getText().toString();
                String secondCode = secondField.getText().toString();
                int validation = HiddenAccountHelper.validateUnlockCode(account, firstCode);
                if (validation == HiddenAccountHelper.VALIDATE_CODE_INVALID) {
                    AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsInvalidUnlockCode));
                    return;
                } else if (validation == HiddenAccountHelper.VALIDATE_CODE_DUPLICATE) {
                    AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsDuplicateUnlockCode));
                    return;
                } else if (validation == HiddenAccountHelper.VALIDATE_CODE_APP_PASSCODE) {
                    AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsUnlockCodeMatchesAppPasscode));
                    return;
                }
                if (!TextUtils.equals(firstCode, secondCode)) {
                    AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsUnlockCodesDoNotMatch));
                    return;
                }
                HiddenAccountHelper.setHiddenAccountCode(account, firstCode);
                HiddenAccountHelper.clearUnlockedHiddenAccount();
                alertDialog.dismiss();
                notifySettingsChanged(account);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                if (!replacing && UserConfig.selectedAccount == account && LaunchActivity.instance != null) {
                    int fallbackAccount = HiddenAccountHelper.getFallbackVisibleAccount(account);
                    if (fallbackAccount >= 0) {
                        LaunchActivity.instance.switchToAccount(fallbackAccount, true);
                    }
                }
            });
        });
        showDialog(alertDialog);
    }

    private EditTextBoldCursor createCodeField(Context context, CharSequence hint) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint(hint);
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        editText.setSingleLine(true);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        return editText;
    }

    private void notifySettingsChanged(int account) {
        ContactsController.getInstance(account).checkAppAccount();
        NotificationsController.getInstance(account).showNotifications();
        MediaDataController.getInstance(UserConfig.selectedAccount).buildShortcuts();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }
}
