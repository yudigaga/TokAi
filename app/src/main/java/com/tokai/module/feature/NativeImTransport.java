package com.tokai.module.feature;

import java.util.function.Consumer;

/** Native message preparation belongs to the host adapter; reply policy owns admission and history. */
interface NativeImTransport {
    String json(Object content) throws ReflectiveOperationException;
    TikTokImBridge.Submission prepare(Object message, Consumer<Boolean> completed) throws ReflectiveOperationException;
    TikTokImBridge.Submission prepareAudio(String conversation, SpeechPreparation.Audio audio,
                                           Consumer<Boolean> completed) throws ReflectiveOperationException;
}
