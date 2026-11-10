package com.tokai.module.feature;

import com.tokai.module.scan.HostScanReport;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/** Exact constructor and field relations of TikTok 47.5.3 comment input/recording models. */
final class TikTokCommentModels {
    private static final String[] INPUT_FIELDS = {"LIZ", "LIZIZ", "LIZJ", "LIZLLL", "LJ", "LJFF", "LJI",
            "LJII", "LJIIIIZZ", "LJIIIZ", "LJIIJ", "LJIIJJI", "LJIIL", "LJIILIIL", "LJIILJJIL",
            "LJIILL", "LJIILLIIL", "LJIIZILJ", "LJIJ", "LJIJI", "LJIJJ", "LJIJJLI"};
    private final Field[] fields = new Field[INPUT_FIELDS.length];
    private final Constructor<?> input;
    private final Constructor<?> audio;

    TikTokCommentModels(HostScanReport report, ClassLoader loader) throws Exception {
        Class<?> type = HostTargets.type(report, "tiktok_comment_input_model", loader);
        Class<?>[] parameters = new Class<?>[fields.length];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = HostTargets.field(type, INPUT_FIELDS[i]);
            parameters[i] = fields[i].getType();
        }
        input = type.getConstructor(parameters);
        Class<?> recording = HostTargets.type(report, "tiktok_comment_audio_model", loader);
        audio = recording.getConstructor(String.class, long.class, List.class, String.class,
                long.class, String.class, String.class, String.class,
                HostTargets.field(recording, "videoModelStruct").getType());
    }

    Object copy(Object original) throws ReflectiveOperationException {
        Object[] values = new Object[fields.length];
        for (int i = 0; i < values.length; i++) values[i] = fields[i].get(original);
        return input.newInstance(values);
    }

    Object recording(File file, long duration) throws ReflectiveOperationException {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String format = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return audio.newInstance(file.getAbsolutePath(), duration, CommentAudioWaveform.nativeFallback(),
                java.util.UUID.randomUUID().toString(), file.length(), format, null, null, null);
    }

    Object withAudio(Object original, File file, long duration, boolean clearText) throws ReflectiveOperationException {
        Object[] values = new Object[fields.length];
        for (int i = 0; i < values.length; i++) values[i] = fields[i].get(original);
        if (clearText) { values[0] = ""; values[1] = Collections.emptyList(); }
        values[3] = Collections.singletonList(recording(file, duration));
        return input.newInstance(values);
    }

    String text(Object model) throws ReflectiveOperationException {
        Object value = fields[0].get(model);
        return value instanceof CharSequence ? value.toString() : "";
    }

    boolean textOnly(Object model) throws ReflectiveOperationException {
        for (int i : new int[]{2, 3}) {
            Object value = fields[i].get(model);
            if (value instanceof List<?> && !((List<?>) value).isEmpty()) return false;
        }
        return fields[4].get(model) == null && fields[5].get(model) == null;
    }

    List<?> recordings(Object model) throws ReflectiveOperationException {
        Object value = fields[3].get(model);
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }
}
