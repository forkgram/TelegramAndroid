package org.opentranscribe.api;

import org.opentranscribe.api.ITranscriptionCallback;
import org.opentranscribe.api.ITranscriptionSession;
import org.opentranscribe.api.TranscriberCapabilities;
import org.opentranscribe.api.TranscriptionRequest;

interface ITranscriptionService {
    TranscriberCapabilities getCapabilities();

    ITranscriptionSession transcribe(
        in ParcelFileDescriptor audio,
        in TranscriptionRequest request,
        ITranscriptionCallback callback);
}
