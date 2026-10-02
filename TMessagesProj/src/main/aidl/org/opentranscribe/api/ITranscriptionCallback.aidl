package org.opentranscribe.api;

import org.opentranscribe.api.TranscriptionError;

oneway interface ITranscriptionCallback {
    void onTranscriptionProgress(String text);
    void onTranscriptionResult(String text);
    void onTranscriptionSegment(long startMs, long endMs, String text);
    void onTranscriptionError(in TranscriptionError error);
}
