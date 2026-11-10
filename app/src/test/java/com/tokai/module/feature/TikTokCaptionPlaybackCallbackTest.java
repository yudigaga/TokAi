package com.tokai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.tokai.module.config.CaptionVisibilitySettings;
import com.tokai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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

/** Exercises installed hook chains with typed host models, without codecs or server requests. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TikTokCaptionPlaybackCallbackTest {
    private static final String OLD_AID = "750000000000000001";
    private static final String CURRENT_AID = "750000000000000002";
    private static final String NEXT_AID = "750000000000000003";

    @Test public void stalePauseCannotCancelCurrentQueuedOrCompletedRecognition() throws Throwable {
        for (boolean completeFirst : new boolean[]{false, true}) {
            try (Fixture f = new Fixture()) {
                f.select();
                if (completeFirst) f.complete();
                Object candidate = f.candidate();
                f.pause(OLD_AID); f.complete();
                assertSame(candidate, f.candidate());
                assertEquals(1, f.requests);
                assertEquals("first", f.text());
                assertEquals(1, f.holder.calls.get("pause").intValue());
            }
        }
    }

    @Test public void staleResumeCannotAdoptTheCurrentHolderBeforeItsRealSelection() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.resume(OLD_AID); f.complete();
            assertEquals(0, f.requests);
            assertNull(f.candidate());
            f.select(); f.complete();
            assertEquals(1, f.requests);
        }
    }

    @Test public void callbacksCaptureAidAndRootBeforeTheOriginalMethodCanRecycleThem() throws Throwable {
        for (String source : List.of(CURRENT_AID, "main_play")) {
            for (boolean replaceRoot : new boolean[]{false, true}) {
                try (Fixture f = new Fixture()) {
                    f.holder.duringOriginal = replaceRoot ? f::replaceRoot : () -> f.holder.work = new Work(NEXT_AID);
                    f.resume(source); f.complete();
                    assertEquals(0, f.requests);
                    assertNull(f.candidate());
                }
            }
        }
    }

    @Test public void currentPauseKeepsItsTimelineForSeekingWithoutAnotherUpload() throws Throwable {
        for (String source : List.of(CURRENT_AID, "main_play")) {
            try (Fixture f = new Fixture()) {
                f.resume(source); f.complete();
                Object candidate = f.candidate();
                f.pause(source); f.complete();
                assertSame(candidate, f.candidate());
                assertEquals("first", f.text());
                f.position = 2100; f.hook.pulse();
                assertEquals("second", f.text());
                f.resume(source); f.complete();
                assertEquals(1, f.requests);
            }
        }
    }

    @Test public void actualUnselectionCancelsQueuedWorkAndDiscardsItsCandidate() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select();
            f.harness.invoke(unselected(), f.holder, false);
            f.complete();
            assertEquals(0, f.requests);
            assertNull(f.candidate());
            assertNull(f.layer());
            assertEquals(1, f.holder.calls.get("unselected").intValue());
            assertEquals(5, f.harness.interceptors.size());
            f.hook.close();
            assertTrue(f.harness.interceptors.isEmpty());
            assertNull(f.harness.invoke(resumed(), f.holder, CURRENT_AID));
            f.complete();
            assertEquals(1, f.holder.calls.get("resume").intValue());
            assertEquals(0, f.requests);
        }
    }

    @Test public void metadataFailuresNeverEscapeTheInstalledCallbackOrSkipItsOriginal() throws Throwable {
        try (Fixture f = new Fixture()) {
            for (Method callback : List.of(resumed(), paused())) {
                f.failAwemeRead = true;
                assertNull(f.harness.invoke(callback, f.holder, CURRENT_AID));
                f.failAwemeRead = false;
                f.complete();
                assertEquals(0, f.requests);
                assertNull(f.candidate());
            }
            assertEquals(1, f.holder.calls.get("resume").intValue());
            assertEquals(1, f.holder.calls.get("pause").intValue());
        }
    }

    @Test public void originalHostExceptionsKeepTheirOriginalIdentity() throws Throwable {
        try (Fixture f = new Fixture()) {
            IllegalStateException original = new IllegalStateException("host callback fixture");
            f.holder.originalFailure = original;
            Throwable caught = assertThrows(IllegalStateException.class,
                    () -> f.harness.invoke(resumed(), f.holder, CURRENT_AID));
            assertSame(original, caught);
            f.complete();
            assertEquals(0, f.requests);
        }
    }

    public static final class Work {
        public final String aweme_id;
        public final int aweme_type = 0;
        public final Video video = new Video();
        Work(String aid) { aweme_id = aid; }
        public String getAid() { return aweme_id; }
        public int getAwemeType() { return aweme_type; }
        public Video getVideo() { return video; }
        public boolean isAd() { return false; }
    }
    public static final class Video {
        public final long duration = 5000;
        public final List<AudioRate> bit_rate_audio = List.of(new AudioRate());
        public final PlaybackUrl play_addr = new PlaybackUrl();
        public long getDuration() { return duration; }
        public List<AudioRate> getBitRateAudio() { return bit_rate_audio; }
    }
    public static final class AudioRate {
        public final AudioMeta audio_meta = new AudioMeta();
        public AudioMeta getAudioMeta() { return audio_meta; }
    }
    public static final class AudioMeta {
        public final AudioUrls url_list = new AudioUrls();
        public AudioUrls getUrlList() { return url_list; }
    }
    public static final class AudioUrls {
        public final String main_url = "https://v16m.tiktokcdn.com/callback-fixture.aac";
        public String getMainUrl() { return main_url; }
    }
    public static final class PlaybackUrl {
        public final List<String> url_list = List.of("https://v16m.tiktokcdn.com/callback-fixture.mp4");
    }
    public static final class Holder {
        Work work = new Work(CURRENT_AID);
        Root root;
        Runnable duringOriginal;
        RuntimeException originalFailure;
        final Map<String, Integer> calls = new HashMap<>();
        public void onViewHolderSelected(int position) { original("selected"); }
        public void onPageSelected(int position) { original("page"); }
        public void onResumePlay(String source) { original("resume"); }
        public void onPausePlay(String source) { original("pause"); }
        public void onViewHolderUnselected(boolean stopped) { original("unselected"); }
        private void original(String name) {
            calls.merge(name, 1, Integer::sum);
            Runnable action = duringOriginal;
            duringOriginal = null;
            if (action != null) action.run();
            if (originalFailure != null) throw originalFailure;
        }
    }
    private static final class Root extends FrameLayout {
        Root(Activity activity) { super(activity); }
        @Override public boolean hasWindowFocus() { return true; }
        @Override public int getWindowVisibility() { return View.VISIBLE; }
    }
    private static final class ManualWorker extends ThreadPoolExecutor {
        final List<Runnable> tasks = new ArrayList<>();
        ManualWorker() { super(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()); }
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
        @Override public List<Runnable> shutdownNow() {
            List<Runnable> pending = new ArrayList<>(tasks); tasks.clear(); super.shutdownNow(); return pending;
        }
    }
    private static final class Fixture implements AutoCloseable, TikTokCaptionDisplayHook.Access {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final Holder holder = new Holder();
        final Harness harness = new Harness();
        final ManualWorker worker = new ManualWorker();
        final TikTokCaptionDisplayHook hook;
        boolean current = true, failAwemeRead;
        long position = 500;
        int requests;
        Fixture() throws Exception {
            replaceRoot();
            hook = new TikTokCaptionDisplayHook((Application) activity.getApplicationContext(),
                    new LocalOptions(Map.of(CaptionVisibilitySettings.FORCE, true, CaptionVisibilitySettings.HIDE, false)),
                    this, (source, token) -> {
                        token.check(); requests++;
                        assertEquals(CURRENT_AID, source.aid);
                        return new CaptionTranscript(List.of(new CaptionTranscript.Cue(0, 1000, "first"),
                                new CaptionTranscript.Cue(2000, 3000, "second")));
                    }, ignored -> { }, worker);
            hook.installCallbacks(harness.framework, selected(), pageSelected(), resumed(), paused(), unselected());
            hook.onActivityResumed(activity);
        }
        void replaceRoot() {
            Root replacement = new Root(activity);
            holder.root = replacement;
            activity.setContentView(replacement);
            replacement.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            replacement.layout(0, 0, 1080, 1920);
        }
        void select() throws Throwable { harness.invoke(selected(), holder, 0); ShadowLooper.idleMainLooper(); }
        void resume(String source) throws Throwable { harness.invoke(resumed(), holder, source); }
        void pause(String source) throws Throwable { harness.invoke(paused(), holder, source); }
        void complete() { ShadowLooper.idleMainLooper(); worker.runAll(); ShadowLooper.idleMainLooper(); }
        Object candidate() throws Exception { return field("candidate"); }
        TextView layer() throws Exception { return (TextView) field("layer"); }
        String text() throws Exception { return layer() == null ? "" : layer().getText().toString(); }
        private Object field(String name) throws Exception {
            Field field = TikTokCaptionDisplayHook.class.getDeclaredField(name); field.setAccessible(true); return field.get(hook);
        }
        @Override public Object aweme(Object owner) {
            if (failAwemeRead) throw new IllegalStateException("host metadata fixture");
            return ((Holder) owner).work;
        }
        @Override public View root(Object owner) { return ((Holder) owner).root; }
        @Override public boolean isCurrent(Object owner) { return current && owner == holder; }
        @Override public long positionMillis(Object owner) { return position; }
        @Override public boolean hasNativeCaptions(Object work) { return false; }
        @Override public boolean unblocked(Object owner) { return true; }
        @Override public void close() { hook.close(); activity.finish(); }
    }
    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                        (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });
        Object invoke(Method target, Object owner, Object... args) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, values) -> {
                        switch (method.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArgs": return Arrays.asList(args);
                            case "getArg": return args[(Integer) values[0]];
                            case "proceed": return original(target, owner, values == null ? args : (Object[]) values[0]);
                            default: return null;
                        }
                    });
            XposedInterface.Hooker hooker = interceptors.get(target);
            return hooker == null ? original(target, owner, args) : hooker.intercept(chain);
        }
        private static Object original(Method method, Object owner, Object[] args) throws Throwable {
            try { return method.invoke(owner, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
    private static Method selected() throws Exception { return Holder.class.getMethod("onViewHolderSelected", int.class); }
    private static Method pageSelected() throws Exception { return Holder.class.getMethod("onPageSelected", int.class); }
    private static Method resumed() throws Exception { return Holder.class.getMethod("onResumePlay", String.class); }
    private static Method paused() throws Exception { return Holder.class.getMethod("onPausePlay", String.class); }
    private static Method unselected() throws Exception { return Holder.class.getMethod("onViewHolderUnselected", boolean.class); }
}
