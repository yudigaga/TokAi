package com.tokai.module.feature;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

import com.tokai.module.config.CaptionVisibilitySettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.diagnostics.TokAiLog;
import com.tokai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Version-bound host UI gates and reversible native view visibility. */
public final class HostBehaviorHooks implements AutoCloseable {
    private static final String HIDDEN_SKIP = "prevent_skip_hidden";
    private static final int MAX_VIEW_UPDATES_PER_FRAME = 64;
    private static final long MAX_VIEW_UPDATE_NANOS_PER_FRAME = 4_000_000L;
    private final Map<String, BehaviorHookGroup> groups = new LinkedHashMap<>();
    private final Map<View, HiddenView> hidden = new WeakHashMap<>();
    private final Map<View, String> observed = new WeakHashMap<>();
    private final Map<String, Map<View, Boolean>> observedByKey = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> log;
    private final AtomicLong hiddenCurrentHits = new AtomicLong();
    private final AtomicLong hiddenCurrentBlocked = new AtomicLong();
    private final AtomicLong hiddenBackgroundHits = new AtomicLong();
    private final AtomicLong hiddenBackgroundBlocked = new AtomicLong();
    private final AtomicLong observedRefreshGeneration = new AtomicLong();
    private final AtomicBoolean hiddenCurrentLogged = new AtomicBoolean();
    private final AtomicBoolean hiddenBackgroundLogged = new AtomicBoolean();
    private volatile boolean hiddenSkipReady;
    private volatile LocalOptions options;
    private volatile boolean closed;
    private TikTokVideoSpacesAdapter tikTokVideoSpaces;
    private TikTokVideoCoverAdapter tikTokVideoCover;

    private HostBehaviorHooks(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static HostBehaviorHooks install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, Context context,
                                             LocalOptions options, Consumer<String> log) {
        return installWithHiddenTaskContract(framework, report, loader, context, options, log, null);
    }

    enum HiddenSafetyTaskContract {
        HOST_406(0, null), HOST_408(0, null);
        final int currentBranch;
        final Integer backgroundBranch;
        HiddenSafetyTaskContract(int currentBranch, Integer backgroundBranch) {
            this.currentBranch = currentBranch;
            this.backgroundBranch = backgroundBranch;
        }
        static HiddenSafetyTaskContract resolve(String current, String background) {
            if ("Lkotlin/jvm/internal/ALambdaS32S1500000_4;".equals(current) && "LX/06ht;".equals(background)) return HOST_406;
            if ("Lkotlin/jvm/internal/ALambdaS38S1500000_35;".equals(current)
                    && "LX/12bW;".equals(background)) return HOST_408;
            throw new IllegalStateException("Hidden video safety task profile combination changed");
        }
    }

    /** Package-local seam replays either proven contract with JVM fixture task classes. */
    static HostBehaviorHooks installWithHiddenTaskContract(XposedInterface framework, HostScanReport report,
                                                            ClassLoader loader, Context context,
                                                            LocalOptions options, Consumer<String> log,
                                                            HiddenSafetyTaskContract hiddenContract) {
        HostBehaviorHooks hooks = new HostBehaviorHooks(options, log);
        boolean tikTok = com.tokai.module.scan.HostFeatureSupport.isTikTok(report);
        hooks.suppress(framework, report, loader, "remove_splash_ads", false,
                "adv_ui_splash_gate");
        if (!tikTok) hooks.suppress(framework, report, loader, "remove_teen_dialog", false,
                "adv_ui_teen_guide");
        hooks.installUpdates(framework, report, loader);
        if (tikTok) {
            // TikTok's handleDoubleClick directly delegates to IDiggComponentAbility.
            // The separate Douyin big-digg callback is not a TikTok dependency.
            hooks.suppress(framework, report, loader, "disable_double_tap_like", null, "adv_ui_double_tap");
        } else {
            hooks.suppress(framework, report, loader, "disable_double_tap_like", null,
                    "adv_ui_double_tap", "adv_ui_big_digg");
        }
        if (!tikTok) hooks.installHiddenSkip(framework, report, loader, hiddenContract);
        if (tikTok) {
            hooks.installTikTokInsertVideo(framework, report, loader);
            hooks.installCaptionRenderer(framework, report, loader);
            hooks.installForceCaptions(framework, report, loader);
            hooks.suppress(framework, report, loader, "disable_data_saver", false, "tiktok_data_saver_enabled");
        } else {
            hooks.suppress(framework, report, loader, "prevent_insert_video", null,
                    "adv_ui_insert_single", "adv_ui_insert_single_fallback",
                    "adv_ui_insert_list", "adv_ui_insert_list_fallback");
        }
        hooks.suppress(framework, report, loader, "block_long_press_speed", null,
                "adv_ui_long_press_speed");
        hooks.installBlackEdges(framework, report, loader);
        hooks.installPublishButton(framework, report, loader);
        return hooks;
    }

    public boolean supports(String key) {
        BehaviorHookGroup group = groups.get(key);
        return group != null && group.isActive() && (!HIDDEN_SKIP.equals(key) || hiddenSkipReady);
    }

    public String installationFailure(String key) {
        BehaviorHookGroup group = groups.get(key);
        return group == null ? "" : group.installationFailure();
    }

