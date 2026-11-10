package com.tokai.module.feature;

import io.github.libxposed.api.XposedInterface;
import com.tokai.module.diagnostics.TokAiLog;
import com.tokai.module.diagnostics.FeatureLogCatalog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** An independently revocable registration, including partially installed hooks. */
final class BehaviorHookGroup implements AutoCloseable {
    private final String id;
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private boolean closed;
    private String installationFailure = "";

    BehaviorHookGroup(String id, Consumer<String> log) {
        this.id = id;
        this.log = log;
    }

    synchronized void add(XposedInterface framework, Method target,
                          XposedInterface.Hooker hooker) {
        add(framework, target, XposedInterface.PRIORITY_DEFAULT, hooker);
    }

    synchronized void add(XposedInterface framework, Method target, int priority,
                          XposedInterface.Hooker hooker) {
        if (closed) throw new IllegalStateException("Closed feature: " + id);
        XposedInterface.HookHandle handle = framework.hook(target)
                .setPriority(priority)
                .setId("TokAi.Advanced." + id + "." + handles.size())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(hooker);
        if (handle == null) throw new IllegalStateException("Empty hook handle: " + id);
        handles.add(handle);
        TokAiLog.event(FeatureLogCatalog.forHook("TokAi.Advanced." + id), "registration", "handle_installed", "handles=" + handles.size() + ";business_result=not_implied");
    }

    synchronized boolean isActive() { return !closed && !handles.isEmpty(); }
    synchronized String installationFailure() { return installationFailure; }

    boolean requireDeclaredRules(com.tokai.module.scan.HostScanReport report, String... rules) {
        String reason = com.tokai.module.scan.HostFeatureSupport.undeclaredRules(report, rules);
        if (reason.isEmpty()) return true;
        synchronized (this) { installationFailure = reason; }
        close();
        TokAiLog.event(FeatureLogCatalog.forHook("TokAi.Advanced." + id), "availability",
                "host_rules_not_declared", "hook_state=unsupported;" + reason);
        return false;
    }

    void fail(Throwable failure) {
        String feature = FeatureLogCatalog.forHook("TokAi.Advanced." + id);
        TokAiLog.failure(feature, "installation_or_runtime_rollback", failure);
        TokAiLog.event(feature, "availability", "rolled_back", "hook_state=uninstalled;business_result=unknown");
        synchronized (this) {
            installationFailure = com.tokai.module.diagnostics.HostAdaptationDiagnostics.installationFailure(failure);
        }
        close();
        try { log.accept("Advanced feature " + id + " rolled back: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
        catch (Throwable ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (int i = handles.size() - 1; i >= 0; i--) {
            try { handles.get(i).unhook(); }
            catch (Throwable failure) {
                try { log.accept("Could not revoke " + id + ": " + failure.getMessage()); }
                catch (Throwable ignored) { }
            }
        }
        handles.clear();
        TokAiLog.event(FeatureLogCatalog.forHook("TokAi.Advanced." + id), "registration", "closed", "handles=0");
    }
}
