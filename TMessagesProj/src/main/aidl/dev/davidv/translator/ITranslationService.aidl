package dev.davidv.translator;

import dev.davidv.translator.ITranslationCallback;

interface ITranslationService {
    void translate(String textToTranslate, String fromLanguage, String toLanguage, ITranslationCallback callback);
}
