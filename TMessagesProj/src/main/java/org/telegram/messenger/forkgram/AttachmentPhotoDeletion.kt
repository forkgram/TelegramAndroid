package org.telegram.messenger.forkgram

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaController

/** Batch deletion only for local MediaStore photos, never paths or Telegram attachments. */
@RequiresApi(Build.VERSION_CODES.R)
object AttachmentPhotoDeletion {
    data class Selection(val imageId: Int, val path: String)

    class Target internal constructor(
        val imageId: Int,
        val path: String,
        internal val uri: Uri,
    ) {
        fun matches(entry: MediaController.PhotoEntry?) =
            entry != null && !entry.isVideo && entry.imageId == imageId && entry.path == path
    }

    /**
     * PhotoEntry IDs come from the synthetic external collection. Resolve the exact
     * item on its owning volume and check the path against the selected gallery row.
     * If one row cannot be identified, do not present consent for a different batch.
     */
    private fun resolve(resolver: ContentResolver, entry: Selection): Target? {
        val path = entry.path
        if (entry.imageId <= 0) return null
        val id = entry.imageId.toLong()
        val row = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return resolver.query(row, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.VOLUME_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.count != 1 || !cursor.moveToFirst() || cursor.getString(0) != path) return@use null
                val volume = cursor.getString(1) ?: return@use null
                if (volume == MediaStore.VOLUME_EXTERNAL || volume == MediaStore.VOLUME_INTERNAL) return@use null
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id)
                Target(entry.imageId, path, uri)
            }
    }

    class Request internal constructor(val intent: PendingIntent, val targets: List<Target>)

    @JvmStatic
    fun prepare(resolver: ContentResolver, entries: List<Selection>): Request? = try {
        val targets = entries.mapNotNull { resolve(resolver, it) }
        if (targets.size < 2 || targets.size != entries.size || targets.distinctBy { it.uri }.size != targets.size) {
            null
        } else {
            Request(MediaStore.createDeleteRequest(resolver, targets.map { it.uri }), targets)
        }
    } catch (e: Exception) {
        FileLog.e(e)
        null
    }

    @JvmStatic
    fun confirmedDeleted(resolver: ContentResolver, targets: List<Target>): List<Target> =
        targets.filter { target ->
            try {
                resolver.query(target.uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.count == 0 } == true
            } catch (e: Exception) {
                // An inaccessible or failed lookup is not proof of deletion.
                FileLog.e(e)
                false
            }
        }
}
