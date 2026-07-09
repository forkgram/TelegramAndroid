package org.opentranscribe.api;

parcelable TranscriberCapabilities {
    int contractVersion;
    @nullable String engineId;
    @nullable String engineVersion;
    @nullable String[] supportedLanguages;
    boolean autoDetectLanguage;
    boolean cancellable;
    boolean modelReady;
}
