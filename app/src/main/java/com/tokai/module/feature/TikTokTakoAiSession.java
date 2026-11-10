package com.tokai.module.feature;

import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Module AI transport for one native Tako page; owns no views, hooks or shared chat storage. */
public final class TikTokTakoAiSession implements AutoCloseable {
    public interface Sink {
        /** Revalidates native account/page/conversation ownership on the main executor. */
        default boolean isCurrent() { return true; }
        void onComplete(String text);
        void onFailure(Exception failure);
    }

    interface Transport {
        String reply(String conversation, String prompt, AiModelSettings settings) throws Exception;
        /** In-memory only; a stopped or stale result must never enter subsequent model context. */
        default void remember(String conversation, String prompt, AiModelSettings settings, String answer) throws Exception { }
        void cancel();
    }

    interface TransportFactory { Transport create(); }

    private final Object lock = new Object();
    private final Executor worker;
    private final Executor cancellation;
    private final Executor main;
    private final TransportFactory transports;
    private final Consumer<String> diagnostics;
    private final boolean ownsExecutors;
    private Binding binding;
    private Request active;
    private long generation;
    private boolean closed;

    public TikTokTakoAiSession(Executor main, Consumer<String> diagnostics) {
        this(singleWorker("TokAi-tako-reply"), singleWorker("TokAi-tako-cancel"), main,
                () -> new Transport() {
                    private final ChatCompletionClient client = new ChatCompletionClient(null, diagnostics);
                    @Override public String reply(String conversation, String prompt, AiModelSettings settings) throws Exception {
                        return client.reply(conversation, prompt, Collections.emptyList(), settings, "", false);
                    }
                    @Override public void remember(String conversation, String prompt, AiModelSettings settings, String answer) throws Exception {
                        client.rememberReply(conversation, prompt, Collections.emptyList(), settings, "", answer);
                    }
                    @Override public void cancel() { client.cancelCurrentRequest(); }
                }, diagnostics, true);
    }

    TikTokTakoAiSession(Executor worker, Executor cancellation, Executor main,
                       TransportFactory transports, Consumer<String> diagnostics) {
        this(worker, cancellation, main, transports, diagnostics, false);
    }

    private TikTokTakoAiSession(Executor worker, Executor cancellation, Executor main,
                               TransportFactory transports, Consumer<String> diagnostics, boolean ownsExecutors) {
        this.worker = Objects.requireNonNull(worker);
        this.cancellation = Objects.requireNonNull(cancellation);
        this.main = Objects.requireNonNull(main);
        this.transports = Objects.requireNonNull(transports);
        this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
        this.ownsExecutors = ownsExecutors;
    }

    /** Uses the global selected model, independently of whether it is listed as an IM contact.
     * Switching account, native conversation or transport settings starts an isolated context.
     * Invalid configuration unbinds the previous context before throwing its validation error. */
    public void bind(String accountKey, String nativeConversationId, AiModelSettings globalSnapshot) {
        AiModelSettings snapshot;
        String identity;
        try {
            if (accountKey == null || accountKey.trim().isEmpty()) throw new IllegalArgumentException("Tako account key is required");
            if (nativeConversationId == null || nativeConversationId.trim().isEmpty()) throw new IllegalArgumentException("Tako conversation ID is required");
            AiModelConfig selected = Objects.requireNonNull(globalSnapshot, "Global AI settings are required").selected();
            snapshot = new AiModelSettings(Collections.singletonList(selected), selected.id);
            JSONObject transport = selected.toJson();
            for (String key : new String[]{"name", "messageContactEnabled", "messageContactName", "messageContactAvatar"}) transport.remove(key);
            identity = digest(accountKey.length() + ":" + accountKey + nativeConversationId.length()
                    + ":" + nativeConversationId + ":" + transport);
        } catch (RuntimeException failure) {
            unbind();
            throw failure;
        }
        Request previous;
        Binding retired;
        synchronized (lock) {
            ensureOpen();
            if (binding != null && binding.identity.equals(identity)) {
                binding.settings = snapshot;
                return;
            }
            Transport transport;
            try { transport = Objects.requireNonNull(transports.create(), "AI transport is required"); }
            catch (RuntimeException failure) {
                unbind();
                throw failure;
            }
            previous = active;
            retired = binding;
            generation++;
            active = null;
            binding = new Binding(identity, snapshot, transport);
            scheduleCancellation(retired);
        }
        if (previous != null) previous.task.cancel(true);
        diagnostic("bound");
    }

    /** Returns false only while another request is awaiting its main-executor callback.
     * An unbound/closed session or invalid prompt throws before any transport is used. */
    public boolean request(String prompt, Sink sink) {
        if (prompt == null || prompt.trim().isEmpty()) throw new IllegalArgumentException("AI message cannot be empty");
        Objects.requireNonNull(sink, "AI result sink is required");
        Request request;
        synchronized (lock) {
            ensureOpen();
            if (binding == null) throw new IllegalStateException("Tako AI session is not bound to a valid global model");
            if (active != null) return false;
            request = new Request(generation, binding, binding.settings, binding.cleanup, prompt, sink);
            request.task = new FutureTask<>(() -> { run(request); return null; });
            active = request;
        }
        try { worker.execute(request.task); }
        catch (RejectedExecutionException rejected) {
            deliver(request, null, new IllegalStateException("AI reply worker is unavailable", rejected));
        }
        return true;
    }

