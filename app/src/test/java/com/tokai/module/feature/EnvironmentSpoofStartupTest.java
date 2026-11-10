package com.tokai.module.feature;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import com.tokai.module.TokAiModule;
import com.tokai.module.config.SettingsRepository;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Calls real bootstrap adapters in order without implying a live Android process or LSPosed run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class EnvironmentSpoofStartupTest {
    private static final String TARGET = "com.zhiliaoapp.musically";
    private SharedPreferences preferences;
    private Context host;
    private TokAiModule module;
    private Harness harness;

    @Before public void setup() throws Exception {
        Context actual = RuntimeEnvironment.getApplication();
        preferences = actual.getSharedPreferences("environment-startup-fixture", Context.MODE_PRIVATE);
        preferences.edit().clear().putBoolean("tokai_host_settings_initialized", true).commit();
        host = hostContext(TARGET, false);
        module = new TokAiModule();
        harness = new Harness();
        field("tracedFramework").set(module, harness.framework());
    }

    @Test public void serviceProcessInstallsAllEnvironmentSurfacesWithoutSchedulingHostDexScan() throws Exception {
        assertFalse(field("isMainProcess").getBoolean(module));
        module.onPackageLoaded(loaded(TARGET, true));
        EnvironmentSpoofHook environment = environment();
        try {
            int registrations = harness.hooks.size();
            module.onPackageLoaded(loaded(TARGET, true));
            assertEquals("Repeated package callbacks must not stack Hooks", registrations, harness.hooks.size());
            assertNotNull(environment);
            assertFalse(field("scanScheduled").getBoolean(module));
            assertNull(field("hostScanReport").get(module));
            assertNotNull(harness.hooks.get(newApplication()));
            assertNotNull(harness.hooks.get(callApplication()));
            for (String family : new String[]{"sim", "language", "timezone", "gps"}) {
                assertTrue(family, environment.diagnostics(environment.settings()).getBoolean(family + ".installed"));
            }
        } finally { environment.close(); }
    }

    @Test public void targetPackageAndFirstPackageGatePreventsHooksInUnrelatedApps() {
        module.onPackageLoaded(loaded("com.example.other", true));
        module.onPackageLoaded(loaded(TARGET, false));
        assertTrue(harness.hooks.isEmpty());
    }

    @Test public void localHostSettingsAreAppliedBeforeApplicationConstructionProceeds() throws Throwable {
        preferences.edit().putBoolean("sim_spoof_enabled", true).putString("target_region", "US").commit();
        module.onPackageLoaded(loaded(TARGET, true));
        EnvironmentSpoofHook environment = environment();
        try {
            assertFalse(field("environmentConfigurationFrozen").getBoolean(module));
            AtomicInteger proceeds = new AtomicInteger();
            Object original = new Object();
            assertSame(original, harness.invoke(newApplication(), new Object[]{getClass().getClassLoader(),
                    "fixture.Application", host}, () -> {
                proceeds.incrementAndGet();
                assertTrue(environment.settings().simEnabled);
                assertEquals("US", environment.settings().regionCode);
                assertTrue(field("environmentConfigurationFrozen").getBoolean(module));
                return original;
            }));
            assertEquals(1, proceeds.get());
            assertFalse(field("scanScheduled").getBoolean(module));
        } finally { environment.close(); }
    }

    @Test public void secondStartupCallbackCannotHotSwapTheFrozenProcessSnapshot() throws Throwable {
        preferences.edit().putBoolean("sim_spoof_enabled", true).putString("target_region", "US").commit();
        module.onPackageLoaded(loaded(TARGET, true));
        EnvironmentSpoofHook environment = environment();
        try {
            harness.invoke(newApplication(), new Object[]{getClass().getClassLoader(), "fixture.Application", host}, () -> null);
            preferences.edit().putString("target_region", "JP").putBoolean("gps_spoof_enabled", true).commit();
            harness.invoke(callApplication(), new Object[]{hostApplication()}, () -> {
                assertEquals("US", environment.settings().regionCode);
                assertFalse(environment.settings().gpsEnabled);
                return null;
            });
            assertEquals("US", environment.settings().regionCode);
        } finally { environment.close(); }
    }

    @Test public void unrelatedContextDoesNotFreezeSettingsAndLaterHostCallbackCanStillConfigure() throws Throwable {
        preferences.edit().putBoolean("sim_spoof_enabled", true).putString("target_region", "GB").commit();
        module.onPackageLoaded(loaded(TARGET, true));
        EnvironmentSpoofHook environment = environment();
        try {
            harness.invoke(newApplication(), new Object[]{getClass().getClassLoader(), "other.Application",
                    hostContext("com.example.other", false)}, () -> null);
            assertFalse(field("environmentConfigurationFrozen").getBoolean(module));
            harness.invoke(callApplication(), new Object[]{hostApplication()}, () -> null);
            assertTrue(field("environmentConfigurationFrozen").getBoolean(module));
            assertEquals("GB", environment.settings().regionCode);
        } finally { environment.close(); }
    }

    @Test public void localReadFailureStillCallsApplicationAndRetriesAtTheNextStartupEntry() throws Throwable {
        preferences.edit().putBoolean("sim_spoof_enabled", true).putString("target_region", "CA").commit();
        module.onPackageLoaded(loaded(TARGET, true));
        EnvironmentSpoofHook environment = environment();
        try {
            AtomicInteger proceeds = new AtomicInteger();
            harness.invoke(newApplication(), new Object[]{getClass().getClassLoader(), "fixture.Application",
                    hostContext(TARGET, true)}, () -> { proceeds.incrementAndGet(); return null; });
            assertEquals(1, proceeds.get());
            assertFalse(field("environmentConfigurationFrozen").getBoolean(module));
            harness.invoke(callApplication(), new Object[]{hostApplication()}, () -> null);
            assertTrue(field("environmentConfigurationFrozen").getBoolean(module));
            assertEquals("CA", environment.settings().regionCode);
        } finally { environment.close(); }
    }

    @Test public void startupRegistrationFailureRollsBackAndPackageReadyCanRetryCompletely() throws Exception {
        harness.rejected = callApplication();
        module.onPackageLoaded(loaded(TARGET, true));
        assertNull(environment());
        assertTrue(harness.hooks.isEmpty());
        assertTrue(harness.revoked > 0);
        assertNotNull(field("environmentHookFailure").get(module));
        harness.rejected = null;
        module.onPackageReady(ready(TARGET, true));
        EnvironmentSpoofHook retried = environment();
        try {
            assertNotNull(retried);
            assertNotNull(harness.hooks.get(newApplication()));
            assertNotNull(harness.hooks.get(callApplication()));
            assertNull(field("environmentHookFailure").get(module));
            assertFalse(field("scanScheduled").getBoolean(module));
        } finally { if (retried != null) retried.close(); }
    }


    private Context hostContext(String packageName, boolean failRead) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public String getPackageName() { return packageName; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                if (!SettingsRepository.PREFERENCES_NAME.equals(name)) return super.getSharedPreferences(name, mode);
                if (failRead) throw new IllegalStateException("fixture preferences temporarily unavailable");
                return preferences;
            }
        };
    }

    private Application hostApplication() {
        return new Application() {
            { attachBaseContext(RuntimeEnvironment.getApplication()); }
            @Override public String getPackageName() { return TARGET; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                if (!SettingsRepository.PREFERENCES_NAME.equals(name)) return super.getSharedPreferences(name, mode);
                return preferences;
            }
        };
    }

    private EnvironmentSpoofHook environment() throws Exception {
        return (EnvironmentSpoofHook) field("environmentSpoofHook").get(module);
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = TokAiModule.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Method newApplication() throws NoSuchMethodException {
        return Instrumentation.class.getMethod("newApplication", ClassLoader.class, String.class, Context.class);
    }

    private static Method callApplication() throws NoSuchMethodException {
        return Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
    }

    private static XposedModuleInterface.PackageLoadedParam loaded(String packageName, boolean first) {
        return (XposedModuleInterface.PackageLoadedParam) Proxy.newProxyInstance(
                XposedModuleInterface.PackageLoadedParam.class.getClassLoader(),
                new Class<?>[]{XposedModuleInterface.PackageLoadedParam.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPackageName": return packageName;
                        case "isFirstPackage": return first;
                        default: throw new AssertionError("Service bootstrap should not request " + method.getName());
                    }
                });
    }

    private static XposedModuleInterface.PackageReadyParam ready(String packageName, boolean first) {
        return (XposedModuleInterface.PackageReadyParam) Proxy.newProxyInstance(
                XposedModuleInterface.PackageReadyParam.class.getClassLoader(),
                new Class<?>[]{XposedModuleInterface.PackageReadyParam.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPackageName": return packageName;
                        case "isFirstPackage": return first;
                        default: throw new AssertionError("Service bootstrap should not request " + method.getName());
                    }
                });
    }

    private interface Proceed { Object run() throws Throwable; }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> hooks = new LinkedHashMap<>();
        Method rejected;
        int revoked;

        XposedInterface framework() {
            return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, operation, args) ->
                            operation.getName().equals("hook") ? builder((Method) args[0]) : null);
        }

        private XposedInterface.HookBuilder builder(Method target) {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class}, (proxy, operation, args) -> {
                        if (!operation.getName().equals("intercept")) return proxy;
                        if (target.equals(rejected)) throw new IllegalStateException("fixture startup hook rejection");
                        assertNull("Duplicate registration: " + target, hooks.put(target, (XposedInterface.Hooker) args[0]));
                        return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, values) -> {
                                    if (action.getName().equals("unhook")) { hooks.remove(target); revoked++; }
                                    return null;
                                });
                    });
        }

        Object invoke(Method target, Object[] arguments, Proceed proceed) throws Throwable {
            XposedInterface.Hooker hooker = hooks.get(target);
            assertNotNull("Missing Hook: " + target, hooker);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, operation, args) -> {
                        switch (operation.getName()) {
                            case "getArg": return arguments[(Integer) args[0]];
                            case "getArgs": return Arrays.asList(arguments);
                            case "getMember": return target;
                            case "getThisObject": return null;
                            case "proceed": return proceed.run();
                            default: throw new UnsupportedOperationException(operation.getName());
                        }
                    });
            return hooker.intercept(chain);
        }
    }
}
