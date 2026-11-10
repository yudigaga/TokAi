package com.tokai.module.feature;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.SystemClock;
import android.telephony.TelephonyManager;

import com.tokai.module.config.EnvironmentSpoofSettings;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;

/** Toki 1.0.4's four Java environment surfaces, scoped by the module to TikTok processes. */
public final class EnvironmentSpoofHook implements AutoCloseable {
    private final XposedInterface framework;
    private final Consumer<String> log;
    private final Map<String, BehaviorHookGroup> groups = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final LocaleList realLocaleList;
    private final TimeZone originalTimeZone;
    private volatile State state;
    private boolean defaultTimeZoneOverridden;
    private boolean timeZoneConfigurationFailed;
    private boolean closed;

    private EnvironmentSpoofHook(XposedInterface framework, Consumer<String> log) {
        this.framework = framework;
        this.log = log == null ? ignored -> { } : log;
        // Capture these before installing getDefault hooks; LocaleList protects native text layout.
        realLocaleList = LocaleList.getDefault();
        originalTimeZone = (TimeZone) TimeZone.getDefault().clone();
        state = new State(EnvironmentSpoofSettings.defaults(), false);
    }

    public static EnvironmentSpoofHook install(XposedInterface framework,
                                               EnvironmentSpoofSettings settings,
                                               Consumer<String> log) {
        if (framework == null || settings == null) throw new IllegalArgumentException();
        EnvironmentSpoofHook hook = new EnvironmentSpoofHook(framework, log);
        hook.register("sim", hook::installSim);
        hook.register("language", hook::installLanguage);
        hook.register("timezone", hook::installTimeZone);
        hook.register("gps", hook::installGps);
        hook.setSettings(settings);
        return hook;
    }

    private void register(String family, Installer installer) {
        BehaviorHookGroup group = new BehaviorHookGroup("environment_" + family, log);
        try {
            installer.install(group);
            if (!group.isActive()) throw new NoSuchMethodException("No " + family + " methods");
            groups.put(family, group);
            failures.remove(family);
        } catch (Throwable failure) {
            group.fail(failure);
            failures.put(family, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private void installSim(BehaviorHookGroup group) {
        for (Method method : TelephonyManager.class.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) continue;
            String name = method.getName();
            Class<?> expected;
            switch (name) {
                case "getSimCountryIso": case "getNetworkCountryIso":
                case "getSimOperator": case "getNetworkOperator":
                case "getSimOperatorName": case "getNetworkOperatorName":
                    expected = String.class; break;
                case "getSimState": case "getPhoneType": expected = int.class; break;
                case "hasIccCard": expected = boolean.class; break;
                default: continue;
            }
            if (method.getReturnType() != expected) continue;
            group.add(framework, method, chain -> {
                EnvironmentSpoofSettings current = state.settings;
                if (!current.simEnabled) return chain.proceed();
                switch (name) {
                    case "getSimCountryIso": case "getNetworkCountryIso":
                        return current.regionCode.toLowerCase(Locale.ROOT);
                    case "getSimOperator": case "getNetworkOperator": return current.operatorCode;
                    case "getSimOperatorName": case "getNetworkOperatorName": return current.operatorName;
                    case "getSimState": return TelephonyManager.SIM_STATE_READY;
                    case "hasIccCard": return true;
                    case "getPhoneType": return TelephonyManager.PHONE_TYPE_GSM;
                    default: return chain.proceed();
                }
            });
        }
    }

    private void installLanguage(BehaviorHookGroup group) {
        for (Method method : Locale.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && "getDefault".equals(method.getName())
                    && method.getReturnType() == Locale.class) {
                group.add(framework, method, chain -> {
                    State current = state;
                    return current.settings.languageEnabled ? current.locale : chain.proceed();
                });
            }
        }
        for (Method method : LocaleList.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == LocaleList.class
                    && ("getDefault".equals(method.getName()) || "getAdjustedDefault".equals(method.getName()))) {
                group.add(framework, method, chain -> state.settings.languageEnabled
                        ? realLocaleList : chain.proceed());
            }
        }
    }

    private void installTimeZone(BehaviorHookGroup group) throws NoSuchMethodException {
        group.add(framework, TimeZone.class.getDeclaredMethod("getDefault"), chain -> {
            State current = state;
            return current.timeZone != null ? current.timeZone.clone() : chain.proceed();
        });
        group.add(framework, ZoneId.class.getDeclaredMethod("systemDefault"), chain -> {
            State current = state;
            return current.zoneId != null ? current.zoneId : chain.proceed();
        });
        group.add(framework, android.icu.util.TimeZone.class.getDeclaredMethod("getDefault"), chain -> {
            State current = state;
            return current.icuTimeZone != null ? current.icuTimeZone.clone() : chain.proceed();
        });
    }