    /** Aggregate route counters contain no work IDs, provider data, content, or URLs. */
    public void appendHiddenSkipDiagnostics(Bundle snapshot) {
        if (snapshot == null) return;
        LocalOptions current = options;
        BehaviorHookGroup group = groups.get(HIDDEN_SKIP);
        snapshot.putBoolean("hidden_skip_enabled", !closed && current != null && current.enabled(HIDDEN_SKIP));
        snapshot.putBoolean("hidden_skip_installed", supports(HIDDEN_SKIP));
        snapshot.putLong("hidden_skip_current_hits", hiddenCurrentHits.get());
        snapshot.putLong("hidden_skip_current_blocked", hiddenCurrentBlocked.get());
        snapshot.putLong("hidden_skip_background_hits", hiddenBackgroundHits.get());
        snapshot.putLong("hidden_skip_background_blocked", hiddenBackgroundBlocked.get());
        snapshot.putString("hidden_skip_failure", group == null ? "" : group.installationFailure());
    }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        LocalOptions previous = this.options;
        this.options = options;
        java.util.Set<String> changedKeys = new java.util.LinkedHashSet<>();
        for (String key : new String[]{"hide_video_black_edges", "hide_camera_plus"}) {
            if (previous == null || options == null || previous.enabled(key) != options.enabled(key)) {
                changedKeys.add(key);
            }
        }
        // Settings sync republishes full snapshots during startup. The LSPosed trace showed the
        // weak-map snapshot itself occupying the main thread when none of these gates changed.
        if (changedKeys.isEmpty()) return;
        long generation = observedRefreshGeneration.incrementAndGet();
        onMain(() -> refreshObservedViews(generation, changedKeys));
    }

    private void refreshObservedViews(long generation, java.util.Set<String> changedKeys) {
        if (closed || generation != observedRefreshGeneration.get()) return;
        for (String key : changedKeys) {
            Map<View, Boolean> views = observedByKey.get(key);
            if (views != null) refreshObservedBatch(views.entrySet().iterator(), key, generation, changedKeys);
        }
        if (changedKeys.contains("hide_video_black_edges") && tikTokVideoSpaces != null) {
            try { tikTokVideoSpaces.reflowAll(); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                failBlackEdges(failure);
            }
        }
        if (changedKeys.contains("hide_video_black_edges") && tikTokVideoCover != null) {
            try { tikTokVideoCover.reflowAll(); }
            catch (ReflectiveOperationException | RuntimeException failure) { failBlackEdges(failure); }
        }
    }

    private void refreshObservedBatch(Iterator<Map.Entry<View, Boolean>> iterator, String key,
                                     long generation, java.util.Set<String> changedKeys) {
        if (closed || generation != observedRefreshGeneration.get()) return;
        long started = android.os.SystemClock.uptimeMillis();
        int count = 0;
        try {
            while (count < MAX_VIEW_UPDATES_PER_FRAME
                    && android.os.SystemClock.uptimeMillis() - started < MAX_VIEW_UPDATE_NANOS_PER_FRAME / 1_000_000L
                    && iterator.hasNext()) {
                Map.Entry<View, Boolean> entry = iterator.next();
                updateView(entry.getKey(), key);
                count++;
            }
            if (iterator.hasNext()) main.postDelayed(
                    () -> refreshObservedBatch(iterator, key, generation, changedKeys), 16L);
        } catch (ConcurrentModificationException changed) {
            main.postDelayed(() -> refreshObservedViews(generation, changedKeys), 16L);
        }
    }

    private boolean enabled(String key) {
        LocalOptions current = options;
        return !closed && current != null && current.enabled(key) && supports(key);
    }

    private void suppress(XposedInterface framework, HostScanReport report, ClassLoader loader,
                          String key, Object skippedResult, String... rules) {
        BehaviorHookGroup group = newGroup(key);
        if (!group.requireDeclaredRules(report, rules)) return;
        try {
            ArrayList<Method> targets = new ArrayList<>();
            for (String rule : rules) targets.add(HostTargets.method(report, rule, loader));
            for (Method target : targets) {
                boolean returnsBoolean = target.getReturnType() == boolean.class;
                if ((returnsBoolean && !(skippedResult instanceof Boolean))
                        || (!returnsBoolean && target.getReturnType() != void.class)) {
                    throw new IllegalStateException("Suppression contract changed: " + target);
                }
                group.add(framework, target, chain -> {
                    boolean apply = enabled(key);
                    TokAiLog.event(key, "decision", apply ? "enabled_host_call_blocked" : "disabled_host_call_forwarded",
                            "host_invoked=" + !apply + ";result_scope=local_interception");
                    if (apply) {
                        TokAiLog.Operation operation = TokAiLog.begin(key, "host_gate");
                        operation.finish(TokAiLog.Outcome.SUCCESS, "host_call_suppressed",
                                "host_invoked=false;result_scope=local_interception;durable_effect=unobserved");
                        return skippedResult;
                    }
                    return chain.proceed();
                });
            }
            reportInstalled(key, targets.size());
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installCaptionRenderer(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "hide_native_captions";
        BehaviorHookGroup group = newGroup(key);
        if (!group.requireDeclaredRules(report, "tiktok_caption_display_gate")) return;
        try {
            Method target = HostTargets.method(report, "tiktok_caption_display_gate", loader);
            Class<?>[] types = target.getParameterTypes();
            if (target.getReturnType() != void.class || types.length != 4
                    || types[0] != String.class || types[2] != String.class
                    || !types[1].isEnum() || !types[3].isEnum()) {
                throw new IllegalStateException("TikTok caption renderer contract changed");
            }
            Object hidden = null;
            for (Object state : types[1].getEnumConstants()) {
                if ("HIDDEN".equals(((Enum<?>) state).name())) hidden = state;
            }
            if (hidden == null) throw new IllegalStateException("TikTok caption hidden state is unavailable");
            Object hiddenState = hidden;
            group.add(framework, target, chain -> {
                if (!enabled(key)) return chain.proceed();
                Object[] arguments = chain.getArgs().toArray();
                arguments[1] = hiddenState;
                // Continue native rendering so it clears an already visible caption as well.
                return chain.proceed(arguments);
            });
            reportInstalled(key, 1);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private boolean forceCaptionsEnabled() {
        LocalOptions current = options;
        return !closed && current != null && current.enabled(CaptionVisibilitySettings.FORCE)
                && !current.enabled(CaptionVisibilitySettings.HIDE) && supports(CaptionVisibilitySettings.FORCE);
    }

    private void installForceCaptions(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = CaptionVisibilitySettings.FORCE;
        BehaviorHookGroup group = newGroup(key);
        String[] rules = {"tiktok_caption_base_state_scope", "tiktok_caption_user_setting_gate",
                "tiktok_caption_display_policy_caller", "tiktok_caption_scope_video_caller",
                "tiktok_caption_scope_setting_caller", "tiktok_caption_scope_duplicate_caller",
                "tiktok_caption_scope_override_caller"};
        if (!group.requireDeclaredRules(report, rules)) return;
        try {
            ArrayList<Method> targets = new ArrayList<>();
            for (String rule : rules) targets.add(HostTargets.method(report, rule, loader));
            Method scope = targets.get(0), setting = targets.get(1), policy = targets.get(2);
            Class<?>[] settingTypes = setting.getParameterTypes(), policyTypes = policy.getParameterTypes();
            if (Modifier.isStatic(scope.getModifiers()) || scope.getReturnType() != void.class
                    || scope.getParameterCount() != 1 || scope.getParameterTypes()[0] != boolean.class
                    || !Modifier.isStatic(setting.getModifiers()) || setting.getReturnType() != boolean.class
                    || settingTypes.length != 1 || settingTypes[0].isPrimitive()
                    || !Modifier.isStatic(policy.getModifiers()) || policy.getReturnType() != boolean.class
                    || policy.getDeclaringClass() != setting.getDeclaringClass() || policyTypes.length != 2
                    || policyTypes[0] != settingTypes[0] || policyTypes[1] != boolean.class) {
                throw new IllegalStateException("TikTok caption preference scope contract changed");
            }
            for (int index = 3; index < targets.size(); index++) {
                Method caller = targets.get(index);
                Class<?>[] parameters = caller.getParameterTypes();
                if (Modifier.isStatic(caller.getModifiers()) || caller.getReturnType() != Object.class
                        || parameters.length != (index == 3 ? 1 : 2) || parameters[0] != Object.class
                        || parameters.length == 2 && parameters[1].isPrimitive()) {
                    throw new IllegalStateException("TikTok caption scope caller contract changed");
                }
            }
            // Both the small preference read and the enclosing scope can be inlined by ART.
            for (Method caller : new Method[]{scope, policy, targets.get(3), targets.get(4),
                    targets.get(5), targets.get(6)}) {
                if (!framework.deoptimize(caller)) {
                    throw new IllegalStateException("TikTok caption caller was not deoptimized: " + caller);
                }
            }
            ThreadLocal<Integer> depth = new ThreadLocal<>();
            group.add(framework, scope, chain -> {
                if (!forceCaptionsEnabled()) return chain.proceed();
                Integer previous = depth.get();
                depth.set(previous == null ? 1 : previous + 1);
                try { return chain.proceed(); }
                finally {
                    if (previous == null) depth.remove();
                    else depth.set(previous);
                }
            });
            group.add(framework, setting, chain -> {
                Object result = chain.proceed();
                if (!forceCaptionsEnabled() || chain.getArg(0) == null) return result;
                Integer current = depth.get();
                if (current == null) { depth.remove(); return result; }
                return current > 0 ? Boolean.TRUE : result;
            });
            reportInstalled(key, 2);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installTikTokInsertVideo(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "prevent_insert_video";
        BehaviorHookGroup group = newGroup(key);
        if (!group.requireDeclaredRules(report, "tiktok_feed_insert_single", "tiktok_feed_insert_batch",
                "tiktok_feed_insert_count", "tiktok_feed_insert_legacy", "tiktok_feed_insert_items",
                "tiktok_feed_insert_legacy_dispatch")) return;
        try {
            Method single = HostTargets.method(report, "tiktok_feed_insert_single", loader);
            Method batch = HostTargets.method(report, "tiktok_feed_insert_batch", loader);
            Method count = HostTargets.method(report, "tiktok_feed_insert_count", loader);
            Method legacy = HostTargets.method(report, "tiktok_feed_insert_legacy", loader);
            Method items = HostTargets.method(report, "tiktok_feed_insert_items", loader);
            Method legacyCaller = HostTargets.method(report, "tiktok_feed_insert_legacy_dispatch", loader);
            Class<?>[] one = single.getParameterTypes(), many = batch.getParameterTypes();
            if (one.length != 3 || many.length != 3 || one[1] != int.class || many[1] != int.class
                    || !java.util.List.class.isAssignableFrom(many[0]) || one[2] != many[2]
                    || single.getReturnType() != batch.getReturnType() || count.getReturnType() != int.class
                    || count.getParameterCount() != 0 || single.getDeclaringClass() != batch.getDeclaringClass()
                    || single.getDeclaringClass() != count.getDeclaringClass()) {
                throw new IllegalStateException("TikTok insertion contract changed");
            }
            if (legacy.getReturnType() != void.class || legacy.getParameterCount() != 3
                    || legacy.getParameterTypes()[0] != int.class || legacy.getParameterTypes()[1] != String.class
                    || !java.util.List.class.isAssignableFrom(legacy.getParameterTypes()[2])
                    || items.getParameterCount() != 0 || !java.util.List.class.isAssignableFrom(items.getReturnType())
                    || items.getDeclaringClass() != legacy.getDeclaringClass()
                    || legacyCaller.getReturnType() != void.class || !framework.deoptimize(legacyCaller)) {
                throw new IllegalStateException("TikTok legacy insertion contract changed or caller was not deoptimized");
            }
            Field token = HostTargets.field(one[2], "LIZ");
            if (token.getType() != String.class) throw new IllegalStateException("TikTok insertion source changed");
            Constructor<?> result = single.getReturnType().getConstructor(int.class, int.class, String.class);
            for (Method target : new Method[]{single, batch}) {
                group.add(framework, target, chain -> {
                    if (!enabled(key) || Looper.myLooper() != Looper.getMainLooper()) return chain.proceed();
                    Object rejected = null;
                    try {
                        Object source = chain.getArg(2);
                        String name = source == null ? null : (String) token.get(source);
                        int position = ((Number) chain.getArg(1)).intValue();
                        Object payload = chain.getArg(0);
                        boolean hasItems = target == single ? payload != null
                                : payload instanceof java.util.List && !((java.util.List<?>) payload).isEmpty();
                        // Unknown/explicit actions, refreshes, initial data and tail appends stay native.
                        if (hasItems && automaticInsertSource(name) && position >= 0
                                && position < ((Number) count.invoke(chain.getThisObject())).intValue()) {
                            rejected = result.newInstance(position, 100, "Automatic insertion blocked by TokAi");
                            TokAiLog.event(key, "decision", "automatic_insertion_blocked",
                                    "source=" + name + ";native_result=100;list_mutated=false");
                        }
                    } catch (ReflectiveOperationException | RuntimeException failure) { group.fail(failure); }
                    return rejected == null ? chain.proceed() : rejected;
                });
            }
            group.add(framework, legacy, chain -> {
                if (!enabled(key) || Looper.myLooper() != Looper.getMainLooper()) return chain.proceed();
                boolean blocked = false;
                try {
                    int position = ((Number) chain.getArg(0)).intValue();
                    Object payload = chain.getArg(2);
                    if (automaticInsertSource((String) chain.getArg(1)) && position >= 0
                            && payload instanceof java.util.List && !((java.util.List<?>) payload).isEmpty()) {
                        Object existing = items.invoke(chain.getThisObject());
                        blocked = existing instanceof java.util.List && position < ((java.util.List<?>) existing).size();
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) { group.fail(failure); }
                if (!blocked) return chain.proceed();
                TokAiLog.event(key, "decision", "legacy_automatic_insertion_blocked", "list_mutated=false");
                return null;
            });
            reportInstalled(key, 3);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private static boolean automaticInsertSource(String source) {
        if (source == null) return false;
        switch (source) {
            case "middle_insert_when_video_lagging": case "real_time_lynx_card": case "paid_ads_video":
            case "pgc_drama_dnu_card": case "drama_nuj_video_card": case "pro_us_discover_insert":
            case "diff_type_minis_instant_play_preview_insert": return true;
            default: return false;
        }
    }

    private void installHiddenSkip(XposedInterface framework, HostScanReport report, ClassLoader loader,
                                   HiddenSafetyTaskContract fixtureContract) {
        BehaviorHookGroup group = newGroup(HIDDEN_SKIP);
        if (!group.requireDeclaredRules(report, "adv_ui_delete_aweme", "adv_ui_delete_aweme_full_feed",
                "adv_ui_hidden_recall_task", "adv_ui_hidden_background_task")) return;
        try {
            Method baseDelete = HostTargets.method(report, "adv_ui_delete_aweme", loader);
            Method fullDelete = HostTargets.method(report, "adv_ui_delete_aweme_full_feed", loader);
            Method currentTask = HostTargets.method(report, "adv_ui_hidden_recall_task", loader);
            Method backgroundTask = HostTargets.method(report, "adv_ui_hidden_background_task", loader);
            HiddenSafetyTaskContract contract = fixtureContract != null ? fixtureContract
                    : HiddenSafetyTaskContract.resolve(
                            HostTargets.candidate(report, "adv_ui_hidden_recall_task").getClassDescriptor(),
                            HostTargets.candidate(report, "adv_ui_hidden_background_task").getClassDescriptor());
            for (Method target : new Method[]{baseDelete, fullDelete}) {
                Class<?>[] parameters = target.getParameterTypes();
                if (Modifier.isStatic(target.getModifiers()) || target.getReturnType() != void.class
                        || parameters.length != 2 || parameters[0] != int.class || parameters[1].isPrimitive()) {
                    throw new IllegalStateException("Hidden video legacy removal contract changed");
                }
            }
            for (Method target : new Method[]{currentTask, backgroundTask}) {
                if (Modifier.isStatic(target.getModifiers()) || target.getParameterCount() != 0
                        || target.getReturnType() != Object.class || !"invoke".equals(target.getName())) {
                    throw new IllegalStateException("Hidden video safety task contract changed");
                }
            }
            Field discriminator = currentTask.getDeclaringClass().getDeclaredField("$t");
            Field backgroundDiscriminator = null;
            if (contract.backgroundBranch != null) {
                backgroundDiscriminator = backgroundTask.getDeclaringClass().getDeclaredField("$t");
            } else {
                for (Field field : backgroundTask.getDeclaringClass().getDeclaredFields()) {
                    if ("$t".equals(field.getName())) throw new IllegalStateException("Dedicated safety task unexpectedly has a discriminator");
                }
            }
            for (Field field : new Field[]{discriminator, backgroundDiscriminator}) {
                if (field == null) continue;
                if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalStateException("Hidden video safety task discriminator changed");
                }
                field.setAccessible(true);
            }
            final Field backgroundBranchField = backgroundDiscriminator;
            Class<?> unitType = Class.forName("kotlin.Unit", false, loader);
            Field instance = unitType.getField("INSTANCE");
            if (!Modifier.isStatic(instance.getModifiers()) || instance.getType() != unitType) {
                throw new IllegalStateException("Host Kotlin Unit contract changed");
            }
            Object hostUnit = instance.get(null);
            if (!unitType.isInstance(hostUnit)) throw new IllegalStateException("Host Kotlin Unit instance unavailable");

            // Ready remains false until all four handles exist, including during partial installation.
            group.add(framework, baseDelete, chain -> enabled(HIDDEN_SKIP) ? null : chain.proceed());
            group.add(framework, fullDelete, chain -> enabled(HIDDEN_SKIP) ? null : chain.proceed());
            group.add(framework, currentTask, chain -> {
                if (closed || !hiddenSkipReady || !group.isActive()) return chain.proceed();
                int branch;
                try { branch = discriminator.getInt(chain.getThisObject()); }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    hiddenSkipReady = false;
                    group.fail(failure);
                    return chain.proceed();
                }
                if (branch != contract.currentBranch) return chain.proceed();
                hiddenCurrentHits.incrementAndGet();
                if (!enabled(HIDDEN_SKIP)) return chain.proceed();
                hiddenCurrentBlocked.incrementAndGet();
                logHiddenSkipOnce(hiddenCurrentLogged, "current");
                return hostUnit;
            });
            group.add(framework, backgroundTask, chain -> {
                if (closed || !hiddenSkipReady || !group.isActive()) return chain.proceed();
                if (backgroundBranchField != null) {
                    int branch;
                    try { branch = backgroundBranchField.getInt(chain.getThisObject()); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        hiddenSkipReady = false;
                        group.fail(failure);
                        return chain.proceed();
                    }
                    if (branch != contract.backgroundBranch) return chain.proceed();
                }
                hiddenBackgroundHits.incrementAndGet();
                if (!enabled(HIDDEN_SKIP)) return chain.proceed();
                hiddenBackgroundBlocked.incrementAndGet();
                logHiddenSkipOnce(hiddenBackgroundLogged, "background");
                return hostUnit;
            });
            hiddenSkipReady = true;
            reportInstalled(HIDDEN_SKIP, 4);
        } catch (Throwable failure) {
            hiddenSkipReady = false;
            group.fail(failure);
        }
    }

    private void logHiddenSkipOnce(AtomicBoolean logged, String route) {
        long count = "current".equals(route) ? hiddenCurrentBlocked.get() : hiddenBackgroundBlocked.get();
        if (count == 1 || (count & (count - 1)) == 0) TokAiLog.event(HIDDEN_SKIP, "decision", "safety_task_suppressed",
                "route=" + route + ";current_hits=" + hiddenCurrentHits.get()
                        + ";current_blocked=" + hiddenCurrentBlocked.get()
                        + ";background_hits=" + hiddenBackgroundHits.get()
                        + ";background_blocked=" + hiddenBackgroundBlocked.get() + ";result_scope=local_interception");
        if (!logged.compareAndSet(false, true)) return;
        try { log.accept("Hidden video auto-skip blocked: route=" + route); }
        catch (Throwable ignored) { }
    }

    private void installUpdates(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "remove_update_dialog";
        BehaviorHookGroup group = newGroup(key);
        try {
            if (report != null && report.getResults().containsKey("tiktok_update_popup")) {
                Method popup = HostTargets.method(report, "tiktok_update_popup", loader);
                Method complete = HostTargets.method(report, "tiktok_popup_complete", loader);
                if (popup.getReturnType() != void.class || popup.getParameterCount() != 2
                        || !complete.getDeclaringClass().isAssignableFrom(popup.getParameterTypes()[1])
                        || complete.getReturnType() != void.class || complete.getParameterCount() != 0) {
                    throw new IllegalStateException("TikTok update popup completion relationship changed");
                }
                group.add(framework, popup, chain -> {
                    if (!enabled(key)) return chain.proceed();
                    // Release the native popup queue even though this update sheet is suppressed.
                    complete.invoke(chain.getArg(1));
                    return null;
                });
                reportInstalled(key, 1);
                return;
            }
            Class<?> notification = HostTargets.type(report, "adv_ui_update_dialog", loader);
            Class<?> bottom = HostTargets.type(report, "adv_ui_update_bottom", loader);
            if (!Dialog.class.isAssignableFrom(notification)) {
                throw new IllegalStateException("Update notification is no longer a Dialog");
            }
            group.add(framework, Dialog.class.getDeclaredMethod("show"), chain -> {
                if (enabled(key) && notification.isInstance(chain.getThisObject())) {
                    TokAiLog.event(key, "decision", "dialog_show_suppressed", "host_invoked=false;dialog_kind=notification");
                    return null;
                }
                return chain.proceed();
            });
            Class<?> fragment = Class.forName("androidx.fragment.app.DialogFragment", false, loader);
            Class<?> manager = Class.forName("androidx.fragment.app.FragmentManager", false, loader);
            Class<?> transaction = Class.forName("androidx.fragment.app.FragmentTransaction", false, loader);
            if (!fragment.isAssignableFrom(bottom)) {
                throw new IllegalStateException("Update bottom sheet is no longer a DialogFragment");
            }
            for (String name : new String[] { "show", "showNow" }) {
                group.add(framework, fragment.getDeclaredMethod(name, manager, String.class), chain -> {
                    if (enabled(key) && bottom.isInstance(chain.getThisObject())) {
                        TokAiLog.event(key, "decision", "dialog_show_suppressed", "host_invoked=false;dialog_kind=bottom_sheet");
                        return null;
                    }
                    return chain.proceed();
                });
            }
            group.add(framework, fragment.getDeclaredMethod("show", transaction, String.class), chain -> {
                if (enabled(key) && bottom.isInstance(chain.getThisObject())) {
                    TokAiLog.event(key, "decision", "dialog_show_suppressed", "host_invoked=false;dialog_kind=transaction");
                    return -1;
                }
                return chain.proceed();
            });
            reportInstalled(key, 4);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installBlackEdges(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "hide_video_black_edges";
        BehaviorHookGroup group = newGroup(key);
        if (com.tokai.module.scan.HostFeatureSupport.isTikTok(report)) {
            installTikTokVideoSpaces(framework, report, loader, group);
            return;
        }
        if (!group.requireDeclaredRules(report, "adv_ui_black_spaces")) return;
        try {
            Method method = HostTargets.method(report, "adv_ui_black_spaces", loader);
            Field top = HostTargets.field(method.getDeclaringClass(), "i");
            Field bottom = HostTargets.field(method.getDeclaringClass(), "j");
            if (!View.class.isAssignableFrom(top.getType())
                    || !View.class.isAssignableFrom(bottom.getType())) {
                throw new IllegalStateException("Feed black-space field types changed");
            }
            group.add(framework, method, chain -> {
                Object result = chain.proceed();
                if (closed || !group.isActive()) return result;
                try {
                    remember((View) top.get(chain.getThisObject()), key);
                    remember((View) bottom.get(chain.getThisObject()), key);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    rollbackViews(key);
                    group.fail(failure);
                }
                return result;
            });
            reportInstalled(key, 1);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installTikTokVideoSpaces(XposedInterface framework, HostScanReport report,
            ClassLoader loader, BehaviorHookGroup group) {
        String key = "hide_video_black_edges";
        String[] coverRules = TikTokVideoCoverAdapter.RULES;
        String[] requiredRules = new String[coverRules.length + 4];
        requiredRules[0] = "adv_ui_black_spaces";
        requiredRules[1] = "adv_ui_black_spaces_init";
        requiredRules[2] = "adv_ui_black_spaces_restore";
        requiredRules[3] = "adv_video_cell_spaces";
        System.arraycopy(coverRules, 0, requiredRules, 4, coverRules.length);
        if (!group.requireDeclaredRules(report, requiredRules)) return;
        try {
            Method update = HostTargets.method(report, "adv_ui_black_spaces", loader);
            Method initialize = HostTargets.method(report, "adv_ui_black_spaces_init", loader);
            Method restore = HostTargets.method(report, "adv_ui_black_spaces_restore", loader);
            Method cellPadding = HostTargets.method(report, "adv_video_cell_spaces", loader);
            if (initialize.getDeclaringClass() != update.getDeclaringClass()
                    || restore.getDeclaringClass() != update.getDeclaringClass()
                    || initialize.getReturnType() != void.class || restore.getReturnType() != void.class
                    || initialize.getParameterCount() != 2 || initialize.getParameterTypes()[0] != View.class
                    || initialize.getParameterTypes()[1] != Bundle.class || restore.getParameterCount() != 0) {
                throw new IllegalStateException("Native feed space lifecycle contract changed");
            }
            TikTokVideoSpacesAdapter adapter = new TikTokVideoSpacesAdapter(update);
            TikTokVideoCoverAdapter cover = new TikTokVideoCoverAdapter(report, loader,
                    () -> enabled(key), failure -> failBlackEdges(failure));
            TikTokVideoSpacesAdapter cellSpaces = new TikTokVideoSpacesAdapter(cellPadding,
                    "LLLJIL", "LLLJL", "LLLL", "LLLLII");
            tikTokVideoSpaces = adapter;
            tikTokVideoCover = cover;
            // FeedCell.Wp applies these cell-local spaces before super.Wp reaches aq.
            // Keep their native heights owned by lq; GONE removes only their layout reservation.
            group.add(framework, cellPadding, chain -> {
                if (!closed && group.isActive()) {
                    try { rememberTikTokCellSpaces(cellSpaces, chain.getThisObject()); }
                    catch (ReflectiveOperationException | RuntimeException failure) { failBlackEdges(failure); }
                }
                Object result = chain.proceed();
                if (!closed && group.isActive()) {
                    try { rememberTikTokCellSpaces(cellSpaces, chain.getThisObject()); }
                    catch (ReflectiveOperationException | RuntimeException failure) { failBlackEdges(failure); }
                    if (group.isActive()) readyCover(cover, chain.getThisObject());
                }
                return result;
            });
            group.add(framework, update, chain -> {
                if (!closed && group.isActive()) {
                    try {
                        adapter.rememberResult(chain.getThisObject(), chain.getArg(0));
                        rememberTikTokSpaces(adapter, chain.getThisObject());
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        group.fail(failure); rollbackViews(key); adapter.clear();
                    }
                }
                Object result = chain.proceed();
                if (!closed && group.isActive()) {
                    try { rememberTikTokSpaces(adapter, chain.getThisObject()); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        group.fail(failure); rollbackViews(key); adapter.clear();
                    }
                }
                return result;
            });

            Method adapt = HostTargets.method(report, "adv_video_cover_apply", loader);
            group.add(framework, adapt, chain -> {
                if (closed || !group.isActive()) return chain.proceed();
                Object[] args;
                try {
                    // The subclass initializes its spaces after the base onViewCreated hook.
                    rememberTikTokCellSpaces(cellSpaces, chain.getThisObject());
                    args = chain.getArgs().toArray();
                    args[0] = cover.prepare(chain.getThisObject(), args[0]);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    failBlackEdges(failure);
                    return chain.proceed();
                }
                Object result = chain.proceed(args);
                if (!closed && group.isActive()) readyCover(cover, chain.getThisObject());
                return result;
            });

            Method awemeLifecycle = HostTargets.method(report, "adv_video_cover_init", loader);
            Method unbind = HostTargets.method(report, "adv_video_cover_unbind", loader);
            Method destroy = HostTargets.method(report, "adv_video_cover_destroy", loader);
            Method firstFrame = HostTargets.method(report, "adv_video_cover_first_frame", loader);
            group.add(framework, awemeLifecycle, chain -> {
                Object result = chain.proceed();
                if (!closed && group.isActive()) readyCover(cover, chain.getThisObject());
                return result;
            });
            group.add(framework, unbind, chain -> {
                if (!closed && group.isActive()) forgetCover(cover, chain.getThisObject());
                return chain.proceed();
            });
            group.add(framework, destroy, chain -> {
                if (!closed && group.isActive()) forgetCover(cover, chain.getThisObject());
                return chain.proceed();
            });
            group.add(framework, firstFrame, chain -> {
                Object result = chain.proceed();
                if (!closed && group.isActive()) readyCover(cover, chain.getThisObject());
                return result;
            });
            for (Method lifecycle : new Method[]{initialize, restore}) {
                group.add(framework, lifecycle, chain -> {
                    Object result = chain.proceed();
                    if (!closed && group.isActive()) {
                        try { rememberTikTokSpaces(adapter, chain.getThisObject()); }
                        catch (ReflectiveOperationException | RuntimeException failure) {
                            group.fail(failure); rollbackViews(key); adapter.clear();
                        }
                    }
                    return result;
                });
            }
            reportInstalled(key, 9);
        } catch (Throwable failure) { group.fail(failure); failBlackEdges(failure); }
    }

    private void readyCover(TikTokVideoCoverAdapter adapter, Object owner) {
        try { adapter.ready(owner); }
        catch (ReflectiveOperationException | RuntimeException failure) { failBlackEdges(failure); }
    }

    private void forgetCover(TikTokVideoCoverAdapter adapter, Object owner) {
        try { adapter.forget(owner); }
        catch (ReflectiveOperationException | RuntimeException failure) { failBlackEdges(failure); }
    }

    private void failBlackEdges(Throwable failure) {
        BehaviorHookGroup group = groups.get("hide_video_black_edges");
        if (group != null && group.isActive()) group.fail(failure);
        if (tikTokVideoCover != null) {
            try { tikTokVideoCover.close(); }
            catch (ReflectiveOperationException ignored) { }
            tikTokVideoCover = null;
        }
        if (tikTokVideoSpaces != null) {
            tikTokVideoSpaces.clear();
            tikTokVideoSpaces = null;
        }
        rollbackViews("hide_video_black_edges");
    }

    private void rememberTikTokCellSpaces(TikTokVideoSpacesAdapter adapter, Object component)
            throws ReflectiveOperationException {
        if (!adapter.owns(component)) return;
        View[] spaces = adapter.views(component);
        StringBuilder state = new StringBuilder("result_scope=cell_space_views");
        String[] labels = {"video_top", "video_bottom", "sticker_top", "sticker_bottom"};
        for (int index = 0; index < spaces.length; index++) {
            View space = spaces[index];
            remember(space, "hide_video_black_edges");
            if (space == null) continue;
            state.append(';').append(labels[index]).append("_visibility=").append(space.getVisibility());
            state.append(';').append(labels[index]).append("_native_height=")
                    .append(space.getLayoutParams() == null ? -1 : space.getLayoutParams().height);
        }
        TokAiLog.event("hide_video_black_edges", "cell_spaces", "visibility_readback", state.toString());
    }

    private void rememberTikTokSpaces(TikTokVideoSpacesAdapter adapter, Object component)
            throws ReflectiveOperationException {
        for (View space : adapter.views(component)) remember(space, "hide_video_black_edges");
    }

    private void installPublishButton(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "hide_camera_plus";
        BehaviorHookGroup group = newGroup(key);
        boolean tikTok = com.tokai.module.scan.HostFeatureSupport.isTikTok(report);
        String rule = tikTok ? "tiktok_publish_button_factory" : "adv_ui_publish_attach";
        if (!group.requireDeclaredRules(report, rule)) return;
        try {
            Method attach = HostTargets.method(report, rule, loader);
            if (!View.class.isAssignableFrom(tikTok ? attach.getReturnType() : attach.getDeclaringClass())) {
                throw new IllegalStateException("Publish button is no longer a View");
            }
            group.add(framework, attach, chain -> {
                Object result = chain.proceed();
                if (!closed && group.isActive()) {
                    try { remember((View) (tikTok ? result : chain.getThisObject()), key); }
                    catch (RuntimeException failure) {
                        rollbackViews(key);
                        group.fail(failure);
                    }
                }
                return result;
            });
            reportInstalled(key, 1);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private BehaviorHookGroup newGroup(String key) {
        BehaviorHookGroup group = new BehaviorHookGroup(key, log);
        groups.put(key, group);
        return group;
    }

    private void reportInstalled(String key, int count) {
        try { log.accept("Advanced feature registered: " + key + " targets=" + count); }
        catch (Throwable ignored) { }
    }

    private void remember(View view, String key) {
        if (view == null) return;
        onMain(() -> {
            if (closed) return;
            observed.put(view, key);
            observedByKey.computeIfAbsent(key, ignored -> new WeakHashMap<>()).put(view, Boolean.TRUE);
            updateView(view, key);
        });
    }

    private void updateView(View view, String key) {
        if (view == null) return;
        HiddenView state = hidden.get(view);
        if (!enabled(key)) {
            if (state != null) {
                hidden.remove(view);
                state.restore();
            }
            return;
        }
        if (state == null) {
            state = new HiddenView(view, key, "hide_camera_plus".equals(key) ? View.INVISIBLE : View.GONE);
            hidden.put(view, state);
        }
        state.apply();
    }

    private void rollbackViews(String key) {
        onMain(() -> {
            for (Map.Entry<View, String> entry : new ArrayList<>(observed.entrySet())) {
                if (!key.equals(entry.getValue())) continue;
                HiddenView state = hidden.remove(entry.getKey());
                if (state != null) state.restore();
                observed.remove(entry.getKey());
            }
            observedByKey.remove(key);
        });
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        hiddenSkipReady = false;
        for (BehaviorHookGroup group : groups.values()) group.close();
        onMain(() -> {
            for (HiddenView state : new ArrayList<>(hidden.values())) state.restore();
            if (tikTokVideoCover != null) {
                try { tikTokVideoCover.close(); }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    try { log.accept("Native video restoration unavailable: " + failure.getClass().getSimpleName()); }
                    catch (RuntimeException ignored) { }
                }
                tikTokVideoCover = null;
            }
            if (tikTokVideoSpaces != null) {
                try { tikTokVideoSpaces.reflowAll(); }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    try { log.accept("Native video space restoration unavailable: " + failure.getClass().getSimpleName()); }
                    catch (RuntimeException ignored) { }
                }
                tikTokVideoSpaces.clear();
                tikTokVideoSpaces = null;
            }
            hidden.clear();
            observed.clear();
            observedByKey.clear();
        });
    }

    private final class HiddenView implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> view;
        private final String key;
        private final int applied;
        private int original;
        private ViewTreeObserver observer;

        HiddenView(View view, String key, int applied) {
            this.view = new WeakReference<>(view);
            this.key = key;
            this.applied = applied;
            original = view.getVisibility();
            observer = view.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnPreDrawListener(this);
        }

        void apply() {
            View target = view.get();
            if (target == null) return;
            ViewTreeObserver next = target.getViewTreeObserver();
            if (next != observer && next.isAlive()) {
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                observer = next;
                observer.addOnPreDrawListener(this);
            }
            if (target.getVisibility() != applied) {
                original = target.getVisibility();
                target.setVisibility(applied);
                TokAiLog.event(key, "view_state", "visibility_applied", "expected=" + applied
                        + ";actual=" + target.getVisibility() + ";result_scope=current_view");
            }
        }

        @Override public boolean onPreDraw() {
            try {
                if (!enabled(key)) {
                    View target = view.get();
                    if (target != null) hidden.remove(target);
                    restore();
                } else apply();
            } catch (RuntimeException failure) {
                BehaviorHookGroup group = groups.get(key);
                if (group != null) group.fail(failure);
                rollbackViews(key);
            }
            return true;
        }

        void restore() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            View target = view.get();
            if (target != null && target.getVisibility() == applied) target.setVisibility(original);
            if (target != null) TokAiLog.event(key, "view_state", "restored",
                    "expected=" + original + ";actual=" + target.getVisibility() + ";result_scope=current_view");
        }
    }
}
