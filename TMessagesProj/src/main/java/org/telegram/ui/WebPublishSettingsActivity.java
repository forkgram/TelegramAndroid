package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.forkgram.WebPublishConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineEditText;

import java.util.Map;

public class WebPublishSettingsActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;

    private EditTextBoldCursor baseField;
    private EditTextBoldCursor tokenField;
    private EditTextBoldCursor templateField;

    private TextView baseError;
    private TextView tokenError;
    private TextView templateError;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.WebPublish));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE_BUTTON) {
                    save();
                }
            }
        });
        actionBar.createMenu().addItemWithWidth(DONE_BUTTON, R.drawable.ic_ab_done, AndroidUtilities.dp(56), LocaleController.getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(16);
        content.setPadding(pad, pad, pad, pad);
        scrollView.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.WebPublishInfo));
        info.setTextSize(14);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        baseError = addField(content, context, LocaleController.getString(R.string.WebPublishBase),
            WebPublishConfig.base(), LocaleController.getString(R.string.WebPublishBaseHint),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT, false);
        baseField = lastField;

        tokenError = addField(content, context, LocaleController.getString(R.string.WebPublishToken),
            WebPublishConfig.token(), null,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_NEXT, false);
        tokenField = lastField;
        tokenField.setTransformationMethod(PasswordTransformationMethod.getInstance());

        TextView advancedToggle = new TextView(context);
        advancedToggle.setTextSize(14);
        advancedToggle.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(4));
        advancedToggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        content.addView(advancedToggle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout advancedGroup = new LinearLayout(context);
        advancedGroup.setOrientation(LinearLayout.VERTICAL);
        advancedGroup.setVisibility(View.GONE);
        content.addView(advancedGroup, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final Runnable updateToggle = () -> {
            boolean open = advancedGroup.getVisibility() == View.VISIBLE;
            advancedToggle.setText((open ? "▾ " : "▸ ") + LocaleController.getString(R.string.WebPublishAdvanced));
        };
        updateToggle.run();
        advancedToggle.setOnClickListener(v -> {
            advancedGroup.setVisibility(advancedGroup.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            updateToggle.run();
        });

        templateError = addField(advancedGroup, context, LocaleController.getString(R.string.WebPublishTemplate),
            WebPublishConfig.template(), null,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE, true);
        templateField = lastField;

        return fragmentView;
    }

    private EditTextBoldCursor lastField;

    private TextView addField(LinearLayout content, Context context, String label, String value, String hint, int inputType, int imeOptions, boolean multiline) {
        OutlineEditText outline = new OutlineEditText(context);
        outline.setHint(label);
        EditTextBoldCursor field = outline.getEditText();
        field.setText(value != null ? value : "");
        if (hint != null) {
            field.setHint(hint);
        }
        if (multiline) {
            field.setSingleLine(false);
            field.setMaxLines(Integer.MAX_VALUE);
            field.setMinLines(2);
            field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            field.setHorizontallyScrolling(false);
        }
        field.setInputType(inputType);
        field.setImeOptions(imeOptions);
        int pad = AndroidUtilities.dp(16);
        field.setPadding(AndroidUtilities.dp(15), pad, AndroidUtilities.dp(15), pad);
        content.addView(outline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, AndroidUtilities.dp(2)));
        lastField = field;
        TextView error = makeError(context);
        content.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(10)));
        return error;
    }

    private TextView makeError(Context context) {
        TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        error.setVisibility(View.GONE);
        return error;
    }

    private static void showError(TextView view, String message) {
        if (message == null) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(message);
            view.setVisibility(View.VISIBLE);
        }
    }

    private void save() {
        String base = baseField.getText().toString();
        String token = tokenField.getText().toString().trim();
        String template = templateField.getText().toString();

        Map<String, String> errors = WebPublishConfig.validateValues(base, token, template);
        showError(baseError, errors.get(WebPublishConfig.FIELD_BASE));
        showError(tokenError, errors.get(WebPublishConfig.FIELD_TOKEN));
        showError(templateError, errors.get(WebPublishConfig.FIELD_TEMPLATE));
        if (!errors.isEmpty()) {
            return;
        }

        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(WebPublishConfig.PREF_BASE, base.trim());
        editor.putString(WebPublishConfig.PREF_TOKEN, token);
        editor.putString(WebPublishConfig.PREF_TEMPLATE, template);
        editor.apply();

        if (getParentActivity() != null) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.WebPublishSaved), android.widget.Toast.LENGTH_SHORT).show();
        }
        finishFragment();
    }
}
