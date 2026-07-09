package org.opentranscribe.api;

import org.opentranscribe.api.ErrorType;

parcelable TranscriptionError {
    ErrorType type;
    @nullable String language;
    @nullable String message;
}
