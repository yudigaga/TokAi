package com.tokai.module.feature;

import android.os.Looper;
import android.view.View;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class TikTokHostBehaviorHooksTest {
    public static final class PublishFactory {
        final View button = new View(RuntimeEnvironment.getApplication());
        int calls;
        boolean fail;
        public View create(Object item, Object config) {
            calls++;
            if (fail) throw new IllegalStateException("native factory failure");
            return button;
        }
    }
    public static final class Token {
        public final String LIZ;
        Token(String source) { LIZ = source; }
    }
    public static final class InsertResult {
        public final int position, status;
        public final String message;
        public InsertResult(int position, int status, String message) {
            this.position = position; this.status = status; this.message = message;
        }
    }
    public static final class DataLayer {
        final List<Object> items = new ArrayList<>(List.of("a", "b", "c"));
        int calls, notifications;
        boolean fail;
        public int count() { return items.size(); }
        public InsertResult single(Object work, int position, Token token) {
            return batch(List.of(work), position, token);
        }
        public InsertResult batch(List<?> work, int position, Token token) {
            calls++;
            if (fail) throw new IllegalStateException("native insertion failure");
            if (Looper.myLooper() != Looper.getMainLooper()) return new InsertResult(position, 103, "not main");
            if (position < 0 || position > items.size()) return new InsertResult(position, 104, "bounds");
            items.addAll(position, work);
            notifications++;
            return new InsertResult(position, 0, "");
        }
    }
    public static final class LegacyPanel {
        final List<Object> items = new ArrayList<>(List.of("a", "b", "c"));
        int calls, notifications;
        public List<Object> getItems() { return items; }
        public void insert(int position, String source, List<?> work) {
            calls++;
            items.addAll(position, work);
            notifications++;
        }
    }
    public static final class LegacyCaller {
        public void dispatch(Object work, int position, boolean modern) { }
    }

    @Test public void bothExperimentalInsertionRoutesAreBlockedBeforeMutation() throws Throwable {
        Harness h = new Harness(); DataLayer modern = new DataLayer(); LegacyPanel legacy = new LegacyPanel();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            assertEquals(100, ((InsertResult) h.invoke(single(), modern, "x", 1,
                    new Token("middle_insert_when_video_lagging"))).status);
            h.invoke(legacy(), legacy, 1, "middle_insert_when_video_lagging", List.of("x"));
            assertEquals(List.of("a", "b", "c"), modern.items);
            assertEquals(List.of("a", "b", "c"), legacy.items);
            assertEquals(0, modern.calls); assertEquals(0, legacy.calls);
            assertEquals(0, modern.notifications); assertEquals(0, legacy.notifications);
            hooks.setOptions(new LocalOptions(Map.of()));
            h.invoke(legacy(), legacy, 1, "middle_insert_when_video_lagging", List.of("x"));
            assertEquals(List.of("a", "x", "b", "c"), legacy.items);
        }
    }

    @Test public void legacyInitialTailUnknownAndRefreshOperationsStayNative() throws Throwable {
        Harness h = new Harness(); LegacyPanel legacy = new LegacyPanel();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            h.invoke(legacy(), legacy, 1, "auto_refresh", List.of("refresh"));
            h.invoke(legacy(), legacy, 1, "unknown", List.of("unknown"));
            h.invoke(legacy(), legacy, legacy.items.size(), "paid_ads_video", List.of("tail"));
            legacy.items.clear();
            h.invoke(legacy(), legacy, 0, "paid_ads_video", List.of("initial"));
            assertEquals(List.of("initial"), legacy.items);
            assertEquals(4, legacy.calls); assertEquals(4, legacy.notifications);
        }
    }

    @Test public void failedLegacyRegistrationRevokesTheModernRouteToo() throws Throwable {
        Harness h = new Harness(); h.rejected = legacy();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            assertFalse(hooks.supports("prevent_insert_video"));
            assertFalse(h.hooks.containsKey(single()));
            assertFalse(h.hooks.containsKey(batch()));
        }
    }

    @Test public void legacyInlineCallerMustBeDeoptimizedBeforeAnyInsertionHookIsAdvertised() throws Throwable {
        Harness h = new Harness(); h.deoptimizationAllowed = false;
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            assertFalse(hooks.supports("prevent_insert_video"));
            assertFalse(h.hooks.containsKey(single()));
            assertFalse(h.hooks.containsKey(legacy()));
            assertEquals(List.of(legacyCaller()), h.deoptimized);
            assertTrue(hooks.supports("hide_camera_plus"));
        }
    }

    @Test public void publishFactoryStillRunsAndOnlyItsReturnedViewIsHidden() throws Throwable {
        Harness h = new Harness(); PublishFactory f = new PublishFactory();
        View unrelated = new View(RuntimeEnvironment.getApplication());
        try (HostBehaviorHooks hooks = install(h, Map.of("hide_camera_plus", true))) {
            assertTrue(hooks.supports("hide_camera_plus"));
            assertSame(f.button, h.invoke(factory(), f, null, null));
            assertEquals(1, f.calls);
            assertEquals(View.INVISIBLE, f.button.getVisibility());
            assertEquals(View.VISIBLE, unrelated.getVisibility());
        }
        assertEquals(View.VISIBLE, f.button.getVisibility());
    }

    @Test public void publishViewSupportsLiveEnableAndRestoresTheLatestNativeVisibility() throws Throwable {
        Harness h = new Harness(); PublishFactory f = new PublishFactory();
        f.button.setVisibility(View.GONE);
        try (HostBehaviorHooks hooks = install(h, Map.of())) {
            h.invoke(factory(), f, null, null);
            assertEquals(View.GONE, f.button.getVisibility());
            hooks.setOptions(new LocalOptions(Map.of("hide_camera_plus", true)));
            assertEquals(View.INVISIBLE, f.button.getVisibility());
            f.button.setVisibility(View.VISIBLE);
            f.button.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.INVISIBLE, f.button.getVisibility());
            hooks.setOptions(new LocalOptions(Map.of()));
            assertEquals(View.VISIBLE, f.button.getVisibility());
        }
    }

    @Test public void originalFactoryExceptionPropagatesOnce() throws Throwable {
        Harness h = new Harness(); PublishFactory f = new PublishFactory(); f.fail = true;
        try (HostBehaviorHooks hooks = install(h, Map.of("hide_camera_plus", true))) {
            try { h.invoke(factory(), f, null, null); fail("expected native failure"); }
            catch (IllegalStateException expected) { assertEquals("native factory failure", expected.getMessage()); }
            assertEquals(1, f.calls);
            assertTrue(hooks.supports("hide_camera_plus"));
        }
    }

    @Test public void knownAutomaticSingleAndBatchInsertionsAreRejectedBeforeMutation() throws Throwable {
        Harness h = new Harness(); DataLayer d = new DataLayer();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            assertTrue(hooks.supports("prevent_insert_video"));
            InsertResult a = (InsertResult) h.invoke(single(), d, "x", 1, new Token("paid_ads_video"));
            InsertResult b = (InsertResult) h.invoke(batch(), d, List.of("x", "y"), 2,
                    new Token("real_time_lynx_card"));
            assertEquals(100, a.status); assertEquals(1, a.position);
            assertEquals(100, b.status); assertEquals(2, b.position);
            assertEquals(List.of("a", "b", "c"), d.items);
            assertEquals(0, d.calls); assertEquals(0, d.notifications);
        }
    }

    @Test public void everyProvenAutomaticSourceIsCoveredWithoutMutatingItsToken() throws Throwable {
        Harness h = new Harness(); DataLayer d = new DataLayer();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            for (String source : List.of("middle_insert_when_video_lagging", "real_time_lynx_card",
                    "paid_ads_video", "pgc_drama_dnu_card", "drama_nuj_video_card",
                    "pro_us_discover_insert", "diff_type_minis_instant_play_preview_insert")) {
                Token token = new Token(source);
                assertEquals(source, 100, ((InsertResult) h.invoke(single(), d, "x", 1, token)).status);
                assertEquals(source, token.LIZ);
            }
            assertEquals(0, d.calls);
        }
    }

    @Test public void disabledUnknownAndRefreshSourcesKeepNativeInsertionAndNotifications() throws Throwable {
        Harness h = new Harness(); DataLayer d = new DataLayer();
        try (HostBehaviorHooks hooks = install(h, Map.of())) {
            assertEquals(0, ((InsertResult) h.invoke(single(), d, "x", 1, new Token("paid_ads_video"))).status);
            hooks.setOptions(new LocalOptions(Map.of("prevent_insert_video", true)));
            for (String source : List.of("auto_refresh", "user_selected_video", "future_unknown_source", "")) {
                assertEquals(0, ((InsertResult) h.invoke(single(), d, source, 1, new Token(source))).status);
            }
            assertEquals(5, d.calls); assertEquals(5, d.notifications);
            hooks.setOptions(new LocalOptions(Map.of()));
            assertEquals(0, ((InsertResult) h.invoke(single(), d, "x", 1, new Token("paid_ads_video"))).status);
        }
    }

    @Test public void initialLoadingTailAppendAndInvalidIndicesRetainNativeSemantics() throws Throwable {
        Harness h = new Harness(); DataLayer d = new DataLayer();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            Token t = new Token("paid_ads_video");
            assertEquals(0, ((InsertResult) h.invoke(single(), d, "tail", d.count(), t)).status);
            assertEquals(104, ((InsertResult) h.invoke(single(), d, "invalid", -1, t)).status);
            assertEquals(104, ((InsertResult) h.invoke(single(), d, "invalid", d.count() + 1, t)).status);
            d.items.clear();
            assertEquals(0, ((InsertResult) h.invoke(batch(), d, List.of("initial"), 0, t)).status);
            assertEquals(List.of("initial"), d.items);
            assertEquals(2, d.notifications);
        }
    }

    @Test public void nativeFailureIsNotSwallowedOrExecutedTwice() throws Throwable {
        Harness h = new Harness(); DataLayer d = new DataLayer(); d.fail = true;
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            try { h.invoke(single(), d, "x", 1, new Token("auto_refresh")); fail("expected native failure"); }
            catch (IllegalStateException expected) { assertEquals("native insertion failure", expected.getMessage()); }
            assertEquals(1, d.calls);
            assertTrue(hooks.supports("prevent_insert_video"));
        }
    }

    @Test public void offMainThreadRetainsNativeThreadFailureInsteadOfFabricatingASuccess() throws Throwable {
        Harness h = new Harness(); DataLayer d = new DataLayer();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true))) {
            final Throwable[] error = new Throwable[1]; final InsertResult[] result = new InsertResult[1];
            Thread thread = new Thread(() -> {
                try { result[0] = (InsertResult) h.invoke(single(), d, "x", 1, new Token("paid_ads_video")); }
                catch (Throwable failure) { error[0] = failure; }
            });
            thread.start(); thread.join();
            assertNull(error[0]); assertEquals(103, result[0].status);
            assertEquals(List.of("a", "b", "c"), d.items);
        }
    }

    @Test public void failedBatchRegistrationRollsBackOnlyTheInsertionGroup() throws Throwable {
        Harness h = new Harness(); h.rejected = batch();
        try (HostBehaviorHooks hooks = install(h, Map.of("prevent_insert_video", true, "hide_camera_plus", true))) {
            assertFalse(hooks.supports("prevent_insert_video"));
            assertFalse(h.hooks.containsKey(single()));
            assertTrue(hooks.supports("hide_camera_plus"));
            PublishFactory f = new PublishFactory(); h.invoke(factory(), f, null, null);
            assertEquals(View.INVISIBLE, f.button.getVisibility());
        }
        assertTrue(h.hooks.isEmpty());
    }

    private HostBehaviorHooks install(Harness harness, Map<String, ?> options) throws Exception {
        List<RuleScanResult> rows = List.of(row("tiktok_feed_materialized", single()),
                row("tiktok_publish_button_factory", factory()), row("tiktok_feed_insert_single", single()),
                row("tiktok_feed_insert_batch", batch()), row("tiktok_feed_insert_count", DataLayer.class.getMethod("count")),
                row("tiktok_feed_insert_legacy", legacy()), row("tiktok_feed_insert_items", LegacyPanel.class.getMethod("getItems")),
                row("tiktok_feed_insert_legacy_dispatch", legacyCaller()));
        return HostBehaviorHooks.install(harness.api, new HostScanReport("fixture", rows, false),
                getClass().getClassLoader(), null, new LocalOptions(options), ignored -> { });
    }
    private static RuleScanResult row(String id, Method m) {
        return new RuleScanResult(id, "interface", List.of(new HostCandidate("base.apk", "classes.dex",
                HostTargets.descriptor(m.getDeclaringClass()), m.getName(), HostTargets.descriptor(m))));
    }
    private static Method factory() throws Exception { return PublishFactory.class.getMethod("create", Object.class, Object.class); }
    private static Method single() throws Exception { return DataLayer.class.getMethod("single", Object.class, int.class, Token.class); }
    private static Method batch() throws Exception { return DataLayer.class.getMethod("batch", List.class, int.class, Token.class); }
    private static Method legacy() throws Exception { return LegacyPanel.class.getMethod("insert", int.class, String.class, List.class); }
    private static Method legacyCaller() throws Exception { return LegacyCaller.class.getMethod("dispatch", Object.class, int.class, boolean.class); }
    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        Method rejected;
        boolean deoptimizationAllowed = true;
        final List<Method> deoptimized = new ArrayList<>();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (method.getName().equals("deoptimize")) {
                        deoptimized.add((Method) args[0]);
                        return deoptimizationAllowed;
                    }
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, action, inputs) -> {
                                if (!action.getName().equals("intercept")) return builder;
                                if (target.equals(rejected)) throw new IllegalStateException("rejected registration");
                                hooks.put(target, (XposedInterface.Hooker) inputs[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                        (handle, call, values) -> { if (call.getName().equals("unhook")) hooks.remove(target); return null; });
                            });
                });
        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArgs": return Arrays.asList(arguments);
                            case "getArg": return arguments[(Integer) args[0]];
                            case "proceed":
                                try { return target.invoke(owner, args == null ? arguments : (Object[]) args[0]); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            default: return null;
                        }
                    });
            if (hooks.containsKey(target)) return hooks.get(target).intercept(chain);
            try { return target.invoke(owner, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
