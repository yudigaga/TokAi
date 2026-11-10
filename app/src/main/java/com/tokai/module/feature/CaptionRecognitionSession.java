package com.tokai.module.feature;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Selection lifetime for one caption-recognition job. */
final class CaptionRecognitionSession implements AutoCloseable {
    private static final long DEADLINE_MILLIS = 180_000;

    interface Backend {
        CaptionTranscript recognize(String aid, MediaReadCancellation token) throws Exception;
    }
    interface Listener {
        void completed(String aid, long generation, CaptionTranscript transcript);
        void failed(String aid, long generation, Throwable failure);
    }

    private final Object state = new Object();
    private final Backend backend;
    private final Executor delivery;
    private final Listener listener;
    private final ThreadPoolExecutor worker;
    private Work selected;
    private long generation;
    private boolean closed;

    CaptionRecognitionSession(Backend backend, Executor delivery, Listener listener) {
        this(backend, delivery, listener, new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, "TokAi-caption-recognition");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** Ownership of the bounded worker transfers to this session. */
    CaptionRecognitionSession(Backend backend, Executor delivery, Listener listener, ThreadPoolExecutor worker) {
        this.backend = Objects.requireNonNull(backend);
        this.delivery = Objects.requireNonNull(delivery);
        this.listener = Objects.requireNonNull(listener);
        this.worker = Objects.requireNonNull(worker);
    }

    long start(String aid) {
        if (aid == null || aid.trim().isEmpty()) throw new IllegalArgumentException("Missing caption selection");
        Work next;
        RejectedExecutionException rejection = null;
        synchronized (state) {
            if (closed) throw new IllegalStateException("Caption recognition session is closed");
            if (selected != null && selected.aid.equals(aid)) return selected.generation;
            cancelSelected();
            next = new Work(aid, ++generation, new MediaReadCancellation(DEADLINE_MILLIS));
            selected = next;
            try { worker.execute(next); }
            catch (RejectedExecutionException failure) {
                next.token.cancel();
                next.cancel(false);
                worker.purge();
                rejection = failure;
            }
        }
        if (rejection != null) deliver(next, null, rejection);
        return next.generation;
    }

    void stop() {
        synchronized (state) {
            if (closed) return;
            generation++;
            cancelSelected();
        }
    }

    @Override public void close() {
        synchronized (state) {
            if (closed) return;
            closed = true;
            generation++;
            cancelSelected();
        }
        worker.shutdownNow();
    }

    private void cancelSelected() {
        Work previous = selected;
        selected = null;
        if (previous != null) {
            previous.token.cancel();
            previous.cancel(true);
        }
        worker.purge();
    }

    private boolean current(Work work) {
        synchronized (state) {
            return !closed && selected == work && generation == work.generation;
        }
    }

    private void deliver(Work work, CaptionTranscript transcript, Throwable failure) {
        if (!current(work)) return;
        try {
            delivery.execute(() -> {
                if (!current(work)) return;
                try {
                    if (failure == null) listener.completed(work.aid, work.generation, transcript);
                    else listener.failed(work.aid, work.generation, failure);
                } catch (RuntimeException ignored) { }
            });
        } catch (RuntimeException ignored) { }
    }

    private final class Work extends FutureTask<CaptionTranscript> {
        final String aid;
        final long generation;
        final MediaReadCancellation token;

        Work(String aid, long generation, MediaReadCancellation token) {
            super(() -> {
                token.start();
                CaptionTranscript transcript = CaptionRecognitionSession.this.backend.recognize(aid, token);
                token.check();
                if (transcript == null) throw new IllegalStateException("Caption recognition returned no transcript");
                return transcript;
            });
            this.aid = aid;
            this.generation = generation;
            this.token = token;
        }

        @Override protected void done() {
            if (isCancelled() || !current(this)) return;
            CaptionTranscript transcript = null;
            Throwable failure = null;
            try { transcript = get(); }
            catch (CancellationException cancelled) { return; }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (ExecutionException failed) { failure = failed.getCause(); }
            // The future is terminal before synchronous delivery can reenter stop/start.
            deliver(this, transcript, failure);
        }
    }
}
