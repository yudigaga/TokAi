package com.tokai.module.feature;

import android.view.View;
import android.widget.FrameLayout;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostFeatureSupport;
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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Replays the common CLA renderer; original/translated states share native hidden-state cleanup. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class TikTokPlaybackOptionsHookTest {
    private static final String CAPTIONS = "hide_native_captions";
    private static final String DATA = "disable_data_saver";
    private static final String CAPTION_RULE = "tiktok_caption_display_gate";
    private static final String DATA_RULE = "tiktok_data_saver_enabled";

    public enum CaptionState { HIDDEN, EXPANDED }
    public enum CaptionKind { NONE, TAP_TO_EDIT, EDIT_CTA, EDIT_CTA_HIGHLIGHTED }
    public enum MissingHiddenState { COLLAPSED, EXPANDED }

    public static final class Renderer {
        final FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        CaptionState state = CaptionState.EXPANDED;
        String aid, text;
        CaptionKind kind;
        int calls, cleanups;
        public void render(String aid, CaptionState next, String text, CaptionKind kind) {
            calls++;
            this.aid = aid; this.text = text; this.kind = kind;
            if (state != CaptionState.HIDDEN && next == CaptionState.HIDDEN) cleanups++;
            state = next;
            root.setVisibility(next == CaptionState.HIDDEN ? View.GONE : View.VISIBLE);
        }
    }

    public static final class BadRenderer {
        public void notAnEnum(String aid, Object state, String text, CaptionKind kind) { }
        public boolean wrongReturn(String aid, CaptionState state, String text, CaptionKind kind) { return true; }
        public void noHidden(String aid, MissingHiddenState state, String text, CaptionKind kind) { }
    }

    public static final class DataSaver {
        static int calls;
        static boolean nativeEnabled;
        public static boolean enabled() { calls++; return nativeEnabled; }
    }

    public static final class OtherGate {
        int calls;
        public boolean splash() { calls++; return true; }
    }

    @Before public void resetNativeDataSaver() { DataSaver.calls = 0; DataSaver.nativeEnabled = true; }

    @Test public void defaultOffForwardsOriginalTranslatedAndHiddenRendersAndNativeDataValues() throws Throwable {
        Harness harness = new Harness();
        Renderer renderer = new Renderer();
        try (HostBehaviorHooks hooks = install(harness, true, Map.of(), caption())) {
            assertTrue(hooks.supports(CAPTIONS)); assertTrue(hooks.supports(DATA));
            for (CaptionKind kind : List.of(CaptionKind.NONE, CaptionKind.EDIT_CTA)) {
                render(harness, renderer, CaptionState.EXPANDED, kind);
                assertEquals(CaptionState.EXPANDED, renderer.state);
                assertSame(kind, renderer.kind);
                assertEquals(View.VISIBLE, renderer.root.getVisibility());
            }
            render(harness, renderer, CaptionState.HIDDEN, CaptionKind.NONE);
            assertEquals(CaptionState.HIDDEN, renderer.state);
            assertEquals(1, renderer.cleanups);
            assertTrue((Boolean) harness.invoke(data(), null));
            DataSaver.nativeEnabled = false;
            assertFalse((Boolean) harness.invoke(data(), null));
            assertEquals(2, DataSaver.calls);
        }
    }

    @Test public void enabledCaptionsRewriteBothKindsToHiddenAndStillRunNativeCleanupOnce() throws Throwable {
        Harness harness = new Harness();
        try (HostBehaviorHooks hooks = install(harness, true, Map.of(CAPTIONS, true, DATA, true), caption())) {
            assertTrue(hooks.supports(CAPTIONS)); assertTrue(hooks.supports(DATA));
            for (CaptionKind kind : List.of(CaptionKind.NONE, CaptionKind.EDIT_CTA)) {
                Renderer renderer = new Renderer();
                String aid = new String("work-id"), text = new String(kind == CaptionKind.NONE ? "original-native-caption" : "translated-native-caption");
                harness.invoke(caption(), renderer, aid, CaptionState.EXPANDED, text, kind);
                assertEquals(CaptionState.HIDDEN, renderer.state);
                assertEquals(View.GONE, renderer.root.getVisibility());
                assertEquals(1, renderer.calls); assertEquals(1, renderer.cleanups);
                assertSame(aid, renderer.aid); assertSame(text, renderer.text); assertSame(kind, renderer.kind);
            }
            assertFalse((Boolean) harness.invoke(data(), null));
            assertEquals(0, DataSaver.calls);
            assertTrue(DataSaver.nativeEnabled);
        }
    }

    @Test public void anAlreadyHiddenRenderStillProceedsWithoutDuplicateCleanup() throws Throwable {
        Harness harness = new Harness();
        Renderer renderer = new Renderer();
        renderer.state = CaptionState.HIDDEN;
        try (HostBehaviorHooks hooks = install(harness, true, Map.of(CAPTIONS, true), caption())) {
            render(harness, renderer, CaptionState.HIDDEN, CaptionKind.EDIT_CTA);
            assertEquals(1, renderer.calls); assertEquals(0, renderer.cleanups);
            assertEquals(CaptionState.HIDDEN, renderer.state);
            assertEquals(View.GONE, renderer.root.getVisibility());
        }
    }

    @Test public void theTwoOptionsCanBeEnabledIndependently() throws Throwable {
        Harness harness = new Harness();
        Renderer renderer = new Renderer();
        try (HostBehaviorHooks hooks = install(harness, true, Map.of(CAPTIONS, true), caption())) {
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.NONE);
            assertEquals(CaptionState.HIDDEN, renderer.state);
            assertTrue((Boolean) harness.invoke(data(), null));
            hooks.setOptions(new LocalOptions(Map.of(DATA, true)));
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.EDIT_CTA);
            assertEquals(CaptionState.EXPANDED, renderer.state);
            assertFalse((Boolean) harness.invoke(data(), null));
            assertEquals(2, renderer.calls); assertEquals(1, DataSaver.calls);
        }
    }

    @Test public void dynamicDisableRestoresSubsequentNativeRenderingAndTheLatestDataDecision() throws Throwable {
        Harness harness = new Harness();
        Renderer renderer = new Renderer();
        try (HostBehaviorHooks hooks = install(harness, true, Map.of(CAPTIONS, true, DATA, true), caption())) {
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.NONE);
            assertEquals(CaptionState.HIDDEN, renderer.state);
            assertFalse((Boolean) harness.invoke(data(), null));
            hooks.setOptions(new LocalOptions(Map.of()));
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.EDIT_CTA);
            assertEquals(CaptionState.EXPANDED, renderer.state);
            assertEquals(View.VISIBLE, renderer.root.getVisibility());
            assertTrue((Boolean) harness.invoke(data(), null));
            DataSaver.nativeEnabled = false;
            hooks.setOptions(new LocalOptions(Map.of(CAPTIONS, true, DATA, true)));
            hooks.setOptions(new LocalOptions(Map.of()));
            render(harness, renderer, CaptionState.HIDDEN, CaptionKind.NONE);
            assertEquals(CaptionState.HIDDEN, renderer.state);
            assertFalse((Boolean) harness.invoke(data(), null));
        }
    }

    @Test public void aCaptionRegistrationFailureLeavesDataAndExistingSplashProtectionActive() throws Throwable {
        Harness harness = new Harness(); harness.failTarget = caption();
        Renderer renderer = new Renderer(); OtherGate other = new OtherGate();
        try (HostBehaviorHooks hooks = install(harness, true,
                Map.of(CAPTIONS, true, DATA, true, "remove_splash_ads", true), caption())) {
            assertFalse(hooks.supports(CAPTIONS)); assertFalse(hooks.installationFailure(CAPTIONS).isEmpty());
            assertTrue(hooks.supports(DATA)); assertTrue(hooks.supports("remove_splash_ads"));
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.NONE);
            assertEquals(CaptionState.EXPANDED, renderer.state);
            assertFalse((Boolean) harness.invoke(data(), null));
            assertFalse((Boolean) harness.invoke(splash(), other));
            assertEquals(1, renderer.calls); assertEquals(0, DataSaver.calls); assertEquals(0, other.calls);
        }
    }

    @Test public void aDataRegistrationFailureLeavesCaptionsAndExistingSplashProtectionActive() throws Throwable {
        Harness harness = new Harness(); harness.failTarget = data();
        Renderer renderer = new Renderer(); OtherGate other = new OtherGate();
        try (HostBehaviorHooks hooks = install(harness, true,
                Map.of(CAPTIONS, true, DATA, true, "remove_splash_ads", true), caption())) {
            assertFalse(hooks.supports(DATA)); assertFalse(hooks.installationFailure(DATA).isEmpty());
            assertTrue(hooks.supports(CAPTIONS)); assertTrue(hooks.supports("remove_splash_ads"));
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.EDIT_CTA);
            assertEquals(CaptionState.HIDDEN, renderer.state);
            assertTrue((Boolean) harness.invoke(data(), null));
            assertFalse((Boolean) harness.invoke(splash(), other));
            assertEquals(1, renderer.calls); assertEquals(1, DataSaver.calls); assertEquals(0, other.calls);
        }
    }

    @Test public void anUndeclaredCaptionTargetDoesNotDisableTheDataOption() throws Throwable {
        Harness harness = new Harness(); Renderer renderer = new Renderer();
        try (HostBehaviorHooks hooks = install(harness, true, Map.of(CAPTIONS, true, DATA, true), null)) {
            assertFalse(hooks.supports(CAPTIONS));
            assertTrue(HostFeatureSupport.isUnavailable(hooks.installationFailure(CAPTIONS)));
            assertTrue(hooks.supports(DATA));
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.NONE);
            assertEquals(CaptionState.EXPANDED, renderer.state);
            assertFalse((Boolean) harness.invoke(data(), null));
        }
    }

    @Test public void incompatibleRendererShapesOrAnEnumWithoutHiddenRejectOnlyTheCaptionGroup() throws Exception {
        List<Method> invalid = List.of(
                BadRenderer.class.getMethod("notAnEnum", String.class, Object.class, String.class, CaptionKind.class),
                BadRenderer.class.getMethod("wrongReturn", String.class, CaptionState.class, String.class, CaptionKind.class),
                BadRenderer.class.getMethod("noHidden", String.class, MissingHiddenState.class, String.class, CaptionKind.class));
        for (Method method : invalid) {
            Harness harness = new Harness();
            try (HostBehaviorHooks hooks = install(harness, true, Map.of(CAPTIONS, true, DATA, true), method)) {
                assertFalse(method.toString(), hooks.supports(CAPTIONS));
                assertFalse(hooks.installationFailure(CAPTIONS).isEmpty());
                assertFalse(harness.interceptors.containsKey(method));
                assertTrue(hooks.supports(DATA));
            }
        }
    }

    @Test public void closeUnhooksBothOptionsAndRestoresSubsequentNativeCalls() throws Throwable {
        Harness harness = new Harness(); Renderer renderer = new Renderer();
        HostBehaviorHooks hooks = install(harness, true, Map.of(CAPTIONS, true, DATA, true), caption());
        assertTrue(hooks.supports(CAPTIONS)); assertTrue(hooks.supports(DATA));
        hooks.close(); hooks.close();
        assertFalse(hooks.supports(CAPTIONS)); assertFalse(hooks.supports(DATA));
        assertTrue(harness.interceptors.isEmpty());
        render(harness, renderer, CaptionState.EXPANDED, CaptionKind.EDIT_CTA);
        assertEquals(CaptionState.EXPANDED, renderer.state);
        assertTrue((Boolean) harness.invoke(data(), null));
        assertEquals(1, renderer.calls); assertEquals(1, DataSaver.calls);
    }

    @Test public void aNonTikTokProfileDoesNotInstallTheseTikTokOnlyTargets() throws Throwable {
        Harness harness = new Harness(); Renderer renderer = new Renderer();
        try (HostBehaviorHooks hooks = install(harness, false, Map.of(CAPTIONS, true, DATA, true), caption())) {
            assertFalse(hooks.supports(CAPTIONS)); assertFalse(hooks.supports(DATA));
            assertFalse(harness.interceptors.containsKey(caption())); assertFalse(harness.interceptors.containsKey(data()));
            render(harness, renderer, CaptionState.EXPANDED, CaptionKind.NONE);
            assertEquals(CaptionState.EXPANDED, renderer.state);
            assertTrue((Boolean) harness.invoke(data(), null));
        }
    }

    private HostBehaviorHooks install(Harness harness, boolean tikTok, Map<String, ?> options,
                                      Method captionTarget) throws Exception {
        List<RuleScanResult> rows = new ArrayList<>();
        if (tikTok) rows.add(result("tiktok_feed_materialized", caption()));
        if (captionTarget != null) rows.add(result(CAPTION_RULE, captionTarget));
        rows.add(result(DATA_RULE, data())); rows.add(result("adv_ui_splash_gate", splash()));
        return HostBehaviorHooks.install(harness.framework, new HostScanReport("fixture", rows, false),
                getClass().getClassLoader(), null, new LocalOptions(options), ignored -> { });
    }

    private static void render(Harness harness, Renderer renderer, CaptionState state, CaptionKind kind) throws Throwable {
        harness.invoke(caption(), renderer, "work-id", state, "native-caption-text", kind);
    }
    private static RuleScanResult result(String id, Method target) {
        return new RuleScanResult(id, "interface", List.of(new HostCandidate("base.apk", "classes.dex",
                HostTargets.descriptor(target.getDeclaringClass()), target.getName(), HostTargets.descriptor(target))));
    }
    private static Method caption() throws NoSuchMethodException {
        return Renderer.class.getMethod("render", String.class, CaptionState.class, String.class, CaptionKind.class);
    }
    private static Method data() throws NoSuchMethodException { return DataSaver.class.getMethod("enabled"); }
    private static Method splash() throws NoSuchMethodException { return OtherGate.class.getMethod("splash"); }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        Method failTarget;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                if (target.equals(failTarget)) throw new IllegalStateException("Rejected fixture registration");
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                        (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
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
            XposedInterface.Hooker hooker = interceptors.get(target);
            return hooker == null ? target.invoke(owner, arguments) : hooker.intercept(chain);
        }
    }
}
