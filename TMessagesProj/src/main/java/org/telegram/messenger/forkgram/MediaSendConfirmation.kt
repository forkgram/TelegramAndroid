package org.telegram.messenger.forkgram

import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment

object MediaSendConfirmation {
    @JvmStatic
    fun confirm(fragment: BaseFragment?, sticker: Boolean, send: Runnable) {
        if (fragment == null || !MessagesController.getGlobalMainSettings().getBoolean("confirmGifStickerSend", false)) {
            send.run()
            return
        }
        val activity = fragment.parentActivity ?: return
        val dialog = AlertDialog.Builder(activity, fragment.resourceProvider)
            .setTitle(getString(if (sticker) R.string.ConfirmSendSticker else R.string.ConfirmSendGif))
            .setNegativeButton(getString(R.string.Cancel), null)
            .setPositiveButton(getString(R.string.Send)) { _, _ -> send.run() }
            .create()
        fragment.showDialog(dialog)
    }
}
