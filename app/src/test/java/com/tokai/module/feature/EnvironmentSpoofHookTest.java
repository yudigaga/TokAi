package com.tokai.module.feature;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.LocaleList;
import android.telephony.TelephonyManager;

import com.tokai.module.config.EnvironmentSpoofSettings;
import com.tokai.module.config.LocalOptions;

import io.github.libxposed.api.XposedInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Replays actual registered Android method adapters; does not simulate LSPosed interception. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class EnvironmentSpoofHookTest {
    private static final Set<String> SIM_NAMES = Set.of("getSimCountryIso", "getNetworkCountryIso",
            "getSimOperator", "getNetworkOperator", "getSimOperatorName", "getNetworkOperatorName",
            "getSimState", "hasIccCard", "getPhoneType");

    @Test public void everyAvailableSimOverloadReturnsOneConsistentCarrierWithoutCallingHost() throws Throwable {
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, Map.of("sim_spoof_enabled", true,
                "target_region", "us", "sim_operator_code", "310260", "sim_operator_name", "Custom carrier"))) {
            int overloads = 0;
            Set<String> seen = new java.util.HashSet<>();
            for (Method target : TelephonyManager.class.getDeclaredMethods()) {
                if (Modifier.isAbstract(target.getModifiers()) || !SIM_NAMES.contains(target.getName())) continue;
                seen.add(target.getName());
                assertTrue(target.toString(), harness.hooks.containsKey(target));
                Object expected;
                switch (target.getName()) {
                    case "getSimCountryIso": case "getNetworkCountryIso": expected = "us"; break;
                    case "getSimOperator": case "getNetworkOperator": expected = "310260"; break;
                    case "getSimOperatorName": case "getNetworkOperatorName": expected = "Custom carrier"; break;
                    case "getSimState": expected = TelephonyManager.SIM_STATE_READY; break;
                    case "hasIccCard": expected = true; break;
                    case "getPhoneType": expected = TelephonyManager.PHONE_TYPE_GSM; break;
                    default: throw new AssertionError(target);
                }
                AtomicInteger proceeds = new AtomicInteger();
                assertEquals(target.toString(), expected,
                        harness.invoke(target, null, null, proceeds, null, arguments(target)));
                assertEquals(0, proceeds.get());
                overloads++;
            }
            assertEquals(SIM_NAMES, seen);
            assertTrue("The Android SDK fixture must exercise overloads", overloads > SIM_NAMES.size());
        }
    }

    @Test public void disabledFlagsForwardEveryRegisteredMethodAndPreserveHostExceptions() throws Throwable {
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, Collections.emptyMap())) {
            Object original = new Object();
            for (Method target : harness.hooks.keySet()) {
                AtomicInteger proceeds = new AtomicInteger();
                assertSame(target.toString(), original,
                        harness.invoke(target, null, original, proceeds, null, arguments(target)));
                assertEquals(target.toString(), 1, proceeds.get());
            }
            Method country = TelephonyManager.class.getMethod("getSimCountryIso");
            SecurityException denied = new SecurityException("permission denied");
            try { harness.invoke(country, null, null, new AtomicInteger(), denied); fail(); }
            catch (SecurityException expected) { assertSame(denied, expected); }
        }
    }

    @Test public void javaLanguageChangesWhileLocaleListRetainsTheRealRenderingLocales() throws Throwable {
        LocaleList real = LocaleList.getDefault();
        Locale javaDefault = Locale.getDefault();
        Locale formatDefault = Locale.getDefault(Locale.Category.FORMAT);
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, Map.of("language_spoof_enabled", true,
                "language_follow_region", false, "custom_language", "ko-KR"))) {
            for (Method target : Locale.class.getDeclaredMethods()) {
                if (Modifier.isStatic(target.getModifiers()) && target.getName().equals("getDefault")) {
                    assertEquals(Locale.forLanguageTag("ko-KR"), harness.call(target, null, arguments(target)));
                }
            }
            assertSame(real, harness.call(LocaleList.class.getMethod("getDefault"), null));
            assertSame(real, harness.call(LocaleList.class.getMethod("getAdjustedDefault"), null));
            assertEquals("Locale.setDefault is deliberately untouched", javaDefault, Locale.getDefault());
            assertEquals(formatDefault, Locale.getDefault(Locale.Category.FORMAT));
        }
    }

    @Test public void threeTimeZoneSurfacesAgreeAndMutableInstancesCannotContaminateLaterReads() throws Throwable {
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, Map.of("timezone_spoof_enabled", true,
                "timezone_follow_region", false, "custom_timezone", "America/New_York"))) {
            Method javaTarget = TimeZone.class.getMethod("getDefault");
            TimeZone first = (TimeZone) harness.call(javaTarget, null);
            assertEquals("America/New_York", first.getID());
            assertEquals(ZoneId.of("America/New_York"), harness.call(ZoneId.class.getMethod("systemDefault"), null));
            android.icu.util.TimeZone icu = (android.icu.util.TimeZone) harness.call(
                    android.icu.util.TimeZone.class.getMethod("getDefault"), null);
            assertEquals("America/New_York", icu.getID());
            first.setID("caller mutation");
            icu.setID("caller mutation");
            assertEquals("America/New_York", ((TimeZone) harness.call(javaTarget, null)).getID());
            assertEquals("America/New_York", ((android.icu.util.TimeZone) harness.call(
                    android.icu.util.TimeZone.class.getMethod("getDefault"), null)).getID());
        }
    }

    @Test public void customFixedOffsetFormsHaveTheSameEffectiveOffsetAcrossAllThreeTimeApis() throws Throwable {
        Map<String, Integer> offsetsInSeconds = Map.of("UTC+08:00", 8 * 3600,
                "GMT-03:30", -(3 * 3600 + 30 * 60), "+05:45", 5 * 3600 + 45 * 60,
                "UT-09:30", -(9 * 3600 + 30 * 60));
        for (Map.Entry<String, Integer> sample : offsetsInSeconds.entrySet()) {
            String configured = sample.getKey();
            assertNull(configured, EnvironmentSpoofSettings.validate("custom_timezone", configured));
            Harness harness = new Harness();
            try (EnvironmentSpoofHook hook = install(harness, Map.of("timezone_spoof_enabled", true,
                    "timezone_follow_region", false, "custom_timezone", configured))) {
                assertEquals(configured, hook.settings().timeZoneId);
                TimeZone javaZone = (TimeZone) harness.call(TimeZone.class.getMethod("getDefault"), null);
                ZoneId zoneId = (ZoneId) harness.call(ZoneId.class.getMethod("systemDefault"), null);
                android.icu.util.TimeZone icuZone = (android.icu.util.TimeZone) harness.call(
                        android.icu.util.TimeZone.class.getMethod("getDefault"), null);
                for (String at : new String[]{"2026-01-15T12:00:00Z", "2026-07-15T12:00:00Z"}) {
                    Instant instant = Instant.parse(at);
                    int expectedMillis = sample.getValue() * 1000;
                    assertEquals(configured + " Java " + at, expectedMillis, javaZone.getOffset(instant.toEpochMilli()));
                    assertEquals(configured + " ZoneId " + at, expectedMillis,
                            zoneId.getRules().getOffset(instant).getTotalSeconds() * 1000);
                    assertEquals(configured + " ICU " + at, expectedMillis, icuZone.getOffset(instant.toEpochMilli()));
                    assertEquals(configured + " process default " + at, expectedMillis,
                            TimeZone.getDefault().getOffset(instant.toEpochMilli()));
                }
            }
        }
    }

    @Test public void processTimeZoneIsAppliedAtStartupAndRestoredWhenClosed() {
        TimeZone before = TimeZone.getDefault();
        Harness harness = new Harness();
        EnvironmentSpoofHook hook = install(harness, Map.of("timezone_spoof_enabled", true,
                "timezone_follow_region", false, "custom_timezone", "Pacific/Honolulu"));
        try { assertEquals("Pacific/Honolulu", TimeZone.getDefault().getID()); }
        finally { hook.close(); }
        assertEquals(before.getID(), TimeZone.getDefault().getID());
        assertEquals(before.getRawOffset(), TimeZone.getDefault().getRawOffset());
    }

    @Test public void startupLocalSettingsCanUndoAnEnabledRemoteTimeZoneBeforeTheSnapshotFreezes() {
        TimeZone before = TimeZone.getDefault();
        Harness harness = new Harness();
        try (EnvironmentSpoofHook hook = install(harness, Map.of("timezone_spoof_enabled", true,
                "timezone_follow_region", false, "custom_timezone", "Pacific/Honolulu"))) {
            assertEquals("Pacific/Honolulu", TimeZone.getDefault().getID());
            hook.setSettings(EnvironmentSpoofSettings.defaults());
            assertEquals(before.getID(), TimeZone.getDefault().getID());
            assertFalse(hook.settings().timeZoneEnabled);
        }
    }

    @Test @Config(sdk = 28)
    public void allFourFamiliesAndRequiredAdaptersRemainAvailableOnTheMinimumAndroidSdk() throws Throwable {
        Harness harness = new Harness();
        try (EnvironmentSpoofHook hook = install(harness, Map.of("sim_spoof_enabled", true,
                "language_spoof_enabled", true, "timezone_spoof_enabled", true, "gps_spoof_enabled", true))) {
            Bundle status = hook.diagnostics(hook.settings());
            for (String family : new String[]{"sim", "language", "timezone", "gps"}) {
                assertTrue(family + ": " + status.getString(family + ".failure"), status.getBoolean(family + ".installed"));
            }
            assertEquals("jp", harness.call(TelephonyManager.class.getMethod("getSimCountryIso"), "actual"));
            assertEquals(Locale.JAPAN, harness.call(Locale.class.getMethod("getDefault"), Locale.ROOT));
            assertEquals("Asia/Tokyo", ((TimeZone) harness.call(TimeZone.class.getMethod("getDefault"), null)).getID());
            assertEquals(ZoneId.of("Asia/Tokyo"), harness.call(ZoneId.class.getMethod("systemDefault"), null));
            assertEquals("Asia/Tokyo", ((android.icu.util.TimeZone) harness.call(
                    android.icu.util.TimeZone.class.getMethod("getDefault"), null)).getID());
            assertEquals(35.6762d, (Double) harness.call(Location.class.getMethod("getLatitude"), 0d), 0d);
            assertEquals(false, harness.call(Location.class.getMethod("isFromMockProvider"), true));
            assertNotNull(harness.call(lastKnown(), null, "gps"));
        }
    }

    @Test public void coordinateAndLegacyMockReadersFollowTheGpsFlag() throws Throwable {
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, gpsOptions())) {
            assertEquals(-33.865d, (Double) harness.call(Location.class.getMethod("getLatitude"), 7d), 0d);
            assertEquals(151.209d, (Double) harness.call(Location.class.getMethod("getLongitude"), 8d), 0d);
            assertEquals(false, harness.call(Location.class.getMethod("isFromMockProvider"), true));
            assertFalse("Toki does not Hook the newer isMock API",
                    harness.hooks.containsKey(Location.class.getMethod("isMock")));
        }
    }

    @Test public void lastKnownNullSynthesizesLocationWithRequestedProviderAndFreshTimestamps() throws Throwable {
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, gpsOptions())) {
            long earliest = System.currentTimeMillis();
            AtomicInteger proceeds = new AtomicInteger();
            Location result = (Location) harness.invoke(lastKnown(), null, null, proceeds, null, "network");
            assertEquals(1, proceeds.get());
            assertEquals("network", result.getProvider());
            assertEquals(-33.865d, result.getLatitude(), 0d);
            assertEquals(151.209d, result.getLongitude(), 0d);
            assertEquals(35d, result.getAltitude(), 0d);
            assertEquals(5f, result.getAccuracy(), 0f);
            assertTrue(result.getTime() >= earliest);
            assertTrue(result.getTime() <= System.currentTimeMillis());
            assertTrue(result.getElapsedRealtimeNanos() >= 0);
            assertEquals("gps", ((Location) harness.call(lastKnown(), null, (Object) null)).getProvider());
        }
    }

    @Test public void lastKnownCopiesCoordinatesWithoutContaminatingTheCachedOriginalOrOtherFields() throws Throwable {
        Location original = new Location("network");
        original.setLatitude(10d); original.setLongitude(20d);
        original.setAccuracy(200f); original.setAltitude(77d);
        original.setTime(123456L); original.setElapsedRealtimeNanos(654321L);
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, gpsOptions())) {
            Location result = (Location) harness.call(lastKnown(), original, "network");
            assertNotSame(original, result);
            assertEquals(-33.865d, result.getLatitude(), 0d);
            assertEquals(151.209d, result.getLongitude(), 0d);
            assertEquals(10d, original.getLatitude(), 0d);
            assertEquals(20d, original.getLongitude(), 0d);
            assertEquals(original.getProvider(), result.getProvider());
            assertEquals(200f, result.getAccuracy(), 0f);
            assertEquals(77d, result.getAltitude(), 0d);
            assertEquals(123456L, result.getTime());
            assertEquals(654321L, result.getElapsedRealtimeNanos());
        }
    }

    @Test public void lastKnownKeepsTheOriginalPermissionFailureInsteadOfFabricatingASuccess() throws Throwable {
        Harness harness = new Harness();
        try (EnvironmentSpoofHook ignored = install(harness, gpsOptions())) {
            SecurityException denied = new SecurityException("no location permission");
            AtomicInteger proceeds = new AtomicInteger();
            try { harness.invoke(lastKnown(), null, null, proceeds, denied, "gps"); fail(); }
            catch (SecurityException expected) { assertSame(denied, expected); }
            assertEquals(1, proceeds.get());
        }
    }

    @Test public void diagnosticsReportPendingPreferenceChangesWithoutChangingTheRunningSnapshot() {
        Harness harness = new Harness();
        EnvironmentSpoofSettings initial = settings(Map.of("sim_spoof_enabled", true, "target_region", "JP"));
        try (EnvironmentSpoofHook hook = EnvironmentSpoofHook.install(harness.framework(), initial, ignored -> { })) {
            EnvironmentSpoofSettings requested = settings(Map.of("sim_spoof_enabled", true, "target_region", "US"));
            assertFalse(hook.diagnostics(initial).getBoolean("restart_required"));
            Bundle diagnostic = hook.diagnostics(requested);
            assertTrue(diagnostic.getBoolean("restart_required"));
            assertSame(initial, hook.settings());
            assertEquals("JP", diagnostic.getString("region"));
            assertTrue(diagnostic.getBoolean("sim.enabled"));
        }
    }

    @Test public void partialSimRegistrationRollsBackOnlySimAndLeavesOtherGroupsUsable() throws Throwable {
        List<Method> sim = new ArrayList<>();
        for (Method target : TelephonyManager.class.getDeclaredMethods()) {
            if (!Modifier.isAbstract(target.getModifiers()) && SIM_NAMES.contains(target.getName())) sim.add(target);
        }
        assertTrue(sim.size() >= 2);
        Harness harness = new Harness(); harness.rejected = sim.get(1);
        try (EnvironmentSpoofHook hook = install(harness, Map.of("sim_spoof_enabled", true,
                "language_spoof_enabled", true, "gps_spoof_enabled", true))) {
            Bundle diagnostic = hook.diagnostics(hook.settings());
            assertFalse(diagnostic.getBoolean("sim.installed"));
            assertTrue(diagnostic.getString("sim.failure").contains("fixture registration rejection"));
            assertTrue(harness.revoked > 0);
            for (Method target : harness.hooks.keySet()) assertFalse(SIM_NAMES.contains(target.getName()));
            assertTrue(diagnostic.getBoolean("language.installed"));
            assertTrue(diagnostic.getBoolean("timezone.installed"));
            assertTrue(diagnostic.getBoolean("gps.installed"));
            assertEquals(Locale.JAPAN, harness.call(Locale.class.getMethod("getDefault"), null));
            assertEquals(35.6762d, (Double) harness.call(Location.class.getMethod("getLatitude"), 0d), 0d);
        }
        assertTrue(harness.hooks.isEmpty());
    }

    @Test public void failedTimeZoneGroupDoesNotMutateProcessDefaultOrDisableGps() throws Exception {
        TimeZone before = TimeZone.getDefault();
        Harness harness = new Harness();
        try { harness.rejected = ZoneId.class.getMethod("systemDefault"); }
        catch (NoSuchMethodException impossible) { throw new AssertionError(impossible); }
        try (EnvironmentSpoofHook hook = install(harness, Map.of("timezone_spoof_enabled", true,
                "timezone_follow_region", false, "custom_timezone", "Pacific/Honolulu", "gps_spoof_enabled", true))) {
            assertEquals(before.getID(), TimeZone.getDefault().getID());
            assertFalse(hook.diagnostics(hook.settings()).getBoolean("timezone.installed"));
            assertTrue(hook.diagnostics(hook.settings()).getBoolean("gps.installed"));
            assertFalse(harness.hooks.containsKey(TimeZone.class.getMethod("getDefault")));
            harness.rejected = null;
            hook.setSettings(hook.settings());
            assertFalse("An actual framework registration failure must remain revoked for this session",
                    hook.diagnostics(hook.settings()).getBoolean("timezone.installed"));
            assertFalse(harness.hooks.containsKey(TimeZone.class.getMethod("getDefault")));
        }
    }

    @Test public void disabledTimeZoneNeverParsesAnUnsupportedPresetOrDisablesOtherFamilies() throws Throwable {
        TimeZone before = TimeZone.getDefault();
        Harness harness = new Harness();
        EnvironmentSpoofSettings unsupported = unsupportedTimeZone(false);
        try (EnvironmentSpoofHook hook = EnvironmentSpoofHook.install(harness.framework(), unsupported, ignored -> { })) {
            Bundle diagnostic = hook.diagnostics(unsupported);
            for (String family : new String[]{"sim", "language", "timezone", "gps"}) {
                assertTrue(family + ": " + diagnostic.getString(family + ".failure"), diagnostic.getBoolean(family + ".installed"));
            }
            assertEquals("", diagnostic.getString("timezone.failure"));
            assertOtherFamiliesWork(harness);
            Object original = new Object();
            assertSame(original, harness.call(TimeZone.class.getMethod("getDefault"), original));
            assertSame(original, harness.call(ZoneId.class.getMethod("systemDefault"), original));
            assertSame(original, harness.call(android.icu.util.TimeZone.class.getMethod("getDefault"), original));
            assertEquals(before.getID(), TimeZone.getDefault().getID());
        }
    }

    @Test public void unsupportedEnabledTimeZoneRevokesOnlyItsOwnGroupAndRestoresTheProcessDefault() throws Throwable {
        TimeZone before = TimeZone.getDefault();
        Harness harness = new Harness();
        try (EnvironmentSpoofHook hook = install(harness, Map.of("sim_spoof_enabled", true,
                "language_spoof_enabled", true, "gps_spoof_enabled", true, "timezone_spoof_enabled", true,
                "timezone_follow_region", false, "custom_timezone", "Pacific/Honolulu"))) {
            assertEquals("Pacific/Honolulu", TimeZone.getDefault().getID());
            int activeBeforeFailure = harness.hooks.size();
            EnvironmentSpoofSettings unsupported = unsupportedTimeZone(true);
            hook.setSettings(unsupported);
            Bundle diagnostic = hook.diagnostics(unsupported);
            assertFalse(diagnostic.getBoolean("timezone.installed"));
            assertFalse(diagnostic.getString("timezone.failure").isEmpty());
            assertTrue(diagnostic.getBoolean("sim.installed"));
            assertTrue(diagnostic.getBoolean("language.installed"));
            assertTrue(diagnostic.getBoolean("gps.installed"));
            assertEquals(activeBeforeFailure - 3, harness.hooks.size());
            assertFalse(harness.hooks.containsKey(TimeZone.class.getMethod("getDefault")));
            assertFalse(harness.hooks.containsKey(ZoneId.class.getMethod("systemDefault")));
            assertFalse(harness.hooks.containsKey(android.icu.util.TimeZone.class.getMethod("getDefault")));
            assertOtherFamiliesWork(harness);
            assertEquals(before.getID(), TimeZone.getDefault().getID());
            assertEquals(before.getRawOffset(), TimeZone.getDefault().getRawOffset());
        }
    }

    @Test public void bootstrapLocalValueCanRecoverOnlyACacheInitializationFailureWhileLaterEditsStayPending() throws Throwable {
        Harness harness = new Harness();
        EnvironmentSpoofSettings unsupported = unsupportedTimeZone(true);
        try (EnvironmentSpoofHook hook = EnvironmentSpoofHook.install(harness.framework(), unsupported, ignored -> { })) {
            assertFalse(hook.diagnostics(unsupported).getBoolean("timezone.installed"));
            assertOtherFamiliesWork(harness);
            Method country = TelephonyManager.class.getMethod("getSimCountryIso");
            XposedInterface.Hooker simRegistration = harness.hooks.get(country);
            int otherRegistrations = harness.hooks.size();
            EnvironmentSpoofSettings local = settings(Map.of("sim_spoof_enabled", true,
                    "language_spoof_enabled", true, "gps_spoof_enabled", true, "timezone_spoof_enabled", true));
            hook.setSettings(local);
            assertTrue(hook.diagnostics(local).getBoolean("timezone.installed"));
            assertEquals("", hook.diagnostics(local).getString("timezone.failure"));
            assertEquals(otherRegistrations + 3, harness.hooks.size());
            assertSame("Recovering a cache must not reinstall unrelated groups", simRegistration, harness.hooks.get(country));
            assertEquals("Asia/Tokyo", ((TimeZone) harness.call(TimeZone.class.getMethod("getDefault"), null)).getID());
            assertEquals(ZoneId.of("Asia/Tokyo"), harness.call(ZoneId.class.getMethod("systemDefault"), null));
            assertEquals("Asia/Tokyo", ((android.icu.util.TimeZone) harness.call(
                    android.icu.util.TimeZone.class.getMethod("getDefault"), null)).getID());
            assertOtherFamiliesWork(harness);

            // After the module freezes the process, preference updates request diagnostics only.
            // EnvironmentSpoofStartupTest separately replays the module's freeze guard.
            EnvironmentSpoofSettings later = settings(Map.of("sim_spoof_enabled", true,
                    "language_spoof_enabled", true, "gps_spoof_enabled", true, "timezone_spoof_enabled", true,
                    "timezone_follow_region", false, "custom_timezone", "Pacific/Honolulu"));
            assertTrue(hook.diagnostics(later).getBoolean("restart_required"));
            assertSame(local, hook.settings());
            assertEquals("Asia/Tokyo", TimeZone.getDefault().getID());
            assertEquals("Asia/Tokyo", ((TimeZone) harness.call(TimeZone.class.getMethod("getDefault"), null)).getID());
        }
    }

    @Test public void closeRevokesAllHandlesExactlyOnceAndLateSettingsCannotReactivateIt() {
        Harness harness = new Harness();
        EnvironmentSpoofHook hook = install(harness, gpsOptions());
        int registered = harness.hooks.size();
        assertTrue(registered > 0);
        hook.close(); hook.close();
        assertTrue(harness.hooks.isEmpty());
        assertEquals(registered, harness.revoked);
        hook.setSettings(settings(gpsOptions()));
        assertTrue(hook.diagnostics(hook.settings()).getBoolean("closed"));
        assertFalse(hook.settings().anyEnabled());
    }

    private static Method lastKnown() throws NoSuchMethodException {
        return LocationManager.class.getMethod("getLastKnownLocation", String.class);
    }

    private static Map<String, Object> gpsOptions() {
        return Map.of("gps_spoof_enabled", true, "gps_follow_region", false,
                "custom_latitude", "-33.865", "custom_longitude", "151.209");
    }

    private static EnvironmentSpoofHook install(Harness harness, Map<String, ?> options) {
        return EnvironmentSpoofHook.install(harness.framework(), settings(options), ignored -> { });
    }

    private static EnvironmentSpoofSettings settings(Map<String, ?> options) {
        return EnvironmentSpoofSettings.read(new LocalOptions(options));
    }

    private static EnvironmentSpoofSettings unsupportedTimeZone(boolean enabled) throws Exception {
        EnvironmentSpoofSettings value = settings(Map.of("sim_spoof_enabled", true,
                "language_spoof_enabled", true, "gps_spoof_enabled", true, "timezone_spoof_enabled", enabled));
        // A preset bypasses custom-input validation. Inject a missing tzdb entry to model an
        // Android platform whose timezone database predates the selected preset's zone ID.
        Field timeZone = EnvironmentSpoofSettings.class.getDeclaredField("timeZoneId");
        timeZone.setAccessible(true);
        timeZone.set(value, "Missing/Zone");
        assertEquals("Missing/Zone", value.timeZoneId);
        return value;
    }

    private static void assertOtherFamiliesWork(Harness harness) throws Throwable {
        assertEquals("jp", harness.call(TelephonyManager.class.getMethod("getSimCountryIso"), "actual"));
        assertEquals(Locale.JAPAN, harness.call(Locale.class.getMethod("getDefault"), Locale.ROOT));
        assertEquals(35.6762d, (Double) harness.call(Location.class.getMethod("getLatitude"), 0d), 0d);
    }

    private static Object[] arguments(Method target) {
        Object[] values = new Object[target.getParameterCount()];
        for (int i = 0; i < values.length; i++) {
            Class<?> type = target.getParameterTypes()[i];
            values[i] = type == int.class ? 1 : type == long.class ? 1L : type == boolean.class ? true
                    : type == String.class ? "gps" : type == Locale.Category.class ? Locale.Category.DISPLAY : null;
        }
        return values;
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> hooks = new LinkedHashMap<>();
        Method rejected;
        int revoked;

        XposedInterface framework() {
            return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, operation, args) -> {
                        if (operation.getName().equals("hook")) return builder((Method) args[0]);
                        return null;
                    });
        }

        private XposedInterface.HookBuilder builder(Method target) {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class}, (proxy, operation, args) -> {
                        if (!operation.getName().equals("intercept")) return proxy;
                        if (target.equals(rejected)) throw new IllegalStateException("fixture registration rejection");
                        assertNull("Duplicate registration: " + target, hooks.put(target, (XposedInterface.Hooker) args[0]));
                        return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, values) -> {
                                    if (action.getName().equals("unhook")) { hooks.remove(target); revoked++; }
                                    return null;
                                });
                    });
        }

        Object call(Method target, Object originalResult, Object... arguments) throws Throwable {
            return invoke(target, null, originalResult, new AtomicInteger(), null, arguments);
        }

        Object invoke(Method target, Object receiver, Object originalResult, AtomicInteger proceeds,
                      Throwable error, Object... arguments) throws Throwable {
            XposedInterface.Hooker hooker = hooks.get(target);
            assertNotNull("Missing Hook: " + target, hooker);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, action, values) -> {
                        switch (action.getName()) {
                            case "getArg": return arguments[(Integer) values[0]];
                            case "getArgs": return Arrays.asList(arguments);
                            case "getThisObject": return receiver;
                            case "getMember": return target;
                            case "proceed":
                                proceeds.incrementAndGet();
                                if (error != null) throw error;
                                return originalResult;
                            default: throw new UnsupportedOperationException(action.getName());
                        }
                    });
            return hooker.intercept(chain);
        }
    }
}
