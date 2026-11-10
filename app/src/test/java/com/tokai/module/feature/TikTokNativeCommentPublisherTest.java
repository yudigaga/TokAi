package com.tokai.module.feature;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokNativeCommentPublisherTest {
    private Framework framework;
    private TikTokNativeCommentPublisher publisher;
    @Before public void install() throws Exception {
        framework = new Framework();
        VM.framework = framework;
        VM.instances.clear();
        VM.allow = true;
        VM.emitUiStatus = false;
        VM.asyncSuccessSibling = false;
        VM.failInitialize = false;
        publisher = TikTokNativeCommentPublisher.install(framework.api, report(),
                getClass().getClassLoader(), ignored -> { }, "TokAi.Test");
    }
    @After public void close() { publisher.close(); ShadowLooper.idleMainLooper(); }

    @Test public void sourceOverrideIsRestrictedToOwnedRequestsAndKeepsNativePermissionGate() throws Throwable {
        VM foreign = new VM();
        assertSame(foreign.original, framework.invoke(VM.class.getMethod("source"), foreign));
        VM.allow = false;
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        assertEquals(0, accepted.get());
        assertEquals(Collections.singletonList("failed"), result);
        VM owned = VM.instances.get(VM.instances.size() - 1);
        assertEquals(1, owned.permissionChecks);
        assertEquals(0, owned.nativeDispatches);
        assertEquals(1, owned.initializes);
        assertEquals(0, owned.preparedCalls);
        assertTrue(owned._initialized);
        assertEquals("work", owned.observed.commonModel.aweme.getAid());
        assertSame(foreign.original, framework.invoke(VM.class.getMethod("source"), foreign));
        framework.invoke(VM.class.getMethod("prepared"), foreign);
        assertEquals(1, foreign.preparedCalls);
    }

    @Test public void deoptimizationFalseKeepsInstallationAndReportsUnconfirmed() throws Exception {
        Framework unsupported = new Framework();
        unsupported.deoptimize = false;
        List<String> logs = new ArrayList<>();
        TikTokNativeCommentPublisher installed = TikTokNativeCommentPublisher.install(unsupported.api,
                report(), getClass().getClassLoader(), logs::add, "TokAi.UnsupportedDeopt");
        try {
            assertTrue(logs.stream().anyMatch(message -> message.contains("confirmed=false")));
            assertFalse(unsupported.hooks.isEmpty());
        } finally { installed.close(); }
    }

    @Test public void genericFailureHelperIsDeoptimizedBeforeAnyNativeSend() throws Exception {
        assertTrue(framework.deoptimizationAttempts.contains(
                VM.class.getMethod("genericFailure", Exception.class, Comment.class)));
        assertTrue(VM.instances.isEmpty());
    }

    @Test public void timeoutStaysUnconfirmedAndRetainsVerifiedLateReceiptWithoutSendingAgain() throws Throwable {
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(1, accepted.get());
        assertEquals(1, owned.nativeDispatches);
        assertTrue(result.isEmpty());
        assertTrue(publisher.ownsPublish(owned.input));
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(30_001));
        assertEquals(Collections.singletonList("submitted"), result);
        Response response = new Response();
        framework.invoke(VM.class.getMethod("success", Response.class, boolean.class), owned, response, false);
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("submitted", "sent"), result);
        framework.invoke(VM.class.getMethod("success", Response.class, boolean.class), owned, response, false);
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("submitted", "sent"), result);
        assertEquals(1, owned.nativeDispatches);
    }

    @Test public void cancellationNeverEntersTheHostAndForeignFailuresCannotCompleteOwnedRequests() throws Throwable {
        List<String> cancelled = new ArrayList<>();
        publisher.send(new Work(), "work", "text", null, null, () -> false,
                () -> { throw new AssertionError(); }, () -> { throw new AssertionError(); }, cancelled::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("cancelled"), cancelled);
        assertTrue(VM.instances.isEmpty());
        List<String> result = new ArrayList<>();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, () -> { }, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        Comment different = new Comment(); different.awemeId = "other";
        framework.invoke(VM.class.getMethod("failure", Throwable.class, Comment.class, String.class),
                owned, new Exception(), different, "");
        ShadowLooper.idleMainLooper();
        assertTrue(result.isEmpty());
        framework.invoke(VM.class.getMethod("failure", Throwable.class, Comment.class, String.class),
                owned, new Exception(), new Comment(), "");
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), result);
    }

    @Test public void ownedInitialDispatchNeverEnqueuesThePanelLifecycleLookup() throws Throwable {
        VM.emitUiStatus = true;
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(1, accepted.get());
        assertEquals(1, owned.nativeDispatches);
        assertTrue(result.isEmpty());

        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));

        assertEquals(0, owned.uiStatusCalls);
        assertTrue(result.isEmpty());
        assertEquals(1, accepted.get());
        assertEquals(1, owned.nativeDispatches);
    }

    @Test public void ownedTextSuccessKeepsNativeReceiptWithoutEnteringPanelStatusRelay() throws Throwable {
        VM.emitUiStatus = true;
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(1, accepted.get());
        assertTrue(result.isEmpty());
        assertEquals("text", owned.input.LIZ);
        assertNull(owned.input.LIZLLL);

        framework.invoke(VM.class.getMethod("success", Response.class, boolean.class),
                owned, new Response(), false);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));

        assertEquals(Collections.singletonList("sent"), result);
        assertEquals(1, owned.nativeSuccessEffects);
        assertEquals(1, owned.nativeDispatches);
        assertEquals(0, owned.uiStatusCalls);
    }

    @Test public void ownedAudioFailureKeepsNativeFailureWithoutEnteringPanelStatusRelay() throws Throwable {
        VM.emitUiStatus = true;
        File audio = Files.createTempFile("tokai-comment-", ".mp3").toFile();
        try {
            Files.write(audio.toPath(), new byte[]{1, 2, 3});
            List<String> result = new ArrayList<>();
            AtomicInteger accepted = new AtomicInteger();
            publisher.send(new Work(), "work", "speech", new SpeechPreparation.Audio(audio, 1_000),
                    null, () -> true, () -> true, accepted::incrementAndGet, result::add);
            ShadowLooper.idleMainLooper();
            VM owned = VM.instances.get(0);
            assertEquals(1, accepted.get());
            assertTrue(result.isEmpty());
            assertEquals("", owned.input.LIZ);
            assertEquals(1, owned.input.LIZLLL.size());

            framework.invoke(VM.class.getMethod("failure", Throwable.class, Comment.class, String.class),
                    owned, new Exception("native failure"), new Comment(), "failed");
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));

            assertEquals(Collections.singletonList("failed"), result);
            assertEquals(1, owned.nativeFailureEffects);
            assertEquals(1, owned.nativeDispatches);
            assertEquals(0, owned.uiStatusCalls);
        } finally { Files.deleteIfExists(audio.toPath()); }
    }

    @Test public void foreignPanelStatusRelayStillRunsWithItsOwnLifecycleAndResponse() throws Throwable {
        VM foreign = new VM();
        LifecycleRegistry lifecycle = new LifecycleRegistry(foreign);
        lifecycle.setCurrentState(Lifecycle.State.STARTED);
        foreign.setLifecycleRef(new WeakReference<>(lifecycle));
        Status status = new Status();
        Response response = new Response();

        framework.invoke(VM.class.getMethod("status", Status.class, Response.class),
                foreign, status, response);
        assertEquals(1, foreign.uiStatusCalls);
        assertEquals(0, foreign.uiLifecycleReads);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));

        assertEquals(1, foreign.uiLifecycleReads);
        assertEquals(Lifecycle.State.STARTED, foreign.uiLifecycleState);
        assertSame(status, foreign.uiStatus);
        assertSame(response, foreign.uiResponse);
        assertSame(lifecycle, foreign.getLifecycle());
    }

    @Test public void ownedPanelStatusRelayStaysSuppressedAfterRequestRetirement() throws Throwable {
        List<String> result = new ArrayList<>();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, () -> { }, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(120_001));
        assertEquals(Collections.singletonList("submitted"), result);
        assertEquals(1, owned.clearCalls);

        framework.invoke(VM.class.getMethod("status", Status.class, Response.class),
                owned, new Status(), new Response());
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));

        assertEquals(0, owned.uiStatusCalls);
        assertEquals(Collections.singletonList("submitted"), result);
        assertEquals(1, owned.nativeDispatches);
    }

    @Test public void ownedTextSuccessRunsNativeLifecycleSiblingAndKeepsItsReceipt() throws Throwable {
        assertNativeSuccessSibling(null);
    }

    @Test public void ownedAudioSuccessRunsNativeLifecycleSiblingAndKeepsItsReceipt() throws Throwable {
        File audio = Files.createTempFile("tokai-native-success-", ".mp3").toFile();
        try {
            Files.write(audio.toPath(), new byte[]{1, 2, 3});
            assertNativeSuccessSibling(new SpeechPreparation.Audio(audio, 1_000));
        } finally { Files.deleteIfExists(audio.toPath()); }
    }

    @Test public void permissionDeniedPublisherReleasesItsLifecycleAtRetirementOnce() {
        VM.allow = false;
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(Collections.singletonList("failed"), result);
        assertEquals(0, accepted.get());
        assertEquals(0, owned.nativeDispatches);
        assertEquals(Lifecycle.State.CREATED, owned.lifecycleAtInitialize);

        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(120_001));
        assertReleasedOnce(owned);
        publisher.close();
        ShadowLooper.idleMainLooper();
        assertReleasedOnce(owned);
    }

    @Test public void cancellationBeforeNativeSendReleasesItsLifecycleOnce() {
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> false, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(Collections.singletonList("cancelled"), result);
        assertEquals(0, accepted.get());
        assertEquals(0, owned.permissionChecks);
        assertReleasedOnce(owned);

        publisher.close();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(120_001));
        assertReleasedOnce(owned);
    }

    @Test public void preSendFailureReleasesItsLifecycleAndNativeStateOnce() {
        List<String> result = new ArrayList<>();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> { throw new IllegalStateException("reservation failed"); },
                () -> fail("A failed reservation must not be accepted"), result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(Collections.singletonList("failed"), result);
        assertEquals(0, owned.permissionChecks);
        assertReleasedOnce(owned);

        publisher.close();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(120_001));
        assertReleasedOnce(owned);
    }

    @Test public void initializationFailureReleasesItsLifecycleAndNativeStateOnce() {
        VM.failInitialize = true;
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(Collections.singletonList("failed"), result);
        assertEquals(0, accepted.get());
        assertEquals(0, owned.permissionChecks);
        assertEquals(0, owned.nativeDispatches);
        assertFalse(owned._initialized);
        assertReleasedOnce(owned);

        publisher.close();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(120_001));
        assertReleasedOnce(owned);
    }

    @Test public void dispatchedPublisherKeepsItsLifecycleUntilRetirementAndReleasesItOnce() {
        List<String> result = new ArrayList<>();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, () -> { }, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(Lifecycle.State.CREATED, owned.lifecycleAtInitialize);
        Lifecycle lifecycle = owned.getLifecycle();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(119_999));
        assertEquals(Lifecycle.State.CREATED, lifecycle.getCurrentState());
        assertEquals(0, owned.clearCalls);
        assertEquals(Collections.singletonList("submitted"), result);

        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(2));
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.getCurrentState());
        assertReleasedOnce(owned);
        publisher.close();
        ShadowLooper.idleMainLooper();
        assertReleasedOnce(owned);
    }

    @Test public void closeFromAnotherThreadDestroysOwnedLifecycleOnMainAndLeavesForeignOwnerAlone() throws Exception {
        List<String> result = new ArrayList<>();
        publisher.send(new Work(), "work", "text", null, null, () -> true,
                () -> true, () -> { }, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        VM foreign = new VM();
        LifecycleRegistry foreignLifecycle = new LifecycleRegistry(foreign);
        foreignLifecycle.setCurrentState(Lifecycle.State.STARTED);
        foreign.setLifecycleRef(new WeakReference<>(foreignLifecycle));
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try { publisher.close(); } catch (Throwable failure) { closeFailure.set(failure); }
        }, "publisher-close-test");
        closer.start();
        closer.join(5_000);
        assertFalse("Publisher close did not finish", closer.isAlive());
        assertNull(closeFailure.get());
        ShadowLooper.idleMainLooper();

        assertReleasedOnce(owned);
        assertEquals(Lifecycle.State.STARTED, foreignLifecycle.getCurrentState());
        assertEquals(0, foreign.clearCalls);
        publisher.close();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(120_001));
        assertReleasedOnce(owned);
        assertTrue(result.isEmpty());
    }

    private void assertNativeSuccessSibling(SpeechPreparation.Audio audio) throws Throwable {
        VM.emitUiStatus = true;
        VM.asyncSuccessSibling = true;
        List<String> result = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(new Work(), "work", "text", audio, null, () -> true,
                () -> true, accepted::incrementAndGet, result::add);
        ShadowLooper.idleMainLooper();
        VM owned = VM.instances.get(0);
        assertEquals(1, accepted.get());
        assertTrue(result.isEmpty());
        assertEquals(audio == null ? "text" : "", owned.input.LIZ);
        if (audio == null) assertNull(owned.input.LIZLLL);
        else assertEquals(1, owned.input.LIZLLL.size());

        framework.invoke(VM.class.getMethod("success", Response.class, boolean.class),
                owned, new Response(), false);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));

        assertEquals(Lifecycle.State.CREATED, owned.lifecycleAtInitialize);
        assertEquals(Lifecycle.State.CREATED, owned.nativeSuccessLifecycle);
        assertEquals(1, owned.commentCount);
        assertEquals(1, owned.nativeCommentEvents);
        assertEquals(1, owned.nativeSuccessEffects);
        assertEquals(Collections.singletonList("sent"), result);
        assertEquals(1, owned.nativeDispatches);
        assertEquals(0, owned.uiStatusCalls);
    }

    private static void assertReleasedOnce(VM owned) {
        assertEquals(Lifecycle.State.CREATED, owned.lifecycleAtInitialize);
        assertEquals(Arrays.asList(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_DESTROY), owned.lifecycleEvents);
        assertEquals(Lifecycle.State.DESTROYED, owned.destroyedState);
        assertTrue(owned.lifecycleEventsOnMain);
        assertEquals(1, owned.clearCalls);
        assertTrue(owned.clearOnMain);
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>();
        add(rows, "tiktok_comment_publish_checked", VM.class.getMethod("submit", Input.class, Comment.class, Object.class));
        add(rows, "tiktok_comment_source", VM.class.getMethod("source"));
        add(rows, "tiktok_comment_publish_dispatch", VM.class.getMethod("dispatch", Comment.class, Input.class, boolean.class));
        add(rows, "tiktok_comment_publish_success", VM.class.getMethod("success", Response.class, boolean.class));
        add(rows, "tiktok_comment_publish_failure", VM.class.getMethod("failure", Throwable.class, Comment.class, String.class));
        add(rows, "tiktok_comment_publish_failure_state", VM.class.getMethod("genericFailure", Exception.class, Comment.class));
        add(rows, "tiktok_comment_publisher_clear", VM.class.getMethod("clear"));
        add(rows, "tiktok_comment_initialize", VM.class.getMethod("initialize", Dispatcher.class, Transform.class));
        add(rows, "tiktok_comment_publisher_prepared", VM.class.getMethod("prepared"));
        add(rows, "tiktok_comment_publish_ui_status", VM.class.getMethod("status", Status.class, Response.class));
        add(rows, "tiktok_comment_publisher_lifecycle", VM.class.getMethod("setLifecycleRef", WeakReference.class));
        Class<?> nativeRegistry = Class.forName("androidx.lifecycle.LifecycleRegistry", false, VM.class.getClassLoader());
        Class<?> nativeEvent = Class.forName("androidx.lifecycle.Lifecycle$Event", false, VM.class.getClassLoader());
        add(rows, "tiktok_comment_lifecycle_event", nativeRegistry.getMethod("handleLifecycleEvent", nativeEvent));
        rows.add(new RuleScanResult("tiktok_comment_lifecycle_registry", "comment", Collections.singletonList(
                new HostCandidate("fixture.apk", "classes.dex", HostTargets.descriptor(nativeRegistry), null, null))));
        rows.add(new RuleScanResult("tiktok_comment_audio_model", "comment", Collections.singletonList(
                new HostCandidate("fixture.apk", "classes.dex", HostTargets.descriptor(Audio.class), null, null))));
        rows.add(new RuleScanResult("tiktok_comment_dispatcher", "comment", Collections.singletonList(
                new HostCandidate("fixture.apk", "classes.dex", HostTargets.descriptor(Dispatcher.class), null, null))));
        return new HostScanReport("fixture", rows, false);
    }
    private static void add(List<RuleScanResult> rows, String id, Method method) {
        rows.add(new RuleScanResult(id, "comment", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                method.getName(), HostTargets.descriptor(method)))));
    }
    public static final class Work { public String getAid() { return "work"; } }
    public static final class Common { public Work aweme; }
    public static final class Source { public Common commonModel = new Common(); }
    public static final class Comment { public String cid = "server-cid", awemeId = "work"; }
    public static final class Response { public int status_code; public Comment comment = new Comment(); }
    public static final class Status { }
    public static final class Input {
        public CharSequence LIZ;
        public Work LJIILLIIL;
        public Comment LJIIIIZZ;
        public List<Audio> LIZLLL;
    }
    public static final class Audio {
        public Object videoModelStruct;
        public Audio(String path, long duration, List<?> wave, String id, long bytes,
                     String format, String vid, String videoModel, Object model) { }
    }
    public interface Transform { Object invoke(Object state); }
    public static final class Dispatcher { public Dispatcher() { } }
    public static final class VM implements LifecycleOwner {
        static Framework framework;
        static boolean allow, emitUiStatus, asyncSuccessSibling, failInitialize;
        static final List<VM> instances = new ArrayList<>();
        final Source original = new Source();
        Source observed;
        Input input;
        int permissionChecks, nativeDispatches;
        int initializes, preparedCalls, clearCalls;
        int nativeSuccessEffects, nativeFailureEffects, uiStatusCalls, uiLifecycleReads;
        int commentCount, nativeCommentEvents;
        Lifecycle.State lifecycleAtInitialize, nativeSuccessLifecycle, destroyedState;
        final List<Lifecycle.Event> lifecycleEvents = new ArrayList<>();
        boolean lifecycleEventsOnMain = true, clearOnMain;
        Lifecycle.State uiLifecycleState;
        Status uiStatus;
        Response uiResponse;
        private WeakReference<Lifecycle> lifecycleRef;
        public boolean _initialized;
        public VM() { instances.add(this); }
        public Source source() { return original; }
        public void initialize(Dispatcher dispatcher, Transform transform) throws Throwable {
            initializes++;
            Lifecycle lifecycle = lifecycleRef == null ? null : lifecycleRef.get();
            if (lifecycle != null) {
                lifecycleAtInitialize = lifecycle.getCurrentState();
                lifecycle.addObserver((LifecycleEventObserver) (owner, event) -> {
                    lifecycleEvents.add(event);
                    lifecycleEventsOnMain &= Looper.myLooper() == Looper.getMainLooper();
                    if (event == Lifecycle.Event.ON_DESTROY) destroyedState = owner.getLifecycle().getCurrentState();
                });
            }
            if (failInitialize) throw new IllegalStateException("native initialization failed");
            assertSame(this, transform.invoke(this));
            framework.invoke(VM.class.getMethod("prepared"), this);
            _initialized = true;
        }
        public void prepared() { preparedCalls++; }
        public void submit(Input input, Comment comment, Object callback) throws Throwable {
            permissionChecks++;
            observed = (Source) framework.invoke(VM.class.getMethod("source"), this);
            this.input = input;
            if (allow) framework.invoke(VM.class.getMethod("dispatch", Comment.class, Input.class, boolean.class),
                    this, new Comment(), input, true);
        }
        public void dispatch(Comment comment, Input input, boolean pending) throws Throwable {
            if (emitUiStatus) framework.invoke(VM.class.getMethod("status", Status.class, Response.class),
                    this, new Status(), null);
            nativeDispatches++;
        }
        public WeakReference<Lifecycle> getLifecycleRef() { return lifecycleRef; }
        public void setLifecycleRef(WeakReference<Lifecycle> lifecycleRef) { this.lifecycleRef = lifecycleRef; }
        @Override public Lifecycle getLifecycle() { return getLifecycleRef().get(); }
        public void status(Status status, Response response) {
            uiStatusCalls++;
            // The host's deferred panel/shared-view-model lookup dereferences the Assem lifecycle.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                uiLifecycleState = getLifecycle().getCurrentState();
                uiLifecycleReads++;
                uiStatus = status;
                uiResponse = response;
            }, 2_000);
        }
        public void success(Response response, boolean pending) throws Throwable {
            if (emitUiStatus) framework.invoke(VM.class.getMethod("status", Status.class, Response.class),
                    this, new Status(), response);
            if (asyncSuccessSibling) {
                // A separate native success job updates the host even when panel fanout is suppressed.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    nativeSuccessLifecycle = getLifecycle().getCurrentState();
                    if (nativeSuccessLifecycle.isAtLeast(Lifecycle.State.CREATED)) {
                        commentCount++;
                        nativeCommentEvents++;
                    }
                }, 2_000);
            }
            nativeSuccessEffects++;
        }
        public void failure(Throwable throwable, Comment comment, String reason) throws Throwable {
            genericFailure(throwable instanceof Exception ? (Exception) throwable : new Exception(throwable), comment);
            nativeFailureEffects++;
        }
        public void genericFailure(Exception failure, Comment comment) throws Throwable {
            if (emitUiStatus) framework.invoke(VM.class.getMethod("status", Status.class, Response.class),
                    this, new Status(), null);
        }
        public void clear() {
            clearCalls++;
            clearOnMain = Looper.myLooper() == Looper.getMainLooper();
        }
    }
    private static final class Framework {
        boolean deoptimize = true;
        final List<Executable> deoptimizationAttempts = new ArrayList<>();
        final Map<Executable, List<XposedInterface.Hooker>> hooks = new HashMap<>();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if ("deoptimize".equals(method.getName())) {
                        deoptimizationAttempts.add((Executable) args[0]);
                        return deoptimize;
                    }
                    if (!"hook".equals(method.getName())) return null;
                    Executable target = (Executable) args[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if (!"intercept".equals(call.getName())) return builder;
                                XposedInterface.Hooker hook = (XposedInterface.Hooker) values[0];
                                hooks.computeIfAbsent(target, ignored -> new ArrayList<>()).add(hook);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, unused) -> null);
                            });
                });
        Object invoke(Method target, Object receiver, Object... input) throws Throwable { return invokeAt(target, receiver, input, 0); }
        private Object invokeAt(Method target, Object receiver, Object[] input, int index) throws Throwable {
            List<XposedInterface.Hooker> entries = hooks.getOrDefault(target, Collections.emptyList());
            if (index == entries.size()) return target.invoke(receiver, input);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getThisObject": return receiver;
                            case "getArg": return input[(Integer) args[0]];
                            case "getArgs": return Arrays.asList(input);
                            case "proceed": return invokeAt(target, receiver, args == null || args.length == 0 ? input : (Object[]) args[0], index + 1);
                            default: return null;
                        }
                    });
            return entries.get(index).intercept(chain);
        }
    }
}
