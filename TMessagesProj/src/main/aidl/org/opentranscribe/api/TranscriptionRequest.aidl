package org.opentranscribe.api;

parcelable TranscriptionRequest {
    @nullable String fileName;
    @nullable String mimeType;
    @nullable String languageHint;
}
