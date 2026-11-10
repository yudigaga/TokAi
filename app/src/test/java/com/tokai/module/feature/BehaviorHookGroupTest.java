package com.tokai.module.feature;

import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class BehaviorHookGroupTest {
    public static final class Fixture {
        public boolean splash() { return true; }
        public void doubleTap() { }
        public void digg() { }
        int doubleClicks;
        public void handleDoubleClick(MotionEvent event) { doubleClicks++; }
    }

    @Test public void tiktokDoubleTapUsesItsVerifiedDispatchWithoutTheDouyinDiggRule() throws Throwable {
        FrameworkFixture fixture = new FrameworkFixture();
        Method target = Fixture.class.getMethod("handleDoubleClick", MotionEvent.class);
        Map<String, RuleScanResult> results = new LinkedHashMap<>();
        add(results, "tiktok_feed_materialized", Fixture.class.getMethod("splash"));
        add(results, "adv_ui_double_tap", target);
        try (HostBehaviorHooks hooks = HostBehaviorHooks.install(fixture.framework(), report(results),
                getClass().getClassLoader(), null,
                new LocalOptions(Map.of("disable_double_tap_like", true)), ignored -> { })) {
            assertTrue("Verified TikTok dispatch must install without the absent Douyin callback",
                    hooks.supports("disable_double_tap_like"));
            Fixture owner = new Fixture();
            fixture.invoke(target, owner);
            assertEquals(0, owner.doubleClicks);
            hooks.setOptions(new LocalOptions(Collections.emptyMap()));
            fixture.invoke(target, owner);
            assertEquals("Disabling the option must forward the original host call", 1, owner.doubleClicks);
        }
    }

    @Test public void partialRegistrationRollsBackOnlyThatFeature() throws Exception {
        FrameworkFixture fixture = new FrameworkFixture();
        fixture.rejected = Fixture.class.getMethod("digg");
        Map<String, RuleScanResult> results = new LinkedHashMap<>();
        add(results, "adv_ui_splash_gate", Fixture.class.getMethod("splash"));
        add(results, "adv_ui_double_tap", Fixture.class.getMethod("doubleTap"));
        add(results, "adv_ui_big_digg", Fixture.class.getMethod("digg"));
        HostBehaviorHooks hooks = HostBehaviorHooks.install(fixture.framework(), report(results),
                getClass().getClassLoader(), null, new LocalOptions(Collections.emptyMap()), ignored -> { });
        assertTrue(hooks.supports("remove_splash_ads"));
        assertFalse(hooks.supports("disable_double_tap_like"));
        assertEquals(1, fixture.active);
        assertEquals(1, fixture.revoked);
        hooks.close();
        assertEquals(0, fixture.active);
        hooks.close();
        assertEquals(2, fixture.revoked);
    }

    @Test public void ambiguousRuleInstallsNoHook() throws Exception {
        FrameworkFixture fixture = new FrameworkFixture();
        Method method = Fixture.class.getMethod("splash");
        HostCandidate candidate = candidate(method);
        Map<String, RuleScanResult> results = new LinkedHashMap<>();
        results.put("adv_ui_splash_gate", new RuleScanResult("adv_ui_splash_gate",
                "fixture", List.of(candidate, candidate)));
        HostBehaviorHooks hooks = HostBehaviorHooks.install(fixture.framework(), report(results),
                getClass().getClassLoader(), null, new LocalOptions(Collections.emptyMap()), ignored -> { });
        assertFalse(hooks.supports("remove_splash_ads"));
        assertEquals(0, fixture.active);
        hooks.close();
    }

    @Test public void undeclaredTargetsStayExplicitlyUnsupportedWhileDeclaredScanFailuresStayErrors() {
        FrameworkFixture fixture = new FrameworkFixture();
        Map<String, RuleScanResult> results = new LinkedHashMap<>();
        results.put("adv_ui_splash_gate", new RuleScanResult("adv_ui_splash_gate", "fixture", List.of()));
        try (HostBehaviorHooks hooks = HostBehaviorHooks.install(fixture.framework(), report(results),
                getClass().getClassLoader(), null, new LocalOptions(Map.of("hide_camera_plus", true)), ignored -> { })) {
            assertTrue(hooks.installationFailure("hide_camera_plus").startsWith("Unsupported host rules:"));
            assertFalse(hooks.supports("hide_camera_plus"));
            assertFalse(hooks.installationFailure("remove_splash_ads").startsWith("Unsupported host rules:"));
            assertTrue(hooks.installationFailure("remove_splash_ads").contains("Host rule is not unique"));
            assertEquals(0, fixture.active);
        }
    }

    @Test public void allLocalAdvancedOptionsDefaultOff() {
        LocalOptions defaults = new LocalOptions(Collections.emptyMap());
        for (String key : new String[] { "remove_splash_ads", "remove_update_dialog", "remove_teen_dialog",
                "hide_camera_plus", "hide_video_black_edges", "disable_double_tap_like",
                "prevent_skip_hidden", "prevent_insert_video", "block_long_press_speed", "auto_speed",
                "pause_auto_on_comments", "smart_swipe", "spoof_profile" }) assertFalse(defaults.enabled(key));
    }

    @Test public void playbackSpeedRejectsNonFiniteAndBoundsHostRange() {
        assertEquals(2f, PlaybackBehaviorHooks.playbackSpeed(new LocalOptions(Map.of("playback_speed", "NaN"))), 0f);
        assertEquals(0.75f, PlaybackBehaviorHooks.playbackSpeed(new LocalOptions(Map.of("playback_speed", -1))), 0f);
        assertEquals(4f, PlaybackBehaviorHooks.playbackSpeed(new LocalOptions(Map.of("playback_speed", 20))), 0f);
        assertEquals(1.5f, PlaybackBehaviorHooks.playbackSpeed(new LocalOptions(Map.of("playback_speed", "1.5"))), 0f);
    }

    @Test public void profileDisplaySupportsLongCountsAndRejectsNegativeNumbers() {
        assertEquals("4000000000", ProfileSpoofHook.displayCount(
                new LocalOptions(Map.of("profile_like_count", "4000000000")), "profile_like_count"));
        assertEquals("0", ProfileSpoofHook.displayCount(
                new LocalOptions(Map.of("profile_follower_count", -7)), "profile_follower_count"));
        assertEquals("0", ProfileSpoofHook.displayCount(
                new LocalOptions(Map.of("profile_mutual_count", "invalid")), "profile_mutual_count"));
    }

    private static HostCandidate candidate(Method method) {
        return new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                method.getName(), HostTargets.descriptor(method));
    }

    private static void add(Map<String, RuleScanResult> results, String id, Method method) {
        results.put(id, new RuleScanResult(id, "fixture", Collections.singletonList(candidate(method))));
    }

    private static HostScanReport report(Map<String, RuleScanResult> results) {
        return new HostScanReport("fixture", new ArrayList<>(results.values()), false);
    }

    private static final class FrameworkFixture {
        final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        int active;
        int revoked;
        Method rejected;

        XposedInterface framework() {
            return (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.class }, (proxy, method, args) -> {
                        if (method.getName().equals("hook")) return builder((Method) args[0]);
                        return null;
                    });
        }

        private XposedInterface.HookBuilder builder(Method target) {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.HookBuilder.class }, (proxy, method, args) -> {
                        if (!method.getName().equals("intercept")) return proxy;
                        if (target.equals(rejected)) throw new IllegalStateException("fixture installation failure");
                        hooks.put(target, (XposedInterface.Hooker) args[0]);
                        active++;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] { XposedInterface.HookHandle.class }, (handle, operation, values) -> {
                                    if (operation.getName().equals("unhook")) { active--; revoked++; hooks.remove(target); }
                                    return null;
                                });
                    });
        }

        Object invoke(Method method, Object owner) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, call, args) -> {
                        if (call.getName().equals("proceed")) return method.invoke(owner, new Object[]{null});
                        if (call.getName().equals("getThisObject")) return owner;
                        return null;
                    });
            return hooks.get(method).intercept(chain);
        }
    }
}
