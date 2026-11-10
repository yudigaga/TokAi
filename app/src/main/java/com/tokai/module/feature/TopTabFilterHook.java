package com.tokai.module.feature;

import com.tokai.module.config.ExtendedSettings;
import com.tokai.module.diagnostics.TokAiLog;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Filters configured home tabs while preserving the native tab model and order. */
public final class TopTabFilterHook implements AutoCloseable {
    private static volatile java.util.Map<String, String> availableTabs = Collections.emptyMap();
    private final Class<?> tabModelClass;
    private final Field tabIdField;
    private final Field tabTitleField;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private volatile ExtendedSettings settings;

    private TopTabFilterHook(Class<?> tabModelClass, Field tabIdField, Field tabTitleField,
                             ExtendedSettings settings, Consumer<String> log) {
        this.tabModelClass = tabModelClass;
        this.tabIdField = tabIdField;
        this.tabTitleField = tabTitleField;
        this.settings = settings;
        this.log = log;
    }

    public static TopTabFilterHook install(XposedInterface framework, HostScanReport report,
                                           ClassLoader loader, ExtendedSettings settings,
                                           Consumer<String> log) throws Exception {
        Method provider = resolveMethod(report, "home_tab_list_provider", loader);
        if (provider.getParameterCount() != 0 || !List.class.isAssignableFrom(provider.getReturnType())) {
            throw new IllegalStateException("Home tab list provider contract changed");
        }
        Class<?> model = resolveClass(report, "home_tab_model", loader);
        boolean tikTok = "com.ss.android.ugc.aweme.experiment.TopTabItem".equals(model.getName());
        Field id = tikTok ? HostTargets.field(model, "topTabKey") : fieldForJsonName(model, "tab_id");
        Field title = tikTok ? id : fieldForJsonName(model, "tab_title");
        if (id == null || title == null || id.getType() != String.class || title.getType() != String.class) {
            throw new IllegalStateException("Home tab model fields changed");
        }
        id.setAccessible(true);
        title.setAccessible(true);
        TopTabFilterHook hook = new TopTabFilterHook(model, id, title, settings,
                log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(provider)
                    .setId("TokAi.HomeTab.Filter")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            if (tikTok) {
                // LIZ supplies the native fallback; LJFF is the resolved/experiment tab list.
                Method resolved = resolveMethod(report, "home_tab_resolved_provider", loader);
                if (resolved.getDeclaringClass() != provider.getDeclaringClass()
                        || resolved.getParameterCount() != 1 || resolved.getReturnType() != List.class) {
                    throw new IllegalStateException("TikTok resolved top tab provider changed");
                }
                XposedInterface.HookHandle extra = framework.hook(resolved)
                        .setId("TokAi.HomeTab.ResolvedFilter")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(hook::intercept);
                if (extra == null) throw new IllegalStateException("Empty resolved tab hook handle");
                hook.handles.add(extra);
            }
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        this.settings = settings;
    }

    public static java.util.Map<String, String> availableTabs() { return availableTabs; }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        ExtendedSettings current = settings;
        if (!(result instanceof List<?>)) {
            TokAiLog.event("top_tabs", "decision", "unexpected_provider_result", "original_result_retained=true");
            return result;
        }
        List<?> source = (List<?>) result;
        if (source.isEmpty()) {
            TokAiLog.event("top_tabs", "decision", "empty_provider_list", "source_count=0");
            return result;
        }
        TokAiLog.Operation operation = TokAiLog.begin("top_tabs", "tab_provider");
        operation.stage("classify", "source_count=" + source.size() + ";enabled=" + (current != null && current.hideTopTabs));
        ArrayList<Object> kept = new ArrayList<>(source.size());
        java.util.Map<String, String> choices = new java.util.LinkedHashMap<>();
        try {
            for (Object item : source) {
                if (item == null || !tabModelClass.isInstance(item)) {
                    operation.finish(TokAiLog.Outcome.SKIPPED, "unexpected_tab_model", "original_list_retained=true");
                    return result;
                }
                String id = (String) tabIdField.get(item);
                String title = (String) tabTitleField.get(item);
                if ("topTabKey".equals(tabIdField.getName())) {
                    Object appearance = HostTargets.read(item, "appearance");
                    Object config = HostTargets.read(appearance, "item");
                    Object label = HostTargets.read(config, "title");
                    if (label instanceof String && !((String) label).isEmpty()) title = (String) label;
                }
                if (id != null && !id.trim().isEmpty()) choices.put(id, title == null || title.isEmpty() ? id : title);
                if (current == null || !current.hideTopTabs || current.hiddenTopTabs.isEmpty()
                        || !matchesHiddenTab(id, title, current.hiddenTopTabs)) kept.add(item);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            operation.error("classify", failure);
            operation.finish(TokAiLog.Outcome.FAILED, "tab_inspection_failed", "original_list_retained=true");
            safeLog("Could not classify home tabs: " + failure.getMessage());
            return result;
        }
        availableTabs = Collections.unmodifiableMap(choices);
        if (kept.isEmpty() || kept.size() == source.size()) {
            operation.finish(TokAiLog.Outcome.SKIPPED, kept.isEmpty() ? "retain_last_tab_guard" : current == null || !current.hideTopTabs
                    ? "disabled" : "no_configured_match", "source_count=" + source.size() + ";kept_count=" + kept.size()
                    + ";original_list_retained=true");
            return result;
        }
        operation.finish(TokAiLog.Outcome.SUCCESS, "filtered_list_returned", "source_count=" + source.size()
                + ";kept_count=" + kept.size() + ";result_scope=provider_list;rendered_tabs=unobserved");
        return kept;
    }

    private static boolean matchesHiddenTab(String id, String title, String configured) {
        String normalizedId = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String normalizedTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        for (String entry : configured.split("[,，\\n]+")) {
            String needle = entry.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && (needle.equals(normalizedId) || needle.equals(normalizedTitle))) {
                return true;
            }
        }
        return false;
    }

    private static Field fieldForJsonName(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (HostGsonBridge.matchesName(field, name)) return field;
        }
        return null;
    }

    private static Class<?> resolveClass(HostScanReport report, String ruleId,
                                         ClassLoader loader) throws ClassNotFoundException {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        if (!candidate.getMethodName().isEmpty()) throw new IllegalStateException("Expected class rule: " + ruleId);
        return Class.forName(className(candidate.getClassDescriptor()), false, loader);
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        Class<?> owner = Class.forName(className(candidate.getClassDescriptor()), false, loader);
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(candidate.getMethodName())
                    || !methodDescriptor(method).equals(candidate.getMethodDescriptor())) continue;
            if (match != null) throw new IllegalStateException("Reflection result is ambiguous: " + ruleId);
            match = method;
        }
        if (match == null) throw new NoSuchMethodException("Scanned method is unavailable: " + ruleId);
        return match;
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }

    private static String className(String descriptor) {
        if (descriptor == null || descriptor.length() < 3
                || descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String methodDescriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    @Override
    public void close() {
        availableTabs = Collections.emptyMap();
        unhookAll(null);
    }

    private void unhookAll(Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable unhookFailure) {
                if (failure == null) failure = unhookFailure;
                else failure.addSuppressed(unhookFailure);
            }
        }
        handles.clear();
        if (originalFailure == null && failure != null) safeLog("Home tab unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not change the host tab list.
        }
    }
}
