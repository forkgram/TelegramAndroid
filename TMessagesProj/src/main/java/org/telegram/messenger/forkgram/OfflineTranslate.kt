package org.telegram.messenger.forkgram

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ForkOfflineTranslate {

    const val PACKAGE = "dev.davidv.translator"
    const val FDROID_URL = "https://f-droid.org/packages/$PACKAGE/"
    private const val ACTION = "dev.davidv.translator.ITranslationService"
    private const val BIND_TIMEOUT_MS = 5000L
    private const val CALL_TIMEOUT_MS = 60000L

    const val PROVIDER_DEFAULT = 0
    const val PROVIDER_ALTERNATIVE = 1
    const val PROVIDER_OFFLINE = 2

    @JvmStatic
    fun provider(): Int =
        MessagesController.getGlobalMainSettings().getInt("translationProvider", PROVIDER_DEFAULT)

    @JvmStatic
    fun method(serverMethod: String?): String? = when (provider()) {
        PROVIDER_ALTERNATIVE -> "alternative"
        PROVIDER_OFFLINE -> "offline"
        else -> serverMethod
    }

    private val bindLock = Object()
    @Volatile private var service: ITranslationService? = null
    private var connection: ServiceConnection? = null
    private var bindLatch: CountDownLatch? = null

    @JvmStatic
    fun isAvailable(): Boolean {
        val context = ApplicationLoader.applicationContext ?: return false
        val intent = Intent(ACTION).setPackage(PACKAGE)
        return context.packageManager.resolveService(intent, 0) != null
    }

    @JvmStatic
    fun translate(text: String, fromLanguage: String?, toLanguage: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null
        }
        val bound = ensureService() ?: return null
        val resultRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ITranslationCallback.Stub() {
            override fun onTranslationResult(translatedText: String?) {
                resultRef.set(translatedText)
                latch.countDown()
            }

            override fun onTranslationError(error: TranslationError?) {
                latch.countDown()
            }
        }
        try {
            bound.translate(text, fromLanguage ?: "", toLanguage, callback)
        } catch (e: Exception) {
            service = null
            return null
        }
        latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return resultRef.get()
    }

    private fun ensureService(): ITranslationService? {
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
                        service = ITranslationService.Stub.asInterface(binder)
                        awaiting.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                }
                val intent = Intent(ACTION).setPackage(PACKAGE)
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
}
