package com.tokai.module.feature;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Local contract fixtures only. No native SDK, network or real message is invoked. */
public final class TikTokNativeSendAdmissionTest {
    @Test public void nativeAdmissionKeepsTheBusinessOutcomePending() throws Exception {
        Sender sender = new Sender();
        List<Boolean> outcomes = new ArrayList<>();
        TikTokImBridge.submit(submission(sender, outcomes, new AtomicBoolean()));
        assertEquals(1, sender.calls);
        assertTrue("Scheduling a native upload is not remote acknowledgment", outcomes.isEmpty());
    }

    @Test public void synchronousTerminalCallbackWinsOverASecondAdmissionFailure() throws Exception {
        Sender sender = new Sender(); sender.rejected = true;
        List<Boolean> outcomes = new ArrayList<>();
        AtomicBoolean terminal = new AtomicBoolean();
        sender.beforeReturn = () -> { if (terminal.compareAndSet(false, true)) outcomes.add(true); };
        TikTokImBridge.submit(submission(sender, outcomes, terminal));
        assertEquals(java.util.Collections.singletonList(true), outcomes);
    }

    @Test public void aThrowingNativeBoundaryDoesNotInventAnAcknowledgment() throws Exception {
        Sender sender = new Sender(); sender.throwAfterEntering = true;
        List<Boolean> outcomes = new ArrayList<>();
        try {
            TikTokImBridge.submit(submission(sender, outcomes, new AtomicBoolean()));
            fail("Native exception should retain its unknown submission boundary");
        } catch (InvocationTargetException expected) { }
        assertEquals(1, sender.calls);
        assertTrue(outcomes.isEmpty());
    }

    private static TikTokImBridge.Submission submission(Sender sender, List<Boolean> outcomes,
                                                        AtomicBoolean terminal) throws Exception {
        return new TikTokImBridge.Submission(Sender.class.getMethod("send", Object.class, Object.class, Object.class),
                sender, new Object(), new Object(), new Object(), outcomes::add, terminal);
    }
    public static final class Admission {
        final boolean failed;
        Admission(boolean failed) { this.failed = failed; }
        public boolean LIZJ() { return failed; }
    }
    public static final class Sender {
        int calls;
        boolean rejected, throwAfterEntering;
        Runnable beforeReturn = () -> { };
        public Admission send(Object params, Object options, Object callback) {
            calls++;
            if (throwAfterEntering) throw new IllegalStateException("Native state may already have changed");
            beforeReturn.run();
            return new Admission(rejected);
        }
    }
}
