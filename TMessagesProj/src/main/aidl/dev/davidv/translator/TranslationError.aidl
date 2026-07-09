package dev.davidv.translator;

import dev.davidv.translator.ErrorType;

parcelable TranslationError {
    ErrorType type;
    @nullable String language;
    @nullable String message;
}
