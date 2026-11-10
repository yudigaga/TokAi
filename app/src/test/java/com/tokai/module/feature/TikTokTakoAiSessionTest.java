package com.tokai.module.feature;

import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokTakoAiSessionTest {
    @Test public void globalSelectedModelDoesNotRequireAnImContactAndCompletesOnlyOnMain() throws Exception {
        Fixture f = new Fixture();
        AiModelConfig global = model("global", AiModelConfig.ANTHROPIC);
        AiModelSettings settings = new AiModelSettings(Arrays.asList(model("unused", AiModelConfig.CHAT), global), "global");
        f.session.bind("account", "native-conversation", settings);
        assertTrue(f.session.request("native text", f.sink));
        assertFalse(f.session.request("duplicate send", new Results()));
        assertEquals(0, f.transports.get(0).prompts.size());
        f.worker.runAll();
        assertTrue(f.session.isBusy());
        assertTrue(f.sink.answers.isEmpty());
        assertTrue(f.transports.get(0).remembered.isEmpty());
        AiModelConfig forwarded = f.transports.get(0).settings.selected();
        assertSame(global, forwarded);
        assertFalse(forwarded.messageContactEnabled);
        assertEquals("module system prompt", forwarded.systemPrompt);
        assertEquals(7, forwarded.contextRounds);
        assertEquals(AiModelConfig.RESPONSE_STREAM, forwarded.responseMode);
        f.main.runAll();
        assertEquals(Collections.singletonList("answer:native text"), f.sink.answers);
        assertEquals(Collections.singletonList("native text"), f.transports.get(0).remembered);
        assertFalse(f.session.isBusy());
        assertTrue(f.session.isBound());
        f.session.close();
        f.cancel.runAll();
    }

    @Test public void accountConversationAndModelSwitchEachFenceQueuedCompletionAndIsolateHistory() throws Exception {
        for (String dimension : new String[]{"account", "conversation", "model"}) {
            Fixture f = new Fixture();
            AiModelSettings initial = settings(model("one", AiModelConfig.CHAT));
            f.session.bind("account-one", "conversation-one", initial);
            assertTrue(f.session.request("old work", f.sink));
            f.worker.runAll();
            f.session.bind("account".equals(dimension) ? "account-two" : "account-one",
                    "conversation".equals(dimension) ? "conversation-two" : "conversation-one",
                    "model".equals(dimension) ? settings(model("two", AiModelConfig.GEMINI)) : initial);
            f.main.runAll();
            assertTrue(dimension, f.sink.answers.isEmpty());
            assertTrue(dimension, f.transports.get(0).remembered.isEmpty());
            assertEquals(0, f.transports.get(0).cancels);
            f.cancel.runAll();
            assertEquals(1, f.transports.get(0).cancels);
            assertTrue(f.session.request("new work", f.sink));
            f.worker.runAll();
            f.main.runAll();
            assertEquals(Collections.singletonList("answer:new work"), f.sink.answers);
            assertEquals(2, f.transports.size());
            assertNotEquals(f.transports.get(0).conversation, f.transports.get(1).conversation);
            assertEquals(Collections.singletonList("new work"), f.transports.get(1).remembered);
            f.session.close();
            f.cancel.runAll();
        }
    }

    @Test public void switchingBackCreatesFreshPrivateContextInsteadOfResurrectingAnotherPage() throws Exception {
        Fixture f = new Fixture();
        AiModelSettings model = settings(model("one", AiModelConfig.CHAT));
        f.session.bind("a", "chat-one", model);
        f.reply("first page");
        f.session.bind("a", "chat-two", model);
        f.reply("second page");
        f.session.bind("a", "chat-one", model);
        assertEquals(3, f.transports.size());
        assertTrue(f.transports.get(2).remembered.isEmpty());
        f.reply("reopened page");
        assertEquals(Collections.singletonList("reopened page"), f.transports.get(2).remembered);
        f.session.close();
        f.cancel.runAll();
    }

    @Test public void sameModelAndDisplayOnlyChangesKeepCompletedContext() throws Exception {
        Fixture f = new Fixture();
        AiModelConfig original = model("one", AiModelConfig.CHAT);
        f.session.bind("a", "chat", settings(original));
        f.reply("first");
        AiModelConfig renamed = AiModelConfig.fromJson(original.toJson().put("name", "New display name")
                .put("messageContactEnabled", true).put("messageContactName", "Local display contact"));
        f.session.bind("a", "chat", settings(renamed));
        f.reply("second");
        assertEquals(1, f.transports.size());
        assertEquals(Arrays.asList("first", "second"), f.transports.get(0).remembered);
        assertSame(renamed, f.transports.get(0).settings.selected());
        assertTrue(f.cancel.tasks.isEmpty());
        f.session.close();
        f.cancel.runAll();
    }

    @Test public void transportSettingChangeStartsFreshContextEvenWhenModelIdIsUnchanged() throws Exception {
        Fixture f = new Fixture();
        AiModelConfig original = model("one", AiModelConfig.CHAT);
        f.session.bind("a", "chat", settings(original));
        f.reply("first");
        f.session.bind("a", "chat", settings(AiModelConfig.fromJson(original.toJson().put("systemPrompt", "Changed system prompt"))));
        f.reply("second");
        assertEquals(2, f.transports.size());
        assertEquals(Collections.singletonList("second"), f.transports.get(1).remembered);
        f.session.close();
        f.cancel.runAll();
    }

    @Test public void cancelFencesCompletedButUndeliveredReplyAndDoesNotRememberIt() throws Exception {
        Fixture f = new Fixture();
        f.session.bind("a", "chat", settings(model("one", AiModelConfig.CHAT)));
        f.session.request("cancelled reply", f.sink);
        f.worker.runAll();
        f.session.cancel();
        assertFalse(f.session.isBusy());
        assertEquals("Cancellation must not disconnect inline on the caller", 0, f.transports.get(0).cancels);
        f.main.runAll();
        assertTrue(f.sink.answers.isEmpty());
        assertTrue(f.transports.get(0).remembered.isEmpty());
        f.cancel.runAll();
        assertEquals(1, f.transports.get(0).cancels);
        f.reply("after cancellation");
        assertEquals(Collections.singletonList("after cancellation"), f.transports.get(0).remembered);
        f.session.close();
        f.cancel.runAll();
    }

    @Test public void queuedCancellationCannotCancelTheNextRequestOrDiscardEarlierCompletedContext() throws Exception {
        Fixture f = new Fixture();
        f.session.bind("a", "chat", settings(model("one", AiModelConfig.CHAT)));
        f.reply("first completed");
        f.session.request("never starts", f.sink);
        f.session.cancel();
        assertTrue(f.session.request("next request", f.sink));
        f.cancel.runAll();
        f.worker.runAll();
        f.main.runAll();
        assertEquals(Arrays.asList("first completed", "next request"), f.transports.get(0).prompts);
        assertEquals(Arrays.asList("first completed", "next request"), f.transports.get(0).remembered);
        assertEquals(1, f.transports.get(0).cancels);
        f.session.close();
        f.cancel.runAll();
    }

    @Test public void blockingDisconnectRunsOffCallerAndAnInterruptIgnoringTransportCannotDeliverLate() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService cancellation = Executors.newSingleThreadExecutor();
        ManualExecutor main = new ManualExecutor();
        CountDownLatch started = new CountDownLatch(1), cancelStarted = new CountDownLatch(1);
        CountDownLatch releaseReply = new CountDownLatch(1), releaseCancel = new CountDownLatch(1), finished = new CountDownLatch(1);
        Thread[] disconnectThread = {null};
        TikTokTakoAiSession.Transport transport = new TikTokTakoAiSession.Transport() {
            @Override public String reply(String conversation, String prompt, AiModelSettings settings) {
                started.countDown();
                awaitIgnoringInterrupt(releaseReply);
                finished.countDown();
                return "late transport result";
            }
            @Override public void cancel() {
                disconnectThread[0] = Thread.currentThread();
                cancelStarted.countDown();
                awaitIgnoringInterrupt(releaseCancel);
                releaseReply.countDown();
            }
        };
        TikTokTakoAiSession session = new TikTokTakoAiSession(worker, cancellation, main, () -> transport, ignored -> { });
        Results sink = new Results();
        try {
            session.bind("a", "chat", settings(model("one", AiModelConfig.CHAT)));
            session.request("slow reply", sink);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            session.cancel();
            assertFalse(session.isBusy());
            assertTrue("cancel returns even while disconnect remains blocked", cancelStarted.await(3, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), disconnectThread[0]);
            releaseCancel.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            worker.submit(() -> { }).get(3, TimeUnit.SECONDS);
            main.runAll();
            assertTrue(sink.answers.isEmpty());
            assertTrue(sink.failures.isEmpty());
        } finally {
            releaseReply.countDown(); releaseCancel.countDown();
            session.close(); worker.shutdownNow(); cancellation.shutdownNow();
        }
    }

    @Test public void failureIsDeliveredOnlyOnMainAndDoesNotCommitContext() throws Exception {
        Fixture f = new Fixture();
        f.session.bind("a", "chat", settings(model("one", AiModelConfig.CHAT)));
        Exception failure = new IllegalStateException("provider unavailable");
        f.transports.get(0).failure = failure;
        f.session.request("message", f.sink);
        f.worker.runAll();
        assertTrue(f.sink.failures.isEmpty());
        f.main.runAll();
        assertSame(failure, f.sink.failures.get(0));
        assertTrue(f.transports.get(0).remembered.isEmpty());
        assertFalse(f.session.isBusy());
        f.session.close();
        f.cancel.runAll();
    }

    @Test public void nativeAcceptanceRunsOnMainAndRejectsChangedOrThrowingOwnersBeforeRemembering() throws Exception {
        for (boolean throwing : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.session.bind("a", "chat", settings(model("one", AiModelConfig.CHAT)));
            int[] checks = {0};
            TikTokTakoAiSession.Sink guarded = new TikTokTakoAiSession.Sink() {
                @Override public boolean isCurrent() {
                    checks[0]++;
                    if (throwing) throw new IllegalStateException("Native page is no longer available");
                    return false;
                }
                @Override public void onComplete(String text) { f.sink.onComplete(text); }
                @Override public void onFailure(Exception failure) { f.sink.onFailure(failure); }
            };
            f.session.request("native account changed before delivery", guarded);
            f.worker.runAll();
            assertEquals("Native ownership must be read on the supplied main executor", 0, checks[0]);
            f.main.runAll();
            assertEquals(1, checks[0]);
            assertTrue(f.sink.answers.isEmpty());
            assertTrue(f.sink.failures.isEmpty());
            assertTrue("Rejected native delivery cannot enter model context", f.transports.get(0).remembered.isEmpty());
            assertFalse(f.session.isBusy());
            assertFalse(f.session.isBound());
            assertEquals("Even failed ownership cleanup stays off the main callback", 0, f.transports.get(0).cancels);
            f.cancel.runAll();
            assertEquals(1, f.transports.get(0).cancels);
            assertThrows(IllegalStateException.class, () -> f.session.request("must rebind", f.sink));
            f.session.close();
        }
    }

    @Test public void invalidBindUnbindsOldAccountAndCannotReuseItsProviderConfiguration() throws Exception {
        Fixture f = new Fixture();
        f.session.bind("old-account", "chat", settings(model("one", AiModelConfig.CHAT)));
        f.session.request("old pending", f.sink);
        assertThrows(IllegalStateException.class, () -> f.session.bind("new-account", "chat",
                new AiModelSettings(Collections.emptyList(), "")));
        assertFalse(f.session.isBound());
        assertThrows(IllegalStateException.class, () -> f.session.request("new-account message", f.sink));
        f.worker.runAll(); f.main.runAll(); f.cancel.runAll();
        assertTrue(f.transports.get(0).prompts.isEmpty());
        assertTrue(f.sink.answers.isEmpty());
        f.session.close();
    }

    @Test public void closeFencesQueuedCallbacksAndRejectsFurtherUse() throws Exception {
        Fixture f = new Fixture();
        AiModelSettings settings = settings(model("one", AiModelConfig.CHAT));
        f.session.bind("a", "chat", settings);
        f.session.request("pending", f.sink);
        f.worker.runAll();
        f.session.close();
        f.main.runAll();
        assertTrue(f.sink.answers.isEmpty());
        assertTrue(f.transports.get(0).remembered.isEmpty());
        assertFalse(f.session.isBound());
        assertThrows(IllegalStateException.class, () -> f.session.request("late", f.sink));
        assertThrows(IllegalStateException.class, () -> f.session.bind("a", "chat", settings));
        f.cancel.runAll();
        assertEquals(1, f.transports.get(0).cancels);
    }

    @Test public void rejectedWorkerReportsFailureOnMainAndDiagnosticsNeverContainUserData() throws Exception {
        ManualExecutor main = new ManualExecutor(), cancel = new ManualExecutor();
        List<String> diagnostics = new ArrayList<>();
        Results sink = new Results();
        TikTokTakoAiSession session = new TikTokTakoAiSession(task -> { throw new RejectedExecutionException(); },
                cancel, main, FakeTransport::new, diagnostics::add);
        try {
            session.bind("private-account", "private-conversation", settings(model("private-model", AiModelConfig.CHAT)));
            assertTrue(session.request("private prompt", sink));
            assertTrue(sink.failures.isEmpty());
            main.runAll();
            assertEquals(1, sink.failures.size());
            assertFalse(session.isBusy());
            String logged = String.join("\n", diagnostics);
            for (String secret : new String[]{"private-account", "private-conversation", "private-model", "private prompt", "test-key", "provider.invalid"}) {
                assertFalse(logged, logged.contains(secret));
            }
        } finally { session.close(); cancel.runAll(); }
    }

    private static AiModelConfig model(String id, String protocol) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", "Module model")
                .put("protocol", protocol).put("apiEndpoint", "https://provider.invalid/v1")
                .put("apiKey", "test-key").put("modelName", "model-name")
                .put("systemPrompt", "module system prompt").put("contextRounds", 7)
                .put("responseMode", AiModelConfig.RESPONSE_STREAM).put("messageContactEnabled", false));
    }

    private static AiModelSettings settings(AiModelConfig model) {
        return new AiModelSettings(Collections.singletonList(model), model.id);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable command) { tasks.add(command); }
        void runAll() {
            for (;;) {
                Runnable next;
                synchronized (this) { next = tasks.poll(); }
                if (next == null) return;
                next.run();
            }
        }
    }

    private static final class Results implements TikTokTakoAiSession.Sink {
        final List<String> answers = new ArrayList<>();
        final List<Exception> failures = new ArrayList<>();
        @Override public void onComplete(String text) { answers.add(text); }
        @Override public void onFailure(Exception failure) { failures.add(failure); }
    }

    private static final class FakeTransport implements TikTokTakoAiSession.Transport {
        final List<String> prompts = new ArrayList<>(), remembered = new ArrayList<>();
        AiModelSettings settings;
        String conversation;
        Exception failure;
        int cancels;
        @Override public String reply(String conversation, String prompt, AiModelSettings settings) throws Exception {
            this.conversation = conversation; this.settings = settings;
            prompts.add(prompt);
            if (failure != null) throw failure;
            return "answer:" + prompt;
        }
        @Override public void remember(String conversation, String prompt, AiModelSettings settings, String answer) { remembered.add(prompt); }
        @Override public void cancel() { cancels++; }
    }

    private static final class Fixture {
        final ManualExecutor worker = new ManualExecutor(), cancel = new ManualExecutor(), main = new ManualExecutor();
        final List<FakeTransport> transports = new ArrayList<>();
        final Results sink = new Results();
        final TikTokTakoAiSession session = new TikTokTakoAiSession(worker, cancel, main, () -> {
            FakeTransport created = new FakeTransport(); transports.add(created); return created;
        }, ignored -> { });
        void reply(String prompt) {
            assertTrue(session.request(prompt, sink)); worker.runAll(); main.runAll();
        }
    }
}
