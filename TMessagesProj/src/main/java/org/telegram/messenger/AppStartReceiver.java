/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AppStartReceiver extends BroadcastReceiver {

    public final static String ACTION_KEEP_ALIVE = "org.telegram.start";

    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        final boolean appRestarted = Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!appRestarted && !ACTION_KEEP_ALIVE.equals(action)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            SharedConfig.loadConfig();
            if (appRestarted && SharedConfig.passcodeHash.length() > 0) {
                SharedConfig.appLocked = true;
                SharedConfig.saveConfig();
            }
            ApplicationLoader.startPushService();
            PushListenerController.IPushListenerServiceProvider provider = ApplicationLoader.getPushProvider();
            if (provider.hasServices() && provider.needsPushToken()) {
                provider.onRequestPushToken();
            }
        });
    }
}
