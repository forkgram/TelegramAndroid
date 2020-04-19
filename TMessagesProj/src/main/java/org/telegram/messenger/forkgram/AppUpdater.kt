package org.telegram.messenger.forkgram

import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.os.AsyncTask
import android.widget.Toast

import org.json.JSONObject
import org.json.JSONArray

import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog

import java.io.*
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val kCheckInterval = 30 * 60 * 1000 // 30 minutes.
    private const val title = "The latest Forkgram version"
    private const val desc = ""

    private var downloadBroadcastReceiver: DownloadReceiver? = null
    private var lastTimestampOfCheck = 0L
    var downloadId = 0L

    @JvmStatic
    fun clearCachedInstallers(context: Context) {
        try {
            val externalDir: File = context.getExternalFilesDir(null) ?: return
            val downloadDir = "${externalDir.absolutePath}/${android.os.Environment.DIRECTORY_DOWNLOADS}"

            val f = File(downloadDir)
            if (!f.exists() || !f.isDirectory) {
                return
            }
            val apks = f.listFiles { f -> (f.name.startsWith(title) && f.name.endsWith(".apk")) } ?: return
            for (apk in apks) {
                try {
                    android.util.Log.i("Fork Client", "File was removed. Path: " + apk.absolutePath)
                    apk.delete()
                } catch (e: Exception) {
                    android.util.Log.e("Fork Client", "Failed to delete file: ${apk.absolutePath}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Fork Client", "Error in clearCachedInstallers", e)
        }
    }

    @JvmStatic
    fun checkNewVersion(
            parentActivity: Activity,
            context: Context,
            callback: (AlertDialog.Builder?) -> Int,
            manual: Boolean = false) {

        try {
            if (!manual && System.currentTimeMillis() - lastTimestampOfCheck < kCheckInterval) {
                return
            }
            if (downloadId != 0L) {
                return
            }
            val currentVersion = BuildVars.BUILD_VERSION_STRING
            val userRepo = BuildVars.USER_REPO
            if (userRepo.isEmpty()) {
                return
            }

            HttpTask { response ->
                try {
                    if (response == null) {
                        if (manual) {
                            Toast.makeText(
                                context,
                                "Can't check, please try later",
                                Toast.LENGTH_SHORT).show()
                        }
                        android.util.Log.w("Fork Client", "Connection error.")
                        return@HttpTask
                    }
                    lastTimestampOfCheck = System.currentTimeMillis()

                    val root = JSONObject(response)
                    val tag = root.optString("tag_name")

                    if (tag <= currentVersion) {
                        if (manual) {
                            Toast.makeText(context, "No updates", Toast.LENGTH_SHORT).show()
                        }
                        return@HttpTask
                    }

                    // New version!
                    val body = root.optString("body")
                    val assets: JSONArray = root.optJSONArray("assets") ?: run {
                        android.util.Log.w("Fork Client", "No assets in release")
                        return@HttpTask
                    }

                    val assetIndex = if (BuildVars.DEBUG_VERSION) 0 else 1
                    if (assets.length() <= assetIndex) {
                        android.util.Log.w("Fork Client", "Not enough assets in release (need index $assetIndex)")
                        return@HttpTask
                    }

                    val asset = assets.optJSONObject(assetIndex) ?: run {
                        android.util.Log.w("Fork Client", "Asset at index $assetIndex is null")
                        return@HttpTask
                    }

                    val url = asset.optString("browser_download_url").takeIf { it.isNotEmpty() } ?: run {
                        android.util.Log.w("Fork Client", "Empty download URL")
                        return@HttpTask
                    }

                    val builder = AlertDialog.Builder(parentActivity)
                    builder.setTitle("New version $tag")
                    builder.setMessage("Release notes:\n$body")
                    builder.setMessageTextViewClickable(false)
                    builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null)
                    builder.setPositiveButton("Install") { _, _ ->
                        try {
                            downloadBroadcastReceiver = DownloadReceiver()
                            val intentFilter = IntentFilter()
                            intentFilter.addAction("android.intent.action.DOWNLOAD_COMPLETE")
                            intentFilter.addAction("android.intent.action.DOWNLOAD_NOTIFICATION_CLICKED")
                            parentActivity.registerReceiver(downloadBroadcastReceiver, intentFilter)

                            val dm = DownloadManagerUtil(context)
                            if (dm.checkDownloadManagerEnable()) {
                                if (downloadId != 0L) {
                                    dm.clearCurrentTask(downloadId)
                                }
                                downloadId = dm.download(url, title, desc)
                            } else {
                                Toast.makeText(context, "Please open Download Manager", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("Fork Client", "Error starting download", e)
                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    callback(builder)
                } catch (e: Exception) {
                    android.util.Log.e("Fork Client", "Error processing update check", e)
                    if (manual) {
                        Toast.makeText(context, "Update check failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }.execute("GET", "https://api.github.com/repos/$userRepo/releases/latest")
        } catch (e: Exception) {
            android.util.Log.e("Fork Client", "Error in checkNewVersion", e)
            if (manual) {
                Toast.makeText(context, "Update check error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    class HttpTask(callback: (String?) -> Unit) : AsyncTask<String, Unit, String>()  {
        private val TIMEOUT = 10 * 1000
        private val callback = callback

        override fun doInBackground(vararg params: String): String? {
            return try {
                val url = URL(params[1])
                val httpClient = url.openConnection() as HttpURLConnection
                httpClient.readTimeout = TIMEOUT
                httpClient.connectTimeout = TIMEOUT
                httpClient.requestMethod = params[0]

                try {
                    if (httpClient.responseCode == HttpURLConnection.HTTP_OK) {
                        val stream = BufferedInputStream(httpClient.inputStream)
                        readStream(inputStream = stream)
                    } else {
                        android.util.Log.w("Fork Client", "HTTP error ${httpClient.responseCode}")
                        null
                    }
                } finally {
                    httpClient.disconnect()
                }
            } catch (e: Exception) {
                android.util.Log.e("Fork Client", "Network error", e)
                null
            }
        }

        private fun readStream(inputStream: BufferedInputStream): String {
            return try {
                val bufferedReader = BufferedReader(InputStreamReader(inputStream))
                val stringBuilder = StringBuilder()
                bufferedReader.forEachLine { stringBuilder.append(it) }
                stringBuilder.toString()
            } catch (e: Exception) {
                android.util.Log.e("Fork Client", "Error reading stream", e)
                ""
            } finally {
                try {
                    inputStream.close()
                } catch (e: Exception) {
                    android.util.Log.e("Fork Client", "Error closing stream", e)
                }
            }
        }

        override fun onPostExecute(result: String?) {
            try {
                super.onPostExecute(result)
                callback(result)
            } catch (e: Exception) {
                android.util.Log.e("Fork Client", "Error in onPostExecute", e)
            }
        }
    }
}