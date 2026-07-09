package org.telegram.messenger.forkgram

import java.util.function.BiConsumer
import java.util.function.Consumer

class TranscriptionCancelledException : Exception()

interface TranscriptionCancellable {
    fun cancel()
}

class TranscriberProvider(
    @JvmField val kind: String,
    @JvmField val packageName: String,
    @JvmField val label: String
) {
    fun id(): String = "$kind:$packageName"
}

interface OfflineTranscriber {

    fun capabilities(): org.opentranscribe.api.TranscriberCapabilities?

    fun requestTranscription(
        audioFilePath: String,
        languageHint: String?,
        onProgress: Consumer<String>,
        onFinal: BiConsumer<String?, Exception?>
    ): TranscriptionCancellable
}
