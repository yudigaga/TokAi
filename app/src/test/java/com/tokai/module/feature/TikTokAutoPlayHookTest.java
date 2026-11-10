package com.tokai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.tokai.module.config.ExtendedSettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import com.ss.android.ugc.aweme.feed.adapter.VideoViewCell;
import com.ss.android.ugc.aweme.feed.adapter.VideoViewCell.Dialogs;
import com.ss.android.ugc.aweme.feed.adapter.VideoViewCell.Manager;
import com.ss.android.ugc.aweme.feed.adapter.VideoViewCell.Pager;
import com.ss.android.ugc.aweme.feed.adapter.VideoViewCell.PagerContext;
import com.ss.android.ugc.aweme.feed.adapter.VideoViewCell.Panel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.tokai.module")
@LooperMode(LooperMode.Mode.PAUSED)
public final class TikTokAutoPlayHookTest {
    @Test public void automaticNextDisablesAnimationInBothNativeBranchesAndKeepsNativeEvents() throws Throwable {
        for (boolean direct : new boolean[]{false, true}) {
            try (Fixture f = new Fixture()) {
                f.panel.LLJZ.directBranch = direct;
                f.complete();
                assertEquals(1, f.panel.LLJZ.nextCalls);
                assertEquals(1, f.panel.LLJZ.nativeEvents);
                assertEquals(1, f.pager.coreCalls);
                assertEquals(1, f.pager.getCurrentItem());
                assertFalse("TikTok's native next path must honor the no-animation policy", f.pager.lastSmooth);
                assertTrue("The native force flag is independent of animation", f.pager.lastForce);
                assertEquals(1, f.pager.lastVelocity);

                f.pager.setCurrentItemWithDefaultVelocity(2);
                assertTrue("The automatic call's animation override cannot leak to later host calls", f.pager.lastSmooth);
            }
        }
    }

