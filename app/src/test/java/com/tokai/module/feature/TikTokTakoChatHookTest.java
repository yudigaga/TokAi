package com.tokai.module.feature;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokTakoChatHookTest {
    public interface Continuation { }
    public interface Observer {
        void onNext(Object chunk);
        void onFailed(Throwable failure);
        void onComplete();
    }
    public interface Backend { void LIZ(Observer observer); }
    public static final class Flow {
        public Backend LL;
        public Flow(Backend backend) { LL = backend; }
        public void LIZLLL(Observer observer) { LL.LIZ(observer); }
    }
    public static final class Request {
        public int LIZ = 1;
        public String LIZLLL = "question-uuid";
        public String LJ = "native-conversation";
        public int LJI = 1;
        public String LJII = "{\"text\":\"native input text\"}";
        public long LJIILJJIL = 1;
    }
    public static final class Operation {
        public int LIZIZ = 1;
        public Message LIZJ = new Message();
        public State LJIILLIIL;
        public boolean LJJIIJZLJL;
    }
    public static final class Requests {
        static int nativeRequests;
        public static Object fetch(Request request, Operation operation, Continuation continuation) {
            nativeRequests++;
            return new Flow(observer -> observer.onComplete());
        }
    }
    public static final class Sender {
        public int senderType;
        public long botId;
        public boolean isSelf;
        public Sender(int type, long user, long bot, boolean self, String icon) {
            senderType = type; botId = bot; isSelf = self;
        }
    }
    public static final class Mix {
        public String text = "";
        public int status;
        public int cardType = -1;
        public List<Object> images = Collections.emptyList();
        public List<Object> videos = Collections.emptyList();
        public Object mediaSendCard;
    }
    public static final class Message {
        public String uuid = "question-uuid";
        public String msgId = "";
        public String replyUuid = "";
        public String replyMsgId = "";
        public String conversationId = "native-conversation";
        public String subConversationId;
        public String sessionId = "native-session";
        public String subSessionId = "";
        public String msgContent = "";
        public int msgType = 1;
        public int serverStatus;
        public int LIZIZ;
        public long botId = 1;
        public long actionId;
        public long LIZLLL;
        public Sender sender = new Sender(1, 0, 0, true, "");
        public Mix mix = new Mix();
        public Mix LJIIIZ() { return mix; }
        public void LJIIJ(Mix value) { mix = value; }
        public Message LJ() {
            Message copy = new Message();
            copy.uuid = uuid; copy.msgId = msgId; copy.conversationId = conversationId;
            copy.sender = sender; copy.mix = mix; copy.sessionId = sessionId;
            copy.subConversationId = subConversationId; copy.msgType = msgType;
            return copy;
        }
    }
    public static final class Chunk {
        public final int event;
        public final Message messages;
        public final int chunkIndex;
        public Chunk(int event, Message message, Object patch, Object sug, Object entity, int chunk,
                long time, Object process, Object questionnaire, Object conversation, int answer,
                boolean upgrade, boolean degrade, Object global, Object card, Integer rank) {
            this.event = event; messages = message; chunkIndex = chunk;
        }
    }
    public static final class Conversation { public String LIZIZ = "native-conversation"; }
    public static final class Operator {
        static int nativeDispatches;
        public Conversation LJII = new Conversation();
        public void dispatch(Operation operation) { nativeDispatches++; }
    }
    public static final class State { public Operator LJIILL = new Operator(); }
    public static final class NativeVm {
        public State LLLIIIL = new State();
        public void onCleared() { }
    }
    public static final class NativePage {
        public NativeVm vm = new NativeVm();
        public View root;
        public NativeVm QX() { return vm; }
        public View getView() { return root; }
        public void onViewCreated(View view, Bundle state) { root = view; }
        public void onResume() { }
        public void onStop() { }
        public void onDestroyView() { root = null; }
    }
    public static final class User {
        public String uid = "current-account";
        public String getUid() { return uid; }
    }
    public static final class AccountService {
        public User user = new User();
        public User getCurUser() { return user; }
    }
    public static final class Accounts {
        static final AccountService SERVICE = new AccountService();
        public static AccountService service() { return SERVICE; }
    }
    private static final class Results implements Observer {
        final List<Integer> events = new ArrayList<>();
        final List<Message> messages = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completions;
        @Override public void onNext(Object value) {
            Chunk chunk = (Chunk) value;
            events.add(chunk.event);
            if (chunk.messages != null) messages.add(chunk.messages);
        }
        @Override public void onFailed(Throwable failure) { errors.add(failure); }
        @Override public void onComplete() { completions++; }
        void clear() { events.clear(); messages.clear(); errors.clear(); completions = 0; }
    }

    @Test public void nativeTextUsesModuleTransportAndExistingNativeStreamObserver() throws Throwable {
        try (Fixture f = new Fixture()) {
            Flow flow = f.send();
            assertEquals("Configured module AI must suppress the actual native request", 0, Requests.nativeRequests);
            flow.LIZLLL(f.results);
            f.worker.runAll(); f.main.runAll();
            assertEquals(Collections.singletonList("native input text"), f.prompts);
            assertEquals(Arrays.asList(1, 3, 6), f.results.events);
            Message answer = f.results.messages.stream().filter(message -> !message.sender.isSelf).findFirst().orElse(null);
            assertNotNull("A native assistant model must reach the native message observer", answer);
            assertEquals("module answer", answer.LJIIIZ().text);
            assertEquals("The native text-answer renderer requires assistant message kind 2", 2, answer.msgType);
            assertEquals("The native text-answer renderer requires card kind 0", 0, answer.LJIIIZ().cardType);
            assertEquals(2, answer.LJIIIZ().status);
            assertEquals(2, answer.sender.senderType);
            assertEquals(1, answer.sender.botId);
            assertEquals("question-uuid", answer.replyUuid);
            assertEquals("native-conversation", answer.conversationId);
            assertEquals("No remote TikTok message identifier may be invented", "", answer.msgId);
            assertEquals(1, f.results.completions);
            assertTrue(f.results.errors.isEmpty());
        }
    }

    @Test public void invalidSelectedModelFailsNativeObserverWithoutServerFallback() throws Throwable {
        try (Fixture f = new Fixture()) {
            AiModelConfig invalid = AiModelConfig.fromJson(new JSONObject().put("id", "invalid").put("name", "invalid"));
            f.models.set(new AiModelSettings(Collections.singletonList(invalid), invalid.id));
            f.send().LIZLLL(f.results);
            f.worker.runAll(); f.main.runAll();
            assertEquals(0, Requests.nativeRequests);
            assertEquals(1, f.results.errors.size());
            assertEquals(0, f.results.completions);
            assertTrue(f.prompts.isEmpty());
        }
    }

    @Test public void noSelectedGlobalModelLeavesTheExistingNativeRequestUntouched() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.models.set(new AiModelSettings(Collections.emptyList(), ""));
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(1, Requests.nativeRequests);
            assertEquals(1, f.results.completions);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.results.events.isEmpty());
        }
    }

    @Test public void thirdPartyBotKeepsItsNativeBackendEvenWhenAModuleModelIsSelected() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.request.LJIILJJIL = 37;
            f.operation.LIZJ.botId = 37;
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(1, Requests.nativeRequests);
            assertEquals(1, f.results.completions);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.results.errors.isEmpty());
        }
    }

    @Test public void nativePageStopCancelsQueuedTransportAndDropsItsPendingReply() throws Throwable {
        assertLifecycleInvalidatesPendingReply("onStop");
    }

    @Test public void nativeStopDispatchCancelsModuleReplyAndCompletesOnlyOnceWithoutRemoteStop() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.stop();
            assertFalse(f.session.isBusy());
            assertEquals(0, Operator.nativeDispatches);
            assertEquals(1, f.results.completions);
            assertTrue(f.results.errors.isEmpty());
            assertEquals("HTTP disconnect must stay off the native stop caller", 0, f.cancellations);
            f.drain();
            assertEquals(1, f.cancellations);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.remembered.isEmpty());
            assertEquals(Collections.singletonList(1), f.results.events);
            assertEquals(1, f.results.completions);
            f.results.clear();
            f.operation.LIZJ.mix.text = "question after stop";
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(Collections.singletonList("question after stop"), f.prompts);
            assertEquals(Collections.singletonList("question after stop"), f.remembered);
            assertEquals(Arrays.asList(1, 3, 6), f.results.events);
            assertEquals(1, f.results.completions);
        }
    }

    @Test public void stoppingAfterTransportCompletesFencesTheQueuedAnswerBeforeModelMemory() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.worker.runAll();
            f.stop();
            f.drain();
            assertEquals(0, Operator.nativeDispatches);
            assertEquals(1, f.cancellations);
            assertTrue(f.remembered.isEmpty());
            assertEquals(Collections.singletonList(1), f.results.events);
            assertEquals(1, f.results.completions);
            assertTrue(f.results.errors.isEmpty());
        }
    }

    @Test public void otherOperationsAndOtherNativeConversationsKeepTheirOriginalDispatch() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            Operation anotherOperation = new Operation();
            anotherOperation.LJIILLIIL = f.operation.LJIILLIIL;
            f.dispatch(f.page.vm.LLLIIIL.LJIILL, anotherOperation);
            Operation anotherConversation = new Operation();
            anotherConversation.LIZIZ = 12;
            anotherConversation.LJIILLIIL = new State();
            f.dispatch(anotherConversation.LJIILLIIL.LJIILL, anotherConversation);
            f.dispatch(new Operator(), f.stopOperation());
            assertEquals(3, Operator.nativeDispatches);
            assertTrue(f.session.isBusy());
            f.drain();
            assertEquals(Arrays.asList(1, 3, 6), f.results.events);
            assertEquals(1, f.results.completions);
            assertEquals(0, f.cancellations);
        }
    }

    @Test public void stoppingWithoutAModuleRequestKeepsTheNativeDispatch() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.stop();
            assertEquals(1, Operator.nativeDispatches);
            assertTrue(f.results.events.isEmpty());
            assertEquals(0, f.results.completions);
        }
    }

    @Test public void detachedPageCannotStartAModuleOrNativeBackend() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.activity.setContentView(new FrameLayout(f.activity));
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(0, Requests.nativeRequests);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.remembered.isEmpty());
            assertTrue(f.results.events.isEmpty());
            assertEquals(1, f.results.errors.size());
        }
    }

    @Test public void recreatedViewDoesNotReceiveOrRememberThePreviousViewsQueuedAnswer() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.worker.runAll();
            FrameLayout replacement = new FrameLayout(f.activity);
            f.activity.setContentView(replacement);
            f.harness.invoke(NativePage.class.getMethod("onViewCreated", View.class, Bundle.class), f.page, replacement, null);
            f.harness.invoke(NativePage.class.getMethod("onResume"), f.page);
            f.drain();
            assertFalse(f.session.isBusy());
            assertEquals(1, f.cancellations);
            assertTrue(f.remembered.isEmpty());
            assertEquals(Collections.singletonList(1), f.results.events);
            assertEquals(0, f.results.completions);
            assertTrue(f.results.errors.isEmpty());
        }
    }

    @Test public void replacingThePagesViewModelRejectsTheOldAnswerBeforeMemory() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.worker.runAll();
            f.page.vm = new NativeVm();
            f.drain();
            assertRejectedLateReply(f);
        }
    }

    @Test public void firstQuestionRequiresTheRealNativeConversationBeforeModuleTransport() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.request.LJ = "";
            f.page.vm.LLLIIIL.LJIILL.LJII.LIZIZ = "";
            f.operation.LIZJ.conversationId = "";
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(0, Requests.nativeRequests);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.remembered.isEmpty());
            assertEquals(1, f.results.errors.size());
            assertEquals("", f.request.LJ);
            assertEquals("", f.page.vm.LLLIIIL.LJIILL.LJII.LIZIZ);
            f.results.clear();
            f.request.LJ = "server-prepared-native-id";
            f.page.vm.LLLIIIL.LJIILL.LJII.LIZIZ = f.request.LJ;
            f.operation.LIZJ.conversationId = f.request.LJ;
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(Arrays.asList(1, 3, 6), f.results.events);
            assertEquals("server-prepared-native-id", f.results.messages.get(1).conversationId);
            assertEquals(1, f.results.completions);
        }
    }

    @Test public void switchingModelBeforeNativeSubscriptionRejectsTheCapturedOldModelBeforeTransport() throws Throwable {
        try (Fixture f = new Fixture()) {
            Flow flow = f.send();
            f.models.set(model("new-global", "new-model"));
            flow.LIZLLL(f.results);
            f.drain();
            assertEquals(0, Requests.nativeRequests);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.remembered.isEmpty());
            assertTrue(f.results.events.isEmpty());
            assertEquals(1, f.results.errors.size());
        }
    }

    @Test public void nativePageDestructionCancelsQueuedTransportAndDropsItsPendingReply() throws Throwable {
        assertLifecycleInvalidatesPendingReply("onDestroyView");
    }

    private static void assertLifecycleInvalidatesPendingReply(String lifecycle) throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            assertEquals(Collections.singletonList(1), f.results.events);
            f.harness.invoke(NativePage.class.getMethod(lifecycle), f.page);
            assertFalse("The request must be fenced immediately when the page leaves", f.session.isBusy());
            assertEquals("Network cleanup is queued, never performed inline with page lifecycle", 0, f.cancellations);
            f.drain();
            assertEquals(1, f.cancellations);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.remembered.isEmpty());
            assertEquals(Collections.singletonList(1), f.results.events);
            assertTrue(f.results.errors.isEmpty());
            assertEquals(0, f.results.completions);
        }
    }

    @Test public void clearingNativeViewModelCancelsItsTransportWithoutWritingToTheOldObserver() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.harness.invoke(NativeVm.class.getMethod("onCleared"), f.page.vm);
            f.drain();
            assertEquals(1, f.cancellations);
            assertFalse(f.session.isBusy());
            assertTrue(f.remembered.isEmpty());
            assertEquals(Collections.singletonList(1), f.results.events);
            assertEquals(0, f.results.completions);
            assertTrue(f.results.errors.isEmpty());
        }
    }

    @Test public void accountChangeAfterTransportCompletionRejectsTheReplyBeforeRememberingIt() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.worker.runAll();
            assertEquals(Collections.singletonList("native input text"), f.prompts);
            Accounts.SERVICE.user.uid = "new-account";
            f.drain();
            assertRejectedLateReply(f);
        }
    }

    @Test public void conversationChangeAfterTransportCompletionRejectsTheReplyBeforeRememberingIt() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.worker.runAll();
            f.page.vm.LLLIIIL.LJIILL.LJII.LIZIZ = "another-native-conversation";
            f.drain();
            assertRejectedLateReply(f);
        }
    }

    @Test public void replacingNativeStateAfterTransportCompletionRejectsTheOldReplyBeforeRememberingIt() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.worker.runAll();
            f.page.vm.LLLIIIL = new State();
            f.drain();
            assertRejectedLateReply(f);
        }
    }

    @Test public void changedSelectedModelIsRecheckedBeforeDeliveringOrRememberingACompletedReply() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.worker.runAll();
            f.models.set(model("second-global", "another-model"));
            f.drain();
            assertRejectedLateReply(f);
        }
    }

    private static void assertRejectedLateReply(Fixture f) {
        assertEquals(0, Requests.nativeRequests);
        assertEquals(Collections.singletonList(1), f.results.events);
        assertTrue(f.remembered.isEmpty());
        assertTrue(f.results.errors.isEmpty());
        assertEquals(0, f.results.completions);
        assertFalse(f.session.isBusy());
        assertFalse("An invalidated native context must not be reused", f.session.isBound());
        assertEquals(1, f.cancellations);
    }

    @Test public void refreshingTheSelectedModelCancelsCurrentWorkAndAllowsTheNextQuestion() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.send().LIZLLL(f.results);
            f.models.set(model("second-global", "another-model"));
            f.hook.refreshSettings();
            assertFalse(f.session.isBusy());
            assertEquals(1, f.results.errors.size());
            assertTrue(f.results.errors.get(0) instanceof java.util.concurrent.CancellationException);
            f.drain();
            f.results.clear();
            f.operation.LIZJ.mix.text = "next model question";
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(Collections.singletonList("next model question"), f.prompts);
            assertEquals(Collections.singletonList("next model question"), f.remembered);
            assertEquals(Arrays.asList(1, 3, 6), f.results.events);
            assertEquals(1, f.results.completions);
            assertEquals(0, Requests.nativeRequests);
        }
    }

    @Test public void selectedModuleModelRejectsMediaThroughTheNativeErrorWithoutServerFallback() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.operation.LIZJ.mix.images = Collections.singletonList(new Object());
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(0, Requests.nativeRequests);
            assertEquals(1, f.results.errors.size());
            assertTrue(f.results.errors.get(0) instanceof IllegalArgumentException);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.remembered.isEmpty());
            assertTrue(f.results.events.isEmpty());
            assertEquals(0, f.results.completions);
        }
    }

    @Test public void duplicateNativeSubscriptionCannotSendTheQuestionTwice() throws Throwable {
        try (Fixture f = new Fixture()) {
            Flow flow = f.send();
            Results duplicate = new Results();
            flow.LIZLLL(f.results);
            flow.LIZLLL(duplicate);
            f.drain();
            assertEquals(Collections.singletonList("native input text"), f.prompts);
            assertEquals(Collections.singletonList("native input text"), f.remembered);
            assertEquals(1, f.results.completions);
            assertEquals(1, duplicate.errors.size());
            assertEquals(0, duplicate.completions);
            assertTrue(duplicate.events.isEmpty());
            assertEquals(0, Requests.nativeRequests);
        }
    }

    @Test public void unavailableCurrentAccountRejectsNativeInputWithoutTouchingEitherBackend() throws Throwable {
        try (Fixture f = new Fixture()) {
            Accounts.SERVICE.user.uid = "0";
            f.send().LIZLLL(f.results);
            f.drain();
            assertEquals(0, Requests.nativeRequests);
            assertTrue(f.prompts.isEmpty());
            assertTrue(f.results.events.isEmpty());
            assertEquals(1, f.results.errors.size());
            assertEquals(0, f.results.completions);
        }
    }

    @Test public void aLaterHookRegistrationFailureRollsBackEarlierNativeHooksAndClosesSession() throws Throwable {
        Harness harness = new Harness();
        LinkageError failure = new NoClassDefFoundError("fixture late registration failure");
        harness.failAt = 3;
        harness.failure = failure;
        Queue worker = new Queue(), cancellation = new Queue(), main = new Queue();
        TikTokTakoAiSession session = new TikTokTakoAiSession(worker, cancellation, main,
                () -> new TikTokTakoAiSession.Transport() {
                    @Override public String reply(String conversation, String prompt, AiModelSettings settings) {
                        fail("Installation failure must not start an AI request"); return "";
                    }
                    @Override public void cancel() { }
                }, ignored -> { });
        AiModelSettings configured = model("global", "test-model");
        LinkageError observed = assertThrows(LinkageError.class, () ->
                TikTokTakoChatHook.install(harness.framework, report(), getClass().getClassLoader(),
                        () -> configured, ignored -> { }, session));
        assertSame(failure, observed);
        assertEquals(2, harness.unhooks);
        assertTrue(harness.hooks.isEmpty());
        assertThrows(IllegalStateException.class, () -> session.bind("account", "conversation", model("global", "test-model")));
    }

    private static AiModelSettings model(String id, String modelName) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", id)
                .put("apiEndpoint", "https://provider.invalid/v1/chat/completions")
                .put("apiKey", "test-only").put("modelName", modelName));
        return new AiModelSettings(Collections.singletonList(config), config.id);
    }

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root = new FrameLayout(activity);
        final NativePage page = new NativePage();
        final Request request = new Request();
        final Operation operation = new Operation();
        final Queue worker = new Queue(), main = new Queue(), cancellation = new Queue();
        final Results results = new Results();
        final List<String> prompts = new ArrayList<>();
        final List<String> remembered = new ArrayList<>();
        int cancellations;
        final AtomicReference<AiModelSettings> models = new AtomicReference<>();
        final Harness harness = new Harness();
        final TikTokTakoChatHook hook;
        final TikTokTakoAiSession session;
        Fixture() throws Throwable {
            Requests.nativeRequests = 0;
            Operator.nativeDispatches = 0;
            Accounts.SERVICE.user.uid = "current-account";
            AiModelConfig model = AiModelConfig.fromJson(new JSONObject().put("id", "global").put("name", "global")
                    .put("apiEndpoint", "https://provider.invalid/v1/chat/completions")
                    .put("apiKey", "test-only").put("modelName", "test-model"));
            models.set(new AiModelSettings(Collections.singletonList(model), model.id));
            operation.LJIILLIIL = page.vm.LLLIIIL;
            operation.LIZJ.mix.text = "native input text";
            activity.setContentView(root);
            session = new TikTokTakoAiSession(worker, cancellation, main,
                    () -> new TikTokTakoAiSession.Transport() {
                        @Override public String reply(String conversation, String prompt, AiModelSettings settings) {
                            prompts.add(prompt); return "module answer";
                        }
                        @Override public void remember(String conversation, String prompt, AiModelSettings settings, String answer) {
                            remembered.add(prompt);
                        }
                        @Override public void cancel() { cancellations++; }
                    }, ignored -> { });
            hook = TikTokTakoChatHook.install(harness.framework, report(), getClass().getClassLoader(),
                    models::get, ignored -> { }, session);
            harness.invoke(NativePage.class.getMethod("onViewCreated", View.class, Bundle.class), page, root, null);
            harness.invoke(NativePage.class.getMethod("onResume"), page);
        }
        Flow send() throws Throwable {
            return (Flow) harness.invoke(Requests.class.getMethod("fetch", Request.class, Operation.class, Continuation.class),
                    null, request, operation, null);
        }
        Operation stopOperation() {
            Operation stop = new Operation();
            stop.LIZIZ = 12;
            stop.LJIILLIIL = operation.LJIILLIIL;
            return stop;
        }
        void stop() throws Throwable { dispatch(page.vm.LLLIIIL.LJIILL, stopOperation()); }
        void dispatch(Operator operator, Operation operation) throws Throwable {
            harness.invoke(Operator.class.getMethod("dispatch", Operation.class), operator, operation);
        }
        void drain() { cancellation.runAll(); worker.runAll(); main.runAll(); cancellation.runAll(); }
        @Override public void close() { hook.close(); cancellation.runAll(); activity.finish(); }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "tiktok_tako_request", Requests.class.getMethod("fetch", Request.class, Operation.class, Continuation.class));
        add(rules, "tiktok_tako_page_vm", NativePage.class.getMethod("QX"));
        add(rules, "tiktok_tako_page_created", NativePage.class.getMethod("onViewCreated", View.class, Bundle.class));
        add(rules, "tiktok_tako_page_resume", NativePage.class.getMethod("onResume"));
        add(rules, "tiktok_tako_page_stop", NativePage.class.getMethod("onStop"));
        add(rules, "tiktok_tako_page_destroy", NativePage.class.getMethod("onDestroyView"));
        add(rules, "tiktok_tako_vm_clear", NativeVm.class.getMethod("onCleared"));
        add(rules, "tiktok_tako_dispatch_stream", Operator.class.getMethod("dispatch", Operation.class));
        add(rules, "media_account_service", Accounts.class.getMethod("service"));
        add(rules, "media_current_user", AccountService.class.getMethod("getCurUser"));
        for (Object[] entry : new Object[][]{{"tiktok_tako_flow", Flow.class}, {"tiktok_tako_chunk", Chunk.class},
                {"tiktok_tako_message", Message.class}, {"tiktok_tako_mix_feed", Mix.class}, {"tiktok_tako_sender", Sender.class}}) {
            rules.add(new RuleScanResult((String) entry[0], "fixture", Collections.singletonList(new HostCandidate(
                    "fixture.apk", "classes.dex", HostTargets.descriptor((Class<?>) entry[1]), "", ""))));
        }
        return new HostScanReport("fixture", rules, false);
    }
    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }
    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        int registrations, unhooks, failAt = -1;
        Throwable failure;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    if (++registrations == failAt) throw failure;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, action, values) -> {
                                if (!action.getName().equals("intercept")) return builder;
                                hooks.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                        (handle, call, ignored) -> {
                                            if (call.getName().equals("unhook")) { unhooks++; hooks.remove(target); }
                                            return null;
                                        });
                            });
                });
        Object invoke(Method target, Object owner, Object... args) throws Throwable {
            XposedInterface.Hooker interceptor = hooks.get(target);
            if (interceptor == null) return proceed(target, owner, args);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, values) -> {
                        switch (method.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArg": return args[(Integer) values[0]];
                            case "getArgs": return Arrays.asList(args);
                            case "proceed": return proceed(target, owner, values == null ? args : (Object[]) values[0]);
                            default: return null;
                        }
                    });
            return interceptor.intercept(chain);
        }
        Object proceed(Method method, Object owner, Object[] args) throws Throwable {
            try { return method.invoke(owner, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
