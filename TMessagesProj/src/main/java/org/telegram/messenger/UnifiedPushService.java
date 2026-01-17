package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.UnifiedPush;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;

public class UnifiedPushService extends PushService {

    private static long lastReceivedNotification = 0;
    private static long numOfReceivedNotifications = 0;

    public static long getLastReceivedNotification() {
        return lastReceivedNotification;
    }

    public static long getNumOfReceivedNotifications() {
        return numOfReceivedNotifications;
    }

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance){
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            String savedDistributor = UnifiedPush.getSavedDistributor(this);

            if (savedDistributor.equals("io.heckel.ntfy")) {
                PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, endpoint.getUrl());
            } else {
                try {
                    PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, SharedConfig.unifiedPushGateway + URLEncoder.encode(endpoint.getUrl(), "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    FileLog.e(e);
                }
            }
        });
    }

    @Override
    public void onMessage(PushMessage message, String instance){
        final long receiveTime = SystemClock.elapsedRealtime();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        lastReceivedNotification = SystemClock.elapsedRealtime();
        numOfReceivedNotifications++;

        AndroidUtilities.runOnUIThread(() -> {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP PRE INIT APP");
            }
            ApplicationLoader.postInitApplication();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP POST INIT APP");
            }
            Utilities.stageQueue.postRunnable(() -> {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("UP START PROCESSING");
                }
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        ConnectionsManager.onInternalPushReceived(a);
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                    }
                }
                countDownLatch.countDown();
            });
        });
        Utilities.globalQueue.postRunnable(()-> {
            try {
                countDownLatch.await();
            } catch (Throwable ignore) {

            }
            if (BuildVars.DEBUG_VERSION) {
                FileLog.d("finished UP service, time = " + (SystemClock.elapsedRealtime() - receiveTime));
            }
        });
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance){
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Failed to get endpoint: " + reason);
        }
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, null);
        });
    }

    @Override
    public void onUnregistered(String instance){
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, null);
        });
    }
}
