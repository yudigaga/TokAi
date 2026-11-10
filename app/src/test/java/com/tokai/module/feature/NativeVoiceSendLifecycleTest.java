package com.tokai.module.feature;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.tokai.module.config.SubmissionLedger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import static org.junit.Assert.*;


/** Replays native admission and late business events against the real Handler and durable ledger. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class NativeVoiceSendLifecycleTest {
    private SharedPreferences preferences;
    private SubmissionLedger ledger;
    private Handler main;
    private AtomicLong now;
    private NativeVoiceSendLifecycle lifecycle;
    private final List<Boolean> mayClear = new ArrayList<>();
    @Before public void setup() {
        preferences=RuntimeEnvironment.getApplication().getSharedPreferences(SubmissionLedger.VOICE_FILE,Context.MODE_PRIVATE);
        preferences.edit().clear().commit();ledger=new SubmissionLedger(preferences,100);
        main=new Handler(Looper.getMainLooper());now=new AtomicLong(1_000_000);
        lifecycle=new NativeVoiceSendLifecycle(ledger,main,Runnable::run,now::get);
    }

    @Test public void missingReceiptReleasesOnlyItsBusyAttemptAndNeverExpiresTheUnknownDraft() throws Exception {
        AtomicBoolean fileDeleted=new AtomicBoolean();
        NativeVoiceSendLifecycle.Attempt first=begin("one","#same");
        Sender old=submit(first,()->fileDeleted.set(true));
        assertTrue(lifecycle.isBusy()); advance(30_000);
        assertEquals(NativeVoiceSendLifecycle.State.UNKNOWN,first.state); assertFalse(lifecycle.isBusy());
        assertFalse("Unknown does not delete the file on the 30 second acknowledgment deadline",fileDeleted.get());
        NativeVoiceSendLifecycle.Attempt duplicate=begin("one","#same");
        assertFalse(lifecycle.reserve(duplicate));assertEquals(NativeVoiceSendLifecycle.State.BLOCKED,duplicate.state);
        assertFalse(lifecycle.isBusy()); advance(600_000);
        assertTrue(fileDeleted.get());
        NativeVoiceSendLifecycle recreated=new NativeVoiceSendLifecycle(new SubmissionLedger(preferences,100),main,Runnable::run,now::get);
        NativeVoiceSendLifecycle.Attempt afterRestart=recreated.begin(1,"one",7,"#same",(a,s,current)->{});
        assertFalse("Even another process-era instance must not resend an unresolved draft",recreated.reserve(afterRestart));
        assertEquals(1,old.calls);
    }

    @Test public void lateSuccessUpdatesOldLedgerWithoutClearingNewBusyOrIdenticalTextInANewConversation() throws Exception {
        NativeVoiceSendLifecycle.Attempt old=begin("old-chat","#identical");Sender oldSender=submit(old,()->{});
        advance(30_000);NativeVoiceSendLifecycle.Attempt current=begin("new-chat","#identical");Sender newSender=submit(current,()->{});
        oldSender.ack(true);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idle();
        assertEquals(NativeVoiceSendLifecycle.State.SENT,old.state);assertTrue(lifecycle.isBusy());
        assertEquals(NativeVoiceSendLifecycle.State.SUBMITTED,current.state);
        assertEquals(Collections.singletonList(false),mayClear);
        SubmissionLedger.Snapshot snapshot=ledger.inspect(SubmissionLedger.accountScope("1"),Collections.emptySet(),now.get());
        assertTrue(snapshot.entries.stream().anyMatch(e->e.identity.equals(old.identity)&&e.status.equals("sent")));
        newSender.ack(true);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idle();assertFalse(lifecycle.isBusy());
        assertEquals(java.util.Arrays.asList(false,true),mayClear);
    }

    @Test public void differentDraftMayProceedAfterTimeoutButALateFailureCannotReleaseUnknown() throws Exception {
        NativeVoiceSendLifecycle.Attempt first=begin("chat","#first");Sender old=submit(first,()->{});advance(30_000);
        NativeVoiceSendLifecycle.Attempt next=begin("chat","#second");assertTrue(lifecycle.reserve(next));
        old.ack(false);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idle();
        assertEquals(NativeVoiceSendLifecycle.State.UNKNOWN,first.state);assertTrue(lifecycle.isBusy());
        lifecycle.abandon(next);NativeVoiceSendLifecycle.Attempt duplicate=begin("chat","#first");
        assertFalse(lifecycle.reserve(duplicate));
    }

    @Test public void definiteFailureReleasesItsReservationAndPreparationCancellationNeverClaimsASend() throws Exception {
        NativeVoiceSendLifecycle.Attempt first=begin("chat","#retryable");Sender sender=submit(first,()->{});
        sender.ack(false);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idle();assertFalse(lifecycle.isBusy());
        assertFalse(ledger.contains(first.identity));
        NativeVoiceSendLifecycle.Attempt preparing=begin("chat","#retryable");assertTrue(lifecycle.reserve(preparing));
        lifecycle.abandon(preparing);assertFalse(ledger.contains(preparing.identity));assertFalse(lifecycle.isBusy());
        assertEquals(1,sender.calls);
    }

    @Test public void knownSuccessDebounceDoesNotPermanentlyBanAnExplicitManualRepeat() throws Exception {
        NativeVoiceSendLifecycle.Attempt first=begin("chat","#repeat");Sender sender=submit(first,()->{});
        sender.ack(true);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idle();
        NativeVoiceSendLifecycle.Attempt immediate=begin("chat","#repeat");assertFalse(lifecycle.reserve(immediate));
        advance(30_000);NativeVoiceSendLifecycle.Attempt explicit=begin("chat","#repeat");assertTrue(lifecycle.reserve(explicit));
        lifecycle.terminal(first,true);assertTrue(lifecycle.isBusy());assertTrue(ledger.isReserved(explicit.identity,explicit.token));
    }

    @Test public void throwingNativeBoundaryIsUnknownAndCloseDoesNotTurnItIntoRetryableFailure() throws Exception {
        NativeVoiceSendLifecycle.Attempt attempt=begin("chat","#uncertain");assertTrue(lifecycle.reserve(attempt));
        assertTrue(lifecycle.submitted(attempt,()->{}));lifecycle.uncertain(attempt);lifecycle.close();
        assertEquals(NativeVoiceSendLifecycle.State.UNKNOWN,attempt.state);assertTrue(ledger.contains(attempt.identity));
        lifecycle.terminal(attempt,true);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idle();
        assertEquals(NativeVoiceSendLifecycle.State.SENT,attempt.state);assertEquals(Collections.singletonList(false),mayClear);
    }

    @Test public void persistedIdentityContainsNoDraftAndSeparatesAccountsAndConversations() {
        String text="private voice draft";
        String one=NativeVoiceSendLifecycle.identity(1,"chat",text);
        assertEquals(64,one.length());assertFalse(one.contains(text));
        assertNotEquals(one,NativeVoiceSendLifecycle.identity(2,"chat",text));
        assertNotEquals(one,NativeVoiceSendLifecycle.identity(1,"other",text));
    }

    private NativeVoiceSendLifecycle.Attempt begin(String conversation,String draft) {
        NativeVoiceSendLifecycle.Attempt attempt=lifecycle.begin(1,conversation,7,draft,(a,state,current)->{
            if(state==NativeVoiceSendLifecycle.State.SENT)mayClear.add(current);
        });assertNotNull(attempt);return attempt;
    }
    private Sender submit(NativeVoiceSendLifecycle.Attempt attempt,Runnable cleanup) throws Exception {
        assertTrue(lifecycle.reserve(attempt));assertTrue(lifecycle.submitted(attempt,cleanup));
        Sender sender=new Sender();AtomicBoolean terminal=new AtomicBoolean();
        Consumer<Boolean> event=success->{if(terminal.compareAndSet(false,true))main.post(()->lifecycle.terminal(attempt,success));};
        TikTokImBridge.submit(new TikTokImBridge.Submission(Sender.class.getMethod("send",Object.class,Object.class,Object.class),
                sender,new Object(),new Object(),event,event,terminal));return sender;
    }
    private void advance(long millis) {now.addAndGet(millis);org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(millis));}
    public static final class Admission {public boolean LIZJ(){return false;}}
    public static final class Sender {
        int calls;Consumer<Boolean> completion;
        @SuppressWarnings("unchecked") public Admission send(Object params,Object options,Object callback) {calls++;completion=(Consumer<Boolean>)callback;return new Admission();}
        void ack(boolean success){completion.accept(success);}
    }
}
