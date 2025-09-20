package org.telegram.messenger;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Helper class to manage app lifecycle and prevent white screen issues
 */
public class LifecycleHelper {
    private static final String TAG = "LifecycleHelper";
    private static final long UI_RESTORE_DELAY = 100;
    
    private static Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * Ensures UI components are visible and properly restored
     */
    public static void ensureUIVisible(Activity activity, View... views) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        
        mainHandler.post(() -> {
            try {
                for (View view : views) {
                    if (view != null && view.getVisibility() != View.VISIBLE) {
                        view.setVisibility(View.VISIBLE);
                    }
                }
            } catch (Exception e) {
                FileLog.e(TAG, e);
            }
        });
    }
    
    /**
     * Delays UI restoration to ensure proper initialization
     */
    public static void delayedUIRestore(Runnable restoreAction) {
        if (restoreAction == null) {
            return;
        }
        
        mainHandler.postDelayed(() -> {
            try {
                restoreAction.run();
            } catch (Exception e) {
                FileLog.e(TAG, e);
            }
        }, UI_RESTORE_DELAY);
    }
    
    /**
     * Checks if the app needs to restore its state
     */
    public static boolean needsStateRestore(Activity activity) {
        if (activity == null) {
            return false;
        }
        
        // Check if main UI components are missing or invisible
        View contentView = activity.findViewById(android.R.id.content);
        return contentView == null || contentView.getVisibility() != View.VISIBLE;
    }
}