package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.forkgram.VoiceTimecodesConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineEditText;

public class VoiceTimecodesSettingsActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;

    private TextCheckCell enabledCell;
    private EditTextBoldCursor sttUrlField;
    private EditTextBoldCursor sttTokenField;
    private EditTextBoldCursor llmUrlField;
    private EditTextBoldCursor llmTokenField;
    private EditTextBoldCursor llmModelField;
    private EditTextBoldCursor minDurationField;
    private EditTextBoldCursor promptField;
    private EditTextBoldCursor exportCodeField;

    private EditTextBoldCursor lastField;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.VoiceTimecodes));
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
        info.setText(LocaleController.getString(R.string.VoiceTimecodesInfo));
        info.setTextSize(14);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        enabledCell = new TextCheckCell(context);
        enabledCell.setTextAndCheck(LocaleController.getString(R.string.VoiceTimecodesEnabled), VoiceTimecodesConfig.isEnabled(), false);
        enabledCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        enabledCell.setOnClickListener(v -> enabledCell.setChecked(!enabledCell.isChecked()));
        content.addView(enabledCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        addField(content, context, LocaleController.getString(R.string.VoiceTimecodesSttUrl),
                VoiceTimecodesConfig.sttUrl(), LocaleController.getString(R.string.VoiceTimecodesSttUrlHint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT, false);
        sttUrlField = lastField;

        addField(content, context, LocaleController.getString(R.string.VoiceTimecodesSttToken),
                VoiceTimecodesConfig.sttToken(), null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_NEXT, false);
        sttTokenField = lastField;
        sttTokenField.setTransformationMethod(PasswordTransformationMethod.getInstance());

        addField(content, context, LocaleController.getString(R.string.VoiceTimecodesLlmUrl),
                VoiceTimecodesConfig.llmUrl(), LocaleController.getString(R.string.VoiceTimecodesLlmUrlHint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT, false);
        llmUrlField = lastField;

        addField(content, context, LocaleController.getString(R.string.VoiceTimecodesLlmToken),
                VoiceTimecodesConfig.llmToken(), null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_NEXT, false);
        llmTokenField = lastField;
        llmTokenField.setTransformationMethod(PasswordTransformationMethod.getInstance());

        addField(content, context, LocaleController.getString(R.string.VoiceTimecodesLlmModel),
                VoiceTimecodesConfig.llmModel(), LocaleController.getString(R.string.VoiceTimecodesLlmModelHint),
                InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT, false);
        llmModelField = lastField;

        addField(content, context, LocaleController.getString(R.string.VoiceTimecodesMinDuration),
                String.valueOf(VoiceTimecodesConfig.minDuration()), "10",
                InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_NEXT, false);
        minDurationField = lastField;

        addField(content, context, LocaleController.getString(R.string.VoiceTimecodesPrompt),
                VoiceTimecodesConfig.promptTemplate(), null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_NONE, true);
        promptField = lastField;

        TextView resetPrompt = new TextView(context);
        resetPrompt.setText(LocaleController.getString(R.string.VoiceTimecodesResetPrompt));
        resetPrompt.setTextSize(14);
        resetPrompt.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        resetPrompt.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(16));
        resetPrompt.setOnClickListener(v -> promptField.setText(VoiceTimecodesConfig.DEFAULT_PROMPT));
        content.addView(resetPrompt, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView codeInfo = new TextView(context);
        codeInfo.setText(LocaleController.getString(R.string.VoiceTimecodesCodeInfo));
        codeInfo.setTextSize(14);
        codeInfo.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        content.addView(codeInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        OutlineEditText codeOutline = new OutlineEditText(context);
        codeOutline.setHint(LocaleController.getString(R.string.VoiceTimecodesCodeTitle));
        exportCodeField = codeOutline.getEditText();
        exportCodeField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        exportCodeField.setHint(LocaleController.getString(R.string.VoiceTimecodesCodeHint));
        exportCodeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        exportCodeField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        exportCodeField.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(16), AndroidUtilities.dp(15), AndroidUtilities.dp(16));
        content.addView(codeOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        LinearLayout codeActions = new LinearLayout(context);
        codeActions.setOrientation(LinearLayout.HORIZONTAL);

        TextView exportButton = new TextView(context);
        exportButton.setText(LocaleController.getString(R.string.VoiceTimecodesCodeExport));
        exportButton.setTextSize(14);
        exportButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        exportButton.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        exportButton.setPadding(0, 0, 0, AndroidUtilities.dp(16));
        exportButton.setOnClickListener(v -> exportCode());
        codeActions.addView(exportButton, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView importButton = new TextView(context);
        importButton.setText(LocaleController.getString(R.string.VoiceTimecodesCodeImport));
        importButton.setTextSize(14);
        importButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        importButton.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        importButton.setPadding(0, 0, 0, AndroidUtilities.dp(16));
        importButton.setOnClickListener(v -> importCode());
        codeActions.addView(importButton, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        content.addView(codeActions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private void addField(LinearLayout content, Context context, String label, String value, String hint, int inputType, int imeOptions, boolean multiline) {
        OutlineEditText outline = new OutlineEditText(context);
        outline.setHint(label);
        EditTextBoldCursor field = outline.getEditText();
        field.setText(value != null ? value : "");
        if (hint != null) {
            field.setHint(hint);
        }
        field.setInputType(inputType);
        field.setImeOptions(imeOptions);
        if (multiline) {
            field.setSingleLine(false);
            field.setMaxLines(Integer.MAX_VALUE);
            field.setMinLines(3);
            field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            field.setHorizontallyScrolling(false);
        }
        int pad = AndroidUtilities.dp(16);
        field.setPadding(AndroidUtilities.dp(15), pad, AndroidUtilities.dp(15), pad);
        content.addView(outline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, AndroidUtilities.dp(10)));
        lastField = field;
    }

    private void exportCode() {
        int minDuration = VoiceTimecodesConfig.DEFAULT_MIN_DURATION;
        try {
            minDuration = Integer.parseInt(minDurationField.getText().toString().trim());
        } catch (Exception ignore) {
        }
        String code = VoiceTimecodesConfig.exportToString(
                enabledCell.isChecked(),
                sttUrlField.getText().toString(),
                sttTokenField.getText().toString(),
                llmUrlField.getText().toString(),
                llmTokenField.getText().toString(),
                llmModelField.getText().toString(),
                minDuration,
                promptField.getText().toString()
        );
        exportCodeField.setText(code);
        exportCodeField.setSelection(0);
        if (getParentActivity() != null) {
            ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Forkgram voice timecodes", code));
            }
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.VoiceTimecodesCodeCopied), Toast.LENGTH_SHORT).show();
        }
    }

    private void importCode() {
        String clipboardText = readClipboard();
        String fieldText = exportCodeField.getText().toString().trim();
        boolean success = !clipboardText.isEmpty() && VoiceTimecodesConfig.importFromString(clipboardText);
        if (!success && !fieldText.isEmpty()) {
            success = VoiceTimecodesConfig.importFromString(fieldText);
        }
        if (success) {
            refreshFromConfig();
            String code = VoiceTimecodesConfig.exportToString();
            exportCodeField.setText(code);
            exportCodeField.setSelection(0);
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.VoiceTimecodesCodeImported), Toast.LENGTH_SHORT).show();
            }
        } else if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.VoiceTimecodesCodeImportFailed), Toast.LENGTH_SHORT).show();
        }
    }

    private String readClipboard() {
        if (getParentActivity() == null) {
            return "";
        }
        ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) {
            return "";
        }
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData.getItemCount() == 0) {
            return "";
        }
        CharSequence text = clipData.getItemAt(0).coerceToText(getParentActivity());
        return text != null ? text.toString().trim() : "";
    }

    private void refreshFromConfig() {
        enabledCell.setChecked(VoiceTimecodesConfig.isEnabled());
        sttUrlField.setText(VoiceTimecodesConfig.sttUrl());
        sttTokenField.setText(VoiceTimecodesConfig.sttToken());
        llmUrlField.setText(VoiceTimecodesConfig.llmUrl());
        llmTokenField.setText(VoiceTimecodesConfig.llmToken());
        llmModelField.setText(VoiceTimecodesConfig.llmModel());
        minDurationField.setText(String.valueOf(VoiceTimecodesConfig.minDuration()));
        promptField.setText(VoiceTimecodesConfig.promptTemplate());
    }

    private void save() {
        boolean enabled = enabledCell.isChecked();
        String sttUrl = sttUrlField.getText().toString().trim();
        String sttToken = sttTokenField.getText().toString().trim();
        String llmUrl = llmUrlField.getText().toString().trim();
        String llmToken = llmTokenField.getText().toString().trim();
        String llmModel = llmModelField.getText().toString().trim();
        int minDuration = 10;
        try {
            minDuration = Integer.parseInt(minDurationField.getText().toString().trim());
        } catch (Exception ignore) {
        }
        String prompt = promptField.getText().toString();

        VoiceTimecodesConfig.saveValues(enabled, sttUrl, sttToken, llmUrl, llmToken, llmModel, minDuration, prompt);

        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.VoiceTimecodesSaved), Toast.LENGTH_SHORT).show();
        }
        finishFragment();
    }
}
