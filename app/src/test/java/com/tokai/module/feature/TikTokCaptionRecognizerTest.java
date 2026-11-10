package com.tokai.module.feature;

import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Replays native auth/upload/Retrofit contracts without contacting the host or a server. */
public final class TikTokCaptionRecognizerTest {
    private File audio;
    private Harness harness;
    private final List<TikTokCaptionRecognizer> recognizers = new ArrayList<>();

    @Before public void prepare() throws Exception {
        audio = File.createTempFile("tokai-asr-fixture-", ".aac");
        Files.write(audio.toPath(), new byte[]{1, 2, 3});
        harness = new Harness();
        Factory.harness = harness;
        Uploader.harness = harness;
    }

    @After public void clean() throws Exception {
        if (harness.authGate != null) harness.authGate.countDown();
        for (TikTokCaptionRecognizer recognizer : recognizers) recognizer.close();
        Files.deleteIfExists(audio.toPath());
    }

    @Test public void nativeSuccessKeepsMillisecondsAndUsesNormalAuthAndUploadPolicy() throws Exception {
        CaptionTranscript result = recognizer().recognize(audio, 10_000, new MediaReadCancellation());
        assertEquals(1, result.cues().size());
        assertEquals(1250, result.cues().get(0).startMillis);
        assertEquals(2490, result.cues().get(0).endMillis);
        assertEquals("hello", result.cues().get(0).text);
        assertEquals("captions_audio", harness.uploader.tag);
        assertEquals(audio.getAbsolutePath(), harness.uploader.path);
        assertSame(harness.config, harness.uploader.config);
        assertEquals(Kind.CAPTION, harness.uploader.kind);
        assertEquals("SubtitleOriginalSoundUpload", harness.networkTag);
        assertEquals(73, harness.policyObserved);
        assertEquals("fixture-tos", harness.api.tos);
        assertEquals("auto_language", harness.api.language);
        assertTrue(harness.api.useLid);
        assertEquals(80, harness.api.maxLines);
        assertEquals(1, harness.api.wordsPerLine);
        assertFalse(harness.api.useDdc);
        assertFalse(harness.api.capitalize);
        assertFalse(harness.api.punctuation);
        assertNull(harness.api.languageModel);
        assertEquals("fixture-task", harness.api.queriedTask);
        assertTrue(harness.uploader.closed);
        assertTrue(audio.isFile());
    }

