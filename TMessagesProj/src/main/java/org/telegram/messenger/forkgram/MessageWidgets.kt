package org.telegram.messenger.forkgram

import org.telegram.messenger.MessagesController
import org.telegram.utils.twidget.WidgetMedia

/** The local opt-out is independent of Telegram's server-side widget kill switch. */
object MessageWidgets {
    @JvmStatic
    fun disabled(): Boolean =
        MessagesController.getGlobalMainSettings().getBoolean("disableMessageWidgets", false)

    fun onChanged() = WidgetMedia.onLocalWidgetPreferenceChanged()
}
