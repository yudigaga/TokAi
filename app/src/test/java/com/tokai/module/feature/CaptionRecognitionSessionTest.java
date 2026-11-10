package com.tokai.module.feature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.*;

public final class CaptionRecognitionSessionTest {
    private static final CaptionTranscript TEXT = new CaptionTranscript(List.of(new CaptionTranscript.Cue(0, 1000, "hello")));

    @Test public void theSameSelectionStartsOnceBeforeAndAfterSuccess() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        AtomicInteger calls = new AtomicInteger();
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            calls.incrementAndGet(); return TEXT;
        }, delivery, events, worker)) {
            long generation = session.start("a");
            assertEquals(generation, session.start("a"));
            assertEquals(1, worker.pending());
            worker.runNext();
            assertEquals(generation, session.start("a"));
            delivery.drain();
            assertEquals(1, calls.get());
            assertEquals(1, events.completed.size());
            assertEquals("a:" + generation, events.completed.get(0));
            assertSame(TEXT, events.transcripts.get(0));
            assertEquals(generation, session.start("a"));
            assertEquals(0, worker.pending());
        }
    }

    @Test public void failureDoesNotRetryUntilTheSelectionIsExplicitlyReset() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        AtomicInteger calls = new AtomicInteger();
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            calls.incrementAndGet(); throw new IOException("fixture failure");
        }, delivery, events, worker)) {
            long first = session.start("a"); worker.runNext(); delivery.drain();
            assertEquals(1, events.failed.size());
            assertTrue(events.failures.get(0) instanceof IOException);
            assertEquals(first, session.start("a"));
            assertEquals(0, worker.pending());
            session.stop();
            long next = session.start("a"); assertTrue(next > first);
            worker.runNext(); delivery.drain();
            assertEquals(2, calls.get()); assertEquals(2, events.failed.size());
        }
    }

    @Test public void rapidSelectionsKeepOnlyTheNewestQueuedBackend() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        List<String> calls = new ArrayList<>();
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            calls.add(aid); return TEXT;
        }, delivery, events, worker)) {
            long first = session.start("a");
            long second = session.start("b");
            long latest = session.start("c");
            assertTrue(first < second && second < latest);
            assertEquals(1, worker.pending());
            worker.runNext(); delivery.drain();
            assertEquals(List.of("c"), calls);
            assertEquals(List.of("c:" + latest), events.completed);
            assertTrue(events.failed.isEmpty());
        }
    }

    @Test public void stopDropsQueuedWorkAndAlreadyQueuedSuccessDelivery() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        AtomicInteger calls = new AtomicInteger();
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            calls.incrementAndGet(); return TEXT;
        }, delivery, events, worker)) {
            session.start("a"); worker.runNext();
            assertEquals(1, delivery.pending());
            session.stop(); delivery.drain();
            assertTrue(events.completed.isEmpty()); assertTrue(events.failed.isEmpty());
            session.start("b"); session.stop();
            assertEquals(0, worker.pending());
            assertEquals(1, calls.get());
        }
    }

    @Test public void aNewSelectionDropsAlreadyQueuedFailureDelivery() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            if (aid.equals("a")) throw new IOException("old failure");
            return TEXT;
        }, delivery, events, worker)) {
            session.start("a"); worker.runNext();
            long latest = session.start("b"); worker.runNext(); delivery.drain();
            assertTrue(events.failed.isEmpty());
            assertEquals(List.of("b:" + latest), events.completed);
        }
    }

    @Test public void anUninterruptibleOldBackendCannotPublishAndDoesNotLoseTheNewestPendingSelection() throws Exception {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<MediaReadCancellation> oldToken = new AtomicReference<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            calls.add(aid);
            if (aid.equals("a")) { oldToken.set(token); entered.countDown(); awaitUninterruptibly(release); }
            return TEXT;
        }, delivery, events, worker);
        Thread running = null;
        try {
            session.start("a"); running = worker.runNextInThread();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            session.start("b"); long latest = session.start("c");
            assertThrows(CancellationException.class, () -> oldToken.get().check());
            assertEquals(1, worker.pending());
            release.countDown(); running.join(1000); assertFalse(running.isAlive());
            worker.runNext(); delivery.drain();
            assertEquals(List.of("a", "c"), calls);
            assertEquals(List.of("c:" + latest), events.completed);
            assertTrue(events.failed.isEmpty());
        } finally { release.countDown(); session.close(); if (running != null) running.join(1000); }
    }

    @Test public void stopAndCloseSuppressAnUninterruptibleBackendsLateFailure() throws Exception {
        for (boolean close : new boolean[]{false, true}) {
            ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<MediaReadCancellation> oldToken = new AtomicReference<>();
            CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
                oldToken.set(token); entered.countDown(); awaitUninterruptibly(release); throw new IOException("late");
            }, delivery, events, worker);
            Thread running = null;
            try {
                session.start("a"); running = worker.runNextInThread(); assertTrue(entered.await(1, TimeUnit.SECONDS));
                if (close) session.close(); else session.stop();
                assertThrows(CancellationException.class, () -> oldToken.get().check());
                release.countDown(); running.join(1000); assertFalse(running.isAlive()); delivery.drain();
                assertTrue(events.failed.isEmpty()); assertTrue(events.completed.isEmpty()); assertEquals(0, worker.pending());
            } finally { release.countDown(); session.close(); if (running != null) running.join(1000); }
        }
    }

    @Test public void closeDropsQueuedDeliveryShutsDownTheOwnedWorkerAndPreventsReuse() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> TEXT, delivery, events, worker);
        session.start("a"); worker.runNext(); session.close(); session.close(); delivery.drain();
        assertTrue(worker.isShutdown()); assertTrue(events.completed.isEmpty()); assertTrue(events.failed.isEmpty());
        assertThrows(IllegalStateException.class, () -> session.start("a"));
    }

    @Test public void aRejectedWorkerDeliversOneFailureAndDoesNotRetryTheSameSelection() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        AtomicInteger calls = new AtomicInteger(); worker.reject = true;
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            calls.incrementAndGet(); return TEXT;
        }, delivery, events, worker)) {
            long generation = session.start("a"); delivery.drain();
            assertEquals(List.of("a:" + generation), events.failed);
            assertTrue(events.failures.get(0) instanceof RejectedExecutionException);
            assertEquals(0, calls.get()); assertEquals(0, worker.pending());
            assertEquals(generation, session.start("a")); assertEquals(0, delivery.pending());
            session.stop(); worker.reject = false;
            long next = session.start("a"); worker.runNext(); delivery.drain();
            assertEquals(List.of("a:" + next), events.completed);
        }
    }

    @Test public void aRejectedWorkerFailureCannotArriveAfterAReplacementSelection() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> TEXT, delivery, events, worker)) {
            worker.reject = true; session.start("a");
            worker.reject = false; long current = session.start("b"); worker.runNext(); delivery.drain();
            assertTrue(events.failed.isEmpty()); assertEquals(List.of("b:" + current), events.completed);
        }
    }

    @Test public void aRejectedDeliveryDoesNotRetryRecognitionOrLeakAListenerCallOntoTheWorker() {
        ManualWorker worker = new ManualWorker(); ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        AtomicInteger calls = new AtomicInteger(); delivery.reject = true;
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            calls.incrementAndGet(); return TEXT;
        }, delivery, events, worker)) {
            long generation = session.start("a"); worker.runNext();
            assertTrue(events.completed.isEmpty()); assertTrue(events.failed.isEmpty());
            assertEquals(generation, session.start("a")); assertEquals(1, calls.get()); assertEquals(0, worker.pending());
        }
    }

    @Test public void synchronousDeliveryAllowsAnotherThreadToStopAndStartBeforeTheListenerReturns() throws Exception {
        ManualWorker worker = new ManualWorker(); Events events = new Events();
        AtomicReference<CaptionRecognitionSession> owner = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CaptionRecognitionSession.Listener listener = new CaptionRecognitionSession.Listener() {
            public void completed(String aid, long generation, CaptionTranscript transcript) {
                events.completed(aid, generation, transcript);
                if (!aid.equals("a")) return;
                CountDownLatch reentered = new CountDownLatch(1);
                Thread next = new Thread(() -> {
                    try { owner.get().stop(); owner.get().start("b"); }
                    catch (Throwable failure) { callbackFailure.set(failure); }
                    finally { reentered.countDown(); }
                }, "caption-session-reentry");
                next.setDaemon(true); next.start();
                try { if (!reentered.await(1, TimeUnit.SECONDS)) callbackFailure.set(new AssertionError("Listener held the session lock")); }
                catch (InterruptedException failure) { callbackFailure.set(failure); Thread.currentThread().interrupt(); }
            }
            public void failed(String aid, long generation, Throwable failure) { events.failed(aid, generation, failure); }
        };
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> TEXT, Runnable::run, listener, worker)) {
            owner.set(session); session.start("a"); worker.runNext();
            assertNull(callbackFailure.get()); assertFalse(Thread.currentThread().isInterrupted());
            assertEquals(1, worker.pending()); worker.runNext();
            assertEquals(2, events.completed.size()); assertTrue(events.failed.isEmpty());
        }
    }

    @Test public void theDefaultWorkerStartsAThreeMinuteDeadlineAndDeliversOnTheRequestedExecutor() throws Exception {
        ManualDelivery delivery = new ManualDelivery(); Events events = new Events();
        CountDownLatch ran = new CountDownLatch(1); AtomicInteger remaining = new AtomicInteger();
        try (CaptionRecognitionSession session = new CaptionRecognitionSession((aid, token) -> {
            remaining.set(token.timeoutMillis(300_000)); ran.countDown(); return TEXT;
        }, delivery, events)) {
            long generation = session.start("a"); assertTrue(ran.await(1, TimeUnit.SECONDS));
            assertTrue(remaining.get() > 170_000 && remaining.get() <= 180_000);
            assertTrue(events.completed.isEmpty());
            assertTrue(delivery.queued.await(1, TimeUnit.SECONDS)); delivery.drain();
            assertEquals(List.of("a:" + generation), events.completed);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean ready = false;
        while (!ready) try { latch.await(); ready = true; } catch (InterruptedException ignored) { }
    }

    private static final class ManualWorker extends ThreadPoolExecutor {
        boolean reject;
        ManualWorker() { super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)); }
        @Override public void execute(Runnable command) {
            if (reject || isShutdown() || !getQueue().offer(command)) throw new RejectedExecutionException("fixture worker rejected");
        }
        int pending() { return getQueue().size(); }
        void runNext() { Runnable task = getQueue().poll(); assertNotNull("Expected queued recognition work", task); task.run(); }
        Thread runNextInThread() { Thread thread = new Thread(this::runNext, "caption-session-blocked"); thread.setDaemon(true); thread.start(); return thread; }
    }

    private static final class ManualDelivery implements Executor {
        final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        final CountDownLatch queued = new CountDownLatch(1);
        boolean reject;
        public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("fixture delivery rejected");
            tasks.add(task); queued.countDown();
        }
        int pending() { return tasks.size(); }
        void drain() { Runnable task; while ((task = tasks.poll()) != null) task.run(); }
    }

    private static final class Events implements CaptionRecognitionSession.Listener {
        final List<String> completed = new CopyOnWriteArrayList<>(), failed = new CopyOnWriteArrayList<>();
        final List<CaptionTranscript> transcripts = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        public void completed(String aid, long generation, CaptionTranscript transcript) { completed.add(aid + ":" + generation); transcripts.add(transcript); }
        public void failed(String aid, long generation, Throwable failure) { failed.add(aid + ":" + generation); failures.add(failure); }
    }
}