    public boolean isBound() { synchronized (lock) { return !closed && binding != null; } }
    public boolean isBusy() { synchronized (lock) { return active != null; } }

    /** Fences queued results immediately. Disconnect/close runs only on the cancellation executor.
     * A subsequent request waits for that cleanup off the main executor and keeps completed context. */
    public void cancel() {
        Request previous;
        synchronized (lock) {
            if (closed) return;
            generation++;
            previous = active;
            active = null;
            if (previous != null) scheduleCancellation(binding);
        }
        if (previous != null) previous.task.cancel(true);
        diagnostic("cancelled");
    }

    private void unbind() {
        Request previous;
        synchronized (lock) {
            generation++;
            previous = active;
            active = null;
            scheduleCancellation(binding);
            binding = null;
        }
        if (previous != null) previous.task.cancel(true);
    }

    private void run(Request request) {
        try {
            if (request.cleanup != null) request.cleanup.get();
            synchronized (lock) { if (!isCurrent(request)) return; }
            String result = request.binding.transport.reply("tako/" + request.binding.identity, request.prompt, request.settings);
            if (result == null || result.trim().isEmpty()) throw new IllegalStateException("AI reply is empty");
            deliver(request, result, null);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            deliver(request, null, new CancellationException("AI reply was interrupted"));
        } catch (ExecutionException cleanupFailure) {
            deliver(request, null, new IllegalStateException("Previous AI cancellation did not finish", cleanupFailure.getCause()));
        } catch (Exception failure) { deliver(request, null, failure); }
    }

    private void deliver(Request request, String result, Exception failure) {
        synchronized (lock) { if (!isCurrent(request)) return; }
        try {
            main.execute(() -> {
                Exception reported = failure;
                synchronized (lock) {
                    if (!isCurrent(request)) return;
                    boolean accepted;
                    try { accepted = request.sink.isCurrent(); }
                    catch (Throwable invalidOwner) {
                        diagnostic("acceptance_failed;type=" + invalidOwner.getClass().getSimpleName());
                        accepted = false;
                    }
                    if (!isCurrent(request)) return;
                    if (!accepted) {
                        generation++;
                        active = null;
                        binding = null;
                        scheduleCancellation(request.binding);
                        diagnostic("native_owner_changed");
                        return;
                    }
                    if (reported == null) {
                        try { request.binding.transport.remember("tako/" + request.binding.identity, request.prompt, request.settings, result); }
                        catch (Exception historyFailure) { reported = historyFailure; }
                    }
                    active = null;
                }
                diagnostic(reported == null ? "reply_complete;chars=" + result.length()
                        : "reply_failed;type=" + reported.getClass().getSimpleName());
                if (reported == null) request.sink.onComplete(result);
                else request.sink.onFailure(reported);
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (lock) { if (isCurrent(request)) active = null; }
            diagnostic("main_executor_rejected");
        }
    }

    private boolean isCurrent(Request request) {
        return !closed && active == request && generation == request.generation && binding == request.binding;
    }

    private void scheduleCancellation(Binding retired) {
        if (retired == null) return;
        FutureTask<Void> preceding = retired.cleanup;
        FutureTask<Void> cleanup = new FutureTask<>(() -> {
            if (preceding != null) {
                try { preceding.get(); }
                catch (ExecutionException | CancellationException ignored) { diagnostic("earlier_cleanup_failed"); }
            }
            retired.transport.cancel();
            return null;
        });
        retired.cleanup = cleanup;
        try { cancellation.execute(cleanup); }
        catch (RejectedExecutionException rejected) {
            cleanup.cancel(false);
            diagnostic("cancel_executor_rejected");
        }
    }

    @Override public void close() {
        Request previous;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            generation++;
            previous = active;
            active = null;
            scheduleCancellation(binding);
            binding = null;
        }
        if (previous != null) previous.task.cancel(true);
        if (ownsExecutors) {
            ((ExecutorService) worker).shutdownNow();
            ((ExecutorService) cancellation).shutdown();
        }
        diagnostic("closed");
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Tako AI session is closed"); }
    private void diagnostic(String stage) { try { diagnostics.accept("tako_ai;stage=" + stage); } catch (RuntimeException ignored) { } }

    private static ExecutorService singleWorker(String name) {
        return Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; });
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Binding {
        final String identity;
        final Transport transport;
        AiModelSettings settings;
        FutureTask<Void> cleanup;
        Binding(String identity, AiModelSettings settings, Transport transport) {
            this.identity = identity; this.settings = settings; this.transport = transport;
        }
    }

    private static final class Request {
        final long generation;
        final Binding binding;
        final AiModelSettings settings;
        final FutureTask<Void> cleanup;
        final String prompt;
        final Sink sink;
        FutureTask<Void> task;
        Request(long generation, Binding binding, AiModelSettings settings, FutureTask<Void> cleanup, String prompt, Sink sink) {
            this.generation = generation; this.binding = binding; this.settings = settings;
            this.cleanup = cleanup; this.prompt = prompt; this.sink = sink;
        }
    }
}
