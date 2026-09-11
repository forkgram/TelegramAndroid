/*
 * This is the source code of Forkgram for Android.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PersianFontManager;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

public class FontSelectBottomSheet extends BottomSheet {

    public interface FontSelectCallback {
        void onFontSelected(String fontKey);
    }

    public static void show(BaseFragment fragment, FontSelectCallback callback) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        FontSelectBottomSheet sheet = new FontSelectBottomSheet(fragment.getParentActivity(), fragment, callback);
        fragment.showDialog(sheet);
    }

    private final BaseFragment fragment;
    private final FontSelectCallback callback;
    private final ArrayList<FontOptionCell> cells = new ArrayList<>();

    public FontSelectBottomSheet(Context context, BaseFragment fragment, FontSelectCallback callback) {
        super(context, false, fragment != null ? fragment.getResourceProvider() : null);
        this.fragment = fragment;
        this.callback = callback;
        fixNavigationBar();

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(16));

        // Header Title
        TextView titleView = new TextView(context);
        titleView.setText(LocaleController.getString("AppFontSelector", R.string.AppFontSelector));
        titleView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        linearLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 22, 8, 22, 4));

        // Subtitle
        TextView subtitleView = new TextView(context);
        subtitleView.setText(LocaleController.getString("AppFontSelectorInfo", R.string.AppFontSelectorInfo));
        subtitleView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        linearLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 22, 0, 22, 14));

        String currentFontKey = PersianFontManager.getCurrentFontKey();
        List<PersianFontManager.FontItem> fonts = PersianFontManager.getFonts();

        for (int i = 0; i < fonts.size(); i++) {
            PersianFontManager.FontItem item = fonts.get(i);
            boolean isSelected = item.key.equals(currentFontKey);
            boolean isLast = (i == fonts.size() - 1);

            FontOptionCell cell = new FontOptionCell(context, resourcesProvider);
            cell.setFontItem(item, isSelected, !isLast);
            cell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(v -> {
                String selectedKey = item.key;
                PersianFontManager.applyFont(selectedKey);
                for (FontOptionCell c : cells) {
                    c.setChecked(c.fontItem.key.equals(selectedKey));
                }
                if (callback != null) {
                    callback.onFontSelected(selectedKey);
                }
                dismiss();
            });
            cells.add(cell);
            linearLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(linearLayout);
        setCustomView(scrollView);
    }

    public static class FontOptionCell extends FrameLayout {
        public PersianFontManager.FontItem fontItem;
        private final TextView titleView;
        private final TextView sampleView;
        private final RadioButton radioButton;
        private boolean needDivider;
        private final Theme.ResourcesProvider resourcesProvider;

        public FontOptionCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            radioButton = new RadioButton(context);
            radioButton.setSize(AndroidUtilities.dp(20));
            radioButton.setColor(Theme.getColor(Theme.key_dialogRadioBackground, resourcesProvider), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resourcesProvider));
            addView(radioButton, LayoutHelper.createFrame(22, 22, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, (LocaleController.isRTL ? 0 : 20), 0, (LocaleController.isRTL ? 20 : 0), 0));

            LinearLayout textContainer = new LinearLayout(context);
            textContainer.setOrientation(LinearLayout.VERTICAL);

            titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            textContainer.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));

            sampleView = new TextView(context);
            sampleView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
            sampleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sampleView.setSingleLine(true);
            sampleView.setEllipsize(TextUtils.TruncateAt.END);
            sampleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            textContainer.addView(sampleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            addView(textContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, (LocaleController.isRTL ? 22 : 58), 12, (LocaleController.isRTL ? 58 : 22), 12));
        }

        public void setFontItem(PersianFontManager.FontItem item, boolean checked, boolean divider) {
            this.fontItem = item;
            this.needDivider = divider;

            titleView.setText(item.name + " (" + item.subtitle + ")");
            sampleView.setText(LocaleController.getString("AppFontPreviewSample", R.string.AppFontPreviewSample));

            Typeface tfBold = PersianFontManager.getTypefaceForFont(item.key, true);
            Typeface tfRegular = PersianFontManager.getTypefaceForFont(item.key, false);

            if (tfBold != null) {
                titleView.setTypeface(tfBold);
            }
            if (tfRegular != null) {
                sampleView.setTypeface(tfRegular);
            }

            radioButton.setChecked(checked, false);
            setWillNotDraw(!divider);
        }

        public void setChecked(boolean checked) {
            radioButton.setChecked(checked, true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(58), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(58) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