    private void installGps(BehaviorHookGroup group) throws NoSuchMethodException {
        group.add(framework, Location.class.getMethod("getLatitude"), chain -> {
            EnvironmentSpoofSettings current = state.settings;
            return current.gpsEnabled ? current.latitude : chain.proceed();
        });
        group.add(framework, Location.class.getMethod("getLongitude"), chain -> {
            EnvironmentSpoofSettings current = state.settings;
            return current.gpsEnabled ? current.longitude : chain.proceed();
        });
        group.add(framework, Location.class.getMethod("isFromMockProvider"), chain ->
                state.settings.gpsEnabled ? false : chain.proceed());
        group.add(framework, LocationManager.class.getMethod("getLastKnownLocation", String.class), chain -> {
            EnvironmentSpoofSettings current = state.settings;
            Object original = chain.proceed();
            if (!current.gpsEnabled) return original;
            String provider = chain.getArg(0) instanceof String ? (String) chain.getArg(0) : "gps";
            return spoofLocation(original instanceof Location ? (Location) original : null, provider, current);
        });
    }

    static Location spoofLocation(Location original, String provider, EnvironmentSpoofSettings settings) {
        // Copy cached service results so a caller cannot contaminate another consumer's original object.
        Location result = original == null ? new Location(provider == null ? "gps" : provider)
                : new Location(original);
        result.setLatitude(settings.latitude);
        result.setLongitude(settings.longitude);
        if (original == null) {
            result.setAltitude(35d);
            result.setAccuracy(5f);
            result.setTime(System.currentTimeMillis());
            result.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
        return result;
    }

    /** Applied only while bootstrapping a process; subsequent preference edits need a restart. */
    public synchronized void setSettings(EnvironmentSpoofSettings settings) {
        if (closed) return;
        // The module can replace its provisional remote snapshot with host-local settings before
        // Application startup. A bad provisional zone must not prevent a valid local zone loading.
        if (timeZoneConfigurationFailed && !groups.containsKey("timezone")) {
            register("timezone", this::installTimeZone);
        }
        State next = new State(settings, groups.containsKey("timezone"));
        state = next;
        if (next.timeZoneFailure != null) {
            BehaviorHookGroup group = groups.remove("timezone");
            if (group != null) group.fail(next.timeZoneFailure);
            failures.put("timezone", next.timeZoneFailure.getClass().getSimpleName()
                    + ": " + next.timeZoneFailure.getMessage());
            timeZoneConfigurationFailed = true;
        } else if (groups.containsKey("timezone")) {
            timeZoneConfigurationFailed = false;
        }
        if (next.timeZone != null && groups.containsKey("timezone")) {
            TimeZone.setDefault(next.timeZone);
            defaultTimeZoneOverridden = true;
        } else if (defaultTimeZoneOverridden) {
            TimeZone.setDefault(originalTimeZone);
            defaultTimeZoneOverridden = false;
        }
    }

    public EnvironmentSpoofSettings settings() { return state.settings; }

    public synchronized Bundle diagnostics(EnvironmentSpoofSettings requested) {
        Bundle result = new Bundle();
        result.putBoolean("restart_required", !state.settings.equals(requested));
        result.putString("region", state.settings.regionCode);
        result.putBoolean("closed", closed);
        for (String family : new String[]{"sim", "language", "timezone", "gps"}) {
            BehaviorHookGroup group = groups.get(family);
            result.putBoolean(family + ".installed", group != null && group.isActive());
            result.putString(family + ".failure", failures.getOrDefault(family, ""));
        }
        result.putBoolean("sim.enabled", state.settings.simEnabled);
        result.putBoolean("language.enabled", state.settings.languageEnabled);
        result.putBoolean("timezone.enabled", state.settings.timeZoneEnabled);
        result.putBoolean("gps.enabled", state.settings.gpsEnabled);
        return result;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        state = new State(EnvironmentSpoofSettings.defaults(), false);
        for (BehaviorHookGroup group : groups.values()) group.close();
        if (defaultTimeZoneOverridden) TimeZone.setDefault(originalTimeZone);
        defaultTimeZoneOverridden = false;
    }

    private interface Installer { void install(BehaviorHookGroup group) throws Exception; }

    private static final class State {
        final EnvironmentSpoofSettings settings;
        final Locale locale;
        final TimeZone timeZone;
        final ZoneId zoneId;
        final android.icu.util.TimeZone icuTimeZone;
        final Throwable timeZoneFailure;

        State(EnvironmentSpoofSettings settings, boolean timeZoneRegistered) {
            this.settings = settings;
            locale = Locale.forLanguageTag(settings.languageTag.replace('_', '-'));
            ZoneId selectedZone = null;
            TimeZone selectedJavaZone = null;
            android.icu.util.TimeZone selectedIcuZone = null;
            Throwable zoneFailure = null;
            // tzdb and ICU availability belong solely to the enabled timezone feature. In
            // particular, a preset ID absent on an older OS cannot disable SIM/language/GPS.
            if (settings.timeZoneEnabled && timeZoneRegistered) {
                try {
                    selectedZone = ZoneId.of(settings.timeZoneId);
                    // Resolve fixed offsets through ZoneId; the String overload can fall back to GMT.
                    selectedJavaZone = TimeZone.getTimeZone(selectedZone);
                    selectedIcuZone = android.icu.util.TimeZone.getTimeZone(selectedJavaZone.getID());
                } catch (RuntimeException | LinkageError unavailable) {
                    selectedZone = null;
                    selectedJavaZone = null;
                    selectedIcuZone = null;
                    zoneFailure = unavailable;
                }
            }
            zoneId = selectedZone;
            timeZone = selectedJavaZone;
            icuTimeZone = selectedIcuZone;
            timeZoneFailure = zoneFailure;
        }
    }
}
