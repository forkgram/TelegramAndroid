package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Trace;

import org.telegram.messenger.AnimatedFileDrawableStream;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AnimatedFileNative {

    private final int[] mMetaData;
    private long mNativePtr;
    private final AtomicBoolean mRecycled = new AtomicBoolean(false);

    private AnimatedFileNative(long nativePtr, int[] metaData) {
        mNativePtr = nativePtr;
        mMetaData = metaData;
    }

    public static AnimatedFileNative createDecoderFrom(String src, int[] params, int account, long streamFileSize, AnimatedFileDrawableStream readCallback, boolean preview) {
        long ptr = createDecoder(src, params, account, streamFileSize, readCallback, preview);
        if (ptr == 0) {
            return null;
        }
        return new AnimatedFileNative(ptr, params);
    }

    public int getWidth() {
        return mMetaData[0];
    }

    public int getHeight() {
        return mMetaData[1];
    }

    public int getRotation() {
        return mMetaData[2];
    }

    public int getProgress(TimeUnit timeUnit) {
        return (int) timeUnit.convert(mMetaData[3], TimeUnit.MILLISECONDS);
    }

    public int getDuration(TimeUnit timeUnit) {
        return (int) timeUnit.convert(mMetaData[4], TimeUnit.MILLISECONDS);
    }

    public int getFps() {
        return mMetaData[5];
    }

    public boolean isLastFrameOpaque() {
        return mMetaData[6] == 1;
    }

    public boolean isStaticVideoDetected() {
        return mMetaData[7] == 1;
    }

    public void stopDecoder() {
        checkNotDestroyed();
        stopDecoder(mNativePtr);
    }

    public int getVideoFrame(Bitmap bitmap, boolean preview, float startTimeSeconds, float endTimeSeconds, boolean loop) {
        checkNotDestroyed();
        return getVideoFrame(mNativePtr, bitmap, mMetaData, preview, startTimeSeconds, endTimeSeconds, loop);
    }

    public void seekToMs(long ms, boolean precise) {
        checkNotDestroyed();
        seekToMs(mNativePtr, ms, mMetaData, precise);
    }

    public int getFrameAtTime(long ms, Bitmap bitmap) {
        checkNotDestroyed();
        return getFrameAtTime(mNativePtr, ms, bitmap, mMetaData);
    }

    public void prepareToSeek() {
        checkNotDestroyed();
        prepareToSeek(mNativePtr);
    }

    public boolean isDestroyed() {
        return mRecycled.get();
    }

    public void recycle() {
        if (mRecycled.compareAndSet(false, true)) {
            long ptr = mNativePtr;
            mNativePtr = 0;
            if (ptr != 0) {
                destroyDecoder(ptr);
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!mRecycled.get()) {
                recycle();
            }
        } finally {
            super.finalize();
        }
    }

    private void checkNotDestroyed() {
        if (mRecycled.get()) {
            if (BuildConfig.DEBUG_PRIVATE_VERSION) {
                throw new IllegalStateException("Called method on a destroyed AnimatedFileNative instance");
            }
        }
    }

    private static long createDecoder(String src, int[] params, int account, long streamFileSize, AnimatedFileDrawableStream readCallback, boolean preview) {
        Trace.beginSection("AnimatedFileNative#createDecoder");
        try {
            return nCreateDecoder(src, params, account, streamFileSize, readCallback, preview);
        } finally {
            Trace.endSection();
        }
    }

    private static void destroyDecoder(long ptr) {
        Trace.beginSection("AnimatedFileNative#destroyDecoder");
        try {
            nDestroyDecoder(ptr);
        } finally {
            Trace.endSection();
        }
    }

    private static void stopDecoder(long ptr) {
        Trace.beginSection("AnimatedFileNative#stopDecoder");
        try {
            nStopDecoder(ptr);
        } finally {
            Trace.endSection();
        }
    }

    private static int getVideoFrame(long ptr, Bitmap bitmap, int[] params, boolean preview, float startTimeSeconds, float endTimeSeconds, boolean loop) {
        Trace.beginSection("AnimatedFileNative#getVideoFrame");
        try {
            return nGetVideoFrame(ptr, bitmap, params, preview, startTimeSeconds, endTimeSeconds, loop);
        } finally {
            Trace.endSection();
        }
    }

    private static void seekToMs(long ptr, long ms, int[] params, boolean precise) {
        Trace.beginSection("AnimatedFileNative#seekToMs");
        try {
            nSeekToMs(ptr, ms, params, precise);
        } finally {
            Trace.endSection();
        }
    }

    private static int getFrameAtTime(long ptr, long ms, Bitmap bitmap, int[] data) {
        Trace.beginSection("AnimatedFileNative#getFrameAtTime");
        try {
            return nGetFrameAtTime(ptr, ms, bitmap, data);
        } finally {
            Trace.endSection();
        }
    }

    private static void prepareToSeek(long ptr) {
        Trace.beginSection("AnimatedFileNative#prepareToSeek");
        try {
            nPrepareToSeek(ptr);
        } finally {
            Trace.endSection();
        }
    }

    public static void getVideoInfo(String src, int[] params, long fileOffset) {
        Trace.beginSection("AnimatedFileNative#getVideoInfo");
        try {
            nGetVideoInfo(src, params, fileOffset, getMaxAv1DecodePixels());
        } finally {
            Trace.endSection();
        }
    }

    private static final String AV1_MIME = "video/av01";
    private static final int[][] AV1_SIZES = { { 3840, 2160 }, { 2560, 1440 }, { 1920, 1080 }, { 1280, 720 } };
    private static final int AV1_UNDECLARED_MAX_PIXELS = 1920 * 1080;
    private static volatile int maxAv1DecodePixels = -1;

    public static int getMaxAv1DecodePixels() {
        if (maxAv1DecodePixels >= 0) {
            return maxAv1DecodePixels;
        }
        int pixels = 0;
        try {
            for (int[] size : AV1_SIZES) {
                final int candidate = size[0] * size[1];
                if (candidate > AV1_UNDECLARED_MAX_PIXELS && !isSizeDeclaredByHardwareDecoder(size[0], size[1])) {
                    continue;
                }
                if (canDecodeAv1(size[0], size[1])) {
                    pixels = candidate;
                    break;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("av1 max decode pixels = " + pixels);
        }
        maxAv1DecodePixels = pixels;
        return pixels;
    }

    private static boolean isSizeDeclaredByHardwareDecoder(int width, int height) {
        final int count = MediaCodecList.getCodecCount();
        for (int i = 0; i < count; ++i) {
            final MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (info.isEncoder() || !isHardwareDecoder(info)) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (!AV1_MIME.equalsIgnoreCase(type)) {
                    continue;
                }
                final MediaCodecInfo.VideoCapabilities capabilities = info.getCapabilitiesForType(type).getVideoCapabilities();
                if (capabilities != null && (capabilities.isSizeSupported(width, height) || capabilities.isSizeSupported(height, width))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean canDecodeAv1(int width, int height) {
        MediaCodec decoder = null;
        try {
            decoder = MediaCodec.createDecoderByType(AV1_MIME);
            decoder.configure(MediaFormat.createVideoFormat(AV1_MIME, width, height), null, null, 0);
            decoder.start();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (decoder != null) {
                try {
                    decoder.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static boolean isHardwareDecoder(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated();
        }
        final String name = info.getName().toLowerCase();
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.startsWith("c2.google.") && !name.endsWith(".sw.dec");
    }



    private static native long nCreateDecoder(String src, int[] params, int account, long streamFileSize, Object readCallback, boolean preview);

    private static native void nDestroyDecoder(long ptr);

    private static native void nStopDecoder(long ptr);

    private static native int nGetVideoFrame(long ptr, Bitmap bitmap, int[] params, boolean preview, float startTimeSeconds, float endTimeSeconds, boolean loop);

    private static native void nSeekToMs(long ptr, long ms, int[] params, boolean precise);

    private static native int nGetFrameAtTime(long ptr, long ms, Bitmap bitmap, int[] data);

    private static native void nPrepareToSeek(long ptr);

    private static native void nGetVideoInfo(String src, int[] params, long fileOffset, int maxAv1DecodePixels);
}