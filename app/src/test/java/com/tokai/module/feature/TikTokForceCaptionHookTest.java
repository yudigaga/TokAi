package com.tokai.module.feature;

import com.tokai.module.config.CaptionVisibilitySettings;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Model replay of the verified native call chain; this does not simulate ART inlining or a device. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class TikTokForceCaptionHookTest {
    private static final String FORCE = CaptionVisibilitySettings.FORCE;
    private static final String HIDE = CaptionVisibilitySettings.HIDE;
    private static final String SCOPE = "tiktok_caption_base_state_scope";
    private static final String SETTING = "tiktok_caption_user_setting_gate";
    private static final String POLICY = "tiktok_caption_display_policy_caller";
    private static final String VIDEO = "tiktok_caption_scope_video_caller";
    private static final String SETTINGS = "tiktok_caption_scope_setting_caller";
    private static final String DUPLICATE = "tiktok_caption_scope_duplicate_caller";
    private static final String OVERRIDE = "tiktok_caption_scope_override_caller";

    public enum State { HIDDEN, EXPANDED }
    public enum Kind { NONE, EDIT_CTA }
    interface Action { void run() throws Throwable; }

    public static final class Work {
        Harness harness;
        String aid = "current-work", text = "original cue";
        boolean preference, content = true, eligible = true, pendingRemoval, lite, burnedIn, foreignOriginal, stale;
        int preferenceReads;
        RuntimeException preferenceFailure;
    }

    public static final class NativePolicy {
        public static boolean settingOn(Work work) {
            if (work == null) return false;
            work.preferenceReads++;
            if (work.preferenceFailure != null) throw work.preferenceFailure;
            return work.preference;
        }
        public static boolean displayPolicy(Work work, boolean includeOverride) throws Throwable {
            return !work.lite && (Boolean) work.harness.invoke(setting(), null, work)
                    && !work.foreignOriginal && !work.burnedIn;
        }
    }

    public static final class Renderer {
        State state = State.HIDDEN;
        Kind kind = Kind.NONE;
        String aid, text = "";
        int calls;
        boolean visible;
        public void render(String aid, State state, String text, Kind kind) {
            calls++;
            this.aid = aid; this.state = state; this.text = text; this.kind = kind;
            visible = state == State.EXPANDED && text != null && !text.isEmpty();
        }
    }

    public static final class NativeController {
        final Harness harness;
        final Work work;
        final Renderer renderer = new Renderer();
        Action beforePreference;
        RuntimeException failure;
        int scopes, playerEnables, languageApplications;
        NativeController(Harness harness, Work work) { this.harness = harness; this.work = work; work.harness = harness; }
        public void evaluate(boolean refresh) throws Throwable {
            scopes++;
            if (beforePreference != null) beforePreference.run();
            if (failure != null) throw failure;
            if (!work.content || !work.eligible || work.pendingRemoval) {
                cue(work.aid, "", State.HIDDEN);
                return;
            }
            boolean expanded = NativePolicy.displayPolicy(work, true);
            if (expanded) { playerEnables++; languageApplications++; }
            cue(work.aid, work.text, expanded && !work.stale ? State.EXPANDED : State.HIDDEN);
        }
        void cue(String aid, String text, State state) throws Throwable {
            if (work.aid.equals(aid)) harness.invoke(renderer(), renderer, aid, state, text, Kind.NONE);
        }
    }

    public static final class VideoCaller { public Object invokeSuspend(Object value) { return value; } }
    public static final class SettingCaller { public Object emit(Object value, Object continuation) { return value; } }
    public static final class DuplicateCaller { public Object emit(Object value, Object continuation) { return value; } }
    public static final class OverrideCaller { public Object emit(Object value, Object continuation) { return value; } }
    public static final class DataSaver { public static boolean enabled() { return true; } }
    public static final class InvalidScope { public boolean evaluate(boolean refresh) { return true; } }

    @Test public void existingOriginalAndTranslatedCuesReachNativePlayerLanguageAndRendererWhenPreferenceIsOff() throws Throwable {
        for (String text : List.of("original cue", "translated cue")) {
            Harness h = new Harness(); Work work = new Work(); work.text = text;
            NativeController controller = new NativeController(h, work);
            try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
                assertTrue(hooks.supports(FORCE));
                h.invoke(scope(), controller, true);
                assertEquals(1, controller.playerEnables);
                assertEquals(1, controller.languageApplications);
                assertEquals(State.EXPANDED, controller.renderer.state);
                assertEquals(text, controller.renderer.text);
                assertTrue(controller.renderer.visible);
                assertEquals(1, work.preferenceReads);
                assertFalse(work.preference);
            }
        }
    }

    @Test public void defaultOffPreservesNativePreferenceAndCanBeEnabledAndDisabledAtRuntime() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        try (HostBehaviorHooks hooks = install(h, true, Map.of())) {
            assertTrue(hooks.supports(FORCE));
            h.invoke(scope(), c, true); assertFalse(c.renderer.visible);
            hooks.setOptions(new LocalOptions(Map.of(FORCE, true)));
            h.invoke(scope(), c, true); assertTrue(c.renderer.visible);
            hooks.setOptions(new LocalOptions(Map.of()));
            h.invoke(scope(), c, true); assertFalse(c.renderer.visible);
            assertEquals(1, c.playerEnables);
            assertEquals(3, work.preferenceReads);
        }
    }

    @Test public void noDataEligibilityRemovalLiteBurnedInAndLanguageGatesRemainNative() throws Throwable {
        for (String gate : List.of("content", "eligible", "pendingRemoval", "lite", "burnedIn", "foreignOriginal", "stale")) {
            Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
            Work.class.getDeclaredField(gate).setBoolean(work, !gate.equals("content") && !gate.equals("eligible"));
            try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
                assertTrue(hooks.supports(FORCE));
                h.invoke(scope(), c, true);
                assertEquals(gate, State.HIDDEN, c.renderer.state);
                assertFalse(gate, c.renderer.visible);
                if (!gate.equals("stale")) assertEquals(gate, 0, c.playerEnables);
            }
        }
    }

    @Test public void oldAidCueIsDroppedAndNativeHiddenAndEmptyCleanupAreNeverExpanded() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
            h.invoke(scope(), c, true); assertTrue(c.renderer.visible);
            int calls = c.renderer.calls;
            c.cue("old-work", "stale old cue", State.EXPANDED);
            assertEquals(calls, c.renderer.calls);
            c.cue(work.aid, "", State.HIDDEN);
            assertEquals(State.HIDDEN, c.renderer.state); assertFalse(c.renderer.visible);
            c.cue(work.aid, "", State.EXPANDED);
            assertEquals(State.EXPANDED, c.renderer.state); assertFalse(c.renderer.visible);
            assertTrue(hooks.supports(FORCE));
        }
    }

    @Test public void preferenceReadOutsideScopeOnOtherThreadAndForNullWorkStaysNative() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
            assertFalse((Boolean) h.invoke(setting(), null, work));
            c.beforePreference = () -> {
                assertFalse((Boolean) h.invoke(setting(), null, new Object[]{null}));
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread other = new Thread(() -> {
                    try { assertFalse((Boolean) h.invoke(setting(), null, work)); }
                    catch (Throwable error) { failure.set(error); }
                });
                other.start(); other.join();
                if (failure.get() != null) throw failure.get();
            };
            h.invoke(scope(), c, true);
            assertTrue(c.renderer.visible);
            assertFalse((Boolean) h.invoke(setting(), null, work));
            assertTrue(hooks.supports(FORCE));
        }
    }

    @Test public void nestedScopesRestoreOuterDepthAndRemoveItWhenOuterReturns() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); Work innerWork = new Work();
        NativeController outer = new NativeController(h, work), inner = new NativeController(h, innerWork);
        try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
            outer.beforePreference = () -> h.invoke(scope(), inner, true);
            h.invoke(scope(), outer, true);
            assertTrue(inner.renderer.visible); assertTrue(outer.renderer.visible);
            assertFalse((Boolean) h.invoke(setting(), null, work));
            assertFalse((Boolean) h.invoke(setting(), null, innerWork));
            assertTrue(hooks.supports(FORCE));
        }
    }

    @Test public void nativeScopeExceptionPropagatesAndDoesNotLeakScope() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        IllegalStateException expected = new IllegalStateException("native base-state failure"); c.failure = expected;
        try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
            try { h.invoke(scope(), c, true); fail("native exception must propagate"); }
            catch (IllegalStateException actual) { assertSame(expected, actual); }
            assertFalse((Boolean) h.invoke(setting(), null, work));
            assertTrue(hooks.supports(FORCE));
        }
    }

    @Test public void nativePreferenceExceptionAndReadArePreservedAndScopeStillCleansUp() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        IllegalStateException expected = new IllegalStateException("native settings failure"); work.preferenceFailure = expected;
        try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
            try { h.invoke(scope(), c, true); fail("native preference exception must propagate"); }
            catch (IllegalStateException actual) { assertSame(expected, actual); }
            assertEquals(1, work.preferenceReads);
            work.preferenceFailure = null;
            assertFalse((Boolean) h.invoke(setting(), null, work));
            assertTrue(hooks.supports(FORCE));
        }
    }

    @Test public void disablingOrHidingMidScopeUsesCurrentSnapshotAndCleanupUsesOriginalEntryToken() throws Throwable {
        for (Map<String, Boolean> next : List.of(Map.<String, Boolean>of(), Map.of(HIDE, true, FORCE, true))) {
            Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
            try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
                c.beforePreference = () -> hooks.setOptions(new LocalOptions(next));
                h.invoke(scope(), c, true);
                assertFalse(c.renderer.visible);
                hooks.setOptions(new LocalOptions(Map.of(FORCE, true)));
                assertFalse((Boolean) h.invoke(setting(), null, work));
                c.beforePreference = null;
                h.invoke(scope(), c, true); assertTrue(c.renderer.visible);
            }
        }
    }

    @Test public void enablingMidwayThroughAnInactiveScopeDoesNotInventAnEntryToken() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        try (HostBehaviorHooks hooks = install(h, true, Map.of())) {
            c.beforePreference = () -> hooks.setOptions(new LocalOptions(Map.of(FORCE, true)));
            h.invoke(scope(), c, true); assertFalse(c.renderer.visible);
            c.beforePreference = null;
            h.invoke(scope(), c, true); assertTrue(c.renderer.visible);
            assertFalse((Boolean) h.invoke(setting(), null, work));
        }
    }

    @Test public void simultaneousHideAndForcePreserveHideAndDoNotEnableNativePlayer() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        try (HostBehaviorHooks hooks = install(h, true, Map.of(HIDE, true, FORCE, true))) {
            h.invoke(scope(), c, true);
            assertEquals(0, c.playerEnables); assertFalse(c.renderer.visible);
            h.invoke(renderer(), c.renderer, work.aid, State.EXPANDED, "native cue", Kind.EDIT_CTA);
            assertEquals(State.HIDDEN, c.renderer.state);
            assertTrue(hooks.supports(HIDE)); assertTrue(hooks.supports(FORCE));
        }
    }

    @Test public void allSixCallersAreDeoptimizedBeforeEitherForceHookIsRegistered() throws Throwable {
        Harness h = new Harness();
        try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true))) {
            assertTrue(hooks.supports(FORCE));
            assertEquals(new HashSet<>(deoptimizations()), new HashSet<>(h.deoptimized));
            assertEquals(6, h.deoptimized.size());
            int firstHook = h.events.indexOf("hook:force");
            assertTrue(firstHook >= 6);
            assertFalse(h.events.subList(firstHook, h.events.size()).contains("deopt"));
        }
    }

    @Test public void anyDeoptimizationFailureLeavesNoForceHandlesAndPreservesHideAndDataGroups() throws Throwable {
        for (Method rejected : deoptimizations()) {
            Harness h = new Harness(); h.rejectDeopt = rejected;
            try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true, HIDE, true, "disable_data_saver", true))) {
                assertFalse(rejected.toString(), hooks.supports(FORCE));
                assertFalse(hooks.installationFailure(FORCE).isEmpty());
                assertFalse(h.interceptors.containsKey(scope())); assertFalse(h.interceptors.containsKey(setting()));
                assertTrue(hooks.supports(HIDE)); assertTrue(hooks.supports("disable_data_saver"));
                assertFalse((Boolean) h.invoke(data(), null));
            }
        }
    }

    @Test public void partialForceRegistrationRollsBackOnlyThatGroup() throws Throwable {
        for (Method rejected : List.of(scope(), setting())) {
            Harness h = new Harness(); h.rejectHook = rejected;
            try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true, HIDE, true))) {
                assertFalse(hooks.supports(FORCE)); assertFalse(hooks.installationFailure(FORCE).isEmpty());
                assertFalse(h.interceptors.containsKey(scope())); assertFalse(h.interceptors.containsKey(setting()));
                assertTrue(hooks.supports(HIDE)); assertTrue(hooks.supports("disable_data_saver"));
            }
        }
    }

    @Test public void anUndeclaredOrInvalidContractRejectsOnlyForceBeforeRegisteringIt() throws Throwable {
        for (boolean missing : List.of(true, false)) {
            Harness h = new Harness(); h.missingScope = missing; h.badScope = !missing;
            try (HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true, HIDE, true))) {
                assertFalse(hooks.supports(FORCE)); assertFalse(hooks.installationFailure(FORCE).isEmpty());
                assertFalse(h.interceptors.containsKey(scope())); assertFalse(h.interceptors.containsKey(setting()));
                assertTrue(hooks.supports(HIDE));
            }
        }
    }

    @Test public void closingDuringScopeStopsOverrideAndFinallyRemovesTheEntry() throws Throwable {
        Harness h = new Harness(); Work work = new Work(); NativeController c = new NativeController(h, work);
        HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true));
        c.beforePreference = hooks::close;
        h.invoke(scope(), c, true);
        assertFalse(c.renderer.visible); assertFalse(hooks.supports(FORCE));
        assertFalse((Boolean) h.invoke(setting(), null, work));
        assertTrue(h.interceptors.isEmpty()); hooks.close();
    }

    @Test public void closeUnhooksAndANonTikTokProfileNeverInstallsOrDeoptimizesForceTargets() throws Throwable {
        Harness h = new Harness();
        HostBehaviorHooks hooks = install(h, true, Map.of(FORCE, true));
        assertTrue(hooks.supports(FORCE)); hooks.close(); hooks.close();
        assertFalse(hooks.supports(FORCE)); assertTrue(h.interceptors.isEmpty());
        Harness other = new Harness();
        try (HostBehaviorHooks nonTikTok = install(other, false, Map.of(FORCE, true))) {
            assertFalse(nonTikTok.supports(FORCE)); assertTrue(other.deoptimized.isEmpty());
            assertFalse(other.interceptors.containsKey(scope())); assertFalse(other.interceptors.containsKey(setting()));
        }
    }

    private HostBehaviorHooks install(Harness h, boolean tikTok, Map<String, ?> options) throws Exception {
        List<RuleScanResult> rows = new ArrayList<>();
        if (tikTok) rows.add(result("tiktok_feed_materialized", renderer()));
        if (!h.missingScope) rows.add(result(SCOPE, h.badScope
                ? InvalidScope.class.getMethod("evaluate", boolean.class) : scope()));
        rows.add(result(SETTING, setting())); rows.add(result(POLICY, policy()));
        rows.add(result(VIDEO, video())); rows.add(result(SETTINGS, settings()));
        rows.add(result(DUPLICATE, duplicate())); rows.add(result(OVERRIDE, override()));
        rows.add(result("tiktok_caption_display_gate", renderer()));
        rows.add(result("tiktok_data_saver_enabled", data()));
        return HostBehaviorHooks.install(h.framework, new HostScanReport("fixture", rows, false),
                getClass().getClassLoader(), null, new LocalOptions(options), ignored -> { });
    }
    private static RuleScanResult result(String id, Method target) {
        return new RuleScanResult(id, "interface", List.of(new HostCandidate("base.apk", "classes.dex",
                HostTargets.descriptor(target.getDeclaringClass()), target.getName(), HostTargets.descriptor(target))));
    }
    private static Method scope() throws Exception { return NativeController.class.getMethod("evaluate", boolean.class); }
    private static Method setting() throws Exception { return NativePolicy.class.getMethod("settingOn", Work.class); }
    private static Method policy() throws Exception { return NativePolicy.class.getMethod("displayPolicy", Work.class, boolean.class); }
    private static Method video() throws Exception { return VideoCaller.class.getMethod("invokeSuspend", Object.class); }
    private static Method settings() throws Exception { return SettingCaller.class.getMethod("emit", Object.class, Object.class); }
    private static Method duplicate() throws Exception { return DuplicateCaller.class.getMethod("emit", Object.class, Object.class); }
    private static Method override() throws Exception { return OverrideCaller.class.getMethod("emit", Object.class, Object.class); }
    private static Method renderer() throws Exception { return Renderer.class.getMethod("render", String.class, State.class, String.class, Kind.class); }
    private static Method data() throws Exception { return DataSaver.class.getMethod("enabled"); }
    private static List<Method> deoptimizations() throws Exception {
        return List.of(scope(), policy(), video(), settings(), duplicate(), override());
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        final List<Method> deoptimized = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Method rejectHook, rejectDeopt;
        boolean missingScope, badScope;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("deoptimize")) {
                        Method target = (Method) arguments[0]; deoptimized.add(target); events.add("deopt");
                        return !target.equals(rejectDeopt);
                    }
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) arguments[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                if (target.equals(rejectHook)) throw new IllegalStateException("Rejected fixture registration");
                                if (target.equals(scope()) || target.equals(setting())) events.add("hook:force");
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
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, values) -> {
                        switch (method.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArgs": return Arrays.asList(arguments);
                            case "getArg": return arguments[(Integer) values[0]];
                            case "proceed": return nativeInvoke(target, owner, values == null ? arguments : (Object[]) values[0]);
                            default: return null;
                        }
                    });
            XposedInterface.Hooker hooker = interceptors.get(target);
            return hooker == null ? nativeInvoke(target, owner, arguments) : hooker.intercept(chain);
        }
        private static Object nativeInvoke(Method target, Object owner, Object[] arguments) throws Throwable {
            try { return target.invoke(owner, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
