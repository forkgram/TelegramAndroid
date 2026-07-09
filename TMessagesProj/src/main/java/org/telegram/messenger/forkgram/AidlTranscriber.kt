package org.telegram.messenger.forkgram

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.opentranscribe.api.ErrorType
import org.opentranscribe.api.ITranscriptionCallback
import org.opentranscribe.api.ITranscriptionService
import org.opentranscribe.api.ITranscriptionSession
import org.opentranscribe.api.TranscriberCapabilities
import org.opentranscribe.api.TranscriptionError
import org.opentranscribe.api.TranscriptionRequest
import org.telegram.messenger.ApplicationLoader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer

class AidlTranscriber(private val servicePackage: String) : OfflineTranscriber {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bindLock = Object()

    @Volatile
    private var service: ITranscriptionService? = null
    private var connection: ServiceConnection? = null
    private var bindLatch: CountDownLatch? = null

    override fun capabilities(): TranscriberCapabilities? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null
        }
        return try {
            ensureService()?.capabilities
        } catch (e: Exception) {
            service = null
            null
        }
    }

    override fun requestTranscription(
        audioFilePath: String,
        languageHint: String?,
        onProgress: Consumer<String>,
        onFinal: BiConsumer<String?, Exception?>
    ): TranscriptionCancellable {
        val session = Session()
        executor.execute {
            try {
                val text = transcribe(audioFilePath, languageHint, onProgress, session)
                if (session.cancelled) {
                    onFinal.accept(null, TranscriptionCancelledException())
                } else {
                    onFinal.accept(text, null)
                }
            } catch (e: Exception) {
                onFinal.accept(null, if (session.cancelled) TranscriptionCancelledException() else e)
            }
        }
        return session
    }

    private fun transcribe(
        audioFilePath: String,
        languageHint: String?,
        onProgress: Consumer<String>,
        session: Session
    ): String? {
        val file = File(audioFilePath)
        val bound = ensureService() ?: return null
        val resultRef = AtomicReference<String?>(null)
        val lastActivity = AtomicLong(SystemClock.elapsedRealtime())
        val callback = object : ITranscriptionCallback.Stub() {
            override fun onTranscriptionProgress(text: String?) {
                lastActivity.set(SystemClock.elapsedRealtime())
                if (text != null) {
                    onProgress.accept(text)
                }
            }

            override fun onTranscriptionResult(text: String?) {
                resultRef.set(text)
                session.latch.countDown()
            }

            override fun onTranscriptionError(error: TranscriptionError?) {
                if (error != null && error.type == ErrorType.CANCELLED) {
                    session.cancelled = true
                }
                session.latch.countDown()
            }
        }
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            return null
        }
        try {
            val request = TranscriptionRequest()
            request.fileName = file.name
            request.mimeType = mimeTypeOf(file.name)
            request.languageHint = languageHint ?: ""
            val remote = bound.transcribe(descriptor, request, callback)
            session.attach(remote)
            while (!session.latch.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (session.cancelled) {
                    break
                }
                if (SystemClock.elapsedRealtime() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    session.cancelRemote()
                    break
                }
            }
        } catch (e: Exception) {
            service = null
            return null
        } finally {
            try {
                descriptor.close()
            } catch (ignore: Exception) {
            }
        }
        return resultRef.get()
    }

    private fun ensureService(): ITranscriptionService? {
        service?.let { return it }
        val context = ApplicationLoader.applicationContext ?: return null
        synchronized(bindLock) {
            service?.let { return it }
            var latch = bindLatch
            if (connection == null) {
                latch = CountDownLatch(1)
                val awaiting = latch
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        service = ITranscriptionService.Stub.asInterface(binder)
                        awaiting.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                }
                val intent = Intent(ACTION).setPackage(servicePackage)
                val ok = try {
                    context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    try {
                        context.unbindService(conn)
                    } catch (ignore: Exception) {
                    }
                    return null
                }
                connection = conn
                bindLatch = latch
            }
            latch?.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return service
        }
    }

    private fun mimeTypeOf(fileName: String): String? {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".opus") -> "audio/ogg"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".m4a") -> "audio/mp4"
            name.endsWith(".mp3") -> "audio/mpeg"
            name.endsWith(".wav") -> "audio/wav"
            else -> null
        }
    }

    private class Session : TranscriptionCancellable {

        val latch = CountDownLatch(1)

        @Volatile
        var cancelled = false

        @Volatile
        private var remote: ITranscriptionSession? = null

        fun attach(session: ITranscriptionSession?) {
            remote = session
            if (cancelled) {
                cancelRemote()
            }
        }

        fun cancelRemote() {
            try {
                remote?.cancel()
            } catch (ignore: Exception) {
            }
        }

        override fun cancel() {
            cancelled = true
            cancelRemote()
            latch.countDown()
        }
    }

    companion object {
        const val ACTION = "org.opentranscribe.api.ITranscriptionService"
        const val CONTRACT_VERSION = 1
        private const val BIND_TIMEOUT_MS = 5000L
        private const val IDLE_TIMEOUT_MS = 300000L
        private const val POLL_INTERVAL_MS = 1000L
    }
}
