package com.tokai.module.feature;

import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TikTokCommentModelsTest {
    @Test public void immutableCopyKeepsReplyWorkMetadataAndCallbacksBeforeAddingSynthesizedAudio() throws Exception {
        TikTokCommentModels access = access();
        Input original = model();
        Input snapshot = (Input) access.copy(original);
        for (Field field : Input.class.getFields()) assertEquals(field.getName(), field.get(original), field.get(snapshot));
        File file = File.createTempFile("tokai-model-audio", ".mp3");
        try {
            byte[] bytes = new byte[]{1, 2, 3, 4};
            Files.write(file.toPath(), bytes);
            Input transformed = (Input) access.withAudio(snapshot, file, 2345L, true);
            assertEquals("", transformed.LIZ);
            assertTrue(transformed.LIZIZ.isEmpty());
            assertEquals(1, transformed.LIZLLL.size());
            Audio audio = (Audio) transformed.LIZLLL.get(0);
            assertEquals(file.getAbsolutePath(), audio.path);
            assertEquals(2345L, audio.duration);
            assertNull(audio.vid);
            for (Field field : Input.class.getFields()) {
                if (Arrays.asList("LIZ", "LIZIZ", "LIZLLL").contains(field.getName())) continue;
                assertEquals(field.getName(), field.get(original), field.get(transformed));
            }
            assertNotEquals("", original.LIZ);
            assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
        } finally { file.delete(); }
    }

    @Test public void recordingReplacementKeepsNativeTextAndReplyTargets() throws Exception {
        Input original = model();
        File file = File.createTempFile("tokai-record-replacement", ".audio");
        try {
            Input replaced = (Input) access().withAudio(original, file, 1000L, false);
            assertSame(original.LIZ, replaced.LIZ);
            assertSame(original.LIZIZ, replaced.LIZIZ);
            assertSame(original.LJIIIIZZ, replaced.LJIIIIZZ);
            assertSame(original.LJIILLIIL, replaced.LJIILLIIL);
            assertNotSame(original.LIZLLL, replaced.LIZLLL);
        } finally { file.delete(); }
    }

    private static TikTokCommentModels access() throws Exception {
        HostScanReport report = new HostScanReport("fixture", Arrays.asList(
                rule("tiktok_comment_input_model", Input.class), rule("tiktok_comment_audio_model", Audio.class)), false);
        return new TikTokCommentModels(report, TikTokCommentModelsTest.class.getClassLoader());
    }
    private static RuleScanResult rule(String id, Class<?> type) {
        return new RuleScanResult(id, "comment", Collections.singletonList(
                new HostCandidate("fixture.apk", "classes.dex", HostTargets.descriptor(type), null, null)));
    }
    private static Input model() throws Exception {
        Constructor<?> constructor = Input.class.getConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = types[i] == int.class ? Integer.valueOf(i + 100)
                    : types[i] == boolean.class ? Boolean.TRUE
                    : types[i] == String.class || types[i] == CharSequence.class ? "field-" + i
                    : types[i] == List.class ? Collections.singletonList("field-" + i) : new Object();
        }
        return (Input) constructor.newInstance(values);
    }
    public static final class Input {
        public final CharSequence LIZ;
        public final List<?> LIZIZ, LIZJ, LIZLLL;
        public final Object LJ, LJFF;
        public final String LJI;
        public final int LJII;
        public final Object LJIIIIZZ;
        public final int LJIIIZ, LJIIJ;
        public final boolean LJIIJJI;
        public final Object LJIIL, LJIILIIL;
        public final boolean LJIILJJIL;
        public final String LJIILL;
        public final Object LJIILLIIL, LJIIZILJ, LJIJ;
        public final boolean LJIJI;
        public final String LJIJJ, LJIJJLI;
        public Input(CharSequence a, List<?> b, List<?> c, List<?> d, Object e, Object f,
                     String g, int h, Object i, int j, int k, boolean l, Object m, Object n,
                     boolean o, String p, Object q, Object r, Object s, boolean t, String u, String v) {
            LIZ = a; LIZIZ = b; LIZJ = c; LIZLLL = d; LJ = e; LJFF = f; LJI = g; LJII = h;
            LJIIIIZZ = i; LJIIIZ = j; LJIIJ = k; LJIIJJI = l; LJIIL = m; LJIILIIL = n;
            LJIILJJIL = o; LJIILL = p; LJIILLIIL = q; LJIIZILJ = r; LJIJ = s; LJIJI = t;
            LJIJJ = u; LJIJJLI = v;
        }
    }
    public static final class Audio {
        public Object videoModelStruct;
        final String path;
        final long duration;
        final String vid;
        public Audio(String path, long duration, List<?> wave, String id, long bytes,
                     String format, String vid, String videoModel, Object model) {
            this.path = path;
            this.duration = duration;
            this.vid = vid;
        }
    }
}
