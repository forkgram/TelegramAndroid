package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.forkgram.QuickAttachConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

public class QuickAttachOverlay extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    public interface Delegate {
        void onCameraSelected();
        void onPhotoSelected(MediaController.PhotoEntry photoEntry);
        void onSettingsSelected();
        void onDismiss();
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private Delegate delegate;

    private final View dimView;
    private final FrameLayout cancelButton;
    private final ImageView attachIconView;
    private final ImageView cancelIconView;
    private final FrameLayout settingsButton;
    private View originalAttachButton;

    private final List<CardItem> cardItems = new ArrayList<>();
    private final RectF sourceRect = new RectF();

    private int highlightedIndex = -1;
    private boolean cancelHighlighted = false;
    private boolean settingsHighlighted = false;
    private boolean dismissing = false;
    private boolean presenting = false;
    private boolean newestFirst = false;

    private final int itemSide;
    private final int itemSpacing;
    private final int hitSlop;
    private final int settingsSide;
    private final int settingsGap;

    private float settingsDestX;
    private float settingsDestY;
    private float settingsTargetCenterX;
    private float settingsTargetCenterY;
    private QuickAttachTuning.SpringEvaluator settingsSpringX;
    private QuickAttachTuning.SpringEvaluator settingsSpringY;

    private ValueAnimator presentAnimator;
    private ValueAnimator cancelAnimator;

    private static class CardItem {
        final FrameLayout view;
        final BackupImageView imageView;
        final ImageView cameraIconView;
        final View veilView;
        final ImageView playIconView;
        final TextView durationTextView;
        final MediaController.PhotoEntry photoEntry;
        final boolean isCamera;
        final int placeholderIndex;
        MediaController.PhotoEntry boundEntry;

        float destX;
        float destY;
        float targetCenterX;
        float targetCenterY;
        float currentCornerRadius;

        QuickAttachTuning.SpringEvaluator springX;
        QuickAttachTuning.SpringEvaluator springY;

        CardItem(FrameLayout view, BackupImageView imageView, ImageView cameraIconView,
                 View veilView, ImageView playIconView, TextView durationTextView,
                 MediaController.PhotoEntry photoEntry, boolean isCamera, int placeholderIndex) {
            this.view = view;
            this.imageView = imageView;
            this.cameraIconView = cameraIconView;
            this.veilView = veilView;
            this.playIconView = playIconView;
            this.durationTextView = durationTextView;
            this.photoEntry = photoEntry;
            this.isCamera = isCamera;
            this.placeholderIndex = placeholderIndex;
        }

        void setCornerRadius(float radius) {
            currentCornerRadius = radius;
            view.invalidateOutline();
            if (imageView != null) {
                imageView.setRoundRadius((int) radius);
            }
        }
    }

    public QuickAttachOverlay(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        itemSide = AndroidUtilities.dp(68);
        itemSpacing = AndroidUtilities.dp(8);
        hitSlop = AndroidUtilities.dp(16);

        dimView = new View(context);
        dimView.setBackgroundColor(0x40000000);
        dimView.setAlpha(0.0f);
        addView(dimView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        cancelButton = new FrameLayout(context);
        int iconColor = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider);
        int buttonBgColor = Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider);
        if (buttonBgColor == 0) {
            buttonBgColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        }

