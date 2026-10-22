package org.telegram.messenger.forkgram

import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

object VoiceTimecodes {

    private const val CONNECT_TIMEOUT_MS = 60000
    private const val READ_TIMEOUT_MS = 120000

    private val executor = Executors.newCachedThreadPool()

    @JvmStatic
    fun onMessageSent(account: Int, message: TLRPC.Message?) {
        if (message == null) return
        if (!message.out && !message.post) return
        if (!MessageObject.isVoiceMessage(message)) return
        if (!VoiceTimecodesConfig.isConfigured()) return

        val duration = getDuration(message)
        if (duration < VoiceTimecodesConfig.minDuration()) {
            FileLog.d("VoiceTimecodes: message ${message.id} duration (${duration}s) < min (${VoiceTimecodesConfig.minDuration()}s), skipping")
            return
        }

        val audioFile = resolveAudioFile(account, message)
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
            FileLog.w("VoiceTimecodes: cannot resolve audio file for message ${message.id}")
            return
        }

        val context = ApplicationLoader.applicationContext ?: return
        val tempFile = try {
            val f = File.createTempFile("vtc_", ".ogg", context.cacheDir)
            audioFile.copyTo(f, overwrite = true)
            f
        } catch (e: Exception) {
            FileLog.e("VoiceTimecodes: failed to create temp copy of audio: $e")
            return
        }

