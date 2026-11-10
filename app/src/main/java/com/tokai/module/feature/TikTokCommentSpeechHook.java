package com.tokai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.ArrayList;
import java.util.List;

/** Converts a deliberate #text native comment send through TikTok's checked publisher. */
final class TikTokCommentSpeechHook implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TikTokCommentModels models;
    private final Method submit;
    private final Consumer<String> log;
    private final ThreadLocal<Boolean> reentry = new ThreadLocal<>();
    private XposedInterface.HookHandle handle;
    private LocalOptions options;
    private Context context;
    private Predicate<Object> automatic = ignored -> false;
    private SpeechSynthesis pending;
    private long generation;
    private boolean closed;
    private final List<HashVoiceGesture> gestures = new ArrayList<>();

    private TikTokCommentSpeechHook(TikTokCommentModels models, Method submit, LocalOptions options,
                                     Consumer<String> log) {
        this.models = models;
        this.submit = submit;
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    static TikTokCommentSpeechHook install(XposedInterface framework, HostScanReport report,
                                            ClassLoader loader, LocalOptions options,
                                            Consumer<String> log) throws Exception {
        TikTokCommentSpeechHook hook = new TikTokCommentSpeechHook(new TikTokCommentModels(report, loader),
                HostTargets.method(report, "tiktok_comment_publish_checked", loader), options, log);
        try {
            hook.handle = framework.hook(hook.submit).setId("TokAi.CommentSpeech.TikTok")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(hook::intercept);
            if (hook.handle == null) throw new IllegalStateException("Empty TikTok comment speech hook");
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object original = chain.getArg(0);
        LocalOptions current;
        Context currentContext;
        synchronized (this) {
            current = options;
            currentContext = context;
            if (closed || Boolean.TRUE.equals(reentry.get()) || currentContext == null
                    || (!current.enabled("comment_tts") && gestures.isEmpty())
                    || original == null || automatic.test(original)) return chain.proceed();
        }
        String text = models.text(original);
        HashVoiceGesture gesture = null;
        String[] identity = commentKey(original, chain.getThisObject());
        synchronized (this) {
            for (HashVoiceGesture candidate : gestures) {
                if (candidate.matches(text, identity[0], identity[1], identity[2])) { gesture = candidate; break; }
            }
        }
        if (gesture != null) {
            gesture.restoreAfterNativeClick();
            if (!current.enabled(HashLongPressVoiceHook.OPTION) || !gesture.claim()) return null;
        } else if (!current.enabled("comment_tts")) return chain.proceed();
        if (!text.startsWith("#") || text.substring(1).trim().isEmpty() || !models.textOnly(original)) {
            if (gesture == null) return chain.proceed();
            gesture.cancel();
            toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m093, "请先清除图片、音频等附件；草稿已保留"));
            return null;
        }
        Object snapshot = models.copy(original);
        Object receiver = chain.getThisObject();
        Object[] arguments = chain.getArgs().toArray();
        synchronized (this) {
            if (pending != null) { toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m061, "语音正在生成，请稍候")); return null; }
            long epoch = generation;
            HashVoiceGesture deliberate = gesture;
            toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m062, "正在生成评论语音"));
            pending = SpeechSynthesis.synthesize(currentContext, text.substring(1).trim(), current, (audio, failure) -> {
                synchronized (TikTokCommentSpeechHook.this) {
                    if (closed || epoch != generation || !options.enabled(deliberate == null
                            ? "comment_tts" : HashLongPressVoiceHook.OPTION)) {
                        if (audio != null) audio.file.delete();
                        return;
                    }
                    pending = null;
                }
                if (deliberate != null && !deliberate.canSend()) {
                    if (audio != null) audio.file.delete();
                    deliberate.cancel();
                    toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m063, "评论或草稿已变化，已取消语音发送"));
                    return;
                }
                if (audio == null) {
                    if (deliberate != null) deliberate.cancel();
                    toast(failure); safeLog("TikTok comment speech generation failed"); return;
                }
                try {
                    arguments[0] = models.withAudio(snapshot, audio.file, audio.durationMillis, true);
                    reentry.set(Boolean.TRUE);
                    submit.invoke(receiver, arguments);
                    safeLog("TikTok synthesized comment passed to native checked publisher; remote result unconfirmed");
                } catch (ReflectiveOperationException | RuntimeException error) {
                    if (deliberate != null) deliberate.cancel();
                    safeLog("TikTok native speech submission failed: " + error.getClass().getSimpleName());
                    toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m064, "评论语音提交失败，请重试"));
                } finally {
                    reentry.remove();
                    main.postDelayed(audio.file::delete, 600_000);
                }
            });
        }
        return null;
    }

    synchronized void setContext(Context context) { this.context = context == null ? null : context.getApplicationContext(); }
    synchronized void setAutomaticPublishGuard(Predicate<Object> guard) { automatic = guard == null ? ignored -> false : guard; }
    synchronized void setOptions(LocalOptions options) {
        boolean changed = !this.options.samePrefix(options, "tts_")
                || !options.enabled("comment_tts") && !options.enabled(HashLongPressVoiceHook.OPTION);
        this.options = options;
        if (changed) { generation++; if (pending != null) { pending.close(); pending = null; } }
        if (!options.enabled(HashLongPressVoiceHook.OPTION)) for (HashVoiceGesture gesture : gestures) gesture.cancel();
    }
    synchronized boolean armLongPress(HashVoiceGesture gesture) {
        if (closed || pending != null || !options.enabled(HashLongPressVoiceHook.OPTION) || !gesture.canSend()) return false;
        for (int i = 0; i < gestures.size(); i++) {
            HashVoiceGesture previous = gestures.get(i);
            if (!previous.matches(gesture.draft, gesture.group, gesture.service, gesture.reply)) continue;
            if (!previous.canRetry()) return false;
            gestures.set(i, gesture);
            return true;
        }
        if (gestures.size() >= 128) return false;
        gestures.add(gesture);
        return true;
    }
    private static String[] commentKey(Object input, Object receiver) {
        Object work = HostTargets.read(input, "LJIILLIIL");
        if (work == null) {
            Object source = HostTargets.invoke(receiver, "Y23");
            work = HostTargets.read(HostTargets.read(source, "commonModel"), "aweme");
        }
        Object aid = HostTargets.invoke(work, "getAid");
        Object reply = HostTargets.read(input, "LJIIIIZZ");
        Object cid = reply == null ? "" : HostTargets.invoke(reply, "getCid");
        return new String[]{aid instanceof String ? (String) aid : "", "", cid instanceof String ? (String) cid : ""};
    }
    private void toast(String message) {
        Context current = context;
        if (current != null && message != null) main.post(() -> Toast.makeText(current, message, Toast.LENGTH_SHORT).show());
    }
    private void safeLog(String message) { try { log.accept(message); } catch (RuntimeException ignored) { } }
    @Override public synchronized void close() {
        closed = true;
        generation++;
        for (HashVoiceGesture gesture : gestures) gesture.cancel();
        gestures.clear();
        if (pending != null) { pending.close(); pending = null; }
        if (handle != null) { try { handle.unhook(); } catch (Throwable ignored) { } handle = null; }
    }
}
