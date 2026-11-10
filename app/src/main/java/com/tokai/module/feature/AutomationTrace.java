package com.tokai.module.feature;

import com.tokai.module.diagnostics.TokAiLog;

import java.util.LinkedHashMap;

/** Correlates the existing history identity across generation, preview and native callbacks. */
final class AutomationTrace {
    private static final LinkedHashMap<String, TokAiLog.Operation> OPERATIONS = new LinkedHashMap<>();

    private AutomationTrace() { }

    static synchronized TokAiLog.Operation operation(String feature, String id, String trigger) {
        TokAiLog.Operation operation = OPERATIONS.get(id);
        if (operation == null) {
            operation = TokAiLog.begin(feature, trigger);
            OPERATIONS.put(id, operation);
            while (OPERATIONS.size() > 512) {
                OPERATIONS.remove(OPERATIONS.keySet().iterator().next());
                TokAiLog.event("automation", "correlation_evicted", "registry_capacity", "capacity=512; explicit_task_trace_retained=true");
            }
        }
        return operation;
    }

    /** New task instance, even when a previously skipped receipt reuses its business identity. */
    static synchronized TokAiLog.Operation start(String feature, String id, String trigger) {
        return remember(id, TokAiLog.begin(feature, trigger));
    }

    static synchronized TokAiLog.Operation start(String feature, String id, String trigger, String effectiveRevision) {
        return remember(id, TokAiLog.begin(feature, trigger, effectiveRevision));
    }

    private static TokAiLog.Operation remember(String id, TokAiLog.Operation operation) {
        OPERATIONS.put(id, operation);
        while (OPERATIONS.size() > 512) {
            OPERATIONS.remove(OPERATIONS.keySet().iterator().next());
            TokAiLog.event("automation", "correlation_evicted", "registry_capacity", "capacity=512;explicit_task_trace_retained=true");
        }
        return operation;
    }

    static TokAiLog.Operation capture(String feature, String trigger) {
        TokAiLog.Operation current = TokAiLog.current();
        return current == null ? TokAiLog.begin(feature, trigger) : current;
    }

    static void outcome(TokAiLog.Operation operation, String status, String reason, String evidence) {
        TokAiLog.Outcome outcome = "sent".equals(status) ? TokAiLog.Outcome.SUCCESS
                : "submitted".equals(status) ? TokAiLog.Outcome.UNCONFIRMED
                : "cancelled".equals(status) ? TokAiLog.Outcome.CANCELLED
                : "deferred".equals(status) ? TokAiLog.Outcome.SKIPPED
                : reason != null && (reason.contains("timeout") || reason.contains("expired")) ? TokAiLog.Outcome.TIMED_OUT : TokAiLog.Outcome.FAILED;
        operation.finish(outcome, reason, evidence);
    }
}
