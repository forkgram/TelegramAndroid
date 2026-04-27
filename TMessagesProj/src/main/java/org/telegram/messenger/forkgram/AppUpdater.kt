package org.telegram.messenger.forkgram

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {

    private const val TITLE = "The latest Forkgram version"
    private const val DESC = ""
    private const val PREFS_NAME = "AppUpdaterPrefs"
    private const val KEY_LAST_APK_PATH = "lastApkPath"

    private var downloadBroadcastReceiver: DownloadReceiver? = null
    private var lastTimestampOfCheck = 0L
    var downloadId = 0L

    @JvmStatic
    fun clearCachedInstallers(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastApkPath = prefs.getString(KEY_LAST_APK_PATH, null)

            if (lastApkPath != null) {
                val apkFile = File(lastApkPath)
                if (apkFile.exists()) {
                    apkFile.delete()
                    FileLog.d("Deleted saved APK: $lastApkPath")
                }
                prefs.edit().remove(KEY_LAST_APK_PATH).apply()
            }
        } catch (e: Exception) {
            FileLog.e("Error in clearCachedInstallers", e)
        }
    }

    private fun saveApkPath(context: Context, path: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_APK_PATH, path)
                .apply()
        } catch (e: Exception) {
            FileLog.e("Error saving APK path", e)
        }
    }

    @JvmStatic
    fun saveApkPathPublic(context: Context, path: String) {
        saveApkPath(context, path)
    }

    @JvmStatic
    fun checkNewVersion(
        parentActivity: Activity,
        context: Context,
        legacyCallback: (AlertDialog.Builder?) -> Int,
        modernCallback: (TLRPC.TL_help_appUpdate?) -> Int,
        manual: Boolean = false
    ) {

        try {
            val updateInterval = MessagesController.getGlobalMainSettings().getLong("updateForkCheckInterval", 30 * 60 * 1000L)
            if (!manual && (updateInterval == 0L || System.currentTimeMillis() - lastTimestampOfCheck < updateInterval)) {
                return
            }
            if (downloadId != 0L) {
                return
            }
            lastTimestampOfCheck = System.currentTimeMillis()
            val currentVersion = BuildVars.BUILD_VERSION_STRING

            // Try Telegram channel first if we have an active session
            if (UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
                checkUpdateFromTelegramChannel(parentActivity, context, legacyCallback, modernCallback, manual, currentVersion)
            } else {
                // Fallback to GitHub API
                checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
            }
        } catch (e: Exception) {
            FileLog.e("Error in checkNewVersion", e)
            if (manual) {
                Toast.makeText(context, "Update check error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkUpdateFromTelegramChannel(
        parentActivity: Activity,
        context: Context,
        legacyCallback: (AlertDialog.Builder?) -> Int,
        modernCallback: (TLRPC.TL_help_appUpdate?) -> Int,
        manual: Boolean,
        currentVersion: String
    ) {

        val req = TLRPC.TL_contacts_resolveUsername()
        req.username = BuildVars.UPDATE_CHANNEL_USERNAME

        ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(req) { response, error ->
            if (error != null || response !is TLRPC.TL_contacts_resolvedPeer) {
                FileLog.w("Failed to resolve update channel, falling back to GitHub")
                checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                return@sendRequest
            }

            val chat = response.chats.firstOrNull()
            if (chat == null) {
                FileLog.w("Update channel not found, falling back to GitHub")
                checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                return@sendRequest
            }

            val messagesReq = TLRPC.TL_messages_getHistory()
            val inputChannel = TLRPC.TL_inputPeerChannel()
            inputChannel.channel_id = chat.id
            inputChannel.access_hash = chat.access_hash
            messagesReq.peer = inputChannel
            messagesReq.limit = 1

            ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(messagesReq) { historyResponse, historyError ->
                if (historyError != null || historyResponse !is TLRPC.messages_Messages) {
                    FileLog.w("Failed to get channel history, falling back to GitHub")
                    checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                    return@sendRequest
                }

                val message = historyResponse.messages.firstOrNull()
                if (message?.message == null) {
                    FileLog.w("No messages in update channel, falling back to GitHub")
                    checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                    return@sendRequest
                }

                try {
                    val updateInfo = JSONObject(message.message)

                    val androidInfo = updateInfo.optJSONObject("android")
                    if (androidInfo == null) {
                        FileLog.w("Invalid update JSON format, falling back to GitHub")
                        checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                        return@sendRequest
                    }

                    val isBeta = org.telegram.messenger.ApplicationLoader.getApplicationId().contains(".beta")
                    val stableInfo = if (isBeta) {
                        androidInfo.optString("beta")
                    } else {
                        androidInfo.optString("release")
                    }
                    val optionalInfo = if (manual && isBeta) androidInfo.optString("optional") else ""
                    val releaseInfo = when {
                        optionalInfo.isEmpty() -> stableInfo
                        stableInfo.isEmpty() -> optionalInfo
                        compareVersions(optionalInfo.substringBefore(":"), stableInfo.substringBefore(":")) > 0 -> optionalInfo
                        else -> stableInfo
                    }

                    if (releaseInfo.isEmpty()) {
                        FileLog.w("No version info found, falling back to GitHub")
                        checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                        return@sendRequest
                    }

                    val parts = releaseInfo.split(":")
                    if (parts.size != 2) {
                        FileLog.w("Invalid version format, falling back to GitHub")
                        checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                        return@sendRequest
                    }

                    val newVersion = parts[0]
                    val fileInfo = parts[1].split("#")

                    if (fileInfo.size != 2) {
                        FileLog.w("Invalid file info format, falling back to GitHub")
                        checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                        return@sendRequest
                    }

                    val filesChannelUsername = fileInfo[0]
                    val messageId = fileInfo[1].toIntOrNull()

                    if (messageId == null) {
                        FileLog.w("Invalid message ID, falling back to GitHub")
                        checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                        return@sendRequest
                    }

                    lastTimestampOfCheck = System.currentTimeMillis()

                    if (compareVersions(newVersion, currentVersion) <= 0) {
                        if (manual) {
                            AndroidUtilities.runOnUIThread {
                                Toast.makeText(context, "No updates", Toast.LENGTH_SHORT).show()
                            }
                        }
                        return@sendRequest
                    }

                    // Get download URL from files channel
                    getDownloadUrlFromFilesChannel(parentActivity, context, modernCallback, newVersion, filesChannelUsername, messageId)

                } catch (e: Exception) {
                    FileLog.e("Error parsing update info from Telegram, falling back to GitHub", e)
                    checkUpdateFromGitHub(parentActivity, context, legacyCallback, manual, currentVersion)
                }
            }
        }
    }

    private fun getDownloadUrlFromFilesChannel(
        parentActivity: Activity,
        context: Context,
        modernCallback: (TLRPC.TL_help_appUpdate?) -> Int,
        newVersion: String,
        filesChannelUsername: String,
        messageId: Int
    ) {

        val req = TLRPC.TL_contacts_resolveUsername()
        req.username = filesChannelUsername

        ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(req) { response, error ->
            if (error != null || response !is TLRPC.TL_contacts_resolvedPeer) {
                FileLog.w("Failed to resolve files channel")
                return@sendRequest
            }

            val chat = response.chats.firstOrNull()
            if (chat == null) {
                FileLog.w("Files channel not found")
                return@sendRequest
            }

            val messagesReq = TLRPC.TL_channels_getMessages()
            val inputChannel = TLRPC.TL_inputChannel()
            inputChannel.channel_id = chat.id
            inputChannel.access_hash = chat.access_hash
            messagesReq.channel = inputChannel
            messagesReq.id.add(messageId)

            ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(messagesReq) { messagesResponse, messagesError ->
                if (messagesError != null || messagesResponse !is TLRPC.messages_Messages) {
                    FileLog.w("Failed to get file message")
                    return@sendRequest
                }

                val fileMessage = messagesResponse.messages.firstOrNull()

                val document = fileMessage?.media?.document
                if (document == null) {
                    FileLog.w("No document found in file message")
                    return@sendRequest
                }

                val fullMessage = fileMessage.message ?: ""
                val changelog = if (fullMessage.contains("Changelog:")) {
                    fullMessage.substringAfter("Changelog:").trim()
                } else {
                    fullMessage.takeIf { it.isNotEmpty() } ?: "A new version is available."
                }

                val update = TLRPC.TL_help_appUpdate()
                update.version = newVersion
                update.text = changelog
                update.entities = ArrayList()
                update.document = document
                update.url = ""
                update.can_not_skip = false

                val readReq = TLRPC.TL_channels_readHistory()
                readReq.channel = inputChannel
                readReq.max_id = messageId
                ConnectionsManager.getInstance(UserConfig.selectedAccount).sendRequest(readReq) { _, _ -> }

                AndroidUtilities.runOnUIThread {
                    modernCallback(update)
                }
            }
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split(".")
        val rightParts = right.split(".")
        for (i in 0 until maxOf(leftParts.size, rightParts.size)) {
            val leftPart = leftParts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val rightPart = rightParts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (leftPart != rightPart) {
                return leftPart.compareTo(rightPart)
            }
        }
        return 0
    }

    private fun checkUpdateFromGitHub(
        parentActivity: Activity,
        context: Context,
        callback: (AlertDialog.Builder?) -> Int,
        manual: Boolean,
        currentVersion: String
    ) {

        try {
            val userRepo = BuildVars.USER_REPO
            if (userRepo.isEmpty()) {
                return
            }

            httpRequest("GET", "https://api.github.com/repos/$userRepo/releases/latest") { response ->
                try {
                    if (response == null) {
                        FileLog.w("Connection error.")
                        return@httpRequest
                    }
                    lastTimestampOfCheck = System.currentTimeMillis()

                    val root = JSONObject(response)
                    val tag = root.optString("tag_name")

                    if (compareVersions(tag, currentVersion) <= 0) {
                        if (manual) {
                            Toast.makeText(context, "No updates", Toast.LENGTH_SHORT).show()
                        }
                        return@httpRequest
                    }

                    // New version!
                    val body = root.optString("body")
                    val assets: JSONArray = root.optJSONArray("assets") ?: run {
                        FileLog.w("No assets in release")
                        return@httpRequest
                    }

                    val apks = (0 until assets.length())
                        .mapNotNull { assets.optJSONObject(it) }
                        .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }

                    val asset = apks.firstOrNull { it.optString("name").contains("compressed", ignoreCase = true) }
                        ?: apks.lastOrNull()
                        ?: run {
                            FileLog.w("No apk asset in release")
                            if (manual) {
                                Toast.makeText(context, "No installable asset in release", Toast.LENGTH_SHORT).show()
                            }
                            return@httpRequest
                        }

                    val url = asset.optString("browser_download_url").takeIf { it.isNotEmpty() } ?: run {
                        FileLog.w("Empty download URL")
                        if (manual) {
                            Toast.makeText(context, "Empty download URL", Toast.LENGTH_SHORT).show()
                        }
                        return@httpRequest
                    }

                    val builder = AlertDialog.Builder(parentActivity)
                    builder.setTitle("New version $tag")
                    builder.setMessage("Release notes:\n$body")
                    builder.setMessageTextViewClickable(false)
                    builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    builder.setPositiveButton("Install") { _, _ ->
                        try {
                            if (downloadBroadcastReceiver == null) {
                                downloadBroadcastReceiver = DownloadReceiver()
                                val intentFilter = IntentFilter()
                                intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                                intentFilter.addAction(DownloadManager.ACTION_NOTIFICATION_CLICKED)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    context.applicationContext.registerReceiver(downloadBroadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                                } else {
                                    context.applicationContext.registerReceiver(downloadBroadcastReceiver, intentFilter)
                                }
                                FileLog.d("DownloadReceiver registered")
                            }

                            val dm = DownloadManagerUtil(context)
                            if (dm.checkDownloadManagerEnable()) {
                                if (downloadId != 0L) {
                                    dm.clearCurrentTask(downloadId)
                                }
                                downloadId = dm.download(url, TITLE, DESC)
                                FileLog.d("Download started with ID: $downloadId")
                                Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Please open Download Manager", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            FileLog.e("Error starting download", e)
                            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    callback(builder)
                } catch (e: Exception) {
                    FileLog.e("Error processing update check", e)
                    if (manual) {
                        Toast.makeText(context, "Update check failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            FileLog.e("Error in checkUpdateFromGitHub", e)
            if (manual) {
                Toast.makeText(context, "Update check error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private const val HTTP_TIMEOUT = 10 * 1000

    private fun httpRequest(method: String, url: String, callback: (String?) -> Unit) {
        Thread {
            val result = try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.readTimeout = HTTP_TIMEOUT
                connection.connectTimeout = HTTP_TIMEOUT
                connection.requestMethod = method
                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        FileLog.w("HTTP error ${connection.responseCode}")
                        null
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                FileLog.e("Network error", e)
                null
            }
            AndroidUtilities.runOnUIThread { callback(result) }
        }.start()
    }
}
