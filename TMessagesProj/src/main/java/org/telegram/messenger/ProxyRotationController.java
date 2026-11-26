package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

    private boolean isRotationScheduled;
    private boolean isCurrentlyChecking;

    private final Runnable checkProxyAndSwitchRunnable = () -> {
        isRotationScheduled = false;
        if (!canRotate()) {
            return;
        }
        isCurrentlyChecking = true;

        int currentAccount = UserConfig.selectedAccount;
        boolean startedCheck = false;
        for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
            SharedConfig.ProxyInfo proxyInfo = SharedConfig.proxyList.get(i);
            if (proxyInfo.checking || SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < 2 * 60 * 1000) {
                continue;
            }
            startedCheck = true;
            proxyInfo.checking = true;
            ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                proxyInfo.checking = false;
                if (time == -1) {
                    proxyInfo.available = false;
                    proxyInfo.ping = 0;
                } else {
                    proxyInfo.ping = time;
                    proxyInfo.available = true;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
            }));
        }

        if (!startedCheck) {
            switchToAvailable();
        }
    };

    public static void init() {
        INSTANCE.initInternal();
    }

    public static void onRotationDisabled() {
        INSTANCE.cancelRotationCheck();
    }

    private boolean canRotate() {
        return SharedConfig.isProxyEnabled() && SharedConfig.proxyRotationEnabled && SharedConfig.proxyList.size() > 1;
    }

    private void cancelRotationCheck() {
        AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
        isRotationScheduled = false;
        isCurrentlyChecking = false;
    }

    @SuppressWarnings("ComparatorCombinators")
    private void switchToAvailable() {
        boolean waitingForAlternative = false;

        List<SharedConfig.ProxyInfo> sortedList = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(sortedList, (o1, o2) -> Long.compare(o1.ping, o2.ping));
        for (SharedConfig.ProxyInfo info : sortedList) {
            if (info == SharedConfig.currentProxy) {
                continue;
            }
            if (info.checking) {
                waitingForAlternative = true;
                continue;
            }
            if (!info.available) {
                continue;
            }

            isCurrentlyChecking = false;
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", true);
            info.settings.toSharedPreferences(editor);
            editor.apply();

            SharedConfig.currentProxy = info;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
            ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
            return;
        }
        // An earlier failure must not discard a healthy alternative still being checked.
        isCurrentlyChecking = waitingForAlternative;
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            if (!canRotate()) {
                cancelRotationCheck();
            } else if (isCurrentlyChecking) {
                switchToAvailable();
            }
        } else if (id == NotificationCenter.proxySettingsChanged) {
            cancelRotationCheck();
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            if (!canRotate() || ConnectionsManager.getInstance(account).getConnectionState() != ConnectionsManager.ConnectionStateConnectingToProxy) {
                cancelRotationCheck();
            } else if (!isRotationScheduled && !isCurrentlyChecking) {
                isRotationScheduled = true;
                AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, ROTATION_TIMEOUTS.get(SharedConfig.proxyRotationTimeout) * 1000L);
            }
        }
    }
}
