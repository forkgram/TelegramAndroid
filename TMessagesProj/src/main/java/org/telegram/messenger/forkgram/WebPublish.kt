package org.telegram.messenger.forkgram

import android.content.SharedPreferences
import android.widget.Toast
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * "Publish to web service": build text + media from messages, upload
 * everything to the web service in one request, put the page link into the
 * clipboard using a user-defined template (%q placeholder).
 *
 * No local files are written except a temporary cache used for the upload,
 * which is deleted afterwards (on error too).
 *
 * Service contract:
 * - POST {base}/upload, multipart: field "text" (final text),
 *   one "files[]" field per media file (filename + content type)
 * - auth header X-Upload-Token on every request (never logged, never shown)
 * - User-Agent: Forkgram/1.0 on every request
 * - response JSON {"id": "<id>", "url": "<page url>"}
 * - DELETE <url> with X-Upload-Token removes the page
 */
object WebPublishConfig {

    const val PREF_BASE = "webpublish_base"
    const val PREF_TOKEN = "webpublish_token"
    const val PREF_TEMPLATE = "webpublish_template"

    const val DEFAULT_TEMPLATE = "%q"

    const val FIELD_BASE = "base"
    const val FIELD_TOKEN = "token"
    const val FIELD_TEMPLATE = "template"

    @JvmStatic
    fun prefs(): SharedPreferences {
        return MessagesController.getGlobalMainSettings()
    }

    @JvmStatic
    fun base(): String = prefs().getString(PREF_BASE, "") ?: ""

    /**
     * Upload token: user-managed credential, sent as X-Upload-Token.
     * Never written to logs, never shown in UI (masked field only).
     * Deliberately NOT overridable via BuildConfig so it never lands in APKs.
     */
    @JvmStatic
    fun token(): String = prefs().getString(PREF_TOKEN, "") ?: ""

    @JvmStatic
    fun hasToken(): Boolean = token().isNotEmpty()

    @JvmStatic
    fun template(): String {
        return prefs().getString(PREF_TEMPLATE, DEFAULT_TEMPLATE) ?: DEFAULT_TEMPLATE
    }

    /**
     * Validate the effective configuration.
     * @return map of field name -> human-readable error; empty means valid.
     */
    @JvmStatic
    fun validate(): Map<String, String> {
        return validateValues(base().trim(), token(), template())
    }

    @JvmStatic
    fun validateValues(base: String, token: String, template: String): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        val b = base.trim()
        if (b.isEmpty()) {
            errors[FIELD_BASE] = "Service base URL is required"
        } else {
            try {
                val url = URL(b)
                if (url.protocol != "http" && url.protocol != "https") {
                    errors[FIELD_BASE] = "Base URL must start with http:// or https://"
                } else if (url.host.isNullOrEmpty()) {
                    errors[FIELD_BASE] = "Base URL has no host"
                }
            } catch (e: Exception) {
                errors[FIELD_BASE] = "Base URL is not a valid URL"
            }
        }
        if (token.isEmpty()) {
            errors[FIELD_TOKEN] = "Upload token is required"
        }
        if (template.isEmpty()) {
            errors[FIELD_TEMPLATE] = "Clipboard template is required (use %q for the link)"
        }
        return errors
    }

    @JvmStatic
    fun isConfigured(): Boolean {
        return base().trim().isNotEmpty()
    }

    @JvmStatic
    fun uploadUrl(): String = base().trim().trimEnd('/') + "/upload"

    @JvmStatic
    fun applyClipboardTemplate(link: String): String {
        val t = template()
        return if (t.contains("%q")) t.replace("%q", link) else "$t $link"
    }
}

object WebPublishClient {

    const val USER_AGENT = "Forkgram/1.0"

    const val IMAGE_MAX_BYTES = 5L * 1024 * 1024
    const val VIDEO_MAX_BYTES = 20L * 1024 * 1024
    const val TEXT_MAX_BYTES = 5L * 1024 * 1024

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000

