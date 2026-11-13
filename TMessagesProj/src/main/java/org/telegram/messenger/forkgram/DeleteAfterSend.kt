package org.telegram.messenger.forkgram

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.LaunchActivity
import java.io.File

/**
 * Photo editor hands over the device file the user flagged with the bin button;
 * the file is removed only after the server acknowledges the message carrying it.
 */
object DeleteAfterSend {

    const val REQUEST_CODE = 9201

    private const val EXPIRE_MS = 24L * 60 * 60 * 1000

    private class Pending(
        val deletePath: String,
        val imageId: Int,
        val isVideo: Boolean,
        val expires: Long
    )

    private val pending = HashMap<String, Pending>()
    private var pendingConsent: Pending? = null

    /**
     * @param sendPath path that SendMessagesHelper reports back on success
     * @param deletePath device file to remove, usually the original behind a cropped copy
     */
    @JvmStatic
    fun arm(sendPath: String?, deletePath: String?, imageId: Int, isVideo: Boolean) {
        if (sendPath.isNullOrEmpty() || deletePath.isNullOrEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        synchronized(pending) {
            pending.values.removeAll { it.expires < now }
            pending[sendPath] = Pending(deletePath, imageId, isVideo, now + EXPIRE_MS)
        }
    }

    /** Runs on the UI thread once the uploaded message reaches the server. */
    @JvmStatic
    fun onMessageSent(sendPath: String?) {
        if (sendPath.isNullOrEmpty()) {
            return
        }
        val entry = synchronized(pending) { pending.remove(sendPath) } ?: return
        if (entry.imageId > 0 && Build.VERSION.SDK_INT >= 30 && startDeleteRequest(entry)) {
            return
        }
        Utilities.globalQueue.postRunnable {
            var deleted = entry.imageId > 0 && deleteMedia(entry)
            if (!deleted) {
                deleted = deleteFile(entry)
            }
            if (deleted) {
                AndroidUtilities.runOnUIThread { showConfirmation() }
            }
        }
    }

    /** Dropped when the queued message is cancelled before it reaches the server. */
    @JvmStatic
    fun disarm(params: HashMap<String, String>?) {
        val sendPath = params?.get("originalPath") ?: return
        synchronized(pending) { pending.remove(sendPath) }
    }

    @JvmStatic
    fun onActivityResult(resultCode: Int) {
        val entry = pendingConsent
        pendingConsent = null
        if (entry != null && resultCode == Activity.RESULT_OK) {
            showConfirmation()
        }
    }

    /**
     * A gallery row owned by another app needs the system consent sheet on Android 11+.
     * @return true when the sheet was started, so the caller must not delete behind its back
     */
    private fun startDeleteRequest(entry: Pending): Boolean {
        val activity = LaunchActivity.instance ?: return false
        return try {
            pendingConsent = entry
            val request = MediaStore.createDeleteRequest(activity.contentResolver, listOf(mediaUri(entry)))
            activity.startIntentSenderForResult(request.intentSender, REQUEST_CODE, null, 0, 0, 0)
            true
        } catch (e: Exception) {
            FileLog.e(e)
            pendingConsent = null
            false
        }
    }

    private fun deleteMedia(entry: Pending): Boolean {
        val uri = mediaUri(entry)
        return try {
            ApplicationLoader.applicationContext.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                AndroidUtilities.runOnUIThread {
                    if (!startDeleteIntent(e.userAction.actionIntent.intentSender, entry)) {
                        pendingConsent = null
                    }
                }
                true
            } else {
                FileLog.e(e)
                false
            }
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    private fun startDeleteIntent(intentSender: IntentSender, entry: Pending): Boolean {
        val activity = LaunchActivity.instance ?: return false
        return try {
            pendingConsent = entry
            activity.startIntentSenderForResult(intentSender, REQUEST_CODE, null, 0, 0, 0)
            true
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    private fun mediaUri(entry: Pending): Uri {
        val collection = if (entry.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(collection, entry.imageId.toLong())
    }

    private fun deleteFile(entry: Pending): Boolean {
        return try {
            val file = File(entry.deletePath)
            file.exists() && file.delete()
        } catch (e: Exception) {
            FileLog.e(e)
            false
        }
    }

    private fun showConfirmation() {
        NotificationCenter.getGlobalInstance().postNotificationName(
            NotificationCenter.showBulletin,
            Bulletin.TYPE_SUCCESS,
            LocaleController.getString(R.string.DeleteFromDeviceDone)
        )
    }
}
