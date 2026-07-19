package org.telegram.messenger.forkgram

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenu
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper

object AccountSelector {

    @JvmStatic
    fun hasAccountsToSwitch(currentAccount: Int): Boolean {
        val accounts = ArrayList<Int>()
        HiddenAccountHelper.collectVisibleAccountNumbers(accounts, currentAccount)
        return !accounts.isEmpty()
    }

    @JvmStatic
    fun addToMenu(
        fragment: BaseFragment,
        menu: ActionBarMenu?,
        id: Int,
        onSelected: Utilities.Callback<Int>
    ): ActionBarMenuItem? {
        val currentAccount = fragment.currentAccount
        if (menu == null || !hasAccountsToSwitch(currentAccount)) {
            return null
        }

        val item = menu.addItemWithWidth(id, 0, AndroidUtilities.dp(56f))

        val user: TLRPC.User? = UserConfig.getInstance(currentAccount).currentUser
        val avatarDrawable = AvatarDrawable()
        avatarDrawable.setTextSize(AndroidUtilities.dp(12f))
        avatarDrawable.setInfo(currentAccount, user)

        val imageView = BackupImageView(menu.context)
        imageView.setRoundRadius(AndroidUtilities.dp(18f))
        imageView.imageReceiver.setCurrentAccount(currentAccount)
        val thumb: Drawable = user?.photo?.strippedBitmap ?: avatarDrawable
        imageView.setImage(
            ImageLocation.getForUserOrChat(currentAccount, user, ImageLocation.TYPE_SMALL), "50_50",
            ImageLocation.getForUserOrChat(user, ImageLocation.TYPE_STRIPPED), "50_50", thumb, user
        )
        item.addView(imageView, LayoutHelper.createFrame(36, 36, Gravity.CENTER))

        var dragOpen: View.OnTouchListener? = null
        val rearm = Runnable { AndroidUtilities.runOnUIThread { item.setOnTouchListener(dragOpen) } }
        dragOpen = View.OnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.y > view.height) {
                view.cancelLongPress()
                showPopup(fragment, view, onSelected, rearm)
                true
            } else {
                false
            }
        }
        item.setOnTouchListener(dragOpen)
        item.setOnClickListener { view -> showPopup(fragment, view, onSelected, rearm) }
        item.setOnLongClickListener { view ->
            showPopup(fragment, view, onSelected, rearm)
            true
        }
        return item
    }

    @JvmStatic
    fun showPopup(fragment: BaseFragment, anchor: View, onSelected: Utilities.Callback<Int>) {
        showPopup(fragment, anchor, onSelected, null)
    }

    private fun showPopup(
        fragment: BaseFragment,
        anchor: View,
        onSelected: Utilities.Callback<Int>,
        onDismiss: Runnable?
    ) {
        val currentAccount = fragment.currentAccount

        val accounts = ArrayList<Int>()
        HiddenAccountHelper.collectVisibleAccountNumbers(accounts)
        if (!accounts.contains(currentAccount)) {
            accounts.add(0, currentAccount)
        }

        val options = ItemOptions.makeOptions(fragment, anchor)
        options.setMinWidth(230)
        for (account in accounts) {
            options.addAccount(account, account == currentAccount) {
                options.dismiss()
                if (account != currentAccount) {
                    onSelected.run(account)
                }
            }
        }

        val background = Theme.createRoundRectDrawable(
            AndroidUtilities.avatarCornerRadius(AndroidUtilities.dp(48f).toFloat()).toInt(),
            fragment.getThemedColor(Theme.key_windowBackgroundWhite)
        )
        background.paint.setShadowLayer(
            AndroidUtilities.dp(6f).toFloat(),
            0f,
            AndroidUtilities.dp(1f).toFloat(),
            Theme.multAlpha(0xFF000000.toInt(), 0.15f)
        )
        options.setViewAdditionalOffsets(
            -AndroidUtilities.dp(4f),
            -AndroidUtilities.dp(4f),
            -AndroidUtilities.dp(4f),
            -AndroidUtilities.dp(4f)
        )
        options.setScrimViewBackground(background)
        options.translate(0f, -AndroidUtilities.dp(4f).toFloat())
        options.setGravity(Gravity.RIGHT)
        if (onDismiss != null) {
            options.setOnDismiss(onDismiss)
        }
        options.show()
    }
}