    data class UploadFile(val file: File, val fileName: String, val mimeType: String)

    data class Uploaded(val id: String, val url: String)

    sealed class Result {
        data class Ok(val uploaded: Uploaded) : Result()
        data class Fail(val message: String) : Result()
    }

    sealed class DeleteResult {
        object Ok : DeleteResult()
        data class Fail(val message: String) : DeleteResult()
    }

    private fun readStream(conn: HttpURLConnection, code: Int): String {
        val stream: InputStream? = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (e: Exception) {
            null
        }
        if (stream == null) return ""
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private fun httpErrorMessage(code: Int, retryAfter: String?): String {
        return when (code) {
            413 -> "Server rejected the upload: file too large (413)"
            415 -> "Server rejected the upload: file type not allowed (415)"
            429 -> {
                val suffix = if (!retryAfter.isNullOrEmpty()) " (retry after $retryAfter)" else ""
                "Server rate limit exceeded$suffix (429)"
            }
            401, 403 -> "Server rejected the upload: bad upload token (HTTP $code)"
            404 -> "Server rejected the upload: unknown endpoint (HTTP 404)"
            else -> "Server error: HTTP $code"
        }
    }

    private fun encodeField(out: java.io.OutputStream, boundary: String, name: String, value: String) {
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    /**
     * Upload text + files in one request.
     * Response link and id are taken from the "url" and "id" fields.
     */
    @JvmStatic
    fun uploadReport(uploadUrl: String, token: String, text: String, files: List<UploadFile>): Result {
        val boundary = "----Forkgram${UUID.randomUUID()}"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(uploadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("X-Upload-Token", token)
            conn.outputStream.use { out ->
                encodeField(out, boundary, "text", text)
                for (f in files) {
                    val head = "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"files[]\"; filename=\"${f.fileName}\"\r\n" +
                        "Content-Type: ${f.mimeType}\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    FileInputStream(f.file).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val code = conn.responseCode
            val retryAfter = try {
                conn.getHeaderField("Retry-After")
            } catch (e: Exception) {
                null
            }
            val body = readStream(conn, code)
            if (code in 200..299) {
                try {
                    val obj = JSONObject(body.trim())
                    val url = obj.optString("url", "")
                    val id = obj.optString("id", "")
                    if (url.isEmpty() || id.isEmpty()) {
                        return Result.Fail("Server returned no link")
                    }
                    return Result.Ok(Uploaded(id, url))
                } catch (e: Exception) {
                    return Result.Fail("Server returned an unreadable response")
                }
            }
            return Result.Fail(httpErrorMessage(code, retryAfter))
        } catch (e: java.net.SocketTimeoutException) {
            return Result.Fail("Connection timed out")
        } catch (e: java.net.UnknownHostException) {
            return Result.Fail("Cannot reach the server (unknown host)")
        } catch (e: javax.net.ssl.SSLException) {
            return Result.Fail("Secure connection failed")
        } catch (e: Exception) {
            FileLog.e(e)
            return Result.Fail("Network error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** Delete a previously uploaded page by its URL. Requires the upload token. */
    @JvmStatic
    fun deleteReport(pageUrl: String, token: String): DeleteResult {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(pageUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("X-Upload-Token", token)
            val code = conn.responseCode
            readStream(conn, code)
            if (code in 200..299) {
                return DeleteResult.Ok
            }
            return DeleteResult.Fail(httpErrorMessage(code, null))
        } catch (e: Exception) {
            FileLog.e(e)
            return DeleteResult.Fail("Network error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}

object WebPublishMarkdown {

    @JvmStatic
    fun textOf(msg: MessageObject): String {
        val caption = msg.caption
        if (!caption.isNullOrEmpty()) return caption.toString()
        val text = msg.messageText
        if (text != null && text.isNotEmpty()) return text.toString()
        return ""
    }
}

/**
 * Orchestrates one publish run on a dedicated worker thread.
 *
 * The [PublishSession] instance is owned by the worker thread and is passed
 * explicitly through every step (strong references only, no weak links), so
 * the chain cannot die silently mid-flight. The terminal step reports to the
 * UI and drops the session, so nothing is retained afterwards.
 */
object WebPublishFlow {

    private const val DOWNLOAD_TIMEOUT_SEC = 120L
    private const val DOWNLOAD_POLL_MS = 400L

    @JvmStatic
    @JvmOverloads
    fun publish(
        currentAccount: Int,
        messages: List<MessageObject>,
        fragment: BaseFragment?,
        onSuccess: Runnable? = null
    ) {
        val errors = WebPublishConfig.validate()
        if (errors.isNotEmpty()) {
            notifyError(fragment, errors.values.first())
            return
        }
        if (messages.isEmpty()) {
            notifyError(fragment, "No messages selected")
            return
        }
        // Owning copies for the worker thread: nothing is captured by reference
        // from the caller's stack.
        val snapshot = ArrayList(messages)
        val fragmentRef = WeakReference<BaseFragment>(fragment)
        AndroidUtilities.runOnUIThread {
            Toast.makeText(ApplicationLoader.applicationContext, "Uploading…", Toast.LENGTH_SHORT).show()
        }
        val session = PublishSession(currentAccount, snapshot, fragmentRef, onSuccess)
        Thread({
            session.run()
            // Terminal step: session is not passed anywhere else, so it is freed here.
        }, "webpublish").start()
    }

    @JvmStatic
    fun notifySuccess(fragment: BaseFragment?, clipboardText: String) {
        AndroidUtilities.runOnUIThread {
            AndroidUtilities.addToClipboard(clipboardText)
            if (BulletinFactory.canShowBulletin(fragment)) {
                BulletinFactory.of(fragment).createCopyBulletin("Link copied to clipboard").show()
            } else {
                Toast.makeText(ApplicationLoader.applicationContext, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JvmStatic
    fun notifyError(fragment: BaseFragment?, message: String) {
        AndroidUtilities.runOnUIThread {
            if (BulletinFactory.canShowBulletin(fragment)) {
                BulletinFactory.of(fragment).createErrorBulletin(message).show()
            } else {
                Toast.makeText(ApplicationLoader.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class PendingMedia(
        val fileName: String,
        val mimeType: String,
        val source: File,
        val isImage: Boolean,
        val isVideo: Boolean
    )

    /**
     * All state of one publish run. Lives on the worker thread; every step is
     * a method on this instance, so the chain holds itself alive until done.
     */
    private class PublishSession(
        private val currentAccount: Int,
        private val messages: List<MessageObject>,
        private val fragmentRef: WeakReference<BaseFragment>,
        // Held strongly only for the duration of the run and dropped in the
        // terminal step together with the session itself.
        private val onSuccess: Runnable?
    ) {
        private val tempDir = File(
            File(ApplicationLoader.applicationContext.cacheDir, "webpublish"),
            "run_${System.currentTimeMillis()}"
        )
        private var tempCounter = 0
        private var aborted = false

        fun run() {
            try {
                runInternal()
            } catch (e: Exception) {
                FileLog.e(e)
                fail("Publish failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                deleteTemp()
            }
        }

        private fun fragment(): BaseFragment? = fragmentRef.get()

        private fun fail(message: String) {
            aborted = true
            notifyError(fragment(), message)
        }

        private fun runInternal() {
            val base = WebPublishConfig.base().trim().trimEnd('/')
            val token = WebPublishConfig.token()
            // Step 1: assemble text, resolve local media files.
            val textBuilder = StringBuilder()
            val pending = ArrayList<PendingMedia>()
            for (msg in messages) {
                val text = WebPublishMarkdown.textOf(msg)
                if (text.isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n\n")
                    textBuilder.append(text)
                }
                val media = resolveMedia(msg)
                if (aborted) return
                if (media != null) {
                    val size = media.source.length()
                    if (media.isImage && size > WebPublishClient.IMAGE_MAX_BYTES) {
                        fail("Image is larger than 5 MB")
                        return
                    }
                    if (media.isVideo && size > WebPublishClient.VIDEO_MAX_BYTES) {
                        fail("Video is larger than 20 MB")
                        return
                    }
                    pending.add(media)
                }
            }
            val text = textBuilder.toString()
            if (text.isEmpty() && pending.isEmpty()) {
                fail("Nothing to publish")
                return
            }
            if (text.toByteArray(Charsets.UTF_8).size > WebPublishClient.TEXT_MAX_BYTES) {
                fail("Resulting text is larger than 5 MB")
                return
            }
            // Step 2: upload text + files sequentially in one request.
            val files = pending.map { WebPublishClient.UploadFile(it.source, it.fileName, it.mimeType) }
            when (val r = WebPublishClient.uploadReport("$base/upload", token, text, files)) {
                is WebPublishClient.Result.Ok -> {
                    val clipboardText = WebPublishConfig.applyClipboardTemplate(r.uploaded.url)
                    notifySuccess(fragment(), clipboardText)
                    val cb = onSuccess
                    if (cb != null) {
                        AndroidUtilities.runOnUIThread {
                            try {
                                cb.run()
                            } catch (e: Exception) {
                                FileLog.e(e)
                            }
                        }
                    }
                }
                is WebPublishClient.Result.Fail -> fail(r.message)
            }
        }

        private fun resolveMedia(msg: MessageObject): PendingMedia? {
            return try {
                resolveMediaInternal(msg)
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
        }

        private fun resolveMediaInternal(msg: MessageObject): PendingMedia? {
            val loader = FileLoader.getInstance(currentAccount)
            if (msg.isPhoto) {
                val media = msg.messageOwner?.media ?: return null
                val photo = media.photo ?: return null
                val thumbs = msg.photoThumbs
                if (thumbs.isNullOrEmpty()) return null
                val biggest = FileLoader.getClosestPhotoSizeWithSize(thumbs, 2560)
                    ?: thumbs.maxByOrNull { it.size } ?: return null
                var file = existingFile(msg.messageOwner?.attachPath)
                    ?: loader.getPathToMessage(msg.messageOwner)?.takeIf { it.exists() }
                if (file == null) {
                    val location = ImageLocation.getForPhoto(biggest, photo) ?: return null
                    AndroidUtilities.runOnUIThread {
                        try {
                            loader.loadFile(location, msg, "jpg", FileLoader.PRIORITY_HIGH, 1)
                        } catch (e: Exception) {
                            FileLog.e(e)
                        }
                    }
                    file = waitForFile {
                        existingFile(msg.messageOwner?.attachPath)
                            ?: loader.getPathToMessage(msg.messageOwner)?.takeIf { it.exists() }
                    }
                    if (file == null) {
                        fail("Could not download photo")
                        return null
                    }
                }
                val staged = stageToTemp(file, "photo", ".jpg") ?: return null
                return PendingMedia("photo.jpg", "image/jpeg", staged, isImage = true, isVideo = false)
            }
            val document = try {
                msg.getDocument()
            } catch (e: Exception) {
                null
            }
            if (document != null) {
                val isVideo = try {
                    msg.isVideo || MessageObject.isVideoDocument(document)
                } catch (e: Exception) {
                    false
                }
                val docAny: TLRPC.Document = document
                var file = existingFile(msg.messageOwner?.attachPath)
                    ?: loader.getPathToAttach(docAny, null, false, true)?.takeIf { it.exists() }
                    ?: loader.getPathToMessage(msg.messageOwner)?.takeIf { it.exists() }
                if (file == null) {
                    AndroidUtilities.runOnUIThread {
                        try {
                            loader.loadFile(docAny, msg, FileLoader.PRIORITY_HIGH, 0)
                        } catch (e: Exception) {
                            FileLog.e(e)
                        }
                    }
                    file = waitForFile {
                        loader.getPathToAttach(docAny, null, false, true)?.takeIf { it.exists() }
                            ?: loader.getPathToMessage(msg.messageOwner)?.takeIf { it.exists() }
                    }
                    if (file == null) {
                        fail("Could not download file")
                        return null
                    }
                }
                val originalName = try {
                    FileLoader.getDocumentFileName(docAny).ifEmpty { "file" }
                } catch (e: Exception) {
                    "file"
                }
                val mime = try {
                    val m = msg.getMimeType()
                    if (m.isNullOrEmpty()) guessMime(originalName) else m
                } catch (e: Exception) {
                    guessMime(originalName)
                }
                val staged = stageToTemp(file, "file", extensionOf(originalName)) ?: return null
                return PendingMedia(sanitizeFileName(originalName), mime, staged, isImage = false, isVideo = isVideo)
            }
            return null
        }

        private fun existingFile(path: String?): File? {
            if (path.isNullOrEmpty()) return null
            val f = File(path)
            return if (f.exists() && f.isFile) f else null
        }

        private fun waitForFile(find: () -> File?): File? {
            // Fast path.
            find()?.let { return it }
            val failed = AtomicReference<String?>(null)
            val observer = object : NotificationCenter.NotificationCenterDelegate {
                override fun didReceivedNotification(id: Int, account: Int, vararg args: Any) {
                    if (account != currentAccount) return
                    if (id == NotificationCenter.fileLoaded) {
                        find()?.let { /* exists now; poll below will pick it up */ }
                    } else if (id == NotificationCenter.fileLoadFailed) {
                        failed.compareAndSet(null, "download")
                    }
                }
            }
            val nc = NotificationCenter.getInstance(currentAccount)
            nc.addObserver(observer, NotificationCenter.fileLoaded)
            nc.addObserver(observer, NotificationCenter.fileLoadFailed)
            try {
                val deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_SEC * 1000
                while (System.currentTimeMillis() < deadline) {
                    find()?.let { return it }
                    if (failed.get() != null) {
                        // Give it one last chance: the file may have arrived anyway.
                        find()?.let { return it }
                        return null
                    }
                    try {
                        Thread.sleep(DOWNLOAD_POLL_MS)
                    } catch (e: InterruptedException) {
                        return find()
                    }
                }
                return find()
            } finally {
                nc.removeObserver(observer, NotificationCenter.fileLoaded)
                nc.removeObserver(observer, NotificationCenter.fileLoadFailed)
            }
        }

        private fun stageToTemp(source: File, prefix: String, ext: String): File? {
            return try {
                if (!tempDir.exists()) tempDir.mkdirs()
                val dest = File(tempDir, "${prefix}_${tempCounter++}${if (ext.startsWith(".")) ext else ".$ext"}")
                FileInputStream(source).use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                dest
            } catch (e: Exception) {
                FileLog.e(e)
                fail("Could not prepare file for upload")
                null
            }
        }

        private fun deleteTemp() {
            try {
                if (tempDir.exists()) tempDir.deleteRecursively()
            } catch (e: Exception) {
                // ignore
            }
        }

        private fun guessMime(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".mov") -> "video/quicktime"
                lower.endsWith(".webm") -> "video/webm"
                lower.endsWith(".mkv") -> "video/x-matroska"
                lower.endsWith(".mp3") -> "audio/mpeg"
                lower.endsWith(".ogg") || lower.endsWith(".oga") -> "audio/ogg"
                lower.endsWith(".pdf") -> "application/pdf"
                lower.endsWith(".zip") -> "application/zip"
                else -> "application/octet-stream"
            }
        }

        private fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return ".bin"
            val ext = name.substring(dot + 1)
            if (ext.length > 10 || !ext.all { it.isLetterOrDigit() }) return ".bin"
            return ".$ext"
        }

        private fun sanitizeFileName(name: String): String {
            val clean = name.replace(Regex("[\\r\\n\"]"), "_").trim()
            return if (clean.isEmpty()) "file" else clean.substring(0, min(clean.length, 128))
        }
    }
}
