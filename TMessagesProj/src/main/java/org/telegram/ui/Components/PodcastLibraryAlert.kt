package org.telegram.ui.Components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.util.LruCache
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.forkgram.PodcastArtwork
import org.telegram.messenger.forkgram.PodcastFeeds
import org.telegram.messenger.forkgram.PodcastPlayback
import org.telegram.messenger.forkgram.PodcastShelf
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future

object PodcastLibraryAlert {
    private const val IMPORT = 63141
    private const val EXPORT = 63142
    private val work = Executors.newFixedThreadPool(2)
    private val shows = LruCache<String, PodcastFeeds.Show>(8)
    private var lastPlayedShow: PodcastFeeds.Show? = null
    private var transferSession: WeakReference<Session>? = null

    @JvmStatic
    fun open(owner: BaseFragment) = showLibrary(owner, null)

    @JvmStatic
    fun openAt(owner: BaseFragment, showUrl: String, episodeKey: String) =
        showLibrary(owner, Target(showUrl, episodeKey))

    private fun showLibrary(owner: BaseFragment, target: Target?) {
        val activity = owner.parentActivity ?: return
        val session = Session(owner, activity, target)
        owner.showDialog(session.sheet) { session.close() }
        if (session.sheet.isShowing) session.openTargetIfNeeded()
    }

    private data class Target(val url: String, val episodeKey: String)

