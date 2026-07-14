/*
 * Copyright 23rd, 2019.
 */

package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.forkgram.FormattingMenu;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class FormattingMenuActivity extends BaseFragment {

    private static final int MENU_RESET = 1;

    private UniversalRecyclerView listView;
    private final ArrayList<String> order = new ArrayList<>();
    private final ArrayList<String> hidden = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        final FormattingMenu formatting = FormattingMenu.load();
        order.addAll(formatting.getOrder());
        hidden.addAll(formatting.getHidden());
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.FormattingMenu));
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_RESET) {
                    resetToDefault();
                }
            }
        });

        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_RESET, R.drawable.msg_reset)
            .setContentDescription(LocaleController.getString(R.string.Reset));

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.listenReorder(this::whenReordered);
        listView.allowReorder(true);
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        adapter.reorderSectionStart();
        for (String key : order) {
            items.add(FormattingOptionCell.Factory.of(key, !hidden.contains(key)));
        }
        adapter.reorderSectionEnd();
        items.add(UItem.asShadow(LocaleController.getString(R.string.FormattingMenuInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof String) || !(view instanceof FormattingOptionCell)) {
            return;
        }
        final String key = (String) item.object;
        if (hidden.contains(key)) {
            hidden.remove(key);
        } else {
            hidden.add(key);
        }
        final boolean visible = !hidden.contains(key);
        item.checked = visible;
        ((FormattingOptionCell) view).setChecked(visible, true);
        FormattingMenu.save(order, hidden);
    }

    private void whenReordered(int sectionId, ArrayList<UItem> items) {
        order.clear();
        for (UItem item : items) {
            if (item.object instanceof String) {
                order.add((String) item.object);
            }
        }
        FormattingMenu.save(order, hidden);
    }

    private void resetToDefault() {
        FormattingMenu.reset();
        final FormattingMenu formatting = FormattingMenu.load();
        order.clear();
        order.addAll(formatting.getOrder());
        hidden.clear();
        hidden.addAll(formatting.getHidden());
        listView.adapter.update(true);
    }

    private static class FormattingOptionCell extends FrameLayout {

        private final CheckBox2 checkBox;
        private final TextView textView;
        private boolean needDivider;

        public FormattingOptionCell(Context context) {
            super(context);

            checkBox = new CheckBox2(context, 21);
            checkBox.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
            checkBox.setDrawUnchecked(true);
            checkBox.setDrawBackgroundAsArc(10);
            addView(checkBox, LayoutHelper.createFrameRelatively(21, 21, Gravity.START | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
            addView(textView, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.START, 60, 0, 56, 0));

            final ImageView reorderView = new ImageView(context);
            reorderView.setScaleType(ImageView.ScaleType.CENTER);
            reorderView.setImageResource(R.drawable.list_reorder);
            reorderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY));
            addView(reorderView, LayoutHelper.createFrameRelatively(50, 50, Gravity.END | Gravity.CENTER_VERTICAL));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50), MeasureSpec.EXACTLY)
            );
        }

        public void set(String key, boolean checked, boolean divider) {
            textView.setText(FormattingMenu.getTitle(key));
            checkBox.setChecked(checked, false);
            needDivider = divider;
            setWillNotDraw(!divider);
        }

        public void setChecked(boolean checked, boolean animated) {
            checkBox.setChecked(checked, animated);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(
                    AndroidUtilities.dp(LocaleController.isRTL ? 0 : 60),
                    getMeasuredHeight() - 1,
                    getMeasuredWidth() - AndroidUtilities.dp(LocaleController.isRTL ? 60 : 0),
                    getMeasuredHeight() - 1,
                    Theme.dividerPaint
                );
            }
        }

        public static final class Factory extends UItem.UItemFactory<FormattingOptionCell> {
            static { setup(new Factory()); }

            @Override
            public FormattingOptionCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new FormattingOptionCell(context);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((FormattingOptionCell) view).set((String) item.object, item.checked, divider);
            }

            public static UItem of(String key, boolean checked) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = FormattingMenu.DEFAULT_ORDER.indexOf(key) + 1;
                item.object = key;
                item.checked = checked;
                return item;
            }
        }
    }
}
