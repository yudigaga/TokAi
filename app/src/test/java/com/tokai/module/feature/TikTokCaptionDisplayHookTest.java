package com.tokai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.tokai.module.config.CaptionVisibilitySettings;
import com.tokai.module.config.LocalOptions;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Real attached views, deterministic workers and fake host metadata; no codec or server claims. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TikTokCaptionDisplayHookTest {
    @Test public void onlyCurrentVisibleForcedVideoStartsAndDuplicateCallbacksDoNotUploadAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.hook.setOptions(options(false, false)); f.select(); f.complete(); assertEquals(0, f.requests);
            f.hook.setOptions(options(true, false)); f.complete(); assertEquals(1, f.requests);
            f.select(); f.select(); f.complete(); assertEquals(1, f.requests);
            assertEquals("https://v16m.tiktokcdn.com/original.m4a?sig=a%2Bb", f.source.url);
        }
    }

    @Test public void nativeCaptionMetadataAlwaysTakesPriority() throws Exception {
        try (Fixture f = new Fixture()) {
            f.nativeCaptions = true; f.select(); f.complete();
            assertEquals(0, f.requests); assertNull(f.layer());
            assertEquals("native_captions", f.state());
        }
    }

    @Test public void neighboringAndRecycledQueuedSelectionsCannotStart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.current = false; f.select(); f.complete(); assertEquals(0, f.requests);
            f.current = true;
            f.hook.selected(f.holder); f.aid = "recycled";
            ShadowLooper.idleMainLooper(); f.complete(); assertEquals(0, f.requests);
        }
    }

    @Test public void visibilityBackgroundAndPausedQueuedSelectionAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.root.focused = false; f.select(); f.complete(); assertEquals(0, f.requests);
            f.hook.unselected(f.holder); // Separate a retained focus-resume candidate from a queued selection.
            f.root.focused = true;
            f.hook.selected(f.holder);
            f.hook.onActivityPaused(f.activity); f.hook.onActivityResumed(f.activity);
            ShadowLooper.idleMainLooper(); f.complete(); assertEquals(0, f.requests);
            f.hook.onActivityPaused(f.activity); f.select(); f.complete(); assertEquals(0, f.requests);
        }
    }

    @Test public void playbackClockControlsCueSeekingAndGapsWithoutWallClockDrift() throws Exception {
        try (Fixture f = new Fixture()) {
            f.select(); f.complete(); assertEquals("first", f.text());
            f.position = 1600; f.hook.pulse(); assertNull(f.layer());
            f.position = 2100; f.hook.pulse(); assertEquals("second", f.text());
            f.position = 400; f.hook.pulse(); assertEquals("first", f.text());
            f.position = -1; f.hook.pulse(); assertNull(f.layer());
            assertEquals(1, f.requests);
        }
    }

    @Test public void hideModeRevokesQueuedRecognitionAndClearsTheLayerImmediately() throws Exception {
        try (Fixture f = new Fixture()) {
            f.select();
            f.hook.setOptions(options(false, true)); f.complete();
            assertEquals(0, f.requests); assertNull(f.layer());
            f.hook.setOptions(options(true, false)); f.complete(); assertEquals("first", f.text());
            f.hook.setOptions(options(true, true)); assertNull(f.layer());
        }
    }

    @Test public void backgroundModeChangeCancelsQueuedUploadBeforeTheMainQueueRuns() throws Exception {
        try (Fixture f = new Fixture()) {
            f.select();
            Thread update = new Thread(() -> f.hook.setOptions(options(false, true)));
            update.start(); update.join(2000); assertFalse(update.isAlive());
            f.worker.runAll(); // Deliberately do not pump the queued Android UI cancellation yet.
            assertEquals(0, f.requests);
            ShadowLooper.idleMainLooper(); assertNull(f.layer());
        }
    }

    @Test public void completedButUndeliveredResultCannotCrossASelection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.select(); f.worker.runAll(); // completion is posted but not delivered
            f.aid = "next"; f.hook.unselected(f.holder); f.hook.selected(f.holder);
            ShadowLooper.idleMainLooper();
            assertNull(f.layer());
            f.complete(); assertEquals("first", f.text()); assertEquals(2, f.requests);
        }
    }

    @Test public void nativeCaptionsArrivingDuringRecognitionSuppressTheResult() throws Exception {
        try (Fixture f = new Fixture()) {
            f.select(); f.worker.runAll(); f.nativeCaptions = true;
            ShadowLooper.idleMainLooper(); f.hook.pulse(); assertNull(f.layer());
        }
    }

    @Test public void coverDetachAndCloseRemoveTextWithoutClearingUnrelatedHostViews() throws Exception {
        try (Fixture f = new Fixture()) {
            TextView hostText = new TextView(f.activity); hostText.setText("host cue"); f.root.addView(hostText);
            f.select(); f.complete(); assertEquals("first", f.text());
            f.root.focused = false; f.hook.pulse(); assertNull(f.layer());
            assertEquals("host cue", hostText.getText().toString());
            f.root.focused = true; f.hook.pulse(); assertEquals("first", f.text());
            f.activity.setContentView(new FrameLayout(f.activity)); assertNull(f.layer());
            f.hook.close(); f.hook.selected(f.holder); f.complete(); assertEquals(1, f.requests);
        }
    }

    @Test public void recognitionFailureDoesNotRetryOnEveryFrameOrLogSourceData() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fail = true; f.select(); f.complete();
            for (int i = 0; i < 5; i++) { f.hook.pulse(); f.complete(); }
            assertEquals(1, f.requests); assertNull(f.layer()); assertEquals("failed", f.state());
            assertFalse(String.join(" ", f.logs).contains("secret"));
            f.hook.setOptions(options(false, false)); f.fail = false;
            f.hook.setOptions(options(true, false)); f.complete(); assertEquals(2, f.requests);
        }
    }

    @Test public void sameWindowNativeBlockingMaskHidesCaptionsEvenWhileRootKeepsFocus() throws Exception {
        try (Fixture f = new Fixture()) {
            f.select(); f.complete(); assertEquals("first", f.text());
            f.unblocked = false;
            TextView mask = new TextView(f.activity); mask.setText("native modal"); f.root.addView(mask);
            assertTrue(f.root.hasWindowFocus()); assertTrue(f.root.isShown());
            f.hook.pulse(); assertNull(f.layer());
            assertEquals("native modal", mask.getText().toString());
            f.unblocked = true; f.hook.pulse(); assertEquals("first", f.text());
            assertEquals(1, f.requests);
        }
    }

    @Test public void invalidMetadataFailsClosedAndDiagnosticContainsNoContentOrUrl() throws Exception {
        try (Fixture f = new Fixture()) {
            f.invalid = true; f.select(); f.complete(); assertEquals(0, f.requests);
            Bundle state = new Bundle(); f.hook.appendDiagnostics(state);
            assertFalse(state.getBoolean("caption_generation_server_verified"));
            assertFalse(state.toString().contains("https")); assertFalse(state.toString().contains("first"));
        }
    }

    private static LocalOptions options(boolean force, boolean hide) {
        return new LocalOptions(Map.of(CaptionVisibilitySettings.FORCE, force, CaptionVisibilitySettings.HIDE, hide));
    }
    private static final class Root extends FrameLayout {
        boolean focused = true;
        Root(Activity activity) { super(activity); }
        @Override public boolean hasWindowFocus() { return focused; }
        @Override public int getWindowVisibility() { return View.VISIBLE; }
    }
    private static final class ManualWorker extends ThreadPoolExecutor {
        final List<Runnable> tasks = new ArrayList<>();
        ManualWorker() { super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable command) { tasks.add(command); }
        @Override public boolean remove(Runnable command) { return tasks.remove(command); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
        @Override public List<Runnable> shutdownNow() { List<Runnable> pending = new ArrayList<>(tasks); tasks.clear(); super.shutdownNow(); return pending; }
    }
    public static final class Work {
        public String aweme_id;
        public int aweme_type;
        public VideoModel video = new VideoModel();
        Work(String aid, int type) { aweme_id = aid; aweme_type = type; }
        public String getAid() { return aweme_id; }
    }
    public static final class VideoModel {
        public long duration = 5000;
        public List<AudioModel> bit_rate_audio = List.of(new AudioModel());
    }
    public static final class AudioModel { public AudioMeta audio_meta = new AudioMeta(); }
    public static final class AudioMeta { public AudioUrls url_list = new AudioUrls(); }
    public static final class AudioUrls { public String main_url = "https://v16m.tiktokcdn.com/original.m4a?sig=a%2Bb"; }
    private static final class Fixture implements AutoCloseable, TikTokCaptionDisplayHook.Access {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final Root root = new Root(activity);
        final Object holder = new Object();
        final ManualWorker worker = new ManualWorker();
        final List<String> logs = new ArrayList<>();
        final TikTokCaptionDisplayHook hook;
        String aid = "work";
        boolean current = true, nativeCaptions, invalid, fail, unblocked = true;
        long position = 500;
        int requests;
        CaptionAudioInput.Source source;
        Fixture() {
            activity.setContentView(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 1920);
            hook = new TikTokCaptionDisplayHook((Application) activity.getApplicationContext(), options(true, false), this,
                    (input, token) -> {
                        requests++; source = input;
                        if (fail) throw new IllegalStateException("https://secret?key=secret");
                        return new CaptionTranscript(List.of(new CaptionTranscript.Cue(0, 1000, "first"),
                                new CaptionTranscript.Cue(2000, 3000, "second")));
                    }, logs::add, worker);
            hook.onActivityResumed(activity);
            assertTrue(root.isAttachedToWindow()); assertTrue(root.getWidth() > 0);
        }
        void select() { hook.selected(holder); ShadowLooper.idleMainLooper(); }
        void complete() { worker.runAll(); ShadowLooper.idleMainLooper(); }
        TextView layer() throws Exception { return (TextView) field("layer"); }
        String text() throws Exception { return layer() == null ? "" : layer().getText().toString(); }
        String state() throws Exception { return (String) field("state"); }
        Object field(String name) throws Exception { Field field = TikTokCaptionDisplayHook.class.getDeclaredField(name); field.setAccessible(true); return field.get(hook); }
        @Override public Object aweme(Object holder) {
            return new Work(aid, invalid ? 2 : 0);
        }
        @Override public View root(Object holder) { return root; }
        @Override public boolean isCurrent(Object holder) { return current; }
        @Override public long positionMillis(Object holder) { return position; }
        @Override public boolean hasNativeCaptions(Object aweme) { return nativeCaptions; }
        @Override public boolean unblocked(Object holder) { return unblocked; }
        @Override public void close() { hook.close(); activity.finish(); }
    }
}
