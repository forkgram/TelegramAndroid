package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;
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

    private static final String NTFY_DISTRIBUTOR = "io.heckel.ntfy";
    private static final long MAX_REGISTRATION_RETRY_DELAY = 15 * 60 * 1000L;

    private static long lastReceivedNotification = 0;
    private static long numOfReceivedNotifications = 0;

    private static int registrationRetries = 0;
    private static Runnable registrationRetryRunnable;

    public static long getLastReceivedNotification() {
        return lastReceivedNotification;
    }

    public static long getNumOfReceivedNotifications() {
        return numOfReceivedNotifications;
    }

    private static void cancelRegistrationRetry() {
        AndroidUtilities.runOnUIThread(() -> {
            registrationRetries = 0;
            if (registrationRetryRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(registrationRetryRunnable);
                registrationRetryRunnable = null;
            }
        });
    }

    private static void scheduleRegistrationRetry() {
        AndroidUtilities.runOnUIThread(() -> {
            if (registrationRetryRunnable != null) {
                return;
            }
            final long delay = Math.min(10_000L << Math.min(registrationRetries, 6), MAX_REGISTRATION_RETRY_DELAY);
            registrationRetries++;
            registrationRetryRunnable = () -> {
                registrationRetryRunnable = null;
                if (SharedConfig.disableUnifiedPush) {
                    return;
                }
                PushListenerController.IPushListenerServiceProvider provider = PushListenerController.UnifiedPushListenerServiceProvider.INSTANCE;
                if (provider.hasServices()) {
                    provider.onRequestPushToken();
                }
            };
            AndroidUtilities.runOnUIThread(registrationRetryRunnable, delay);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UnifiedPush registration retry in " + delay + " ms");
            }
        });
    }

    private static String buildWebPushToken(PushEndpoint endpoint) {
        if (endpoint.getPubKeySet() == null) {
            return null;
        }
        try {
            JSONObject keys = new JSONObject();
            keys.put("p256dh", endpoint.getPubKeySet().getPubKey());
            keys.put("auth", endpoint.getPubKeySet().getAuth());
            JSONObject token = new JSONObject();
            token.put("endpoint", endpoint.getUrl());
            token.put("keys", keys);
            return token.toString();
        } catch (JSONException e) {
            FileLog.e(e);
            return null;
        }
    }

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        cancelRegistrationRetry();
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            ApplicationLoader.startPushService();
            Utilities.globalQueue.postRunnable(() -> {
                SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

                String savedDistributor = UnifiedPush.getSavedDistributor(this);

                int pushType = PushListenerController.PUSH_TYPE_SIMPLE;
                String token = endpoint.getUrl();
                if (!NTFY_DISTRIBUTOR.equals(savedDistributor)) {
                    if (!TextUtils.isEmpty(SharedConfig.unifiedPushGateway)) {
                        try {
                            token = SharedConfig.unifiedPushGateway + URLEncoder.encode(endpoint.getUrl(), "UTF-8");
                        } catch (UnsupportedEncodingException e) {
                            FileLog.e(e);
                        }
                    } else {
                        String webPushToken = buildWebPushToken(endpoint);
                        if (webPushToken != null) {
                            pushType = PushListenerController.PUSH_TYPE_WEB;
                            token = webPushToken;
                        }
                    }
                }
                PushListenerController.sendRegistrationToServer(pushType, token);
            });
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
        if (reason == FailedReason.NETWORK || reason == FailedReason.INTERNAL_ERROR) {
            scheduleRegistrationRetry();
        }
        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
    }

    @Override
    public void onUnregistered(String instance){
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, null);
        });
        if (SharedConfig.disableUnifiedPush) {
            cancelRegistrationRetry();
        } else {
            scheduleRegistrationRetry();
        }
        AndroidUtilities.runOnUIThread(ApplicationLoader::startPushService);
    }
}
