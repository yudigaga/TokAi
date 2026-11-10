package com.tokai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.SubmissionLedger;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Explicit native send-button actions only; automatic replies never enter this adapter. */
final class TikTokDirectMessageSpeech implements AutoCloseable {
    private final BehaviorHookGroup hooks;
    private final TikTokImBridge im;
    private final Method editorGetter, conversation, single;
    private final ThreadLocal<Boolean> userClick = new ThreadLocal<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong epoch = new AtomicLong();
    private volatile LocalOptions options;
    private volatile Context context;
    private volatile boolean closed;
    private volatile NativeVoiceSendLifecycle voiceSends;
    private volatile DirectMessageSpeechHook.AudioFile replacement;
    private String replacementSource = "";
    private final List<WeakReference<Object>> inputModels = new ArrayList<>();
    private final Map<View, Boolean> longClicks = new WeakHashMap<>();
    private final ThreadLocal<Boolean> changingLongClickable = new ThreadLocal<>();

    TikTokDirectMessageSpeech(XposedInterface framework, HostScanReport report, ClassLoader loader,
                              LocalOptions options, Consumer<String> log) throws Exception {
        this.options = options;
        hooks = new BehaviorHookGroup("dm_speech", log);
        im = new TikTokImBridge(report, loader);
        editorGetter = HostTargets.method(report, "auto_message_editor_getter", loader);
        conversation = HostTargets.method(report, "auto_message_session_id", loader);
        single = HostTargets.method(report, "tiktok_message_single_chat", loader);
        try {
            hooks.add(framework, HostTargets.method(report, "tiktok_message_input_factory", loader), chain -> {
                Object model = chain.proceed();
                if (model != null) synchronized (inputModels) {
                    inputModels.removeIf(value -> value.get() == null || value.get() == model);
                    inputModels.add(new WeakReference<>(model));
                }
                return model;
            });
            hooks.add(framework, HostTargets.method(report, "tiktok_dm_send_bind", loader), chain -> {
                Object result = chain.proceed();
                if (chain.getArg(0) instanceof View) {
                    View sendView = (View) chain.getArg(0);
                    if (!longClicks.containsKey(sendView)) longClicks.put(sendView, sendView.isLongClickable());
                    updateLongClickable(sendView);
                }
                return result;
            });
            for (Method method : new Method[]{View.class.getDeclaredMethod("performLongClick"),
                    View.class.getDeclaredMethod("performLongClick", float.class, float.class)}) {
                hooks.add(framework, method, chain -> {
                    View sendView = (View) chain.getThisObject();
                    if (closed || !longClicks.containsKey(sendView) || !options.enabled(HashLongPressVoiceHook.OPTION)) return chain.proceed();
                    Object model = uniqueInputModel(sendView);
                    if (model == null) return chain.proceed();
                    EditText editor = editor(model);
                    String draft = editor == null ? "" : editor.getText().toString();
                    if (!DirectMessageSpeechHook.canStartLongPress(options, draft)) return chain.proceed();
                    String id = (String) conversation.invoke(HostTargets.read(model, "LLJJJJLIIL"));
                    WeakReference<Object> weakModel = new WeakReference<>(model);
                    WeakReference<View> weakSend = new WeakReference<>(sendView);
                    HashVoiceGesture gesture = new HashVoiceGesture(draft, id, "dm", "",
                            () -> hashCurrent(weakModel.get(), weakSend.get(), draft, id),
                            () -> weakSend.get() != null && weakSend.get().hasWindowFocus(), () -> { });
                    try { start(model, gesture); }
                    catch (Exception failure) { gesture.cancel(); toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m009, "私聊语音入口异常，草稿已保留")); }
                    return true;
                });
            }
            hooks.add(framework, View.class.getDeclaredMethod("setLongClickable", boolean.class), chain -> {
                View sendView = (View) chain.getThisObject();
                if (!longClicks.containsKey(sendView) || Boolean.TRUE.equals(changingLongClickable.get())) return chain.proceed();
                longClicks.put(sendView, (Boolean) chain.getArg(0));
                if (!closed && options.enabled(HashLongPressVoiceHook.OPTION)) {
                    Object[] args = chain.getArgs().toArray(); args[0] = true; return chain.proceed(args);
                }
                return chain.proceed();
            });
            hooks.add(framework, HostTargets.method(report, "tiktok_dm_send_click", loader), chain -> {
                Object listener = chain.getArg(0);
                Object owner = HostTargets.read(listener, "l0");
                Object view = chain.getArg(1);
                if (owner == null || !"com.ss.android.ugc.aweme.im.chatroom.input.components.sendbtn.SendButtonAssemV2"
                        .equals(owner.getClass().getName()) || !(view instanceof View)
                        || !((View) view).isShown() || !((View) view).hasWindowFocus()) return chain.proceed();
                Boolean before = userClick.get();
                userClick.set(Boolean.TRUE);
                try { return chain.proceed(); }
                finally { if (before == null) userClick.remove(); else userClick.set(before); }
            });
            hooks.add(framework, HostTargets.method(report, "tiktok_dm_user_send", loader), chain -> {
                if (closed || !Boolean.TRUE.equals(userClick.get()) || !options.enabled("dm_tts")) return chain.proceed();
                try { return start(chain.getThisObject()) ? null : chain.proceed(); }
                catch (Exception failure) { toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m009, "私聊语音入口异常，草稿已保留")); return null; }
            });
            hooks.add(framework, HostTargets.method(report, "tiktok_dm_voice_dispatch", loader), chain -> {
                DirectMessageSpeechHook.AudioFile source = replacement;
                if (closed || !options.enabled("dm_voice_replace") || source == null || !source.file.isFile()
                        || !(chain.getArg(1) instanceof String) || !(chain.getArg(5) instanceof String)
                        || ((String) chain.getArg(5)).isEmpty()) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[1] = source.file.getAbsolutePath();
                args[2] = source.durationMillis;
                args[4] = Collections.emptyList();
                return chain.proceed(args);
            });
        } catch (Throwable failure) {
            close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    void setContext(Context value) { context = value == null ? null : value.getApplicationContext(); setOptions(options); }

    synchronized void setOptions(LocalOptions next) {
        if (!options.samePrefix(next, "tts_") || !options.samePrefix(next, "dm_")
                || options.enabled(HashLongPressVoiceHook.OPTION) != next.enabled(HashLongPressVoiceHook.OPTION)) epoch.incrementAndGet();
        options = next;
        main.post(() -> { for (View sendView : new ArrayList<>(longClicks.keySet())) updateLongClickable(sendView); });
        String source = next.enabled("dm_voice_replace") ? next.text("dm_voice_path", "").trim() : "";
        if (source.equals(replacementSource) || context == null) return;
        replacementSource = source;
        DirectMessageSpeechHook.AudioFile previous = replacement;
        replacement = null;
        if (previous != null) main.postDelayed(() -> previous.file.delete(), DirectMessageSpeechHook.AUDIO_RETENTION_MILLIS);
        if (source.isEmpty() || closed) return;
        long revision = epoch.get(); Context owner = context;
        worker.execute(() -> {
            File copied = null;
            try {
                copied = DirectMessageSpeechHook.copySource(owner, source);
                DirectMessageSpeechHook.AudioFile audio = DirectMessageSpeechHook.encode(copied, owner);
                if (closed || epoch.get() != revision) audio.file.delete(); else replacement = audio;
            } catch (Exception failure) { toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m094, "私聊语音文件不可用，原录音已保留")); }
            finally { if (copied != null) copied.delete(); }
        });
    }

    private boolean start(Object vm) throws Exception {
        return start(vm, null);
    }

    private boolean start(Object vm, HashVoiceGesture gesture) throws Exception {
        EditText editor = editor(vm);
        if (editor == null) return false;
        String draft = editor.getText().toString();
        if (gesture == null ? !DirectMessageSpeechHook.canStart(options, true, draft)
                : !DirectMessageSpeechHook.canStartLongPress(options, draft) || !gesture.draft.equals(draft)
                || !gesture.canSend()) return false;
        Object session = HostTargets.read(vm, "LLJJJJLIIL");
        if (session == null || !Boolean.TRUE.equals(single.invoke(session))) return false;
        Object recording = vm.getClass().getMethod("V23").invoke(vm);
        Object state = HostTargets.read(recording, "LJIJI");
        Object activeState = state == null ? null : state.getClass().getMethod("getValue").invoke(state);
        if (activeState instanceof Enum<?>) {
            String name = ((Enum<?>) activeState).name();
            if ("PRESSED".equals(name) || "LOCKED".equals(name) || "RECORD_FINISHED".equals(name)) return false;
        }
        String id = (String) conversation.invoke(session);
        String text = DirectMessageSpeechHook.speechText(draft);
        if (context == null || !editor.isShown() || !editor.hasWindowFocus() || id == null || id.isEmpty()) return true;
        NativeVoiceSendLifecycle lifecycle = voiceLifecycle();
        if (lifecycle.isBusy()) { toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m095, "私聊语音正在生成或等待回执，草稿已保留")); return true; }
        if (gesture != null && !gesture.claim()) return true;
        if (text == null || text.isEmpty()) { toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m011, "语音内容为空，草稿已保留")); return true; }
        long account = im.getUid(), revision = epoch.get();
        if (account <= 0) return true;
        LocalOptions snapshot = options;
        DraftOwner owner = new DraftOwner(vm, editor, id, draft, account, revision);
        NativeVoiceSendLifecycle.Attempt attempt = lifecycle.begin(account, id, revision, draft, (value, outcome, current) -> {
            owner.detach();
            if (!current) return;
            if (outcome == NativeVoiceSendLifecycle.State.SENT && owner.current()) {
                EditText input = owner.input.get();
                if (input != null && uniqueInputModel(input) == owner.model.get()) input.setText("");
            } else if (outcome == NativeVoiceSendLifecycle.State.UNKNOWN) {
                toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m096, "原生语音发送状态未确认，请检查消息；草稿已保留"));
            } else if (outcome == NativeVoiceSendLifecycle.State.FAILED) {
                toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m097, "原生语音发送失败，草稿已保留"));
            } else if (outcome == NativeVoiceSendLifecycle.State.BLOCKED) {
                toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m098, "此草稿刚刚提交或回执尚未确认，草稿已保留"));
            }
        });
        if (attempt == null) return true;
        owner.attach();
        Context preparationContext = context;
        try { worker.execute(() -> {
            SpeechPreparation.Audio audio = null;
            try {
                if (!lifecycle.reserve(attempt)) return;
                audio = SpeechPreparation.prepare(preparationContext, text, snapshot, 120_000,
                        () -> !closed && revision == epoch.get()
                                && options.enabled(gesture == null ? "dm_tts" : HashLongPressVoiceHook.OPTION));
                SpeechPreparation.Audio ready = audio;
                main.post(() -> submit(lifecycle, attempt, owner, ready, gesture));
                audio = null;
            } catch (Exception failure) {
                main.post(() -> { lifecycle.abandon(attempt); if (owner.current()) toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m099, "私聊语音生成失败，草稿已保留")); });
            }
            finally { if (audio != null) audio.delete(); }
        }); } catch (RuntimeException unavailable) { lifecycle.abandon(attempt); toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m100, "私聊语音入口已关闭，草稿已保留")); }
        return true;
    }

    private void submit(NativeVoiceSendLifecycle lifecycle, NativeVoiceSendLifecycle.Attempt attempt,
                        DraftOwner owner, SpeechPreparation.Audio audio, HashVoiceGesture gesture) {
        boolean submitted = false;
        try {
            if (!owner.current() || !options.enabled(gesture == null ? "dm_tts" : HashLongPressVoiceHook.OPTION)
                    || gesture != null && !gesture.canSend() || im.getConversation(attempt.conversation) == null) return;
            TikTokImBridge.Submission prepared = im.prepareAudio(attempt.conversation, audio,
                    success -> main.post(() -> lifecycle.terminal(attempt, success)));
            if (!lifecycle.submitted(attempt, audio::delete)) return;
            submitted = true;
            TikTokImBridge.submit(prepared);
        } catch (Exception failure) {
            if (submitted) lifecycle.uncertain(attempt);
            else if (owner.current()) toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m101, "原生语音发送准备失败，草稿已保留"));
        } finally { if (!submitted) { audio.delete(); lifecycle.abandon(attempt); } }
    }

    private synchronized NativeVoiceSendLifecycle voiceLifecycle() {
        if (voiceSends == null) voiceSends = new NativeVoiceSendLifecycle(new SubmissionLedger(
                context.getSharedPreferences(SubmissionLedger.VOICE_FILE, Context.MODE_PRIVATE), SubmissionLedger.DEFAULT_CAPACITY),
                main, command -> java.util.concurrent.CompletableFuture.runAsync(command), System::currentTimeMillis);
        return voiceSends;
    }

    private final class DraftOwner implements TextWatcher {
        final WeakReference<Object> model;
        final WeakReference<EditText> input;
        final String id, draft;
        final long account, revision;
        boolean changed, attached;
        DraftOwner(Object model, EditText input, String id, String draft, long account, long revision) {
            this.model = new WeakReference<>(model); this.input = new WeakReference<>(input);
            this.id = id; this.draft = draft; this.account = account; this.revision = revision;
        }
        void attach() { EditText value = input.get(); if (value != null) { value.addTextChangedListener(this); attached = true; } }
        void detach() { EditText value = input.get(); if (attached && value != null) value.removeTextChangedListener(this); attached = false; }
        boolean current() {
            EditText value = input.get(); Object vm = model.get();
            if (closed || changed || revision != epoch.get() || value == null || vm == null
                    || !value.isShown() || !value.hasWindowFocus() || !draft.contentEquals(value.getText())) return false;
            try {
                Object session = HostTargets.read(vm, "LLJJJJLIIL");
                return editor(vm) == value && session != null && id.equals(conversation.invoke(session)) && im.getUid() == account;
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
        }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed = true; }
        @Override public void afterTextChanged(Editable s) { }
    }

    private EditText editor(Object model) throws ReflectiveOperationException {
        Object ability = HostTargets.read(model, "LLJJJ");
        Object raw = ability == null ? null : editorGetter.invoke(ability);
        return raw instanceof EditText ? (EditText) raw : null;
    }

    private Object uniqueInputModel(View sendView) {
        if (!sendView.isShown() || !sendView.hasWindowFocus() || sendView.getWindowToken() == null) return null;
        Object selected = null;
        synchronized (inputModels) {
            inputModels.removeIf(value -> value.get() == null);
            for (WeakReference<Object> reference : inputModels) {
                Object model = reference.get();
                try {
                    EditText input = editor(model);
                    Object session = HostTargets.read(model, "LLJJJJLIIL");
                    if (input == null || !input.isShown() || !input.hasWindowFocus()
                            || input.getWindowToken() != sendView.getWindowToken() || session == null
                            || !Boolean.TRUE.equals(single.invoke(session))) continue;
                    if (selected != null) return null;
                    selected = model;
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
        return selected;
    }

    private boolean hashCurrent(Object model, View sendView, String draft, String id) {
        if (closed || model == null || sendView == null || !options.enabled(HashLongPressVoiceHook.OPTION)
                || uniqueInputModel(sendView) != model) return false;
        try { EditText input = editor(model); return input != null && draft.contentEquals(input.getText())
                && id.equals(conversation.invoke(HostTargets.read(model, "LLJJJJLIIL"))); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    private void updateLongClickable(View sendView) {
        Boolean original = longClicks.get(sendView);
        if (original == null) return;
        changingLongClickable.set(Boolean.TRUE);
        try { sendView.setLongClickable(!closed && options.enabled(HashLongPressVoiceHook.OPTION) || original); }
        finally { changingLongClickable.remove(); }
    }

    private void toast(String message) {
        Context owner = context;
        if (!closed && owner != null) main.post(() -> Toast.makeText(owner, message, Toast.LENGTH_LONG).show());
    }

    @Override public void close() {
        closed = true; epoch.incrementAndGet();
        NativeVoiceSendLifecycle lifecycle = voiceSends; if (lifecycle != null) lifecycle.close();
        main.post(() -> { for (View sendView : new ArrayList<>(longClicks.keySet())) updateLongClickable(sendView); longClicks.clear(); });
        hooks.close(); worker.shutdownNow();
        DirectMessageSpeechHook.AudioFile source = replacement;
        replacement = null;
        if (source != null) main.postDelayed(() -> source.file.delete(), DirectMessageSpeechHook.AUDIO_RETENTION_MILLIS);
    }
}