    @Test public void automaticPreviousDisablesAnimationAndRetainsNativeBoundaryChecks() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.pager.currentItem = 1;
            f.swipeDown();
            f.complete();
            assertEquals(1, f.panel.LLJZ.previousCalls);
            assertEquals(1, f.panel.LLJZ.nativeEvents);
            assertEquals(0, f.pager.getCurrentItem());
            assertFalse(f.pager.lastSmooth);
            assertTrue(f.pager.lastForce);
            assertEquals(1, f.pager.lastVelocity);
        }
        try (Fixture f = new Fixture()) {
            f.pager.currentItem = f.panel.LLJZ.count - 1;
            f.complete();
            assertEquals(1, f.panel.LLJZ.nextCalls);
            assertEquals(1, f.panel.LLJZ.nativeEvents);
            assertEquals("A native boundary rejection cannot become a direct pager selection", 0, f.pager.coreCalls);
        }
        try (Fixture f = new Fixture()) {
            f.swipeDown();
            f.complete();
            assertEquals(1, f.panel.LLJZ.previousCalls);
            assertEquals(1, f.panel.LLJZ.nativeEvents);
            assertEquals("The previous boundary remains owned by the native component", 0, f.pager.coreCalls);
        }
    }

    @Test public void manualLjjjRedirectUsesTheTikTokOverloadAndOtherNativeSelectionsKeepTheirFlags() throws Throwable {
        try (Fixture f = new Fixture()) {
            Method manual = Pager.class.getMethod("LJJJ", int.class, boolean.class);
            assertTrue("The real TikTok overload must be intercepted", f.harness.hooks.containsKey(manual));
            f.harness.invoke(manual, f.pager, 1, true);
            ShadowLooper.idleMainLooper();
            assertEquals(1, f.pager.getCurrentItem());
            assertFalse(f.pager.lastSmooth);
            assertFalse(f.pager.lastForce);
            assertEquals(0, f.pager.lastVelocity);

            f.pager.setCurrentItemWithDefaultVelocity(2);
            assertTrue("Unrelated host selection retains its own animation policy", f.pager.lastSmooth);
            assertTrue(f.pager.lastForce);
            assertEquals(1, f.pager.lastVelocity);
        }
    }

    private static final class FeedRoot extends FrameLayout {
        FeedRoot(Context context) { super(context); }
        @Override public boolean hasWindowFocus() { return true; }
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FeedRoot root = new FeedRoot(activity);
        final Panel panel = new Panel();
        final Pager pager = new Pager(activity);
        final VideoViewCell holder = new VideoViewCell();
        final Harness harness = new Harness();
        final AutoPlayHook hook;

        Fixture() throws Throwable {
            activity.setContentView(root);
            panel.LLJLLL = pager;
            panel.LLJZ = new PagerContext();
            panel.LLJZ.pager = pager;
            panel.current = holder;
            holder.u = root;
            holder.LLLZLZ = panel;
            holder.aid = "fixture-current";
            pager.dispatcher = (target, smooth, force, velocity) -> {
                try {
                    harness.invoke(Pager.class.getMethod("LJJJI", int.class, boolean.class, boolean.class, int.class),
                            pager, target, smooth, force, velocity);
                } catch (RuntimeException | Error failure) { throw failure; }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("tiktok-auto-slide-test", 0);
            preferences.edit().clear().putBoolean("auto_play", true).commit();
            hook = AutoPlayHook.install(harness.framework, report(), getClass().getClassLoader(), activity,
                    ExtendedSettings.readFrom(preferences), new LocalOptions(Collections.emptyMap()), ignored -> { });
            hook.onActivityResumed(activity);
            harness.invoke(VideoViewCell.class.getMethod("onPageSelected", int.class), holder, 0);
            ShadowLooper.idleMainLooper();
        }

        void complete() throws Throwable {
            harness.invoke(VideoViewCell.class.getMethod("onPlayCompleted", String.class), holder, holder.aid);
            ShadowLooper.idleMainLooper();
        }

        void swipeDown() throws Throwable {
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(0, 100, action, 100, action == MotionEvent.ACTION_DOWN ? 100 : 600, 0);
                try { harness.invoke(Pager.class.getMethod("onTouchEvent", MotionEvent.class), pager, event); }
                finally { event.recycle(); }
            }
        }

        @Override public void close() {
            hook.close();
            ShadowLooper.idleMainLooper();
            activity.finish();
        }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>();
        add(rows, "feed_holder_page_selected", VideoViewCell.class.getMethod("onPageSelected", int.class));
        add(rows, "feed_holder_resume", VideoViewCell.class.getMethod("onResume"));
        add(rows, "feed_holder_pause", VideoViewCell.class.getMethod("onPause"));
        add(rows, "feed_aweme_accessor", VideoViewCell.class.getMethod("getAweme"));
        addType(rows, "feed_holder_main_root", VideoViewCell.class);
        addType(rows, "adv_gesture_item_params", VideoViewCell.class);
        addType(rows, "auto_slide_panel_context", Panel.class);
        add(rows, "auto_slide_current_holder", Panel.class.getMethod("LJLJLLL"));
        add(rows, "adv_player_move_next", PagerContext.class.getMethod("CJ"));
        add(rows, "adv_player_move_previous", PagerContext.class.getMethod("e60"));
        add(rows, "auto_slide_pager_touch", Pager.class.getMethod("onTouchEvent", MotionEvent.class));
        add(rows, "auto_slide_scroll_state", Pager.class.getMethod("getScrollState"));
        add(rows, "auto_slide_comments_showing", Dialogs.class.getMethod("manager"));
        add(rows, "auto_slide_comment_fragment", Manager.class.getMethod("findFragmentByTag", String.class));
        add(rows, "auto_slide_completed", VideoViewCell.class.getMethod("onPlayCompleted", String.class));
        add(rows, "component_player_playing", VideoViewCell.class.getMethod("onResumePlay", String.class));
        add(rows, "component_player_pause", VideoViewCell.class.getMethod("onPausePlay", String.class));
        return new HostScanReport("tiktok-autoplay-fixture", rows, false);
    }

    private static void add(List<RuleScanResult> rows, String id, Method target) {
        rows.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(target.getDeclaringClass()),
                target.getName(), HostTargets.descriptor(target)))));
    }

    private static void addType(List<RuleScanResult> rows, String id, Class<?> target) {
        rows.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(target), "", ""))));
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, call, args) -> {
                    if (!call.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                hooks.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                        (handle, action, ignored) -> {
                                            if (action.getName().equals("unhook")) hooks.remove(target);
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object owner, Object... args) throws Throwable {
            XposedInterface.Hooker hook = hooks.get(target);
            if (hook == null) return reflect(target, owner, args);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, call, values) -> {
                        switch (call.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArg": return args[(Integer) values[0]];
                            case "getArgs": return Arrays.asList(args);
                            case "proceed": return reflect(target, owner, values == null ? args : (Object[]) values[0]);
                            default: return null;
                        }
                    });
            return hook.intercept(chain);
        }

        private Object reflect(Method target, Object owner, Object[] args) throws Throwable {
            try { return target.invoke(owner, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