    @Test public void refusedAuthNeverStartsAnUpload() throws Exception {
        harness.auth = new Step(7, new AuthConfig(harness.config));
        assertThrows(IllegalStateException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertNull(harness.uploader);
        assertEquals(0, harness.api.submitCount.get());
    }

    @Test public void genericCreationCannotBeGuessedIntoUploadCredentials() throws Exception {
        harness.auth = new Step(0, new Creation());
        assertThrows(IllegalStateException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertNull(harness.uploader);
    }

    @Test public void uploadFailureClosesUploaderAndDoesNotSubmit() throws Exception {
        harness.uploadStatus = 2;
        assertThrows(IllegalStateException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertTrue(harness.uploader.closed);
        assertEquals(0, harness.api.submitCount.get());
    }

    @Test public void cancellingUploadClosesItBeforeReturnAndIgnoresLateSuccess() throws Exception {
        harness.holdUpload = true;
        MediaReadCancellation token = new MediaReadCancellation();
        FutureTask<CaptionTranscript> task = start(recognizer(), token);
        assertTrue(harness.uploadStarted.await(1, TimeUnit.SECONDS));
        token.cancel();
        assertCancelled(task);
        assertTrue(harness.uploader.closed);
        harness.uploader.listener.LIZLLL(0, 1, new UploadInfo("late-key"));
        assertEquals(0, harness.api.submitCount.get());
    }

    @Test public void cancellingPendingHttpCancelsNativeCall() throws Exception {
        harness.api.holdSubmit = true;
        MediaReadCancellation token = new MediaReadCancellation();
        FutureTask<CaptionTranscript> task = start(recognizer(), token);
        assertTrue(harness.api.submit.started.await(1, TimeUnit.SECONDS));
        token.cancel();
        assertCancelled(task);
        assertTrue(harness.api.submit.cancelled);
        assertEquals(0, harness.api.queryCount.get());
        harness.api.submit.succeed(harness.api.submitBody);
        assertEquals(0, harness.api.queryCount.get());
    }

    @Test public void cancellingBlockedAuthReturnsAndLateAuthCannotUpload() throws Exception {
        harness.authGate = new CountDownLatch(1);
        MediaReadCancellation token = new MediaReadCancellation();
        FutureTask<CaptionTranscript> task = start(recognizer(), token);
        assertTrue(harness.authStarted.await(1, TimeUnit.SECONDS));
        token.cancel();
        assertCancelled(task);
        assertNull(harness.uploader);
        harness.authGate.countDown();
        assertTrue(harness.authFinished.await(1, TimeUnit.SECONDS));
        assertNull(harness.uploader);
    }

    @Test public void serverFailureDoesNotTreatDefaultDtoAsSuccess() throws Exception {
        harness.api.queryBody.statusCode = 4;
        assertThrows(IllegalStateException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertTrue(harness.uploader.closed);
    }

    @Test public void cancellingPendingQueryCancelsNativeCallAndKeepsAudioCallerOwned() throws Exception {
        harness.api.holdQuery = true;
        MediaReadCancellation token = new MediaReadCancellation();
        FutureTask<CaptionTranscript> task = start(recognizer(), token);
        assertTrue(harness.api.query.started.await(1, TimeUnit.SECONDS));
        token.cancel();
        assertCancelled(task);
        assertTrue(harness.api.query.cancelled);
        harness.api.query.succeed(harness.api.queryBody);
        assertTrue(audio.isFile());
    }

    @Test public void closeCancelsUploadAndPreventsReuse() throws Exception {
        harness.holdUpload = true;
        TikTokCaptionRecognizer recognizer = recognizer();
        FutureTask<CaptionTranscript> task = start(recognizer, new MediaReadCancellation());
        assertTrue(harness.uploadStarted.await(1, TimeUnit.SECONDS));
        recognizer.close();
        assertCancelled(task);
        assertTrue(harness.uploader.closed);
        assertThrows(CancellationException.class, () -> recognizer.recognize(audio, 10_000, new MediaReadCancellation()));
    }

    @Test public void uploadDeadlineClosesNativeResourceBeforeReturning() throws Exception {
        harness.holdUpload = true;
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        MediaReadCancellation token = new MediaReadCancellation(100, clock::get);
        FutureTask<CaptionTranscript> task = start(recognizer(), token);
        assertTrue(harness.uploadStarted.await(1, TimeUnit.SECONDS));
        clock.set(1_000_000_000L);
        try { task.get(1, TimeUnit.SECONDS); fail("Expected deadline"); }
        catch (java.util.concurrent.ExecutionException failure) {
            assertTrue(failure.getCause() instanceof java.util.concurrent.TimeoutException);
        }
        assertTrue(harness.uploader.closed);
        assertEquals(0, harness.api.submitCount.get());
    }

    @Test public void nativeHttpFailureStopsBeforeQuery() throws Exception {
        harness.api.submit.failure = new java.io.IOException("private fixture transport data");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertFalse(failure.toString().contains("private fixture"));
        assertEquals(0, harness.api.queryCount.get());
    }

    @Test public void httpErrorCannotUseAnApparentlySuccessfulBody() throws Exception {
        harness.api.submit.httpSuccess = false;
        assertThrows(IllegalStateException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertEquals(0, harness.api.queryCount.get());
    }

    @Test public void nativePolicyExceptionReturnsDisconnectedAndTerminatesWithoutEscaping() throws Exception {
        harness.networkFailure = new IllegalStateException("private fixture policy state");
        assertThrows(IllegalStateException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertEquals(0, harness.policyObserved);
        assertTrue(harness.uploader.closed);
        assertEquals(0, harness.api.submitCount.get());
    }

    @Test public void malformedNativeCallbackBecomesTerminalFailureOnTheWorker() throws Exception {
        harness.holdUpload = true;
        FutureTask<CaptionTranscript> task = start(recognizer(), new MediaReadCancellation());
        assertTrue(harness.uploadStarted.await(1, TimeUnit.SECONDS));
        harness.uploader.listener.LIZLLL(0, 1, null);
        try { task.get(1, TimeUnit.SECONDS); fail("Expected callback failure"); }
        catch (java.util.concurrent.ExecutionException failure) { assertTrue(failure.getCause() instanceof IllegalStateException); }
        assertTrue(harness.uploader.closed);
        assertEquals(0, harness.api.submitCount.get());
    }

    @Test public void interruptedWorkerCancelsAndClosesTheNativeUpload() throws Exception {
        harness.holdUpload = true;
        TikTokCaptionRecognizer recognizer = recognizer();
        FutureTask<CaptionTranscript> task = new FutureTask<>(() -> recognizer.recognize(audio, 10_000, new MediaReadCancellation()));
        Thread worker = new Thread(task, "interrupted-caption-fixture");
        worker.setDaemon(true); worker.start();
        assertTrue(harness.uploadStarted.await(1, TimeUnit.SECONDS));
        worker.interrupt();
        assertCancelled(task);
        assertTrue(harness.uploader.closed);
        assertEquals(0, harness.api.submitCount.get());
    }

    @Test public void unexpectedHttpBodyTypeIsRejected() throws Exception {
        harness.api.submit.overrideBody = "untrusted object";
        assertThrows(IllegalStateException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
        assertEquals(0, harness.api.queryCount.get());
    }

    @Test public void missingRuleFailsAtConstructionWithoutRequestingCredentials() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>(report().getRuleResults());
        rows.removeIf(row -> row.getRuleId().equals("tiktok_caption_asr_upload_close"));
        assertThrows(IllegalStateException.class, () -> new TikTokCaptionRecognizer(
                new HostScanReport("fixture-missing", rows, false), getClass().getClassLoader()));
        assertEquals(0, harness.authRequests.get());
    }

    @Test public void mismatchedResultFieldContractFailsBeforeAnyRequest() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>(report().getRuleResults());
        rows.removeIf(row -> row.getRuleId().equals("tiktok_caption_asr_result"));
        type(rows, "result", BadQuery.class);
        assertThrows(IllegalStateException.class, () -> new TikTokCaptionRecognizer(
                new HostScanReport("fixture-drift", rows, false), getClass().getClassLoader()));
        assertEquals(0, harness.authRequests.get());
    }

    @Test public void noSpeechReturnsAnEmptyTrack() throws Exception {
        harness.api.queryBody.videoCaption.mList = List.of();
        CaptionTranscript transcript = recognizer().recognize(audio, 10_000, new MediaReadCancellation());
        assertTrue(transcript.cues().isEmpty());
    }

    @Test public void malformedTimelineIsRejected() throws Exception {
        harness.api.queryBody.videoCaption.mList = List.of(new Utterance(2000, 1000, "bad"));
        assertThrows(IllegalArgumentException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
    }

    @Test public void transcriptPastMediaDurationIsRejected() throws Exception {
        harness.api.queryBody.videoCaption.mList = List.of(new Utterance(9000, 12_000, "bad"));
        assertThrows(IllegalArgumentException.class, () -> recognizer().recognize(audio, 10_000, new MediaReadCancellation()));
    }

    @Test public void cancelledBeforeStartNeverRequestsAuth() throws Exception {
        MediaReadCancellation token = new MediaReadCancellation();
        token.cancel();
        assertThrows(CancellationException.class, () -> recognizer().recognize(audio, 10_000, token));
        assertEquals(0, harness.authRequests.get());
    }

    private TikTokCaptionRecognizer recognizer() throws Exception {
        TikTokCaptionRecognizer recognizer = new TikTokCaptionRecognizer(report(), getClass().getClassLoader());
        recognizers.add(recognizer);
        return recognizer;
    }

    private FutureTask<CaptionTranscript> start(TikTokCaptionRecognizer recognizer, MediaReadCancellation token) {
        FutureTask<CaptionTranscript> result = new FutureTask<>(() -> recognizer.recognize(audio, 10_000, token));
        Thread thread = new Thread(result, "caption-native-fixture");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private static void assertCancelled(FutureTask<?> task) throws Exception {
        try { task.get(1, TimeUnit.SECONDS); fail("Expected cancellation"); }
        catch (java.util.concurrent.ExecutionException failure) { assertTrue(failure.getCause() instanceof CancellationException); }
    }

    public interface HttpCallback {
        void onFailure(Call call, Throwable failure);
        void onResponse(Call call, Response response);
    }
    public interface UploadListener {
        void LIZ(); void LIZIZ(); void LIZJ();
        void LIZLLL(int event, long value, UploadInfo info);
        int LJ();
    }
    public enum Kind { CAPTION }
    public static class Creation { }
    public static final class UploadConfig { }
    public static final class AuthConfig extends Creation {
        public UploadConfig videoConfig;
        AuthConfig(UploadConfig value) { videoConfig = value; }
    }
    public static final class Step {
        public int LIZ; public String LIZIZ = ""; public Object LIZJ;
        Step(int code, Object value) { LIZ = code; LIZJ = value; }
    }
    public static final class Response {
        public Object LIZIZ;
        final boolean successful;
        Response(Object value, boolean successful) { LIZIZ = value; this.successful = successful; }
        public boolean LIZJ() { return successful; }
    }
    public static final class Body { public int statusCode; public Query videoCaption = new Query(); }
    public static final class Query {
        public String id = "fixture-task";
        public List<Utterance> mList = List.of(new Utterance(1250, 2490, "hello"));
    }
    public static final class BadQuery { public int id; }
    public static final class Utterance {
        public long startTime, endTime; public String text;
        Utterance(long start, long end, String value) { startTime = start; endTime = end; text = value; }
    }
    public static final class UploadInfo { public String mTosKey; UploadInfo(String value) { mTosKey = value; } }
    public static final class Call {
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean cancelled;
        HttpCallback callback;
        Body body;
        Object overrideBody;
        Throwable failure;
        boolean hold, httpSuccess = true;
        public void enqueue(HttpCallback value) {
            callback = value; started.countDown();
            if (!hold) {
                if (failure != null) callback.onFailure(this, failure);
                else succeed(overrideBody == null ? body : overrideBody);
            }
        }
        public void cancel() { cancelled = true; }
        void succeed(Object value) { callback.onResponse(this, new Response(value, httpSuccess)); }
    }
    public static final class Api {
        final Body submitBody = new Body(), queryBody = new Body();
        final Call submit = new Call(), query = new Call();
        final AtomicInteger submitCount = new AtomicInteger(), queryCount = new AtomicInteger();
        boolean holdSubmit, holdQuery, useLid, useDdc, capitalize, punctuation;
        int maxLines, wordsPerLine;
        String tos, language, languageModel, queriedTask;
        public Call submit(String key, int lines, int words, String lang, boolean lid, boolean ddc,
                           String model, boolean caps, boolean punc) {
            submitCount.incrementAndGet(); tos = key; maxLines = lines; wordsPerLine = words;
            language = lang; useLid = lid; useDdc = ddc; languageModel = model; capitalize = caps; punctuation = punc;
            submit.body = submitBody; submit.hold = holdSubmit; return submit;
        }
        public Call query(String id) { queryCount.incrementAndGet(); queriedTask = id; query.body = queryBody; query.hold = holdQuery; return query; }
    }
    public static final class Factory {
        static Harness harness;
        public Factory() { }
        public static Step LIZIZ() {
            Harness current = harness;
            current.authRequests.incrementAndGet(); current.authStarted.countDown();
            CountDownLatch gate = current.authGate;
            if (gate != null) {
                boolean done = false;
                while (!done) try { gate.await(); done = true; } catch (InterruptedException ignored) { }
            }
            current.authFinished.countDown();
            return current.auth;
        }
        public Api LIZJ() { return harness.api; }
        public static int network(UploadConfig config, String tag) {
            harness.networkTag = tag;
            if (harness.networkFailure != null) throw harness.networkFailure;
            return 73;
        }
    }
    public static final class Uploader {
        static Harness harness;
        final UploadConfig config; final Kind kind;
        UploadListener listener;
        String path, tag;
        volatile boolean closed;
        public Uploader(UploadConfig config, Kind kind) { this.config = config; this.kind = kind; harness.uploader = this; }
        public void LIZLLL(String value) { tag = value; }
        public void LJFF(String value) { path = value; }
        public void LJ(UploadListener value) { listener = value; }
        public void LJII() {
            harness.policyObserved = listener.LJ();
            harness.uploadStarted.countDown();
            if (!harness.holdUpload) listener.LIZLLL(harness.uploadStatus, 1, new UploadInfo("fixture-tos"));
        }
        public void LIZ() { closed = true; }
    }
    private static final class Harness {
        final UploadConfig config = new UploadConfig();
        final Api api = new Api();
        Step auth = new Step(0, new AuthConfig(config));
        final AtomicInteger authRequests = new AtomicInteger();
        final CountDownLatch authStarted = new CountDownLatch(1), authFinished = new CountDownLatch(1), uploadStarted = new CountDownLatch(1);
        volatile CountDownLatch authGate;
        volatile Uploader uploader;
        boolean holdUpload;
        RuntimeException networkFailure;
        int uploadStatus, policyObserved;
        String networkTag;
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>();
        method(rows, "auth", Factory.class.getMethod("LIZIZ"));
        method(rows, "api", Factory.class.getMethod("LIZJ"));
        method(rows, "submit", Api.class.getMethod("submit", String.class, int.class, int.class, String.class, boolean.class, boolean.class, String.class, boolean.class, boolean.class));
        method(rows, "query", Api.class.getMethod("query", String.class));
        method(rows, "call_enqueue", Call.class.getMethod("enqueue", HttpCallback.class));
        method(rows, "call_cancel", Call.class.getMethod("cancel"));
        method(rows, "upload_policy", Factory.class.getMethod("network", UploadConfig.class, String.class));
        method(rows, "upload_tag", Uploader.class.getMethod("LIZLLL", String.class));
        method(rows, "upload_file", Uploader.class.getMethod("LJFF", String.class));
        method(rows, "upload_listener", Uploader.class.getMethod("LJ", UploadListener.class));
        method(rows, "upload_start", Uploader.class.getMethod("LJII"));
        method(rows, "upload_close", Uploader.class.getMethod("LIZ"));
        type(rows, "step", Step.class); type(rows, "auth_model", AuthConfig.class);
        type(rows, "upload_config", UploadConfig.class); type(rows, "upload_kind", Kind.class);
        type(rows, "listener", UploadListener.class); type(rows, "http_callback", HttpCallback.class);
        type(rows, "http_response", Response.class); type(rows, "body", Body.class);
        type(rows, "result", Query.class); type(rows, "utterance", Utterance.class); type(rows, "upload_info", UploadInfo.class);
        return new HostScanReport("native-caption-fixture", rows, false);
    }
    private static void method(List<RuleScanResult> rows, String name, Method method) {
        rows.add(new RuleScanResult("tiktok_caption_asr_" + name, "interface", List.of(new HostCandidate("base.apk", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }
    private static void type(List<RuleScanResult> rows, String name, Class<?> type) {
        rows.add(new RuleScanResult("tiktok_caption_asr_" + name, "interface", List.of(new HostCandidate("base.apk", "classes.dex", HostTargets.descriptor(type), "", ""))));
    }
}
