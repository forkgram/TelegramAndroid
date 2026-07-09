package dev.davidv.translator;

import dev.davidv.translator.TranslationError;

oneway interface ITranslationCallback {
    void onTranslationResult(String translatedText);
    void onTranslationError(in TranslationError error);
}
