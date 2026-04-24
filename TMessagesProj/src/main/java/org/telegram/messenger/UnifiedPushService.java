package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;

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

    private static final long MAX_REGISTRATION_RETRY_DELAY = 15 * 60 * 1000L;
    private static final long REGISTRATION_ANSWER_TIMEOUT = 30 * 1000L;
    private static final long REGISTRATION_FRESHNESS = 24 * 60 * 60 * 1000L;
    private static final long REGISTRATION_REFRESH = 60 * 60 * 1000L;
    private static final long MIN_REGISTRATION_ATTEMPT_INTERVAL = 5 * 60 * 1000L;
    private static final String LAST_ENDPOINT_KEY = "unifiedPushLastEndpointTime";

    private static long lastReceivedNotification = 0;
    private static long numOfReceivedNotifications = 0;
    private static long lastRegistrationAttempt = 0;

    private static int registrationRetries = 0;
    private static Runnable registrationRetryRunnable;
    private static Runnable registrationTimeoutRunnable;

    public static long getLastReceivedNotification() {
        return lastReceivedNotification;
    }

    public static long getNumOfReceivedNotifications() {
        return numOfReceivedNotifications;
    }

    private static long getLastEndpointTime() {
        try {
            return ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                    .getLong(LAST_ENDPOINT_KEY, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void markEndpointReceived() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                    .edit()
                    .putLong(LAST_ENDPOINT_KEY, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignore) {
        }
    }

    private static void markRegistrationLost() {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                    .edit()
                    .remove(LAST_ENDPOINT_KEY)
                    .apply();
        } catch (Throwable ignore) {
        }
    }

    private static boolean isEndpointYoungerThan(long age) {
        final long last = getLastEndpointTime();
        if (last <= 0) {
            return false;
        }
        final long elapsed = System.currentTimeMillis() - last;
        return elapsed >= 0 && elapsed < age;
    }

    public static boolean isRegistrationFresh() {
        return isEndpointYoungerThan(REGISTRATION_FRESHNESS);
    }

    public static void refreshRegistration() {
        if (SharedConfig.disableUnifiedPush) {
            return;
        }
        if (registrationRetryRunnable != null || registrationTimeoutRunnable != null) {
            return;
        }
        if (isEndpointYoungerThan(REGISTRATION_REFRESH)) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (lastRegistrationAttempt != 0 && now - lastRegistrationAttempt < MIN_REGISTRATION_ATTEMPT_INTERVAL) {
            return;
        }
        final PushListenerController.IPushListenerServiceProvider provider = ApplicationLoader.getPushProvider();
        if (!(provider instanceof PushListenerController.UnifiedPushListenerServiceProvider) || !provider.hasServices()) {
            return;
        }
        lastRegistrationAttempt = now;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UnifiedPush registration refresh");
        }
        provider.onRequestPushToken();
    }

    public static void awaitRegistrationAnswer() {
        final long startedAt = System.currentTimeMillis();
        AndroidUtilities.runOnUIThread(() -> {
            cancelRegistrationTimeout();
            registrationTimeoutRunnable = () -> {
                registrationTimeoutRunnable = null;
                if (SharedConfig.disableUnifiedPush) {
                    return;
                }
                if (getLastEndpointTime() >= startedAt) {
                    return;
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("UnifiedPush distributor did not answer the registration");
                }
                markRegistrationLost();
                scheduleRegistrationRetry();
                ApplicationLoader.startPushService();
            };
            AndroidUtilities.runOnUIThread(registrationTimeoutRunnable, REGISTRATION_ANSWER_TIMEOUT);
        });
    }

    private static void cancelRegistrationTimeout() {
        if (registrationTimeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(registrationTimeoutRunnable);
            registrationTimeoutRunnable = null;
        }
    }

    private static void cancelRegistrationRetry() {
        AndroidUtilities.runOnUIThread(() -> {
            registrationRetries = 0;
            cancelRegistrationTimeout();
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

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        cancelRegistrationRetry();
        markEndpointReceived();
        AndroidUtilities.runOnUIThread(() -> {
            ApplicationLoader.postInitApplication();
            ApplicationLoader.startPushService();
            Utilities.globalQueue.postRunnable(() -> {
                SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

                String token = endpoint.getUrl();
                if (!TextUtils.isEmpty(SharedConfig.unifiedPushGateway)) {
                    try {
                        token = SharedConfig.unifiedPushGateway + URLEncoder.encode(endpoint.getUrl(), "UTF-8");
                    } catch (UnsupportedEncodingException e) {
                        FileLog.e(e);
                    }
                }
                PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_SIMPLE, token);
            });
        });
    }

    @Override
    public void onMessage(PushMessage message, String instance){
        final long receiveTime = SystemClock.elapsedRealtime();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        lastReceivedNotification = SystemClock.elapsedRealtime();
        markEndpointReceived();
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
        AndroidUtilities.runOnUIThread(UnifiedPushService::cancelRegistrationTimeout);
        markRegistrationLost();
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
        AndroidUtilities.runOnUIThread(UnifiedPushService::cancelRegistrationTimeout);
        markRegistrationLost();
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
