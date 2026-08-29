/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.util.Log;

public class NotificationsService extends Service {

    private static final int NOTIFICATION_ID = 9999;
    private static final String CHANNEL_ID = "push_service_channel";

    private static volatile boolean foregroundStartPending;
    private static volatile boolean stopWhenForeground;

    private Notification notification;

    public static void onForegroundStartRequested() {
        stopWhenForeground = false;
        foregroundStartPending = true;
    }

    public static void onForegroundStartFailed() {
        foregroundStartPending = false;
    }

    public static boolean isForegroundStartPending() {
        return foregroundStartPending;
    }

    public static void requestStopWhenForeground() {
        stopWhenForeground = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        moveToForeground();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        moveToForeground();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        foregroundStartPending = false;
        stopWhenForeground = false;
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }

    private void moveToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForeground(NOTIFICATION_ID, getNotification());
            } catch (Throwable e) {
                Log.d("Fork Client", "Failed to move push service to foreground");
            }
        }
        foregroundStartPending = false;
        if (stopWhenForeground) {
            stopSelf();
        }
    }

    private Notification getNotification() {
        if (notification == null) {
            int pendingIntentFlags;
            if (Build.VERSION.SDK_INT >= 34) {
                pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE;
            } else {
                pendingIntentFlags = PendingIntent.FLAG_MUTABLE;
            }
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Push Notifications Service", NotificationManager.IMPORTANCE_DEFAULT));
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setShowWhen(false)
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.notification)
                    .setContentText("Push service: tap to learn more");
            try {
                Intent explainIntent = new Intent("android.intent.action.VIEW");
                explainIntent.setData(Uri.parse("https://github.com/forkgram/TelegramAndroid"));
                builder.setContentIntent(PendingIntent.getActivity(this, 0, explainIntent, pendingIntentFlags));
            } catch (Throwable ignore) {
                Log.d("Fork Client", "Failed to set intent");
            }
            notification = builder.build();
        }
        return notification;
    }
}
