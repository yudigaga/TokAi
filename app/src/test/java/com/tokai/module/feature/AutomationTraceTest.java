package com.tokai.module.feature;

import com.tokai.module.diagnostics.TokAiLog;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public final class AutomationTraceTest {
    @Test public void readmittedReceiptDoesNotReuseTheOldTerminalTrace() {
        String identity = UUID.randomUUID().toString();
        TokAiLog.Operation old = AutomationTrace.start("auto_reply", identity, "receipt");
        old.finish(TokAiLog.Outcome.SKIPPED, "conversation_unavailable", "native_invoked=false");
        TokAiLog.Operation next = AutomationTrace.start("auto_reply", identity, "receipt_repeated");
        assertNotEquals(old.traceId(), next.traceId());
        assertSame(next, AutomationTrace.operation("auto_reply", identity, "preview"));
        old.stage("late_callback", "must_not_target_next=true");
        assertSame(next, AutomationTrace.operation("auto_reply", identity, "preview"));
        next.finish(TokAiLog.Outcome.CANCELLED, "fixture_complete", "");
    }

    @Test public void retriesAndForeignThreadCallbackKeepTheOriginalOperation() throws Exception {
        TokAiLog.Operation root = AutomationTrace.operation("auto_comment", UUID.randomUUID().toString(), "fixture");
        Queue<Runnable> scheduled = new ArrayDeque<>();
        List<String> traces = new ArrayList<>();
        List<Consumer<String>> results = new ArrayList<>();
        AutoCommentQueue<String> queue = new AutoCommentQueue<>(new AutoCommentQueue.Scheduler() {
            @Override public void post(Runnable action) { action.run(); }
            @Override public void later(Runnable action, long delayMillis) { scheduled.add(action); }
        }, (target, attempt, result) -> {
            assertNotNull(TokAiLog.current());
            traces.add(TokAiLog.current().traceId());
            results.add(result);
        }, (target, status, attempts) -> {
            assertEquals(root.traceId(), TokAiLog.current().traceId());
            AutomationTrace.outcome(root, status, "fixture", "attempts=" + attempts);
        });
        root.wrap(() -> queue.offer("target", "target", 0)).run();
        scheduled.remove().run();
        Thread callback = new Thread(() -> results.get(0).accept("failed"));
        callback.start(); callback.join();
        scheduled.remove().run();
        results.get(0).accept("sent"); // obsolete attempt must not complete the second attempt
        results.get(1).accept("submitted");
        assertEquals(List.of(root.traceId(), root.traceId()), traces);
        assertTrue(scheduled.isEmpty());
        assertNull(TokAiLog.current());
    }

    @Test public void submittedAndLateAcknowledgmentCannotProduceTwoFinalOutcomes() throws Exception {
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        TokAiLog.setFrameworkSink((priority, json) -> records.add(json));
        TokAiLog.Operation root = AutomationTrace.operation("auto_reply", UUID.randomUUID().toString(), "fixture");
        try {
            root.attempt(1);
            root.stage("native_accepted", "business_success=false");
            AutomationTrace.outcome(root, "submitted", "ack_timeout", "remote_outcome=unknown");
            root.wrap(() -> AutomationTrace.outcome(root, "sent", "late_ack", "native_ack=true")).run();
            CountDownLatch flushed = new CountDownLatch(1);
            TokAiLog.flush(flushed::countDown);
            assertTrue(flushed.await(5, TimeUnit.SECONDS));
            long finalCount = records.stream().filter(line -> line.contains(root.traceId())
                    && line.contains("\"event\":\"operation_finished\"")).count();
            assertEquals(1, finalCount);
            assertTrue(records.stream().anyMatch(line -> line.contains(root.traceId())
                    && line.contains("\"event\":\"operation_finished\"") && line.contains("unconfirmed")));
            assertTrue(records.stream().anyMatch(line -> line.contains(root.traceId()) && line.contains("late_callback")));
        } finally {
            TokAiLog.setFrameworkSink(null);
        }
    }
}
