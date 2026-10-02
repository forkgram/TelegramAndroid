package org.telegram.messenger.forkgram

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.Utilities
import java.io.File
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer

object ForkVideoSubtitles {

    private val cache = ConcurrentHashMap<Int, List<SubtitleSegment>>()
    private val operations = ConcurrentHashMap<Int, TranscriptionCancellable>()

    @JvmStatic
    fun segmentsOf(messageObject: MessageObject?): List<SubtitleSegment>? {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null
        }
        val key = key(messageObject)
        cache[key]?.let { return it.ifEmpty { null } }
        val file = subtitleFile(messageObject) ?: return null
        val segments = if (file.exists()) readSrt(file) else emptyList()
        cache[key] = segments
        return segments.ifEmpty { null }
    }

    @JvmStatic
    fun isGenerating(messageObject: MessageObject?): Boolean {
        return messageObject != null && operations.containsKey(key(messageObject))
    }

    @JvmStatic
    fun cancel(messageObject: MessageObject?) {
        if (messageObject == null) {
            return
        }
        operations.remove(key(messageObject))?.cancel()
    }

    @JvmStatic
    fun generate(messageObject: MessageObject, onUpdate: Runnable, onFinished: Runnable) {
        if (messageObject.messageOwner == null || !messageObject.isVideo() || isGenerating(messageObject)) {
            return
        }
        val path = resolveMediaFile(messageObject)
        if (path == null) {
            onFinished.run()
            return
        }
        val operationKey = key(messageObject)
        val segments = ArrayList<SubtitleSegment>()
        val documentDurationMs = messageObject.getDuration().toLong() * 1000L
        val cancellable = ForkOfflineTranscribe.requestTranscription(
            path.absolutePath, "",
            Consumer<String> { },
            Consumer<SubtitleSegment> { segment ->
                synchronized(segments) {
                    segments.add(segment)
                }
                AndroidUtilities.runOnUIThread(onUpdate)
            },
            BiConsumer<String?, Exception?> { text, exception ->
                operations.remove(operationKey)
                val result = synchronized(segments) {
                    ArrayList(segments)
                }
                AndroidUtilities.runOnUIThread {
                    if (text != null) {
                        if (result.isEmpty() && text.isNotEmpty()) {
                            result.add(SubtitleSegment(0L, documentDurationMs, text))
                        }
                        result.sortBy { it.startMs }
                        cache[operationKey] = result
                        writeAsync(messageObject, result)
                    }
                    onFinished.run()
                }
            }
        )
        if (cancellable == null) {
            onFinished.run()
            return
        }
        operations[operationKey] = cancellable
    }

    private fun subtitleFile(messageObject: MessageObject): File? {
        val document = messageObject.getDocument() ?: return null
        val name = FileLoader.getAttachFileName(document) ?: return null
        val dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_FILES) ?: return null
        return File(dir, "$name.srt")
    }

    private fun writeAsync(messageObject: MessageObject, segments: List<SubtitleSegment>) {
        val file = subtitleFile(messageObject) ?: return
        Utilities.globalQueue.postRunnable {
            try {
                file.parentFile?.mkdirs()
                val temp = File(file.absolutePath + ".tmp")
                temp.writeText(formatSrt(segments))
                if (file.exists()) {
                    file.delete()
                }
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
            } catch (ignore: Exception) {
            }
        }
    }

    private fun resolveMediaFile(messageObject: MessageObject): File? {
        val attachPath = messageObject.messageOwner.attachPath
        if (!attachPath.isNullOrEmpty()) {
            val file = File(attachPath)
            if (file.exists()) {
                return file
            }
        }
        val account = messageObject.currentAccount
        val messageFile = FileLoader.getInstance(account).getPathToMessage(messageObject.messageOwner)
        if (messageFile != null && messageFile.exists()) {
            return messageFile
        }
        val document = messageObject.getDocument()
        if (document != null) {
            val documentFile = FileLoader.getInstance(account).getPathToAttach(document, true)
            if (documentFile != null && documentFile.exists()) {
                return documentFile
            }
        }
        return null
    }

    private fun key(messageObject: MessageObject): Int =
        Objects.hash(messageObject.currentAccount, messageObject.getDialogId(), messageObject.getId())

    private fun formatSrt(segments: List<SubtitleSegment>): String {
        val builder = StringBuilder()
        for (i in segments.indices) {
            val segment = segments[i]
            builder.append(i + 1).append('\n')
            builder.append(formatTime(segment.startMs)).append(" --> ").append(formatTime(segment.endMs)).append('\n')
            builder.append(segment.text).append("\n\n")
        }
        return builder.toString()
    }

    private fun formatTime(ms: Long): String {
        val value = if (ms < 0) 0L else ms
        val hours = value / 3600000
        val minutes = value / 60000 % 60
        val seconds = value / 1000 % 60
        val millis = value % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun readSrt(file: File): List<SubtitleSegment> {
        val content = try {
            file.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        return parseSrt(content)
    }

    private fun parseSrt(content: String): List<SubtitleSegment> {
        val segments = ArrayList<SubtitleSegment>()
        val lines = content.split('\n')
        var i = 0
        while (i < lines.size) {
            while (i < lines.size && lines[i].isBlank()) {
                i++
            }
            if (i >= lines.size) {
                break
            }
            i++
            if (i >= lines.size) {
                break
            }
            val timeLine = lines[i]
            i++
            val arrow = timeLine.indexOf("-->")
            if (arrow < 0) {
                continue
            }
            val startMs = parseTime(timeLine.substring(0, arrow).trim())
            val endMs = parseTime(timeLine.substring(arrow + 3).trim())
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (text.isNotEmpty()) {
                    text.append('\n')
                }
                text.append(lines[i].trim())
                i++
            }
            if (startMs >= 0 && endMs >= 0 && text.isNotEmpty()) {
                segments.add(SubtitleSegment(startMs, endMs, text.toString()))
            }
        }
        return segments
    }

    private fun parseTime(value: String): Long {
        val clean = value.replace(',', '.')
        val parts = clean.split(':')
        if (parts.size != 3) {
            return -1L
        }
        return try {
            val hours = parts[0].trim().toLong()
            val minutes = parts[1].trim().toLong()
            val seconds = parts[2].trim().toDouble()
            hours * 3600000L + minutes * 60000L + (seconds * 1000.0).toLong()
        } catch (e: Exception) {
            -1L
        }
    }
}
