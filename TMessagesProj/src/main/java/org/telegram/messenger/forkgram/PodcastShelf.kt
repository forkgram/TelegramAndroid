package org.telegram.messenger.forkgram

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import java.io.IOException

object PodcastShelf {
    const val KEY = "rssPodcasts"
    const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
    private const val MAX_SHOWS = 100
    private const val MAX_PROGRESS = 6000
    private const val MAX_DURATION = 7 * 24 * 60 * 60
    private val episodeKey = Regex("[0-9a-f]{64}")
    private val prefs by lazy { ApplicationLoader.applicationContext.getSharedPreferences("fork_podcast_shelf", Context.MODE_PRIVATE) }
    private val shows = LinkedHashMap<String, Subscription>()
    private val progress = LinkedHashMap<String, Progress>()
    private var loaded = false

    data class Subscription(val url: String, val title: String)
    data class Progress(val position: Int, val duration: Int, val completed: Boolean, val updatedAt: Long)

    @JvmStatic
    fun isEnabled(): Boolean = MessagesController.getGlobalMainSettings().getBoolean(KEY, false)

    @Synchronized
    fun subscriptions(): List<Subscription> {
        load()
        return shows.values.toList()
    }

    @Synchronized
    fun add(url: String, title: String): Boolean {
        load()
        val normalized = PodcastFeeds.url(url)?.toString() ?: return false
        if (shows.size >= MAX_SHOWS && normalized !in shows) return false
        shows[normalized] = Subscription(normalized, title.trim().take(300).ifBlank { normalized })
        save()
        return true
    }

    @Synchronized
    fun remove(url: String) {
        load()
        if (shows.remove(url) != null) save()
    }

    @Synchronized
    fun progress(key: String): Progress? {
        load()
        return progress[key]
    }

    @Synchronized
    fun setProgress(key: String, pos: Int, total: Int, completed: Boolean) {
        if (!episodeKey.matches(key) || total !in 1..MAX_DURATION || pos !in 0..total || pos == 0 && !completed) return
        load()
        val next = Progress(if (completed) total else pos, total, completed, System.currentTimeMillis())
        val previous = progress[key]
        if (previous != null && previous.position == next.position && previous.duration == next.duration && previous.completed == next.completed) return
        progress.remove(key)
        progress[key] = next
        trim()
        save()
    }

    @Synchronized
    fun exportBackup(): ByteArray {
        load()
        return encode().toString(2).toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_BACKUP_BYTES) throw IOException("Backup too large")
        }
    }

    @Synchronized
    fun importBackup(data: ByteArray): Boolean {
        if (data.isEmpty() || data.size > MAX_BACKUP_BYTES) return false
        val parsed = runCatching { decode(JSONObject(data.toString(Charsets.UTF_8))) }.getOrNull() ?: return false
        load()
        for (show in parsed.first.values) {
            if (shows.size >= MAX_SHOWS && show.url !in shows) continue
            shows.putIfAbsent(show.url, show)
        }
        for ((key, item) in parsed.second) {
            val previous = progress[key]
            if (previous == null || item.updatedAt > previous.updatedAt) progress[key] = item
        }
        trim()
        save()
        return true
    }

    private fun trim() {
        if (progress.size <= MAX_PROGRESS) return
        val oldest = progress.entries.sortedBy { it.value.updatedAt }.take(progress.size - MAX_PROGRESS).map { it.key }
        for (key in oldest) progress.remove(key)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val content = prefs.getString("library_v1", null) ?: return
        val saved = runCatching { decode(JSONObject(content)) }.getOrNull() ?: return
        shows.putAll(saved.first)
        progress.putAll(saved.second)
    }

    private fun save() {
        prefs.edit().putString("library_v1", encode().toString()).apply()
    }

    private fun encode(): JSONObject {
        val feeds = JSONArray()
        for (show in shows.values) feeds.put(JSONObject().put("url", show.url).put("title", show.title))
        val playback = JSONArray()
        for ((key, item) in progress) playback.put(JSONObject().put("key", key).put("position", item.position)
            .put("duration", item.duration).put("completed", item.completed).put("updatedAt", item.updatedAt))
        return JSONObject().put("format", "forkgram-podcasts").put("version", 1).put("feeds", feeds).put("progress", playback)
    }

    private fun decode(root: JSONObject): Pair<LinkedHashMap<String, Subscription>, LinkedHashMap<String, Progress>> {
        if (root.optString("format") != "forkgram-podcasts" || root.optInt("version", -1) != 1) throw IOException("Unsupported backup")
        val result = LinkedHashMap<String, Subscription>()
        val states = LinkedHashMap<String, Progress>()
        val feeds = root.getJSONArray("feeds")
        val playback = root.getJSONArray("progress")
        require(feeds.length() <= MAX_SHOWS && playback.length() <= MAX_PROGRESS)
        for (i in 0 until feeds.length()) {
            val f = feeds.getJSONObject(i)
            val normalized = PodcastFeeds.url(f.getString("url"))?.toString() ?: throw IOException("Invalid feed URL")
            result[normalized] = Subscription(normalized, f.optString("title").trim().take(300).ifBlank { normalized })
        }
        val now = System.currentTimeMillis()
        for (i in 0 until playback.length()) {
            val p = playback.getJSONObject(i)
            val key = p.getString("key")
            val total = p.getInt("duration")
            val pos = p.getInt("position")
            require(episodeKey.matches(key) && total in 1..MAX_DURATION && pos in 0..total)
            val complete = p.optBoolean("completed")
            states[key] = Progress(if (complete) total else pos, total, complete, p.getLong("updatedAt").coerceIn(0L, now))
        }
        return result to states
    }
}
