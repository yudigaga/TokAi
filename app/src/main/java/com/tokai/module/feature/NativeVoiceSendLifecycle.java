package com.tokai.module.feature;

import android.os.Handler;
import android.os.Looper;
import com.tokai.module.config.SubmissionLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Per-attempt ownership. Missing acknowledgments free the editor, never the durable send reservation. */
final class NativeVoiceSendLifecycle implements AutoCloseable {
    static final long ACK_TIMEOUT_MILLIS = 30_000L;
    enum State { PREPARING, READY, SUBMITTED, UNKNOWN, SENT, FAILED, ABANDONED, BLOCKED }
    interface Listener { void changed(Attempt attempt, State state, boolean ownsCurrentUi); }
    private final SubmissionLedger ledger;
    private final Handler main;
    private final Executor storage;
    private final LongSupplier clock;
    private Attempt active;
    private long sequence;
    private boolean closed;

    NativeVoiceSendLifecycle(SubmissionLedger ledger, Handler main, Executor storage, LongSupplier clock) {
        this.ledger = ledger; this.main = main; this.storage = storage; this.clock = clock;
    }
    synchronized boolean isBusy() { return active != null; }
    synchronized Attempt begin(long account, String conversation, long revision, String draft, Listener listener) {
        if (closed || active != null || account <= 0 || conversation == null || conversation.isEmpty()
                || draft == null || draft.isEmpty() || listener == null) return null;
        Attempt attempt = new Attempt(++sequence, account, conversation, revision,
                identity(account, conversation, draft), listener);
        active = attempt; return attempt;
    }

    /** Worker-only durable reservation, before TTS and before the native boundary. */
    boolean reserve(Attempt attempt) {
        synchronized (this) { if (closed || active != attempt || attempt.state != State.PREPARING) return false; }
        SubmissionLedger.ReserveResult result = ledger.reserveManualVoice(attempt.identity, attempt.token,
                clock.getAsLong(), SubmissionLedger.accountScope(Long.toString(attempt.account)));
        boolean abandoned = false, blocked = false;
        synchronized (this) {
            attempt.reservationResult = result;
            if (result == SubmissionLedger.ReserveResult.RESERVED) {
                attempt.reserved = true;
                if (!closed && active == attempt && attempt.state == State.PREPARING) {
                    attempt.state = State.READY; return true;
                }
                abandoned = true;
            } else if (active == attempt && attempt.state == State.PREPARING) {
                attempt.state = State.BLOCKED; active = null; blocked = true;
            }
        }
        if (abandoned) ledger.abandon(attempt.identity, attempt.token);
        if (blocked) notifyState(attempt, State.BLOCKED, true);
        return false;
    }

    /** Starts the acknowledgment timer and independent retention only after preparation succeeds. */
    boolean submitted(Attempt attempt, Runnable retainedFileCleanup) {
        synchronized (this) {
            if (closed || active != attempt || attempt.state != State.READY || !attempt.reserved
                    || !ledger.isReserved(attempt.identity, attempt.token)) return false;
            attempt.state = State.SUBMITTED;
            attempt.timeout = () -> uncertain(attempt);
            main.postDelayed(attempt.timeout, ACK_TIMEOUT_MILLIS);
            main.postDelayed(retainedFileCleanup, DirectMessageSpeechHook.AUDIO_RETENTION_MILLIS);
            return true;
        }
    }

    void terminal(Attempt attempt, boolean success) {
        State state; boolean owned;
        synchronized (this) {
            if (attempt.state == State.SENT || attempt.state == State.FAILED || attempt.state == State.ABANDONED
                    || attempt.state == State.BLOCKED || attempt.state == State.PREPARING || attempt.state == State.READY) return;
            // An acknowledgment timeout is not a failure and must never open an automatic retry path.
            if (attempt.state == State.UNKNOWN && !success) return;
            owned = !closed && active == attempt && attempt.sequence == sequence;
            state = attempt.state = success ? State.SENT : State.FAILED;
            if (active == attempt) active = null;
            if (attempt.timeout != null) main.removeCallbacks(attempt.timeout);
        }
        persist(() -> ledger.complete(attempt.identity, attempt.token, state == State.SENT ? "sent" : "failed", clock.getAsLong()));
        notifyState(attempt, state, owned);
    }

    void uncertain(Attempt attempt) {
        boolean owned;
        synchronized (this) {
            if (attempt.state != State.SUBMITTED) return;
            owned = !closed && active == attempt && attempt.sequence == sequence;
            attempt.state = State.UNKNOWN;
            if (active == attempt) active = null;
            if (attempt.timeout != null) main.removeCallbacks(attempt.timeout);
        }
        persist(() -> ledger.complete(attempt.identity, attempt.token, "unknown", clock.getAsLong()));
        notifyState(attempt, State.UNKNOWN, owned);
    }

    /** This path is legal only before submitted(), when the host has not been invoked. */
    void abandon(Attempt attempt) {
        boolean owned, reserved;
        synchronized (this) {
            if (attempt.state != State.PREPARING && attempt.state != State.READY) return;
            owned = !closed && active == attempt && attempt.sequence == sequence;
            attempt.state = State.ABANDONED; reserved = attempt.reserved;
            if (active == attempt) active = null;
        }
        if (reserved) persist(() -> ledger.abandon(attempt.identity, attempt.token));
        notifyState(attempt, State.ABANDONED, owned);
    }

    private void notifyState(Attempt attempt, State state, boolean owned) {
        Runnable action = () -> {
            boolean current;
            synchronized (this) { current = owned && !closed && attempt.sequence == sequence; }
            attempt.listener.changed(attempt, state, current);
        };
        if (Looper.myLooper() == main.getLooper()) action.run(); else main.post(action);
    }
    private void persist(Runnable action) {
        // The reservation already blocks duplicates while a durable outcome update is queued.
        try { storage.execute(action); } catch (RuntimeException unavailable) { action.run(); }
    }
    @Override public void close() {
        Attempt pending;
        synchronized (this) { if (closed) return; closed = true; ++sequence; pending = active; }
        if (pending != null) {
            if (pending.state == State.SUBMITTED) uncertain(pending); else abandon(pending);
        }
    }
    static String identity(long account, String conversation, String draft) {
        // Plain text never enters the ledger or its identity input; only the independently digested draft does.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(draft.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest) text.append(Character.forDigit((value >>> 4) & 15,16)).append(Character.forDigit(value & 15,16));
            return SubmissionLedger.identity("manual_voice:" + account + ":" + conversation.length() + ":" + conversation + ":" + text);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static final class Attempt {
        final long sequence, account, revision;
        final String conversation, identity, token = UUID.randomUUID().toString();
        final Listener listener;
        volatile State state = State.PREPARING;
        volatile SubmissionLedger.ReserveResult reservationResult;
        private boolean reserved;
        private Runnable timeout;
        private Attempt(long sequence, long account, String conversation, long revision, String identity, Listener listener) {
            this.sequence = sequence; this.account = account; this.conversation = conversation; this.revision = revision;
            this.identity = identity; this.listener = listener;
        }
    }
}
