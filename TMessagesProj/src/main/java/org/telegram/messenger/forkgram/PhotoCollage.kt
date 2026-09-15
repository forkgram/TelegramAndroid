package org.telegram.messenger.forkgram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.MediaController
import org.telegram.messenger.SharedConfig
import org.telegram.ui.Components.ChatAttachAlertPhotoLayoutPreview

import java.io.File
import java.io.FileOutputStream

object PhotoCollage {

    private const val MAX_PHOTOS = 10
    private const val MAX_SIZE_HEIGHT = 814.0f
    private const val QUALITY = 90

    @JvmStatic
    fun collect(
        selectedPhotos: HashMap<Any?, Any?>?,
        selectedPhotosOrder: ArrayList<Any?>?
    ): ArrayList<MediaController.PhotoEntry>? {
        if (selectedPhotos == null || selectedPhotosOrder == null) {
            return null
        }
        if (selectedPhotosOrder.size < 2 || selectedPhotosOrder.size > MAX_PHOTOS) {
            return null
        }
        val photos = ArrayList<MediaController.PhotoEntry>(selectedPhotosOrder.size)
        for (key in selectedPhotosOrder) {
            val photoEntry = selectedPhotos[key]
            if (photoEntry !is MediaController.PhotoEntry) {
                return null
            }
            if (photoEntry.isVideo || photoEntry.ttl != 0 || sourcePath(photoEntry).isNullOrEmpty()) {
                return null
            }
            photos.add(photoEntry)
        }
        return photos
    }

    @JvmStatic
    fun create(photos: ArrayList<MediaController.PhotoEntry>?): MediaController.PhotoEntry? {
        if (photos == null || photos.size < 2) {
            return null
        }
        val group = ChatAttachAlertPhotoLayoutPreview.GroupCalculator(photos, 0)
        if (group.width <= 0 || group.height <= 0f) {
            return null
        }
        val highQuality = photos.any { it.isHighQuality() }
        val sizeWidth = group.width.toFloat()
        val sizeHeight = group.height * MAX_SIZE_HEIGHT
        val scale = AndroidUtilities.getPhotoSize(highQuality) / Math.max(sizeWidth, sizeHeight)
        val resultWidth = Math.max(1, Math.round(sizeWidth * scale))
        val resultHeight = Math.max(1, Math.round(sizeHeight * scale))

        var result: Bitmap? = null
        try {
            val collage = Bitmap.createBitmap(resultWidth, resultHeight, Bitmap.Config.ARGB_8888)
            result = collage
            val canvas = Canvas(collage)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val src = Rect()
            val dst = Rect()
            for (photoEntry in photos) {
                val position = group.positions[photoEntry] ?: continue
                dst.set(
                    Math.round(position.left / group.width * resultWidth),
                    Math.round(position.top / group.height * resultHeight),
                    Math.round((position.left + position.pw) / group.width * resultWidth),
                    Math.round((position.top + position.ph) / group.height * resultHeight)
                )
                if (dst.isEmpty) {
                    continue
                }
                val bitmap = ImageLoader.loadBitmap(
                    sourcePath(photoEntry), null, dst.width().toFloat(), dst.height().toFloat(), true
                ) ?: continue
                val cropScale = Math.max(
                    dst.width() / bitmap.width.toFloat(),
                    dst.height() / bitmap.height.toFloat()
                )
                val srcWidth = Math.min(bitmap.width, Math.max(1, Math.round(dst.width() / cropScale)))
                val srcHeight = Math.min(bitmap.height, Math.max(1, Math.round(dst.height() / cropScale)))
                val srcLeft = (bitmap.width - srcWidth) / 2
                val srcTop = (bitmap.height - srcHeight) / 2
                src.set(srcLeft, srcTop, srcLeft + srcWidth, srcTop + srcHeight)
                canvas.drawBitmap(bitmap, src, dst, paint)
                bitmap.recycle()
            }
            val file = File(
                FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
                "${SharedConfig.getLastLocalId()}_collage.jpg"
            )
            FileOutputStream(file).use { stream ->
                collage.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
            }
            val photoEntry = MediaController.PhotoEntry(
                0, SharedConfig.getLastLocalId(), 0L, file.absolutePath, 0, false,
                resultWidth, resultHeight, file.length()
            )
            photoEntry.canDeleteAfter = true
            photoEntry.highQuality = highQuality
            return photoEntry
        } catch (e: Throwable) {
            FileLog.e(e)
            return null
        } finally {
            result?.recycle()
        }
    }

    private fun sourcePath(photoEntry: MediaController.PhotoEntry): String? {
        return photoEntry.imagePath ?: photoEntry.path
    }
}
