package org.telegram.messenger.forkgram

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

object FolderIconSelector {

    @JvmStatic
    fun show(fragment: BaseFragment?, currentEmoticon: String?, onSelect: Utilities.Callback<String?>) {
        if (fragment == null || fragment.parentActivity == null) {
            return
        }
        val context: Context = fragment.parentActivity
        val resourcesProvider = fragment.resourceProvider

        val listView = RecyclerListView(context, resourcesProvider)
        listView.layoutManager = GridLayoutManager(context, 6)
        listView.setPadding(AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
        listView.clipToPadding = false
        listView.isVerticalScrollBarEnabled = false
        listView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56 * 6f))
        listView.setAdapter(object : RecyclerListView.SelectionAdapter() {
            override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean {
                return true
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val cell = FolderIconCell(context)
                cell.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56f))
                return RecyclerListView.Holder(cell)
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val cell = holder.itemView as FolderIconCell
                if (position == 0) {
                    cell.set(R.drawable.msg_folders, TextUtils.isEmpty(currentEmoticon))
                } else {
                    val emoticon = FolderIcons.EMOTICONS[position - 1]
                    cell.set(FolderIcons.getIconResByEmoticon(emoticon), TextUtils.equals(currentEmoticon, emoticon))
                }
            }

            override fun getItemCount(): Int {
                return 1 + FolderIcons.EMOTICONS.size
            }
        })

        val builder = BottomSheet.Builder(context, false, resourcesProvider)
        builder.setTitle(LocaleController.getString(R.string.FolderIcon), true)
        builder.setCustomView(listView)
        val sheet = builder.create()
        listView.setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
            onSelect.run(if (position == 0) null else FolderIcons.EMOTICONS[position - 1])
            sheet.dismiss()
        })
        fragment.showDialog(sheet)
    }

    private class FolderIconCell(context: Context) : FrameLayout(context) {

        private val imageView = ImageView(context)
        private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var selected = false

        init {
            setWillNotDraw(false)
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            addView(imageView, LayoutHelper.createFrame(28, 28, Gravity.CENTER))
        }

        fun set(iconRes: Int, selected: Boolean) {
            this.selected = selected
            val drawable: Drawable? = if (iconRes != 0) resources.getDrawable(iconRes).mutate() else null
            if (drawable != null) {
                drawable.colorFilter = PorterDuffColorFilter(Theme.getColor(if (selected) Theme.key_featuredStickers_buttonText else Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN)
            }
            imageView.setImageDrawable(drawable)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (selected) {
                selectPaint.color = Theme.getColor(Theme.key_featuredStickers_addButton)
                canvas.drawCircle(width / 2f, height / 2f, AndroidUtilities.dp(20f).toFloat(), selectPaint)
            }
        }
    }
}
