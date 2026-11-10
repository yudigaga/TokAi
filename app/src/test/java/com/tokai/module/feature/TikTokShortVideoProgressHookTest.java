package com.tokai.module.feature;

import android.content.SharedPreferences;
import com.tokai.module.config.ComponentHideSettings;
import com.tokai.module.config.ExtendedSettings;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Models the exact style decision, separately from TikTok's overall visibility permission. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokShortVideoProgressHookTest {
    private static final String[] CALLER_IDS = {
            "tiktok_short_progress_update", "tiktok_short_progress_tick", "tiktok_short_progress_status",
            "tiktok_short_progress_video_event", "tiktok_short_progress_restore", "tiktok_short_progress_recovery"};

    @Test public void onlyTheDurationStyleIsHookedAndEveryNativeCallerIsDeoptimized() throws Throwable {
        try (Fixture f = new Fixture()) {
            assertTrue("The TikTok duration style must be installed", f.hook.isLegacyInstalled());
            assertFalse("The non-draggable Assem thin bar is deliberately not claimed", f.hook.isProInstalled());
            assertEquals(Collections.singleton(f.style), f.framework.hooks.keySet());
            assertEquals(new LinkedHashSet<>(Arrays.asList(f.callers)), f.framework.deoptimized);
            assertEquals(0, f.style());
            assertEquals(1, f.owner.styleReads);
            assertEquals(1, f.owner.gateReads);
            assertEquals(1, f.owner.LLJLIL.control.showProgressBar);
        }
    }

    @Test public void allOtherNativeStylesAndLongOrUnknownDurationKeepTheirResult() throws Throwable {
        try (Fixture f = new Fixture()) {
            for (int style : new int[]{0, 1, 2, 3, 100, 101, 102}) {
                f.owner.forcedStyle = style;
                assertEquals(style, f.style());
            }
            assertEquals("Non-hidden styles need no additional host gate evaluation", 0, f.owner.gateReads);
            f.owner.forcedStyle = 4;
            f.owner.LLJLIL.video.duration = 30_001;
            assertEquals(4, f.style());
            f.owner.LLJLIL.video.duration = 0;
            assertEquals(4, f.style());
            f.owner.LLJLIL.video.duration = 20_000;
            f.owner.LLJLLL = 31;
            assertEquals(4, f.style());
            f.owner.LLJLLL = 20;
            assertEquals(0, f.style());
        }
    }

    @Test public void settingsNegativeAlphaUniformAlphaAndCloseRetainTheirExistingMeaning() throws Throwable {
        try (Fixture f = new Fixture()) {
            for (int alpha : new int[]{0, -1, -2}) {
                f.preferences.edit().putString(ComponentHideSettings.Component.PROGRESS.key, Integer.toString(alpha)).commit();
                f.reload(); assertEquals(4, f.style());
            }
            for (int alpha : new int[]{1, 64, 255}) {
                f.preferences.edit().putString(ComponentHideSettings.Component.PROGRESS.key, Integer.toString(alpha)).commit();
                f.reload(); assertEquals(0, f.style());
            }
            f.preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                    .putString(ComponentHideSettings.UNIFORM_ALPHA, "0").commit();
            f.reload(); assertEquals(4, f.style());
            f.preferences.edit().putString(ComponentHideSettings.UNIFORM_ALPHA, "100").commit();
            f.reload(); assertEquals(0, f.style());
            f.preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            f.reload(); assertEquals(4, f.style());
            f.preferences.edit().clear().commit();
            f.reload(); assertEquals(4, f.style());
            f.hook.close();
            assertFalse(f.hook.isLegacyInstalled());
            assertEquals(4, f.style());
        }
    }

    @Test public void advertisementsPhotosMissingVideoAndMissingCurrentWorkAreNotExtended() throws Throwable {
        try (Fixture f = new Fixture()) {
            Work work = f.owner.LLJLIL;
            work.ad = true; assertEquals(4, f.style());
            work.ad = false; work.type = 150; assertEquals(4, f.style());
            work.type = 0; Video video = work.video; work.video = null; assertEquals(4, f.style());
            work.video = video; f.owner.LLJLIL = null; assertEquals(4, f.style());
            f.owner.LLJLIL = work; assertEquals(0, f.style());
        }
    }

    @Test public void nativeResumeCurrentDragStoryMaskPromptAndProhibitionGatesRemainAuthoritative() throws Throwable {
        try (Fixture f = new Fixture()) {
            for (int gate = 0; gate < 8; gate++) {
                f.owner.blockedGate = gate;
                assertEquals("A delayed style restoration must also respect the native gate", 4, f.style());
            }
            f.owner.blockedGate = -1;
            f.owner.LLJLIL.control.showProgressBar = 0;
            assertEquals(4, f.style());
            assertEquals("The native control model must never be rewritten", 0, f.owner.LLJLIL.control.showProgressBar);
            f.owner.LLJLIL.control.showProgressBar = 1;
            assertEquals(0, f.style());
        }
    }

    @Test public void anUnconfirmedNativeCallerLeavesTheWholeDurationExtensionUnavailable() throws Throwable {
        try (Fixture f = new Fixture(false, "tiktok_short_progress_recovery", NativeOwner.class)) {
            assertFalse(f.hook.isLegacyInstalled());
            assertFalse(f.hook.isProInstalled());
            assertTrue(f.framework.hooks.isEmpty());
            assertTrue(f.hook.installationFailure().startsWith("Unsupported host rules:"));
            assertEquals(4, f.style());
        }
        try (Fixture f = new Fixture(true, null, NativeOwner.class)) {
            assertFalse(f.hook.isLegacyInstalled());
            assertFalse(f.hook.isProInstalled());
            assertTrue(f.framework.hooks.isEmpty());
            assertFalse(f.hook.installationFailure().isEmpty());
            assertEquals(4, f.style());
        }
    }

    @Test public void inheritedOwnerFieldsAreSupportedButAnUnrelatedOwnerContractIsRejected() throws Throwable {
        try (Fixture f = new Fixture(false, null, DerivedOwner.class)) {
            assertTrue(f.hook.isLegacyInstalled());
            assertEquals(0, f.style());
        }
        try (Fixture f = new Fixture(false, null, Work.class)) {
            assertFalse(f.hook.isLegacyInstalled());
            assertTrue(f.framework.hooks.isEmpty());
            assertEquals(4, f.style());
        }
    }

    @Test public void nativeStyleExceptionsAreNotConvertedIntoSuccessfulDisplayDecisions() throws Throwable {
        try (Fixture f = new Fixture()) {
            RuntimeException nativeFailure = new IllegalStateException("native style failure");
            f.owner.styleFailure = nativeFailure;
            try { f.style(); fail("Native failures must remain visible to the host"); }
            catch (RuntimeException actual) { assertSame(nativeFailure, actual); }
            assertEquals(0, f.owner.gateReads);
        }
    }

    public static class NativeOwner {
        public Work LLJLIL = new Work();
        public int LLJLLL = 20;
        public int styleReads, gateReads, blockedGate = -1;
        public Integer forcedStyle;
        public RuntimeException styleFailure;
        public int LJIIL(boolean gesture) {
            styleReads++;
            if (styleFailure != null) throw styleFailure;
            if (forcedStyle != null) return forcedStyle;
            return LLJLLL < 30 ? 4 : 0;
        }
        public boolean LJII(Work work) {
            gateReads++;
            // Each value represents one original early-return guard in the real LJII body.
            return blockedGate < 0 && work != null && work.control.showProgressBar > 0;
        }
        public void update(boolean initial) { }
        public void tick(boolean active, boolean selected, Work work, float progress, String scene, Boolean visible) { }
        public void status(Object event) { }
        public void videoEvent(Object event) { }
    }
    public static final class DerivedOwner extends NativeOwner { }
    public static final class Restore implements Runnable { @Override public void run() { } }
    public static final class Recovery { public void recover() { } }
    public static final class Work {
        public Video video = new Video();
        public final Control control = new Control();
        public boolean ad;
        public int type;
        public Video getVideo() { return video; }
        public int getAwemeType() { return type; }
        public boolean isAd() { return ad; }
    }
    public static final class Video { public int duration = 20_000; public int getDuration() { return duration; } }
    public static final class Control { public int showProgressBar = 1; }

    private static final class Fixture implements AutoCloseable {
        final SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("tiktok_short_progress", 0);
        final Framework framework = new Framework();
        final NativeOwner owner;
        final Method style = NativeOwner.class.getMethod("LJIIL", boolean.class);
        final Method[] callers = {
                NativeOwner.class.getMethod("update", boolean.class),
                NativeOwner.class.getMethod("tick", boolean.class, boolean.class, Work.class, float.class, String.class, Boolean.class),
                NativeOwner.class.getMethod("status", Object.class), NativeOwner.class.getMethod("videoEvent", Object.class),
                Restore.class.getMethod("run"), Recovery.class.getMethod("recover")};
        final ShortVideoProgressHook hook;
        Fixture() throws Exception { this(false, null, NativeOwner.class); }
        Fixture(boolean failDeopt, String omittedRule, Class<?> ownerType) throws Exception {
            owner = ownerType == DerivedOwner.class ? new DerivedOwner() : new NativeOwner();
            preferences.edit().clear().putBoolean(ComponentHideSettings.ENABLED, true)
                    .putString(ComponentHideSettings.Component.PROGRESS.key, "100").commit();
            List<RuleScanResult> rules = new ArrayList<>();
            rules.add(method("tiktok_short_progress_style", style));
            rules.add(method("tiktok_short_progress_gate", NativeOwner.class.getMethod("LJII", Work.class)));
            rules.add(method("aweme_ad_flag", Work.class.getMethod("isAd")));
            rules.add(new RuleScanResult("tiktok_short_progress_owner", "feed_ui", Collections.singletonList(new HostCandidate(
                    "fixture.apk", "classes.dex", HostTargets.descriptor(ownerType), "", ""))));
            for (int i = 0; i < CALLER_IDS.length; i++) if (!CALLER_IDS[i].equals(omittedRule)) rules.add(method(CALLER_IDS[i], callers[i]));
            framework.failDeopt = failDeopt ? callers[2] : null;
            hook = ShortVideoProgressHook.install(framework.api, new HostScanReport("tiktok-fixture", rules, false),
                    getClass().getClassLoader(), ExtendedSettings.readFrom(preferences), ignored -> { });
        }
        int style() throws Throwable { return (Integer) framework.invoke(style, owner, false); }
        void reload() { hook.setSettings(ExtendedSettings.readFrom(preferences)); }
        @Override public void close() { hook.close(); }
    }
    private static RuleScanResult method(String id, Method method) {
        return new RuleScanResult(id, "feed_ui", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                method.getName(), HostTargets.descriptor(method))));
    }
    private static final class Framework {
        final Map<Method, XposedInterface.Hooker> hooks = new LinkedHashMap<>();
        final Set<Method> deoptimized = new LinkedHashSet<>();
        Method failDeopt;
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if ("deoptimize".equals(method.getName())) {
                        Method target = (Method) arguments[0]; deoptimized.add(target); return !target.equals(failDeopt);
                    }
                    if (!"hook".equals(method.getName())) return null;
                    Method target = (Method) arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if (!"intercept".equals(call.getName())) return builder;
                                hooks.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, unused) -> {
                                            if ("unhook".equals(action.getName())) hooks.remove(target);
                                            return null;
                                        });
                            });
                });
        Object invoke(Method target, Object receiver, Object... input) throws Throwable {
            XposedInterface.Hooker hook = hooks.get(target);
            if (hook == null) return nativeCall(target, receiver, input);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getThisObject": return receiver;
                            case "getArg": return input[(Integer) arguments[0]];
                            case "getArgs": return Arrays.asList(input);
                            case "proceed": return nativeCall(target, receiver, input);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hook.intercept(chain);
        }
        private static Object nativeCall(Method method, Object receiver, Object[] input) throws Throwable {
            try { return method.invoke(receiver, input); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
