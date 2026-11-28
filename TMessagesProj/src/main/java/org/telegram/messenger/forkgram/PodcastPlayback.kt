package org.telegram.messenger.forkgram

import android.net.Uri
import android.widget.Toast
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import kotlin.math.abs

object PodcastPlayback : NotificationCenter.NotificationCenterDelegate {
    private const val MAX_DURATION = 604_800
    private var serial = -2_000_000_000
    private var active: Session? = null

    private class Session(
        val message: MessageObject,
        val key: String,
        val showUrl: String,
        val uri: Uri,
        val audio: TLRPC.TL_documentAttributeAudio,
        val resumePosition: Int,
        var duration: Int
    ) {
        var lastSaved = resumePosition
        var restored = false
    }

    internal fun play(account: Int, showTitle: String, episode: PodcastFeeds.Episode, showUrl: String): Boolean {
        val uri = PodcastFeeds.url(episode.url)?.let { Uri.parse(it.toString()) } ?: return false
        val controller = MediaController.getInstance()
        val previous = active
        if (previous != null && previous.key == episode.key && controller.playingMessageObject === previous.message) {
            return controller.playMessage(previous.message)
        }
        stopObserving()
        val saved = PodcastShelf.progress(episode.key)
        val fallbackDuration = episode.duration.takeIf { it > 0 } ?: saved?.duration ?: 0
        val audio = TLRPC.TL_documentAttributeAudio().apply {
            duration = fallbackDuration.toDouble()
            title = episode.title
            performer = showTitle
            flags = 3
        }
        val document = TLRPC.TL_document().apply {
            id = serial.toLong()
            mime_type = episode.type.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
            file_reference = ByteArray(0)
            attributes.add(audio)
        }
        val synthetic = TLRPC.TL_message().apply {
            id = serial--
            peer_id = TLRPC.TL_peerUser()
            out = true
            media_unread = false
            noforwards = true
            message = ""
            media = TLRPC.TL_messageMediaDocument().apply { this.document = document }
        }
        val item = MessageObject(account, synthetic, false, false)
        active = Session(item, episode.key, showUrl, uri, audio, saved?.takeUnless { it.completed }?.position ?: 0, fallbackDuration)
        val center = NotificationCenter.getInstance(account)
        center.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged)
        center.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
        center.addObserver(this, NotificationCenter.messagePlayingDidStart)
        center.addObserver(this, NotificationCenter.messagePlayingDidReset)
        if (controller.setPlaylist(arrayListOf(item), item, 0L, false, null)) return true
        stopObserving(false)
        return false
    }

    @JvmStatic
    fun streamUri(message: MessageObject?): Uri? = active?.takeIf { it.message === message }?.uri

    @JvmStatic
    fun isPodcast(message: MessageObject?): Boolean = message != null && active?.message === message

    @JvmStatic
    fun getShowUrl(message: MessageObject?): String? = active?.takeIf { it.message === message }?.showUrl

    @JvmStatic
    fun getEpisodeKey(message: MessageObject?): String? = active?.takeIf { it.message === message }?.key

    @JvmStatic
    fun onReady(message: MessageObject, durationMs: Long, canSeek: Boolean): Long {
        val s = active?.takeIf { it.message === message } ?: return 0
        if (durationMs !in 1L..MAX_DURATION * 1000L) return 0
        s.duration = ((durationMs + 999L) / 1000).toInt()
        s.audio.duration = s.duration.toDouble()
        if (s.restored || !canSeek) return 0
        s.restored = true
        return (s.resumePosition * 1000L).coerceIn(0L, (durationMs - 1000L).coerceAtLeast(0L))
    }

    @JvmStatic
    fun saveIfPodcast(message: MessageObject?) {
        active?.takeIf { it.message === message }?.let { capture(it, true) }
    }

    @JvmStatic
    fun finishIfPodcast(message: MessageObject): Boolean {
        val s = active?.takeIf { it.message === message } ?: return false
        val total = s.duration.takeIf { it > 0 } ?: message.audioProgressSec
        PodcastShelf.setProgress(s.key, total, total, true)
        stopObserving(false)
        return true
    }

    @JvmStatic
    fun failIfPodcast(message: MessageObject): Boolean {
        if (!isPodcast(message)) return false
        stopObserving()
        Toast.makeText(ApplicationLoader.applicationContext, R.string.PodcastAudioError, Toast.LENGTH_SHORT).show()
        return true
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        val s = active ?: return
        if (id == NotificationCenter.messagePlayingDidStart && args.firstOrNull() !== s.message) {
            stopObserving()
        } else if (account == s.message.currentAccount && args.firstOrNull() == s.message.id) {
            if (id == NotificationCenter.messagePlayingDidReset) stopObserving() else {
                capture(s, id == NotificationCenter.messagePlayingPlayStateChanged)
            }
        }
    }

    private fun capture(s: Session, force: Boolean) {
        val total = s.duration.takeIf { it > 0 } ?: return
        val seconds = s.message.audioProgressSec.coerceIn(0, total)
        if (seconds == 0 || seconds == s.lastSaved || !force && abs(seconds - s.lastSaved) < 5) return
        s.lastSaved = seconds
        PodcastShelf.setProgress(s.key, seconds, total, false)
    }

    private fun stopObserving(save: Boolean = true) {
        val previous = active ?: return
        if (save) capture(previous, true)
        val center = NotificationCenter.getInstance(previous.message.currentAccount)
        center.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged)
        center.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
        center.removeObserver(this, NotificationCenter.messagePlayingDidStart)
        center.removeObserver(this, NotificationCenter.messagePlayingDidReset)
        active = null
    }
}