    @JvmStatic
    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != IMPORT && request != EXPORT) return false
        val session = transferSession?.get()
        transferSession = null
        val uri = data?.data ?: return true
        if (result != Activity.RESULT_OK) return true
        val context = ApplicationLoader.applicationContext
        val resolver = context.contentResolver
        session?.begin()
        work.execute {
            val ok = runCatching {
                if (request == EXPORT) {
                    val bytes = PodcastShelf.exportBackup()
                    requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    true
                } else {
                    requireNotNull(resolver.openInputStream(uri)).use {
                        PodcastShelf.importBackup(PodcastFeeds.bounded(it, PodcastShelf.MAX_BACKUP_BYTES))
                    }
                }
            }.getOrDefault(false)
            AndroidUtilities.runOnUIThread {
                val message = if (ok) R.string.Done else R.string.PodcastBackupError
                session?.finish(message)
                Toast.makeText(context, str(message), Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun str(id: Int) = LocaleController.getString(id)
    private fun dp(n: Int) = AndroidUtilities.dp(n.toFloat())

    private class Session(
        private val owner: BaseFragment,
        private val context: Context,
        target: Target?
    ) {
        private val provider = owner.resourceProvider
        private val accent = color(Theme.key_dialogTextBlue)
        private val secondary = color(Theme.key_dialogTextGray2)
        private val dividerColor = color(Theme.key_divider)
        private val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        private val title = label(21f, Theme.key_dialogTextBlack, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        private val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        private val topDivider = divider()
        private val footerDivider = divider()
        private val statusLine = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = Theme.createRoundRectDrawable(dp(10), tinted(0.08f))
        }
        private val spinner = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(accent) }
        private val status = label(14f, Theme.key_dialogTextBlack).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val empty = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(22))
        }
        private val emptyLabel = label(14f, Theme.key_dialogTextGray2).apply { gravity = Gravity.CENTER }
        private val manager = LinearLayoutManager(context)
        private val list = object : RecyclerView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val cap = (context.resources.displayMetrics.heightPixels * .50f).toInt().coerceAtMost(dp(530))
                val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) cap
                else cap.coerceAtMost(MeasureSpec.getSize(heightSpec))
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            }
        }.apply {
            layoutManager = manager
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val sheet: BottomSheet = BottomSheet.Builder(context, true, provider).setCustomView(content).create()
        private var returnTo = target
        private var jumpTo = target?.episodeKey
        private var feed: PodcastFeeds.Show? = target?.let { entry ->
            lastPlayedShow?.takeIf { it.url == entry.url && it.episodes.any { episode -> episode.key == entry.episodeKey } }
                ?: shows.get(entry.url)
        }
        private var selected: PodcastShelf.Subscription? = target?.let { entry ->
            feed?.let { PodcastShelf.Subscription(it.url, it.title) }
                ?: PodcastShelf.subscriptions().firstOrNull { it.url == entry.url }
                ?: PodcastShelf.Subscription(entry.url, PodcastFeeds.url(entry.url)?.host ?: str(R.string.RssPodcasts))
        }
        private var renderedUrl: String? = null
        private val expandedDescriptions = mutableSetOf<String>()
        private var busy = false
        private var statusId = 0
        private var closed = false
        private var pending: Future<*>? = null

        fun close() {
            closed = true
            pending?.cancel(true)
            pending = null
            list.adapter = null
        }

        fun openTargetIfNeeded() {
            val target = returnTo ?: return
            if (feed?.episodes?.any { it.key == target.episodeKey } != true) load(target.url, true)
        }

        init {
            statusLine.addView(spinner, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
            statusLine.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            empty.addView(badge(R.drawable.podcast_menu, 56, 26), LinearLayout.LayoutParams(dp(56), dp(56)))
            empty.addView(emptyLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            content.addView(statusLine, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            content.addView(topDivider, LinearLayout.LayoutParams(-1, 1))
            content.addView(list, LinearLayout.LayoutParams(-1, -2))
            content.addView(empty, LinearLayout.LayoutParams(-1, -2))
            content.addView(footerDivider, LinearLayout.LayoutParams(-1, 1))
            content.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            render()
        }

        fun begin() {
            if (closed) return
            busy = true
            statusId = R.string.Loading
            render()
        }

        fun finish(message: Int) {
            if (closed) return
            pending = null
            busy = false
            statusId = message
            render()
        }

        private fun render() {
            if (closed) return
            val show = selected
            header.removeAllViews()
            title.text = show?.title ?: str(R.string.RssPodcasts)
            if (show == null) {
                header.addView(badge(R.drawable.podcast_menu, 42, 22), LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
            } else {
                header.addView(headerAction(R.drawable.ic_ab_back, R.string.Back, true) {
                    selected = null
                    feed = null
                    returnTo = null
                    jumpTo = null
                    expandedDescriptions.clear()
                    finish(0)
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            if (show != null) {
                header.addView(headerAction(R.drawable.msg_retry, R.string.Refresh) { load(show.url, true) },
                    LinearLayout.LayoutParams(dp(48), dp(48)))
            }

            actions.removeAllViews()
            footer.removeAllViews()
            actions.visibility = if (show == null) View.VISIBLE else View.GONE
            footer.visibility = actions.visibility
            footerDivider.visibility = footer.visibility
            if (show == null) {
                actions.addView(command(R.string.PodcastAddFeed, R.drawable.msg_add, true) { askUrl() },
                    LinearLayout.LayoutParams(-1, -2))
                footer.addView(command(R.string.PodcastImport) { transfer(false) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                footer.addView(command(R.string.PodcastExport) { transfer(true) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            }

            val rows = if (show == null) {
                PodcastShelf.subscriptions().map { sub ->
                    val cached = shows.get(sub.url)
                    Row(sub.title, PodcastFeeds.url(sub.url)?.host.orEmpty(), true, null, false, {
                        selected = sub
                        feed = cached
                        if (cached != null) finish(0) else load(sub.url, true)
                    }, { remove(sub) }, artworkUrl = cached?.artworkUrl)
                }
            } else {
                val openedFeed = feed
                openedFeed?.episodes.orEmpty().map { episode ->
                    val saved = PodcastShelf.progress(episode.key)
                    val subtitle = when {
                        saved?.completed == true -> str(R.string.PodcastPlayed)
                        saved != null -> time(saved.position) + " / " + time(saved.duration)
                        episode.duration > 0 -> time(episode.duration)
                        else -> ""
                    }
                    val fraction = saved?.takeIf { !it.completed && it.duration > 0 }
                        ?.let { it.position.toFloat() / it.duration }?.coerceIn(0f, 1f)
                    Row(
                        episode.title, subtitle, false, fraction, saved?.completed == true,
                        { play(show, episode) },
                        artworkUrl = episode.artworkUrl,
                        fallbackArtworkUrl = openedFeed?.artworkUrl,
                        episodeKey = episode.key,
                        description = episode.description,
                        highlighted = episode.key == returnTo?.episodeKey
                    )
                }
            }
            statusLine.visibility = if (statusId == 0) View.GONE else View.VISIBLE
            spinner.visibility = if (busy) View.VISIBLE else View.GONE
            status.text = if (statusId == 0) "" else str(statusId)
            topDivider.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            list.visibility = topDivider.visibility
            empty.visibility = if (rows.isEmpty() && statusId == 0) View.VISIBLE else View.GONE
            emptyLabel.text = str(if (show == null) R.string.PodcastEmpty else R.string.PodcastNoEpisodes)
            val state = if (renderedUrl == show?.url) manager.onSaveInstanceState() else null
            renderedUrl = show?.url
            list.adapter = Rows(rows)
            val jump = jumpTo
            val targetPosition = if (jump == null) -1 else rows.indexOfFirst { it.episodeKey == jump }
            if (targetPosition >= 0) {
                jumpTo = null
                manager.scrollToPositionWithOffset(targetPosition, dp(8))
            } else if (state != null) {
                manager.onRestoreInstanceState(state)
            }
        }

        private fun askUrl() {
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack, provider))
                setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2, provider))
                setSingleLine(true)
                hint = str(R.string.PodcastFeedHint)
            }
            val frame = LinearLayout(context).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(input, LinearLayout.LayoutParams(-1, dp(52)))
            }
            AlertDialog.Builder(context, provider).setTitle(str(R.string.PodcastAddFeed))
                .setView(frame).setNegativeButton(str(R.string.Cancel), null)
                .setPositiveButton(str(R.string.Add)) { _, _ -> load(input.text.toString().trim(), false) }.show()
        }

        private fun remove(show: PodcastShelf.Subscription) {
            AlertDialog.Builder(context, provider).setTitle(str(R.string.Delete))
                .setMessage(show.title).setNegativeButton(str(R.string.Cancel), null)
                .setPositiveButton(str(R.string.Delete)) { _, _ ->
                    PodcastShelf.remove(show.url)
                    shows.remove(show.url)
                    finish(0)
                }.show()
        }

        private fun load(raw: String, openShow: Boolean) {
            val uri = PodcastFeeds.url(raw)
            if (uri == null) {
                finish(R.string.PodcastInvalidUrl)
                return
            }
            begin()
            pending = work.submit {
                val loaded = runCatching { PodcastFeeds.fetch(uri.toString()) }.getOrNull()
                AndroidUtilities.runOnUIThread {
                    if (!closed) {
                        val subscribed = PodcastShelf.subscriptions().any { it.url == uri.toString() }
                        if (loaded == null || (!openShow || subscribed) && !PodcastShelf.add(loaded.url, loaded.title)) {
                            finish(R.string.PodcastFeedError)
                        } else {
                            shows.put(loaded.url, loaded)
                            if (openShow) {
                                selected = PodcastShelf.Subscription(loaded.url, loaded.title)
                                feed = loaded
                            }
                            finish(0)
                        }
                    }
                }
            }
        }

        private fun play(show: PodcastShelf.Subscription, episode: PodcastFeeds.Episode) {
            val activity = owner.parentActivity ?: return
            if (!PodcastPlayback.play(owner.currentAccount, show.title, episode, show.url)) {
                finish(R.string.PodcastAudioError)
                return
            }
            lastPlayedShow = feed
            sheet.dismiss()
            AudioPlayerAlert(activity, provider).show()
        }

        private fun transfer(export: Boolean) {
            val intent = Intent(if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (export) "application/json" else "*/*"
                if (export) putExtra(Intent.EXTRA_TITLE, "forkgram-podcasts.json")
            }
            transferSession = WeakReference(this)
            runCatching { owner.startActivityForResult(intent, if (export) EXPORT else IMPORT) }
                .onFailure {
                    transferSession = null
                    finish(R.string.PodcastBackupError)
                }
        }

        private fun color(key: Int) = Theme.getColor(key, provider)
        private fun tinted(alpha: Float) = ColorUtils.setAlphaComponent(accent, (alpha * 255).toInt())
        private fun divider() = View(context).apply {
            setBackgroundColor(dividerColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private fun icon(res: Int, tint: Int, padding: Int = 0) = ImageView(context).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private fun badge(res: Int, size: Int, imageSize: Int) = icon(res, accent, (size - imageSize) / 2).apply {
            background = Theme.createRoundRectDrawable(dp(size / 2), tinted(.12f))
        }

        private fun headerAction(res: Int, key: Int, mirror: Boolean = false, press: () -> Unit) =
            icon(res, accent, 12).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = str(key)
                background = Theme.createSimpleSelectorRoundRectDrawable(dp(24), android.graphics.Color.TRANSPARENT, tinted(.18f))
                isEnabled = !busy
                isFocusable = true
                alpha = if (busy) .45f else 1f
                if (mirror && LocaleController.isRTL) scaleX = -1f
                setOnClickListener { if (!busy && !closed) press() }
            }

        private fun command(key: Int, res: Int = 0, primary: Boolean = false, press: () -> Unit) =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isEnabled = !busy
                isFocusable = true
                alpha = if (busy) .45f else 1f
                background = Theme.createSimpleSelectorRoundRectDrawable(dp(12), tinted(if (primary) .16f else .07f), tinted(.24f))
                if (res != 0) addView(icon(res, accent), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                addView(label(14f, Theme.key_dialogTextBlue, true).apply {
                    text = str(key)
                    maxLines = 2
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-2, -2))
                setOnClickListener { if (!busy && !closed) press() }
            }

        private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            textSize = size
            setTextColor(this@Session.color(color))
            gravity = Gravity.CENTER_VERTICAL
            if (bold) setTypeface(null, Typeface.BOLD)
        }

        private fun time(s: Int): String {
            val seconds = s.coerceAtLeast(0)
            return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, (seconds / 60) % 60, seconds % 60)
            else "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        }

        private inner class Rows(private val rows: List<Row>) : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = rows.size

            override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
                val top = label(16f, Theme.key_dialogTextBlack, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                val sub = label(13f, Theme.key_dialogTextGray2).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val details = DescriptionLine(context, provider)
                val rowIcon = badge(R.drawable.podcast_menu, 44, 21)
                val artwork = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Theme.createRoundRectDrawable(dp(10), tinted(.12f))
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    visibility = View.GONE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val overlay = icon(R.drawable.ic_action_play, android.graphics.Color.WHITE, 4).apply {
                    background = Theme.createRoundRectDrawable(dp(10), android.graphics.Color.BLACK)
                    background.alpha = 190
                    visibility = View.GONE
                }
                val artTile = FrameLayout(context).apply {
                    addView(rowIcon, FrameLayout.LayoutParams(-1, -1))
                    addView(artwork, FrameLayout.LayoutParams(-1, -1))
                    addView(overlay, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.BOTTOM))
                }
                val chevron = icon(R.drawable.msg_arrowright, secondary, 2).apply {
                    if (LocaleController.isRTL) scaleX = -1f
                }
                val progress = PositionLine(context, tinted(.18f), accent)
                val labels = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(top, LinearLayout.LayoutParams(-1, -2))
                    addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                    addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                    addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(7) })
                }
                val layout = LinearLayout(context).apply {
                    minimumHeight = dp(66)
                    setPadding(dp(8), dp(9), dp(8), dp(9))
                    gravity = Gravity.CENTER_VERTICAL
                    background = Theme.createSimpleSelectorRoundRectDrawable(dp(12), android.graphics.Color.TRANSPARENT, tinted(.13f))
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                    addView(artTile, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
                    addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
                    isFocusable = true
                }
                return Holder(layout, top, sub, details, rowIcon, artwork, overlay, chevron, progress)
            }

            override fun onBindViewHolder(holder: Holder, position: Int) {
                val row = rows[position]
                clearArtwork(holder)
                holder.row = row
                holder.title.text = row.title
                holder.subtitle.text = row.subtitle
                holder.subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
                val episodeKey = row.episodeKey
                holder.details.bind(row.description, episodeKey != null && episodeKey in expandedDescriptions) { expanded ->
                    if (episodeKey != null) {
                        if (expanded) expandedDescriptions.add(episodeKey) else expandedDescriptions.remove(episodeKey)
                    }
                }
                holder.rowIcon.setImageResource(when {
                    row.show -> R.drawable.podcast_menu
                    row.complete -> R.drawable.ic_ab_done
                    else -> R.drawable.ic_action_play
                })
                holder.chevron.visibility = if (row.show) View.VISIBLE else View.GONE
                holder.progress.visibility = if (row.progress == null) View.GONE else View.VISIBLE
                holder.progress.value = row.progress ?: 0f
                holder.itemView.isEnabled = !busy
                holder.itemView.isSelected = row.highlighted
                holder.itemView.background = Theme.createSimpleSelectorRoundRectDrawable(
                    dp(12), if (row.highlighted) tinted(.15f) else android.graphics.Color.TRANSPARENT, tinted(.24f)
                )
                holder.itemView.setOnClickListener { if (!busy && !closed) row.click() }
                holder.itemView.setOnLongClickListener(row.longClick?.let { action ->
                    View.OnLongClickListener { if (busy || closed) false else { action(); true } }
                })
                holder.itemView.isLongClickable = row.longClick != null
                if (holder.itemView.isAttachedToWindow) loadArtwork(holder)
            }

            override fun onViewAttachedToWindow(holder: Holder) {
                super.onViewAttachedToWindow(holder)
                loadArtwork(holder)
            }

            override fun onViewDetachedFromWindow(holder: Holder) {
                clearArtwork(holder)
                super.onViewDetachedFromWindow(holder)
            }

            override fun onViewRecycled(holder: Holder) {
                clearArtwork(holder)
                holder.row = null
                super.onViewRecycled(holder)
            }

            private fun loadArtwork(holder: Holder) {
                val row = holder.row ?: return
                if (closed || holder.cancelArtwork != null || row.artworkUrl == null && row.fallbackArtworkUrl == null) return
                holder.cancelArtwork = PodcastArtwork.load(row.artworkUrl, row.fallbackArtworkUrl) { bitmap ->
                    if (bitmap != null && !closed && holder.row === row && holder.itemView.isAttachedToWindow) {
                        holder.artwork.setImageBitmap(bitmap)
                        holder.artwork.visibility = View.VISIBLE
                        holder.overlay.setImageResource(if (row.complete) R.drawable.ic_ab_done else R.drawable.ic_action_play)
                        holder.overlay.visibility = if (row.show) View.GONE else View.VISIBLE
                    }
                }
            }

            private fun clearArtwork(holder: Holder) {
                holder.cancelArtwork?.invoke()
                holder.cancelArtwork = null
                holder.artwork.setImageDrawable(null)
                holder.artwork.visibility = View.GONE
                holder.overlay.visibility = View.GONE
            }
        }
    }

    private class DescriptionLine(context: Context, provider: Theme.ResourcesProvider?) : LinearLayout(context) {
        private val summary = TextView(context).apply {
            textSize = 13f
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2, provider))
            gravity = Gravity.START
            ellipsize = TextUtils.TruncateAt.END
        }
        private val toggle = TextView(context).apply {
            textSize = 13f
            setTextColor(Theme.getColor(Theme.key_dialogTextBlue, provider))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = dp(40)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = true
        }
        private var description: String? = null
        private var preview = ""
        private var expanded = false
        private var toggleChange: ((Boolean) -> Unit)? = null

        init {
            addView(summary)
            addView(toggle)
            toggle.setOnClickListener {
                expanded = !expanded
                toggleChange?.invoke(expanded)
                updateLayout()
            }
        }

        fun bind(value: String?, expanded: Boolean, onToggle: (Boolean) -> Unit) {
            description = value
            preview = value?.replace('\n', ' ').orEmpty()
            visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
            this.expanded = expanded
            toggleChange = onToggle
            updateLayout()
        }

        private fun updateLayout() {
            orientation = if (expanded) VERTICAL else HORIZONTAL
            gravity = if (expanded) Gravity.START else Gravity.CENTER_VERTICAL
            summary.text = if (expanded) description else preview
            summary.maxLines = if (expanded) Int.MAX_VALUE else 1
            summary.layoutParams = if (expanded) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)
            toggle.text = str(if (expanded) R.string.PodcastShowLess else R.string.PodcastShowMore)
            toggle.setPaddingRelative(if (expanded) 0 else dp(8), 0, dp(2), 0)
            toggle.layoutParams = LayoutParams(-2, -2)
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
            toggle.visibility = if (expanded ||
                (available > 0 && Layout.getDesiredWidth(preview, summary.paint) > available)
            ) View.VISIBLE else View.GONE
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private class PositionLine(context: Context, private val track: Int, private val fill: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var value = 0f
            set(value) {
                field = value
                invalidate()
            }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            val done = w * value
            paint.color = track
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            if (done > 0f) {
                paint.color = fill
                if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.drawRoundRect(w - done, 0f, w, h, r, r, paint)
                else canvas.drawRoundRect(0f, 0f, done, h, r, r, paint)
            }
        }
    }

    private class Holder(
        container: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val details: DescriptionLine,
        val rowIcon: ImageView,
        val artwork: ImageView,
        val overlay: ImageView,
        val chevron: ImageView,
        val progress: PositionLine
    ) : RecyclerView.ViewHolder(container) {
        var row: Row? = null
        var cancelArtwork: (() -> Unit)? = null
    }

    private data class Row(
        val title: String,
        val subtitle: String,
        val show: Boolean,
        val progress: Float?,
        val complete: Boolean,
        val click: () -> Unit,
        val longClick: (() -> Unit)? = null,
        val artworkUrl: String? = null,
        val fallbackArtworkUrl: String? = null,
        val episodeKey: String? = null,
        val description: String? = null,
        val highlighted: Boolean = false
    )
}
