package com.tokai.module.feature;

import com.tokai.module.diagnostics.TokAiLog;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Safe observations at the host invocation boundary; never changes its result. */
final class HostTransportFailureDiagnostics {
    private static final long WINDOW_NS = 30_000_000_000L;
    private final LongSupplier clock;
    private final Consumer<String> sink;
    private long lastEmission;
    private boolean emitted;

    HostTransportFailureDiagnostics() {
        this(System::nanoTime, evidence -> TokAiLog.event("feed_filter", "host_transport_failure",
                "host_transport", evidence));
    }

    HostTransportFailureDiagnostics(LongSupplier clock, Consumer<String> sink) {
        this.clock = clock;
        this.sink = sink;
    }

    void record(Throwable failure) {
        try {
            IOException transport = failure instanceof IOException ? (IOException) failure : null;
            Throwable current = failure;
            // Only the verified host Cronet IOException may classify a wrapped failure.
            for (int depth = 0; current != null && depth < 8; depth++) {
                if (current instanceof IOException && "X.0CNE".equals(current.getClass().getName())) {
                    transport = (IOException) current;
                    break;
                }
                Throwable next = current.getCause();
                if (next == current) break;
                current = next;
            }
            if (transport == null) return;
            synchronized (this) {
                long now = clock.getAsLong();
                if (emitted && now - lastEmission < WINDOW_NS) return;
                emitted = true;
                lastEmission = now;
            }
            String status = "unknown", cronet = "unknown";
            if ("X.0CNE".equals(transport.getClass().getName())) {
                status = numericGetter(transport, "getStatusCode");
                cronet = numericGetter(transport, "getCronetInternalErrorCode");
            }
            sink.accept("origin=host_transport;status_code=" + status
                    + ";cronet_internal_error_code=" + cronet);
        } catch (Throwable unavailable) {
            // Diagnostics, reflection, and sinks cannot replace the original failure.
        }
    }

    private static String numericGetter(IOException failure, String name) {
        try {
            Method getter = failure.getClass().getMethod(name);
            if (getter.getReturnType() != int.class || Modifier.isStatic(getter.getModifiers())
                    || !"X.0CNE".equals(getter.getDeclaringClass().getName())) return "unknown";
            return Integer.toString((Integer) getter.invoke(failure));
        } catch (Throwable unavailable) {
            return "unknown";
        }
    }
}
