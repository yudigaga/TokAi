package com.tokai.module.feature;

import com.tokai.module.config.ComponentHideSettings;
import com.tokai.module.config.ComponentHideSettings.Component;
import com.tokai.module.config.ExtendedSettings;
import com.tokai.module.diagnostics.TokAiLog;
import com.tokai.module.scan.HostFeatureSupport;
import com.tokai.module.scan.HostScanReport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Changes only the host's duration-based default, leaving its progress engine and gestures intact. */
final class ShortVideoProgressHook implements AutoCloseable {
    private final BehaviorHookGroup legacy;
    private final BehaviorHookGroup pro;
    private final Consumer<String> log;
    private volatile ComponentHideSettings settings;

    private ShortVideoProgressHook(ExtendedSettings settings, Consumer<String> log) {
        this.settings = settings == null ? null : settings.components;
        this.log = log == null ? ignored -> { } : log;
        legacy = new BehaviorHookGroup("short_progress_legacy", this.log);
        pro = new BehaviorHookGroup("short_progress_pro", this.log);
    }

    static ShortVideoProgressHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, ExtendedSettings settings, Consumer<String> log) {
        ShortVideoProgressHook hook = new ShortVideoProgressHook(settings, log);
        if (HostFeatureSupport.isTikTok(report)
                || report != null && report.getResults().containsKey("tiktok_short_progress_style")) {
            hook.installTikTokStyle(framework, report, loader);
            return hook;
        }
        hook.install(framework, report, loader, "component_short_progress_legacy", hook.legacy, 2);
        hook.install(framework, report, loader, "component_short_progress_pro", hook.pro, 1);
        return hook;
    }

    private void installTikTokStyle(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String[] callerIds = {"tiktok_short_progress_update", "tiktok_short_progress_tick",
                "tiktok_short_progress_status", "tiktok_short_progress_video_event",
                "tiktok_short_progress_restore", "tiktok_short_progress_recovery"};
        if (!legacy.requireDeclaredRules(report, "tiktok_short_progress_style", "tiktok_short_progress_owner",
                "tiktok_short_progress_gate", "aweme_ad_flag", callerIds[0], callerIds[1], callerIds[2],
                callerIds[3], callerIds[4], callerIds[5])) return;
        try {
            Method style = HostTargets.method(report, "tiktok_short_progress_style", loader);
            Method gate = HostTargets.method(report, "tiktok_short_progress_gate", loader);
            Method advertisement = HostTargets.method(report, "aweme_ad_flag", loader);
            Class<?> ownerType = HostTargets.type(report, "tiktok_short_progress_owner", loader);
            Field work = HostTargets.field(ownerType, "LLJLIL");
            Field seconds = HostTargets.field(ownerType, "LLJLLL");
            if (!style.getDeclaringClass().isAssignableFrom(ownerType)
                    || !gate.getDeclaringClass().isAssignableFrom(ownerType)
                    || style.getReturnType() != int.class || style.getParameterCount() != 1
                    || style.getParameterTypes()[0] != boolean.class || Modifier.isStatic(style.getModifiers())
                    || gate.getReturnType() != boolean.class || gate.getParameterCount() != 1
                    || gate.getParameterTypes()[0] != work.getType() || Modifier.isStatic(gate.getModifiers())
                    || seconds.getType() != int.class || Modifier.isStatic(seconds.getModifiers())
                    || Modifier.isStatic(work.getModifiers())
                    || !advertisement.getDeclaringClass().isAssignableFrom(work.getType())
                    || advertisement.getReturnType() != boolean.class || advertisement.getParameterCount() != 0
                    || Modifier.isStatic(advertisement.getModifiers())) {
                throw new IllegalStateException("TikTok duration style owner contract changed");
            }
            for (String id : callerIds) {
                Method caller = HostTargets.method(report, id, loader);
                if (caller.getReturnType() != void.class || Modifier.isStatic(caller.getModifiers())
                        || !framework.deoptimize(caller)) {
                    throw new IllegalStateException("TikTok duration style caller could not be deoptimized: " + id);
                }
            }
            legacy.add(framework, style, chain -> {
                Object result = chain.proceed();
                if (!legacy.isActive() || !Integer.valueOf(4).equals(result)) return result;
                try {
                    Object owner = chain.getThisObject();
                    Object current = work.get(owner);
                    int nativeSeconds = seconds.getInt(owner);
                    if (current == null || nativeSeconds < 0 || nativeSeconds >= 30
                            || Boolean.TRUE.equals(advertisement.invoke(current))
                            || overrideDecision(true, current, null)
                            || !Boolean.TRUE.equals(gate.invoke(owner, current))) return result;
                    // Style 4 is only the short-duration transparency. Preserve the complete native visibility gate.
                    TokAiLog.event("short_progress", "native_style", "duration_transparency_relaxed",
                            "native_gate=true;style_from=4;style_to=0;thin_bar_unchanged=true;rendered_progress=unobserved");
                    return 0;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    legacy.fail(failure);
                    return result;
                }
            });
            try { log.accept("TikTok short progress duration style registered; native_gate_retained=true; non_draggable_thin_bar=false"); }
            catch (RuntimeException ignored) { }
        } catch (Exception | LinkageError failure) { legacy.fail(failure); }
    }

    private void install(XposedInterface framework, HostScanReport report, ClassLoader loader,
                         String rule, BehaviorHookGroup group, int parameters) {
        if (!group.requireDeclaredRules(report, rule)) return;
        try {
            Method target = HostTargets.method(report, rule, loader);
            if (target.getReturnType() != boolean.class || target.getParameterCount() != parameters) {
                throw new IllegalStateException("Short progress predicate contract changed");
            }
            group.add(framework, target, chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result)) return result;
                Object duration = parameters == 2 ? chain.getArg(1) : null;
                return overrideDecision(true, chain.getArg(0), duration);
            });
        } catch (Exception failure) { group.fail(failure); }
    }

    void setSettings(ExtendedSettings value) { settings = value == null ? null : value.components; }

    boolean overrideDecision(boolean hidden, Object work, Object suppliedDuration) {
        ComponentHideSettings current = settings;
        if (!hidden || current == null || !current.configured || !current.enabled
                || current.alpha(Component.PROGRESS) <= 0 || work == null) {
            TokAiLog.event("short_progress", "decision", "disabled_or_not_applicable", "native_predicate_retained=true");
            return hidden;
        }
        if (Boolean.TRUE.equals(HostTargets.invoke(work, "getAd"))
                || Boolean.TRUE.equals(HostTargets.invoke(work, "getIsAdAweme"))) {
            TokAiLog.event("short_progress", "decision", "advertisement", "native_predicate_retained=true");
            return hidden;
        }
        Object type = HostTargets.invoke(work, "getAwemeType");
        if (!(type instanceof Number) || ((Number) type).intValue() != 0) {
            TokAiLog.event("short_progress", "decision", "work_type_not_video", "native_predicate_retained=true");
            return hidden;
        }
        Object video = HostTargets.invoke(work, "getVideo");
        if (video == null) {
            TokAiLog.event("short_progress", "decision", "video_model_unavailable", "native_predicate_retained=true");
            return hidden;
        }
        Object duration = suppliedDuration;
        if (!(duration instanceof Number)) duration = HostTargets.invoke(video, "getDuration");
        if (!(duration instanceof Number)) duration = HostTargets.read(video, "x");
        if (!(duration instanceof Number)) duration = HostTargets.read(work, "duration");
        if (!(duration instanceof Number)) {
            TokAiLog.event("short_progress", "decision", "duration_unavailable", "native_predicate_retained=true");
            return hidden;
        }
        long millis = ((Number) duration).longValue();
        boolean result = millis <= 0 || millis > 30_000;
        TokAiLog.event("short_progress", "predicate_result", result ? "duration_outside_short_range" : "short_progress_allowed",
                "duration_ms=" + millis + ";returned_hidden=" + result + ";rendered_progress=unobserved");
        return result;
    }

    boolean isLegacyInstalled() { return legacy.isActive(); }
    boolean isProInstalled() { return pro.isActive(); }

    String installationFailure() {
        if (isLegacyInstalled() || isProInstalled()) return "";
        String first = legacy.installationFailure(), second = pro.installationFailure();
        if (!first.isEmpty() && !com.tokai.module.scan.HostFeatureSupport.isUnavailable(first)) return first;
        if (!second.isEmpty() && !com.tokai.module.scan.HostFeatureSupport.isUnavailable(second)) return second;
        return first.isEmpty() ? second : first;
    }

    @Override public void close() {
        settings = null;
        legacy.close();
        pro.close();
    }
}
