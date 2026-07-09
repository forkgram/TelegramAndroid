package org.telegram.messenger.forkgram

import android.content.Intent
import android.os.SystemClock
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer

object ForkOfflineTranscribe {

    const val KIND_AIDL = "aidl"

    const val SUGGESTED_PACKAGE = "org.scrib.transcriber"
    const val SUGGESTED_FDROID_URL = "https://f-droid.org/packages/$SUGGESTED_PACKAGE/"

    private const val PROVIDER_PREF = "offlineSttProvider"
    private const val LEGACY_ENABLED_PREF = "offlineSttEnabled"
    private const val LEGACY_PACKAGE = "org.forkclient.transcriber"
    private const val CACHE_TTL_MS = 15000L

    private val transcribers = ConcurrentHashMap<String, OfflineTranscriber>()

    @Volatile
    private var cachedId: String? = null

    @Volatile
    private var cachedProvider: TranscriberProvider? = null

    @Volatile
    private var cachedAt = 0L

    @JvmStatic
    fun availableProviders(): List<TranscriberProvider> {
        val context = ApplicationLoader.applicationContext ?: return emptyList()
        val packageManager = context.packageManager
        val services = try {
            packageManager.queryIntentServices(Intent(AidlTranscriber.ACTION), 0)
        } catch (e: Exception) {
            return emptyList()
        }
        return services.mapNotNull { resolved ->
            val info = resolved.serviceInfo ?: return@mapNotNull null
            val label = try {
                info.applicationInfo.loadLabel(packageManager).toString()
            } catch (e: Exception) {
                info.packageName
            }
            TranscriberProvider(KIND_AIDL, info.packageName, label)
        }
    }

    @JvmStatic
    fun selectedProviderId(): String {
        val settings = MessagesController.getGlobalMainSettings()
        val stored = settings.getString(PROVIDER_PREF, null)
        if (stored != null) {
            return stored
        }
        if (settings.getBoolean(LEGACY_ENABLED_PREF, false)) {
            val migrated = "$KIND_AIDL:$LEGACY_PACKAGE"
            settings.edit().putString(PROVIDER_PREF, migrated).remove(LEGACY_ENABLED_PREF).apply()
            return migrated
        }
        return ""
    }

    @JvmStatic
    fun setProvider(provider: TranscriberProvider?) {
        val settings = MessagesController.getGlobalMainSettings()
        settings.edit().putString(PROVIDER_PREF, provider?.id() ?: "").remove(LEGACY_ENABLED_PREF).apply()
        invalidate()
    }

    @JvmStatic
    fun selectedProvider(): TranscriberProvider? {
        val id = selectedProviderId()
        if (id.isEmpty()) {
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (id == cachedId && now - cachedAt < CACHE_TTL_MS) {
            return cachedProvider
        }
        val resolved = availableProviders().firstOrNull { it.id() == id }
        cachedId = id
        cachedProvider = resolved
        cachedAt = now
        return resolved
    }

    @JvmStatic
    fun selectedProviderLabel(): String? = selectedProvider()?.label

    @JvmStatic
    fun isEnabled(): Boolean = selectedProviderId().isNotEmpty()

    @JvmStatic
    fun isAvailable(): Boolean = availableProviders().isNotEmpty()

    @JvmStatic
    fun isActive(): Boolean = selectedProvider() != null

    @JvmStatic
    fun invalidate() {
        cachedId = null
        cachedProvider = null
        cachedAt = 0L
    }

    @JvmStatic
    fun capabilitiesOf(provider: TranscriberProvider): org.opentranscribe.api.TranscriberCapabilities? =
        transcriberFor(provider)?.capabilities()

    @JvmStatic
    fun requestTranscription(
        audioFilePath: String,
        languageHint: String?,
        onProgress: Consumer<String>,
        onFinal: BiConsumer<String?, Exception?>
    ): TranscriptionCancellable? {
        val provider = selectedProvider() ?: return null
        val transcriber = transcriberFor(provider) ?: return null
        return transcriber.requestTranscription(audioFilePath, languageHint, onProgress, onFinal)
    }

    private fun transcriberFor(provider: TranscriberProvider): OfflineTranscriber? {
        if (provider.kind != KIND_AIDL) {
            return null
        }
        return transcribers.getOrPut(provider.id()) { AidlTranscriber(provider.packageName) }
    }
}
