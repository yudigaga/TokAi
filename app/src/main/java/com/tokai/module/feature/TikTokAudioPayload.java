package com.tokai.module.feature;

import android.net.Uri;
import android.util.SparseArray;
import com.tokai.module.scan.HostScanReport;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Native type-1813 voice template and native upload attachment, without a fabricated conversation. */
final class TikTokAudioPayload {
    private final Class<?> attachmentType, mediaType;
    private final Constructor<?> target, wave, template, protobuf, mediaParams;
    private final Method encode, messageType;

    TikTokAudioPayload(HostScanReport report, ClassLoader loader) throws Exception {
        attachmentType = HostTargets.type(report, "tiktok_im_attachment_model", loader);
        mediaType = HostTargets.type(report, "tiktok_im_media_type", loader);
        Class<?> targetType = HostTargets.type(report, "tiktok_im_send_target", loader);
        Class<?> waveType = HostTargets.type(report, "tiktok_im_voice_wave", loader);
        Class<?> templateType = HostTargets.type(report, "tiktok_im_voice_template", loader);
        Class<?> pbType = HostTargets.type(report, "tiktok_im_pb_content", loader);
        Class<?> mediaParamsType = HostTargets.type(report, "tiktok_im_media_params", loader);
        target = targetType.getConstructor(String.class);
        wave = waveType.getConstructor(List.class, float.class);
        String components = "com.ss.android.ugc.aweme.im.message.template.component.";
        template = templateType.getConstructor(waveType,
                Class.forName(components + "FallbackInfoComponent", false, loader),
                Class.forName(components + "PreviewHintComponent", false, loader),
                Class.forName(components + "BaseRequestComponent", false, loader),
                Class.forName(components + "BaseResponseComponent", false, loader),
                Class.forName(components + "AttachmentComponent", false, loader),
                Class.forName(components + "BaseHeaderComponent", false, loader), int.class);
        encode = templateType.getMethod("encode");
        messageType = templateType.getMethod("getMessageType");
        Class<?> byteString = Class.forName("okio.ByteString", false, loader);
        protobuf = pbType.getConstructor(byteString);
        mediaParams = mediaParamsType.getConstructor(targetType, int.class, pbType, List.class, String.class,
                Map.class, Map.class, byteString, byteString, SparseArray.class,
                Class.forName("com.bytedance.im.core.proto.ReferenceInfo", false, loader), List.class,
                Class.forName("X.0BLQ", false, loader), int.class);
    }

    Object prepare(String conversation, SpeechPreparation.Audio audio) throws ReflectiveOperationException {
        if (conversation == null || conversation.isEmpty() || audio == null || !audio.file.isFile()
                || audio.file.length() == 0 || audio.duration <= 0 || audio.duration > 120_000) {
            throw new IllegalArgumentException("Native audio is unavailable or exceeds 120 seconds");
        }
        // Empty native waveform preserves the existing synthesized-audio behavior.
        Object voice = template.newInstance(wave.newInstance(Collections.emptyList(), 0f),
                null, null, null, null, null, null, 126);
        int type = ((Number) messageType.invoke(voice)).intValue();
        if (type != 1813) throw new IllegalStateException("TikTok voice template type changed");
        Object attachment = attachmentType.getConstructor().newInstance();
        attachmentType.getMethod("setIndex", int.class).invoke(attachment, 0);
        attachmentType.getMethod("setMediaType", mediaType).invoke(attachment, mediaType.getField("AUDIO").get(null));
        attachmentType.getMethod("setType", String.class).invoke(attachment, "audio");
        attachmentType.getMethod("setLocalPath", String.class).invoke(attachment, audio.file.getAbsolutePath());
        attachmentType.getMethod("setUploadUri", Uri.class).invoke(attachment, Uri.fromFile(audio.file));
        attachmentType.getMethod("setLength", long.class).invoke(attachment, audio.file.length());
        attachmentType.getMethod("setDuration", Long.class).invoke(attachment, Long.valueOf(audio.duration));
        attachmentType.getMethod("setOriginalDuration", long.class).invoke(attachment, audio.duration);
        String file = audio.file.getName().toLowerCase(java.util.Locale.ROOT);
        String mime = file.endsWith(".aac") ? "audio/aac" : file.endsWith(".mp3") ? "audio/mpeg"
                : file.endsWith(".wav") ? "audio/wav" : file.endsWith(".ogg") ? "audio/ogg" : "audio/mp4";
        attachmentType.getMethod("setMimeType", String.class).invoke(attachment, mime);
        // Mask 32 supplies the native default source; 8192 creates its native media-send tracer.
        return mediaParams.newInstance(target.newInstance(conversation), type,
                protobuf.newInstance(encode.invoke(voice)), Collections.singletonList(attachment), "",
                Collections.emptyMap(), Collections.emptyMap(), null, null, null, null, null, null, 8224);
    }
}
