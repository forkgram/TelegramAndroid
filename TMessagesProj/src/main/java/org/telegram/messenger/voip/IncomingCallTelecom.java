package org.telegram.messenger.voip;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.UUID;

/** Best-effort companion controls for a call the app is already presenting. */
@TargetApi(Build.VERSION_CODES.O)
final class IncomingCallTelecom {
    static final String EXTRA_TOKEN = "org.telegram.voip.BLUETOOTH_CALL_TOKEN";
    private static final String ACCOUNT_ID = "forkgram-incoming-voice";
    private final Context context;
    private static final long UI_TIMEOUT_MS = 3000;
    enum Submission { UNAVAILABLE, DENIED, SUBMITTED }
    private Runnable show, denied, answer, reject, hangUp, silence;
    private final Runnable timeout = this::failPending;
    private boolean waitingForUi;
    private boolean presented;
    // Do not pass Telegram user or call identifiers to Telecom.
    private final String token = UUID.randomUUID().toString();
    private boolean awaitingConnection;
    private Connection connection;

    IncomingCallTelecom(Context context, Runnable show, Runnable denied,
                       Runnable answer, Runnable reject, Runnable hangUp, Runnable silence) {
        this.context = context;
        this.show = show;
        this.denied = denied;
        setControls(answer, reject, hangUp, silence);
    }

    void setControls(Runnable answer, Runnable reject, Runnable hangUp, Runnable silence) {
        this.answer = answer;
        this.reject = reject;
        this.hangUp = hangUp;
        this.silence = silence;
    }

    static boolean enabled(boolean video, boolean notificationsDisabled) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !video && !notificationsDisabled
                && MessagesController.getGlobalMainSettings().getBoolean("bluetoothCallControls", false);
    }

    boolean isPresented() {
        return presented;
    }

    Submission report() {
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom == null) {
            return Submission.UNAVAILABLE;
        }
        PhoneAccountHandle handle = new PhoneAccountHandle(new ComponentName(context, TelegramConnectionService.class), ACCOUNT_ID);
        try {
            telecom.registerPhoneAccount(new PhoneAccount.Builder(handle, LocaleController.getString(R.string.AppName))
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                    .build());
        } catch (RuntimeException error) {
            FileLog.e(error);
            return Submission.UNAVAILABLE;
        }
        try {
            if (!telecom.isIncomingCallPermitted(handle)) {
                return Submission.DENIED;
            }
            Bundle extras = new Bundle();
            extras.putString(EXTRA_TOKEN, token);
            awaitingConnection = true;
            waitingForUi = true;
            AndroidUtilities.runOnUIThread(timeout, UI_TIMEOUT_MS);
            telecom.addNewIncomingCall(handle, extras);
            return Submission.SUBMITTED;
        } catch (RuntimeException error) {
            close(DisconnectCause.CANCELED);
            FileLog.e(error);
            return Submission.DENIED;
        }
    }

    static boolean isBluetoothAccount(ConnectionRequest request) {
        PhoneAccountHandle handle = request.getAccountHandle();
        return handle != null && ACCOUNT_ID.equals(handle.getId());
    }

    static Connection canceled() {
        return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.CANCELED));
    }

    private boolean matches(ConnectionRequest request) {
        Bundle extras = request.getExtras();
        return isBluetoothAccount(request) && extras != null && token.equals(extras.getString(EXTRA_TOKEN));
    }

    void onSubmissionFailed(ConnectionRequest request) {
        if (matches(request)) failPending();
    }

    private void failPending() {
        if (!waitingForUi) return;
        close(DisconnectCause.CANCELED);
        denied.run();
    }

    Connection createConnection(ConnectionRequest request) {
        if (!awaitingConnection || !matches(request)) {
            return canceled();
        }
        awaitingConnection = false;
        connection = new Connection() {
            @Override public void onShowIncomingCallUi() {
                if (connection == this && waitingForUi) {
                    waitingForUi = false;
                    presented = true;
                    AndroidUtilities.cancelRunOnUIThread(timeout);
                    show.run();
                }
            }

            @Override public void onAnswer() {
                if (connection == this && presented) answer.run();
            }

            @Override public void onAnswer(int videoState) {
                onAnswer();
            }

            @Override public void onReject() {
                if (connection == this) {
                    boolean wasPresented = presented;
                    close(DisconnectCause.REJECTED);
                    (wasPresented ? reject : denied).run();
                }
            }

            @Override public void onDisconnect() {
                if (connection == this) {
                    boolean wasPresented = presented;
                    close(DisconnectCause.LOCAL);
                    (wasPresented ? hangUp : denied).run();
                }
            }

            @Override public void onSilence() {
                if (connection == this) silence.run();
            }
        };
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connection.setAudioModeIsVoip(true);
        connection.setCallerDisplayName(LocaleController.getString(R.string.AppName), TelecomManager.PRESENTATION_ALLOWED);
        connection.setRinging();
        return connection;
    }

    void setActive() {
        awaitingConnection = false;
        if (connection != null) {
            connection.setActive();
        }
    }

    void close(int cause) {
        awaitingConnection = false;
        waitingForUi = false;
        presented = false;
        AndroidUtilities.cancelRunOnUIThread(timeout);
        if (connection != null) {
            Connection old = connection;
            connection = null;
            old.setDisconnected(new DisconnectCause(cause));
            old.destroy();
        }
    }
}
