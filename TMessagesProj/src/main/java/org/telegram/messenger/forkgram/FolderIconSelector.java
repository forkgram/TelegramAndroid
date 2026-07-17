package org.telegram.messenger.forkgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public class FolderIconSelector {

    public static void show(BaseFragment fragment, String currentEmoticon, Utilities.Callback<String> onSelect) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        RecyclerListView listView = new RecyclerListView(context, resourcesProvider);
        listView.setLayoutManager(new GridLayoutManager(context, 6));
        listView.setPadding(dp(10), 0, dp(10), dp(10));
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(56 * 6)));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                FolderIconCell cell = new FolderIconCell(context);
                cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(56)));
                return new RecyclerListView.Holder(cell);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                FolderIconCell cell = (FolderIconCell) holder.itemView;
                if (position == 0) {
                    cell.set(R.drawable.msg_folders, TextUtils.isEmpty(currentEmoticon));
                } else {
                    String emoticon = FolderIcons.EMOTICONS[position - 1];
                    cell.set(FolderIcons.getIconResByEmoticon(emoticon), TextUtils.equals(currentEmoticon, emoticon));
                }
            }

            @Override
            public int getItemCount() {
                return 1 + FolderIcons.EMOTICONS.length;
            }
        });

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.FolderIcon), true);
        builder.setCustomView(listView);
        BottomSheet sheet = builder.create();
        listView.setOnItemClickListener((view, position) -> {
            onSelect.run(position == 0 ? null : FolderIcons.EMOTICONS[position - 1]);
            sheet.dismiss();
        });
        fragment.showDialog(sheet);
    }

    private static class FolderIconCell extends FrameLayout {

        private final ImageView imageView;
        private boolean selected;
        private final Paint selectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public FolderIconCell(Context context) {
            super(context);
            setWillNotDraw(false);
            imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            addView(imageView, LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        }

        public void set(int iconRes, boolean selected) {
            this.selected = selected;
            Drawable drawable = iconRes != 0 ? getResources().getDrawable(iconRes).mutate() : null;
            if (drawable != null) {
                drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(selected ? Theme.key_featuredStickers_buttonText : Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
            }
            imageView.setImageDrawable(drawable);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (selected) {
                selectPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, dp(20), selectPaint);
            }
        }
    }
}