        val dialogId = message.dialog_id
        val messageId = message.id
        executor.submit {
            try {
                processVoice(account, message, tempFile)
            } catch (e: Exception) {
                FileLog.e("VoiceTimecodes: processing error for msg $messageId: $e")
            } finally {
                try {
                    tempFile.delete()
                } catch (ignore: Exception) {
                }
            }
        }
    }

    @JvmStatic
    fun processManual(account: Int, message: TLRPC.Message?) {
        if (message == null) return
        val sttUrl = VoiceTimecodesConfig.sttUrl()
        val llmUrl = VoiceTimecodesConfig.llmUrl()
        if (sttUrl.isEmpty() || llmUrl.isEmpty()) {
            showErrorBulletin(LocaleController.getString(R.string.VoiceTimecodesError))
            return
        }

        showProcessingBulletin()

        val dialogId = message.dialog_id
        val messageId = message.id
        executor.submit {
            var tempFile: File? = null
            try {
                var audioFile = resolveAudioFile(account, message)
                if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
                    val doc = message.media?.document
                    if (doc != null) {
                        AndroidUtilities.runOnUIThread {
                            FileLoader.getInstance(account).loadFile(doc, message, FileLoader.PRIORITY_HIGH, 0)
                        }
                        val deadline = System.currentTimeMillis() + 15000L
                        while (System.currentTimeMillis() < deadline) {
                            Thread.sleep(300)
                            audioFile = resolveAudioFile(account, message)
                            if (audioFile != null && audioFile.exists() && audioFile.length() > 0L) {
                                break
                            }
                        }
                    }
                }

                if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
                    FileLog.w("VoiceTimecodes: audio file could not be resolved or downloaded for msg $messageId")
                    AndroidUtilities.runOnUIThread {
                        showErrorBulletin(LocaleController.getString(R.string.VoiceTimecodesError))
                    }
                    return@submit
                }

                val context = ApplicationLoader.applicationContext ?: return@submit
                val f = File.createTempFile("vtc_", ".ogg", context.cacheDir)
                audioFile.copyTo(f, overwrite = true)
                tempFile = f

                val ok = processVoice(account, message, f)
                if (!ok) {
                    AndroidUtilities.runOnUIThread {
                        showErrorBulletin(LocaleController.getString(R.string.VoiceTimecodesError))
                    }
                }
            } catch (e: Exception) {
                FileLog.e("VoiceTimecodes: manual processing error for msg $messageId: $e")
                AndroidUtilities.runOnUIThread {
                    showErrorBulletin(LocaleController.getString(R.string.VoiceTimecodesError))
                }
            } finally {
                try {
                    tempFile?.delete()
                } catch (ignore: Exception) {
                }
            }
        }
    }

    private fun processVoice(account: Int, message: TLRPC.Message, audioFile: File): Boolean {
        val dialogId = message.dialog_id
        val messageId = message.id
        val sttUrl = VoiceTimecodesConfig.sttUrl()
        val sttToken = VoiceTimecodesConfig.sttToken()
        val llmUrl = VoiceTimecodesConfig.llmUrl()
        val llmToken = VoiceTimecodesConfig.llmToken()
        val llmModel = VoiceTimecodesConfig.llmModel()
        val promptTpl = VoiceTimecodesConfig.promptTemplate()

        FileLog.d("VoiceTimecodes: transcribing msg $messageId via $sttUrl...")
        val segments = transcribeAudio(audioFile, sttUrl, sttToken)
        if (segments.isEmpty()) {
            FileLog.w("VoiceTimecodes: no segments from STT for msg $messageId")
            return false
        }

        val transcript = buildTranscript(segments)
        if (transcript.isBlank()) {
            FileLog.w("VoiceTimecodes: empty transcript for msg $messageId")
            return false
        }

        FileLog.d("VoiceTimecodes: calling LLM for msg $messageId ($llmModel)...")
        val prompt = promptTpl.replace("{transcript}", transcript)
        val rawTimecodes = requestLlm(llmUrl, llmToken, llmModel, prompt)
        if (rawTimecodes.isNullOrBlank()) {
            FileLog.w("VoiceTimecodes: LLM returned empty timecodes for msg $messageId")
            return false
        }

        val latestMessage = try {
            MessagesStorage.getInstance(account).getMessage(dialogId, messageId.toLong())
        } catch (e: Exception) {
            null
        }
        val existingMsg = latestMessage ?: message
        val existingText = existingMsg.message ?: ""
        val userText = extractUserText(existingText)

        val limit = VoiceTimecodesConfig.maxCaptionLimit(account)
        val finalCaption = combineTextAndTimecodes(userText, rawTimecodes, limit)
        if (finalCaption.isBlank()) {
            FileLog.w("VoiceTimecodes: final caption is blank for msg $messageId")
            return false
        }

        val preservedEntities = ArrayList<TLRPC.MessageEntity>()
        if (userText.isNotEmpty() && !existingMsg.entities.isNullOrEmpty()) {
            for (entity in existingMsg.entities) {
                if (entity.offset + entity.length <= userText.length) {
                    preservedEntities.add(entity)
                }
            }
        }

        FileLog.d("VoiceTimecodes: applying caption (${finalCaption.length} chars, userText=${userText.length}) to msg $messageId")
        applyCaption(account, dialogId, messageId, finalCaption, preservedEntities)
        return true
    }

    private fun transcribeAudio(audioFile: File, sttUrl: String, token: String): List<Segment> {
        val boundary = "----ForkgramVtc" + UUID.randomUUID().toString().replace("-", "")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(sttUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            conn.outputStream.use { out ->
                out.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                out.write("verbose_json\r\n".toByteArray(StandardCharsets.UTF_8))

                out.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
                out.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.ogg\"\r\n".toByteArray(StandardCharsets.UTF_8))
                out.write("Content-Type: audio/ogg\r\n\r\n".toByteArray(StandardCharsets.UTF_8))

                FileInputStream(audioFile).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val code = conn.responseCode
            val body = readStream(conn, code)
            if (code !in 200..299) {
                FileLog.e("VoiceTimecodes STT error: HTTP $code, body: $body")
                return emptyList()
            }

            val root = JSONObject(body)
            val segmentsArray = root.optJSONArray("segments") ?: return emptyList()
            val list = mutableListOf<Segment>()
            for (i in 0 until segmentsArray.length()) {
                val item = segmentsArray.optJSONObject(i) ?: continue
                val start = item.optDouble("start", 0.0)
                val text = item.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    list.add(Segment(start, text))
                }
            }
            return list
        } catch (e: Exception) {
            FileLog.e("VoiceTimecodes STT exception: $e")
            return emptyList()
        } finally {
            try {
                conn?.disconnect()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun requestLlm(llmUrl: String, token: String, model: String, prompt: String): String? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(llmUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            val payload = JSONObject()
            payload.put("model", model)
            val messages = JSONArray()
            val userMsg = JSONObject()
            userMsg.put("role", "user")
            userMsg.put("content", prompt)
            messages.put(userMsg)
            payload.put("messages", messages)
            payload.put("temperature", 0.3)

            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }

            val code = conn.responseCode
            val body = readStream(conn, code)
            if (code !in 200..299) {
                FileLog.e("VoiceTimecodes LLM error: HTTP $code, body: $body")
                return null
            }

            val root = JSONObject(body)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val choice = choices.optJSONObject(0) ?: return null
            val message = choice.optJSONObject("message") ?: return null
            return message.optString("content", "").trim()
        } catch (e: Exception) {
            FileLog.e("VoiceTimecodes LLM exception: $e")
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (ignore: Exception) {
            }
        }
    }

    private fun applyCaption(
        account: Int,
        dialogId: Long,
        messageId: Int,
        caption: String,
        entities: ArrayList<TLRPC.MessageEntity>? = null
    ) {
        AndroidUtilities.runOnUIThread {
            val req = TLRPC.TL_messages_editMessage()
            req.peer = MessagesController.getInstance(account).getInputPeer(dialogId)
            req.id = messageId
            req.message = caption
            req.flags = req.flags or 2048
            req.no_webpage = true
            if (!entities.isNullOrEmpty()) {
                req.entities = entities
                req.flags = req.flags or 8
            }

            ConnectionsManager.getInstance(account).sendRequest(req) { response, error ->
                AndroidUtilities.runOnUIThread {
                    if (error == null && response is TLRPC.Updates) {
                        MessagesController.getInstance(account).processUpdates(response, false)
                        FileLog.d("VoiceTimecodes: caption applied to msg $messageId")
                        showSuccessBulletin()
                    } else if (error != null) {
                        FileLog.e("VoiceTimecodes: editMessage failed code=${error.code} text=${error.text}")
                        showErrorBulletin(LocaleController.getString(R.string.VoiceTimecodesError))
                    }
                }
            }
        }
    }

    private fun showSuccessBulletin() {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment()
            if (fragment != null && BulletinFactory.canShowBulletin(fragment)) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                    R.raw.chats_infotip,
                    LocaleController.getString(R.string.VoiceTimecodesDone)
                ).show()
            }
        }
    }

    private fun showProcessingBulletin() {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment()
            if (fragment != null && BulletinFactory.canShowBulletin(fragment)) {
                BulletinFactory.of(fragment).createSimpleBulletin(
                    R.raw.chats_infotip,
                    LocaleController.getString(R.string.VoiceTimecodesProcessing)
                ).show()
            }
        }
    }

    private fun showErrorBulletin(text: String) {
        AndroidUtilities.runOnUIThread {
            val fragment = LaunchActivity.getSafeLastFragment()
            if (fragment != null && BulletinFactory.canShowBulletin(fragment)) {
                BulletinFactory.of(fragment).createErrorBulletin(text).show()
            }
        }
    }

    private fun formatTimestamp(seconds: Double): String {
        val totalSec = seconds.toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun buildTranscript(segments: List<Segment>): String {
        val sb = StringBuilder()
        for (seg in segments) {
            sb.append("[${formatTimestamp(seg.start)}] ${seg.text}\n")
        }
        return sb.toString().trim()
    }

    private fun cleanTimecodes(raw: String, maxLimit: Int): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val firstLineEnd = text.indexOf('\n')
            if (firstLineEnd != -1) {
                text = text.substring(firstLineEnd + 1)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - 3)
            }
            text = text.trim()
        }
        if (text.length <= maxLimit) {
            return text
        }
        val lines = text.split("\n")
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length + line.length + 1 <= maxLimit) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(line)
            } else {
                break
            }
        }
        return sb.toString().trim()
    }

    private val TIMECODE_LINE_REGEX = Regex(
        """^\s*\[?\(?\d{1,2}:\d{2}(?::\d{2})?\)?\]?\s*[-—–:.]?(\s+.*)?$"""
    )

    private val ZERO_START_REGEX = Regex("""^\s*\[?\(?0{1,2}:00(?::00)?\)?\]?""")

    private val HEADER_FOOTER_REGEX = Regex(
        """^(тайм[- ]?коды|timecodes?|содержание|главы|chapters?):?.*$|^#(подкаст|таймкод|timecode)""",
        RegexOption.IGNORE_CASE
    )

    private fun isTimecodeLine(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && TIMECODE_LINE_REGEX.matches(t)
    }

    private fun isTimecodeHeaderOrFooter(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && HEADER_FOOTER_REGEX.matches(t)
    }

    fun extractUserText(existingText: String?): String {
        if (existingText.isNullOrBlank()) return ""
        val lines = existingText.split("\n")
        var i = lines.size - 1
        while (i >= 0 && lines[i].trim().isEmpty()) {
            i--
        }
        var tcLinesCount = 0
        var hasZeroStart = false
        var curr = i
        while (curr >= 0) {
            val line = lines[curr].trim()
            if (line.isEmpty()) {
                curr--
                continue
            }
            if (isTimecodeLine(line)) {
                tcLinesCount++
                if (ZERO_START_REGEX.containsMatchIn(line)) {
                    hasZeroStart = true
                }
                curr--
            } else if (isTimecodeHeaderOrFooter(line)) {
                curr--
            } else {
                break
            }
        }
        return if (tcLinesCount > 0 && (hasZeroStart || tcLinesCount >= 2)) {
            lines.subList(0, curr + 1).joinToString("\n").trim()
        } else {
            existingText.trim()
        }
    }

    fun combineTextAndTimecodes(userText: String, rawTimecodes: String, limit: Int): String {
        val cleanUser = userText.trim()
        if (cleanUser.isEmpty()) {
            return cleanTimecodes(rawTimecodes, limit)
        }
        val separator = "\n\n"
        val availableForTc = limit - cleanUser.length - separator.length
        if (availableForTc <= 0) {
            return cleanUser.take(limit)
        }
        val fittedTc = cleanTimecodes(rawTimecodes, availableForTc)
        return if (fittedTc.isEmpty()) {
            cleanUser
        } else {
            "$cleanUser$separator$fittedTc"
        }
    }

    private fun resolveAudioFile(account: Int, message: TLRPC.Message): File? {
        if (!message.attachPath.isNullOrEmpty()) {
            val f = File(message.attachPath)
            if (f.exists() && f.length() > 0) {
                return f
            }
        }
        val loaderFile = FileLoader.getInstance(account).getPathToMessage(message)
        if (loaderFile != null && loaderFile.exists() && loaderFile.length() > 0) {
            return loaderFile
        }
        val doc = message.media?.document
        if (doc != null) {
            val attachFile = FileLoader.getInstance(account).getPathToAttach(doc, true)
            if (attachFile != null && attachFile.exists() && attachFile.length() > 0) {
                return attachFile
            }
        }
        return null
    }

    private fun getDuration(message: TLRPC.Message): Double {
        val doc = message.media?.document ?: return 0.0
        for (attr in doc.attributes) {
            if (attr is TLRPC.TL_documentAttributeAudio) {
                return attr.duration
            }
        }
        return 0.0
    }

    private fun readStream(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return ""
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
    }

    data class Segment(val start: Double, val text: String)
}