        View circleBg = new View(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(buttonBgColor);
        circleBg.setBackground(bg);
        circleBg.setElevation(AndroidUtilities.dp(3));
        cancelButton.addView(circleBg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final float iconElevation = AndroidUtilities.dp(3) + 1;

        attachIconView = new ImageView(context);
        attachIconView.setImageResource(R.drawable.msg_input_attach2);
        attachIconView.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
        attachIconView.setScaleType(ImageView.ScaleType.CENTER);
        attachIconView.setElevation(iconElevation);
        cancelButton.addView(attachIconView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        cancelIconView = new ImageView(context);
        cancelIconView.setImageResource(R.drawable.ic_ab_close);
        cancelIconView.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        cancelIconView.setScaleType(ImageView.ScaleType.CENTER);
        cancelIconView.setElevation(iconElevation);
        cancelIconView.setAlpha(0.0f);
        cancelIconView.setScaleX(0.5f);
        cancelIconView.setScaleY(0.5f);
        cancelIconView.setRotation(-90.0f);
        cancelButton.addView(cancelIconView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        addView(cancelButton);

        settingsButton = new FrameLayout(context);
        View settingsBg = new View(context);
        GradientDrawable settingsBgDrawable = new GradientDrawable();
        settingsBgDrawable.setShape(GradientDrawable.OVAL);
        settingsBgDrawable.setColor(buttonBgColor);
        settingsBg.setBackground(settingsBgDrawable);
        settingsBg.setElevation(AndroidUtilities.dp(3));
        settingsButton.addView(settingsBg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ImageView settingsIconView = new ImageView(context);
        settingsIconView.setImageResource(R.drawable.msg_settings_old);
        settingsIconView.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
        settingsIconView.setScaleType(ImageView.ScaleType.CENTER);
        settingsIconView.setElevation(iconElevation);
        settingsButton.addView(settingsIconView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        settingsSide = AndroidUtilities.dp(31);
        settingsGap = AndroidUtilities.dp(8);
        settingsButton.setContentDescription(LocaleController.getString(R.string.QuickAttach));
        settingsButton.setAlpha(0.0f);
        addView(settingsButton, LayoutHelper.createFrame(settingsSide, settingsSide));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void present(View sourceButton, float touchX, float touchY) {
        if (presenting || dismissing) return;
        presenting = true;
        this.originalAttachButton = sourceButton;

        if (sourceButton instanceof ImageView) {
            android.graphics.ColorFilter filter = ((ImageView) sourceButton).getColorFilter();
            if (filter != null) {
                attachIconView.setColorFilter(filter);
                cancelIconView.setColorFilter(filter);
            }
        }

        int[] viewLoc = new int[2];
        sourceButton.getLocationInWindow(viewLoc);
        int[] myLoc = new int[2];
        if (getParent() instanceof View) {
            ((View) getParent()).getLocationInWindow(myLoc);
        } else {
            getLocationInWindow(myLoc);
        }

        float btnX = viewLoc[0] - myLoc[0];
        float btnY = viewLoc[1] - myLoc[1];
        int btnW = sourceButton.getWidth();
        int btnH = sourceButton.getHeight();
        if (btnW <= 0) btnW = AndroidUtilities.dp(48);
        if (btnH <= 0) btnH = AndroidUtilities.dp(48);
        sourceRect.set(btnX, btnY, btnX + btnW, btnY + btnH);

        cancelButton.setLayoutParams(new LayoutParams(btnW, btnH));
        cancelButton.setTranslationX(sourceRect.left);
        cancelButton.setTranslationY(sourceRect.top);
        cancelButton.setScaleX(1.0f);
        cancelButton.setScaleY(1.0f);
        cancelButton.setAlpha(1.0f);

        sourceButton.setVisibility(View.INVISIBLE);

        MediaController.AlbumEntry album = MediaController.allPhotosAlbumEntry != null
                ? MediaController.allPhotosAlbumEntry
                : MediaController.allMediaAlbumEntry;
        ArrayList<MediaController.PhotoEntry> photos = (album != null && album.photos != null) ? album.photos : null;

        for (CardItem item : cardItems) {
            removeView(item.view);
        }
        cardItems.clear();

        newestFirst = QuickAttachConfig.newestFirst();

        MediaController.PhotoEntry p0 = (photos != null && photos.size() > 0) ? photos.get(0) : null;
        MediaController.PhotoEntry p1 = (photos != null && photos.size() > 1) ? photos.get(1) : null;
        MediaController.PhotoEntry p2 = (photos != null && photos.size() > 2) ? photos.get(2) : null;

        cardItems.add(createCameraCard());
        if (newestFirst) {
            cardItems.add(createPhotoCard(p0, 1));
            cardItems.add(createPhotoCard(p1, 2));
            cardItems.add(createPhotoCard(p2, 3));
        } else {
            cardItems.add(createPhotoCard(p2, 1));
            cardItems.add(createPhotoCard(p1, 2));
            cardItems.add(createPhotoCard(p0, 3));
        }

        for (CardItem card : cardItems) {
            addView(card.view);
        }
        bringChildToFront(settingsButton);
        bringChildToFront(cancelButton);

        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.albumsDidLoad);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.albumsDidLoad);

        int count = cardItems.size();
        int stripWidth = count * itemSide + (count - 1) * itemSpacing;
        int totalWidth = stripWidth + settingsGap + settingsSide;
        int stripBottomGap = AndroidUtilities.dp(14);
        float stripY = sourceRect.top - stripBottomGap - itemSide;
        if (stripY < AndroidUtilities.dp(48)) {
            stripY = sourceRect.bottom + stripBottomGap;
        }

        float targetStartX = sourceRect.right - totalWidth;
        float minX = AndroidUtilities.dp(12);
        int parentW = getWidth();
        if (parentW <= 0) parentW = AndroidUtilities.displaySize.x;
        float maxX = parentW - totalWidth - AndroidUtilities.dp(12);
        float startX = Math.max(minX, Math.min(maxX, targetStartX));

        for (int i = 0; i < count; i++) {
            CardItem card = cardItems.get(i);
            card.destX = startX + i * (itemSide + itemSpacing);
            card.destY = stripY;
            card.targetCenterX = card.destX + itemSide / 2.0f;
            card.targetCenterY = card.destY + itemSide / 2.0f;

            card.springX = new QuickAttachTuning.SpringEvaluator(
                    QuickAttachTuning.xStiffness,
                    QuickAttachTuning.xDamping
            );
            float yDamp = Math.max(0.20f, QuickAttachTuning.yDamping - i * QuickAttachTuning.yOvershootStep);
            card.springY = new QuickAttachTuning.SpringEvaluator(
                    QuickAttachTuning.yStiffness,
                    yDamp
            );

            card.view.setLayoutParams(new LayoutParams(itemSide, itemSide));
            card.view.setTranslationX(sourceRect.centerX() - itemSide / 2.0f);
            card.view.setTranslationY(sourceRect.centerY() + QuickAttachTuning.birthYOffset - itemSide / 2.0f);
            card.view.setScaleX(QuickAttachTuning.birthScale);
            card.view.setScaleY(QuickAttachTuning.birthScale);
            card.view.setAlpha(0.0f);
            card.setCornerRadius(itemSide / 2.0f);
        }

        settingsDestX = startX + stripWidth + settingsGap;
        settingsDestY = stripY + (itemSide - settingsSide) / 2.0f;
        settingsTargetCenterX = settingsDestX + settingsSide / 2.0f;
        settingsTargetCenterY = settingsDestY + settingsSide / 2.0f;
        settingsSpringX = new QuickAttachTuning.SpringEvaluator(
                QuickAttachTuning.xStiffness,
                QuickAttachTuning.xDamping
        );
        settingsSpringY = new QuickAttachTuning.SpringEvaluator(
                QuickAttachTuning.yStiffness,
                QuickAttachTuning.yDamping
        );
        settingsButton.setLayoutParams(new LayoutParams(settingsSide, settingsSide));
        settingsButton.setTranslationX(sourceRect.centerX() - settingsSide / 2.0f);
        settingsButton.setTranslationY(sourceRect.centerY() + QuickAttachTuning.birthYOffset - settingsSide / 2.0f);
        settingsButton.setScaleX(QuickAttachTuning.birthScale);
        settingsButton.setScaleY(QuickAttachTuning.birthScale);
        settingsButton.setAlpha(0.0f);
        settingsButton.setElevation(0);
        settingsHighlighted = false;

        dimView.animate().alpha(1.0f).setDuration(120).start();

        attachIconView.animate().scaleX(0.5f).scaleY(0.5f).rotation(90f).alpha(0.0f).setDuration(200).start();
        cancelIconView.animate().scaleX(1.0f).scaleY(1.0f).rotation(0f).alpha(1.0f).setDuration(200).start();

        long animStartTime = SystemClock.uptimeMillis();
        presentAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        presentAnimator.setDuration(550);
        presentAnimator.addUpdateListener(animation -> {
            float elapsedSec = (SystemClock.uptimeMillis() - animStartTime) / 1000.0f;
            for (int i = 0; i < cardItems.size(); i++) {
                CardItem card = cardItems.get(i);
                float cardDelay = i * (QuickAttachTuning.staggerMs / 1000.0f);
                float t = elapsedSec - cardDelay;
                if (t < 0.0f) {
                    card.view.setAlpha(0.0f);
                    continue;
                }

                float curCenterX = card.springX.evaluate(sourceRect.centerX(), card.targetCenterX, t);
                float curCenterY = card.springY.evaluate(sourceRect.centerY() + QuickAttachTuning.birthYOffset, card.targetCenterY, t);

                card.view.setTranslationX(curCenterX - itemSide / 2.0f);
                card.view.setTranslationY(curCenterY - itemSide / 2.0f);

                float scale = card.springX.evaluate(QuickAttachTuning.birthScale, 1.0f, t);
                card.view.setScaleX(scale);
                card.view.setScaleY(scale);

                float corner = card.springX.evaluate(itemSide / 2.0f, AndroidUtilities.dp(14), t);
                card.setCornerRadius(corner);

                float alpha = Math.min(1.0f, t / 0.15f);
                card.view.setAlpha(alpha);

                if (card.veilView != null) {
                    float veilAlpha = Math.max(0.0f, 1.0f - t / 0.18f);
                    card.veilView.setAlpha(veilAlpha);
                }
            }

            float settingsDelay = cardItems.size() * (QuickAttachTuning.staggerMs / 1000.0f);
            float settingsT = elapsedSec - settingsDelay;
            if (settingsT < 0.0f) {
                settingsButton.setAlpha(0.0f);
            } else {
                float curCenterX = settingsSpringX.evaluate(sourceRect.centerX(), settingsTargetCenterX, settingsT);
                float curCenterY = settingsSpringY.evaluate(sourceRect.centerY() + QuickAttachTuning.birthYOffset, settingsTargetCenterY, settingsT);
                settingsButton.setTranslationX(curCenterX - settingsSide / 2.0f);
                settingsButton.setTranslationY(curCenterY - settingsSide / 2.0f);
                float scale = settingsSpringX.evaluate(QuickAttachTuning.birthScale, 1.0f, settingsT);
                settingsButton.setScaleX(scale);
                settingsButton.setScaleY(scale);
                settingsButton.setAlpha(Math.min(1.0f, settingsT / 0.15f));
            }
        });
        presentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                presenting = false;
            }
        });
        presentAnimator.start();

        updateTracking(touchX, touchY);
    }

    private CardItem createCameraCard() {
        Context context = getContext();
        FrameLayout container = new FrameLayout(context);
        container.setElevation(AndroidUtilities.dp(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E2124);
        bg.setCornerRadius(AndroidUtilities.dp(14));
        container.setBackground(bg);

        ImageView cameraIcon = new ImageView(context);
        cameraIcon.setImageResource(R.drawable.camera);
        cameraIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        cameraIcon.setScaleType(ImageView.ScaleType.CENTER);
        container.addView(cameraIcon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        setupCardOutline(container);
        return new CardItem(container, null, cameraIcon, null, null, null, null, true, 0);
    }

    private CardItem createPhotoCard(MediaController.PhotoEntry entry, int placeholderIndex) {
        Context context = getContext();
        FrameLayout container = new FrameLayout(context);
        container.setElevation(AndroidUtilities.dp(4));

        BackupImageView imageView = new BackupImageView(context);
        imageView.setAspectFit(false);
        imageView.setRoundRadius(AndroidUtilities.dp(14));
        container.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        applyPhotoImage(imageView, entry, placeholderIndex);

        ImageView playIcon = new ImageView(context);
        playIcon.setImageResource(R.drawable.play_mini_video);
        playIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        container.addView(playIcon, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        TextView durationText = new TextView(context);
        durationText.setTextColor(Color.WHITE);
        durationText.setTextSize(10);
        durationText.setShadowLayer(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(1), 0x80000000);
        container.addView(durationText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 4, 0, 0, 4));

        boolean initialVideo = entry != null && entry.isVideo && !entry.isLivePhoto();
        playIcon.setVisibility(initialVideo ? View.VISIBLE : View.GONE);
        durationText.setVisibility(initialVideo ? View.VISIBLE : View.GONE);
        if (initialVideo) {
            durationText.setText(AndroidUtilities.formatShortDuration(entry.duration));
        }

        View veil = new View(context);
        veil.setBackgroundColor(0x33FFFFFF);
        container.addView(veil, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        setupCardOutline(container);
        CardItem item = new CardItem(container, imageView, null, veil, playIcon, durationText, entry, false, placeholderIndex);
        item.boundEntry = entry;
        return item;
    }

    private void applyPhotoImage(BackupImageView imageView, MediaController.PhotoEntry entry, int placeholderIndex) {
        if (entry == null) {
            imageView.setImageDrawable(createPlaceholderDrawable(placeholderIndex));
        } else if (entry.coverPath != null) {
            imageView.setImage(entry.coverPath, null, Theme.chat_attachEmptyDrawable);
        } else if (entry.thumbPath != null) {
            imageView.setImage(entry.thumbPath, null, Theme.chat_attachEmptyDrawable);
        } else if (entry.path != null) {
            if (entry.isVideo && !entry.isLivePhoto()) {
                imageView.setImage("vthumb://" + entry.imageId + ":" + entry.path, null, Theme.chat_attachEmptyDrawable);
            } else {
                imageView.setOrientation(entry.orientation, entry.invert, true);
                imageView.setImage("thumb://" + entry.imageId + ":" + entry.path, null, Theme.chat_attachEmptyDrawable);
            }
        } else {
            imageView.setImageDrawable(createPlaceholderDrawable(placeholderIndex));
        }
    }

    private void setupCardOutline(FrameLayout container) {
        container.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float rad = AndroidUtilities.dp(14);
                for (CardItem c : cardItems) {
                    if (c.view == view) {
                        rad = c.currentCornerRadius;
                        break;
                    }
                }
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
            }
        });
        container.setClipToOutline(true);
    }

    private Drawable createPlaceholderDrawable(int index) {
        int[][] colors = new int[][] {
            {0xFFFFBE87, 0xFFF2736B},
            {0xFF8CCCFC, 0xFF4D82F2},
            {0xFFB8F2B2, 0xFF47B878},
            {0xFFEDBFF9, 0xFFA866F0}
        };
        int[] pair = colors[index % colors.length];
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, pair);
        gd.setCornerRadius(AndroidUtilities.dp(14));
        return gd;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.albumsDidLoad) {
            refreshPhotoCards();
        }
    }

    private void refreshPhotoCards() {
        if (dismissing || cardItems.isEmpty()) {
            return;
        }
        MediaController.AlbumEntry album = MediaController.allPhotosAlbumEntry != null
                ? MediaController.allPhotosAlbumEntry
                : MediaController.allMediaAlbumEntry;
        ArrayList<MediaController.PhotoEntry> photos = (album != null && album.photos != null) ? album.photos : null;

        for (int slot = 1; slot < cardItems.size(); slot++) {
            CardItem card = cardItems.get(slot);
            if (card.isCamera || card.imageView == null) {
                continue;
            }
            int photoIndex = newestFirst ? slot - 1 : cardItems.size() - 1 - slot;
            MediaController.PhotoEntry entry = (photos != null && photos.size() > photoIndex) ? photos.get(photoIndex) : null;
            if (sameEntry(entry, card.boundEntry)) {
                continue;
            }
            applyPhotoImage(card.imageView, entry, card.placeholderIndex);
            card.boundEntry = entry;
            if (card.playIconView != null) {
                card.playIconView.setVisibility(entry != null && entry.isVideo && !entry.isLivePhoto() ? View.VISIBLE : View.GONE);
            }
            if (card.durationTextView != null) {
                if (entry != null && entry.isVideo && !entry.isLivePhoto()) {
                    card.durationTextView.setText(AndroidUtilities.formatShortDuration(entry.duration));
                    card.durationTextView.setVisibility(View.VISIBLE);
                } else {
                    card.durationTextView.setVisibility(View.GONE);
                }
            }
        }
    }

    private static boolean sameEntry(MediaController.PhotoEntry a, MediaController.PhotoEntry b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return TextUtils.equals(a.path, b.path)
                && TextUtils.equals(a.thumbPath, b.thumbPath)
                && TextUtils.equals(a.coverPath, b.coverPath);
    }

    public void updateTracking(float rawX, float rawY) {
        if (dismissing) return;
        int[] myLoc = new int[2];
        if (getParent() instanceof View) {
            ((View) getParent()).getLocationInWindow(myLoc);
        } else {
            getLocationInWindow(myLoc);
        }
        float x = rawX - myLoc[0];
        float y = rawY - myLoc[1];

        int newIndex = -1;
        for (int i = 0; i < cardItems.size(); i++) {
            CardItem card = cardItems.get(i);
            if (x >= card.destX - hitSlop && x <= card.destX + itemSide + hitSlop &&
                y >= card.destY - hitSlop * 2 && y <= card.destY + itemSide + hitSlop) {
                newIndex = i;
                break;
            }
        }

        RectF cancelHit = new RectF(sourceRect);
        cancelHit.inset(-hitSlop, -hitSlop);
        boolean overSettings = newIndex == -1
                && x >= settingsDestX - hitSlop && x <= settingsDestX + settingsSide + hitSlop
                && y >= settingsDestY - hitSlop * 2 && y <= settingsDestY + settingsSide + hitSlop;
        boolean overCancel = (newIndex == -1 && !overSettings && cancelHit.contains(x, y));

        if (newIndex != highlightedIndex) {
            highlightedIndex = newIndex;
            if (newIndex != -1) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            for (int i = 0; i < cardItems.size(); i++) {
                CardItem card = cardItems.get(i);
                boolean hl = (i == highlightedIndex);
                card.view.animate()
                        .scaleX(hl ? 1.18f : 1.0f)
                        .scaleY(hl ? 1.18f : 1.0f)
                        .setDuration(180)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                        .start();
                card.view.setElevation(AndroidUtilities.dp(hl ? 12 : 4));
                if (hl) {
                    bringChildToFront(card.view);
                }
            }
        }

        if (overSettings != settingsHighlighted) {
            settingsHighlighted = overSettings;
            if (overSettings) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            settingsButton.animate()
                    .scaleX(overSettings ? 1.18f : 1.0f)
                    .scaleY(overSettings ? 1.18f : 1.0f)
                    .setDuration(150)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                    .start();
            settingsButton.setElevation(AndroidUtilities.dp(overSettings ? 12 : 0));
        }

        if (overCancel != cancelHighlighted) {
            cancelHighlighted = overCancel;
            if (overCancel) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            cancelButton.animate()
                    .scaleX(overCancel ? 1.2f : 1.0f)
                    .scaleY(overCancel ? 1.2f : 1.0f)
                    .setDuration(150)
                    .start();
        }
    }

    public void finishTracking(float rawX, float rawY) {
        if (dismissing) return;
        updateTracking(rawX, rawY);
        if (highlightedIndex != -1) {
            CardItem selected = cardItems.get(highlightedIndex);
            dismissWithSelection(selected);
        } else if (settingsHighlighted) {
            dismissToSettings();
        } else {
            dismissCancel();
        }
    }

    public void cancelTracking() {
        if (dismissing) return;
        dismissCancel();
    }

    private void dismissWithSelection(CardItem selected) {
        dismissing = true;
        if (presentAnimator != null) {
            presentAnimator.cancel();
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        dimView.animate().alpha(0.0f).setDuration(180).start();
        morphToAttach();

        for (CardItem card : cardItems) {
            if (card != selected) {
                card.view.animate()
                        .alpha(0.0f)
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(120)
                        .start();
            }
        }
        settingsButton.animate()
                .alpha(0.0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(120)
                .start();

        selected.view.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .alpha(0.0f)
                .setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_IN)
                .withEndAction(() -> {
                    cleanupAndRemove();
                    if (selected.isCamera) {
                        if (delegate != null) delegate.onCameraSelected();
                    } else if (selected.boundEntry != null) {
                        if (delegate != null) delegate.onPhotoSelected(selected.boundEntry);
                    }
                })
                .start();
    }

    private void dismissToSettings() {
        dismissCancel(true);
    }

    private void dismissCancel() {
        dismissCancel(false);
    }

    private void dismissCancel(boolean openSettings) {
        dismissing = true;
        if (presentAnimator != null) {
            presentAnimator.cancel();
        }
        dimView.animate().alpha(0.0f).setDuration(200).start();
        morphToAttach();

        long cancelStartTime = SystemClock.uptimeMillis();
        cancelAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        cancelAnimator.setDuration(400);

        final float birthCenterX = sourceRect.centerX();
        final float birthCenterY = sourceRect.centerY() + QuickAttachTuning.birthYOffset;

        cancelAnimator.addUpdateListener(animation -> {
            float elapsedSec = (SystemClock.uptimeMillis() - cancelStartTime) / 1000.0f;
            for (int i = 0; i < cardItems.size(); i++) {
                CardItem card = cardItems.get(i);
                float cardDelay = (cardItems.size() - 1 - i) * (QuickAttachTuning.staggerMs / 1000.0f);
                float t = elapsedSec - cardDelay;
                if (t < 0.0f) continue;

                float curCenterX = card.springY.evaluate(card.targetCenterX, birthCenterX, t);
                float curCenterY = card.springX.evaluate(card.targetCenterY, birthCenterY, t);

                card.view.setTranslationX(curCenterX - itemSide / 2.0f);
                card.view.setTranslationY(curCenterY - itemSide / 2.0f);

                float scale = card.springX.evaluate(1.0f, QuickAttachTuning.birthScale, t);
                card.view.setScaleX(scale);
                card.view.setScaleY(scale);

                float corner = card.springX.evaluate(AndroidUtilities.dp(14), itemSide / 2.0f, t);
                card.setCornerRadius(corner);

                float alpha = Math.max(0.0f, 1.0f - t / 0.12f);
                card.view.setAlpha(alpha);
            }

            if (settingsSpringX != null && settingsSpringY != null) {
                float curCenterX = settingsSpringX.evaluate(settingsTargetCenterX, birthCenterX, elapsedSec);
                float curCenterY = settingsSpringY.evaluate(settingsTargetCenterY, birthCenterY, elapsedSec);
                settingsButton.setTranslationX(curCenterX - settingsSide / 2.0f);
                settingsButton.setTranslationY(curCenterY - settingsSide / 2.0f);
                float scale = settingsSpringX.evaluate(1.0f, QuickAttachTuning.birthScale, elapsedSec);
                settingsButton.setScaleX(scale);
                settingsButton.setScaleY(scale);
                settingsButton.setAlpha(Math.max(0.0f, 1.0f - elapsedSec / 0.12f));
            }
        });

        cancelAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                final Delegate currentDelegate = delegate;
                cleanupAndRemove();
                if (openSettings && currentDelegate != null) {
                    currentDelegate.onSettingsSelected();
                }
            }
        });
        cancelAnimator.start();
    }

    private void morphToAttach() {
        attachIconView.animate().scaleX(1.0f).scaleY(1.0f).rotation(0f).alpha(1.0f).setDuration(200).start();
        cancelIconView.animate().scaleX(0.5f).scaleY(0.5f).rotation(-90f).alpha(0.0f).setDuration(200).start();
        cancelButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start();
    }

    private void cleanupAndRemove() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.albumsDidLoad);
        if (originalAttachButton != null) {
            originalAttachButton.setVisibility(View.VISIBLE);
        }
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        if (delegate != null) {
            delegate.onDismiss();
        }
    }

    public void detach() {
        dismissing = true;
        presenting = false;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.albumsDidLoad);
        if (originalAttachButton != null) {
            originalAttachButton.setVisibility(View.VISIBLE);
            originalAttachButton = null;
        }
    }
}
