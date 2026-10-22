package org.telegram.ui.Components;

import org.telegram.messenger.FileLog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;

public class OutlineEditText extends OutlineTextContainerView implements Theme.Colorable {

    EditTextBoldCursor editText;

    public OutlineEditText(Context context) {
        super(context);

        editText = new EditTextBoldCursor(context) {
            @Override
            protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
                super.onFocusChanged(focused, direction, previouslyFocusedRect);
                animateSelection(focused || isFocused() ? 1f : 0f);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                canvas.save();
                int right = getWidth() - getPaddingRight();
                int left = getPaddingLeft();
                if (right > left) {
                    canvas.clipRect(getScrollX() + left, 0, getScrollX() + right, getHeight());
                }
                super.onDraw(canvas);
                canvas.restore();
            }
        };
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        ThemeBind.textColor(editText, Theme.key_windowBackgroundWhiteBlackText);
        ThemeBind.hintTextColor(editText, Theme.key_windowBackgroundWhiteHintText);
        editText.setBackground(null);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setTypeface(Typeface.DEFAULT);
        ThemeBind.cursorColor(editText, Theme.key_windowBackgroundWhiteInputFieldActivated);
        editText.setCursorWidth(1.5f);
        editText.setPadding(
                AndroidUtilities.dp(15), 0, AndroidUtilities.dp(15), 0
        );
        attachEditText(editText);

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                invalidate();
            }
        });

        addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

    }

    public void setHint(String hint) {
        setText(hint);
    }

    public EditTextBoldCursor getEditText() {
        return editText;
    }

    @Override
    public void updateColors() {
        super.updateColors();
        try {
            invalidate();
    
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
