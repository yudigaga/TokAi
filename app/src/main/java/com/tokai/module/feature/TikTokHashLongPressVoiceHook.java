package com.tokai.module.feature;

import android.content.Context;
import android.view.View;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.Toast;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Verifies the TikTok comment keyboard's send/editor/context ownership before consuming a long press. */
final class TikTokHashLongPressVoiceHook implements AutoCloseable {
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, Binding> bindings = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final NativeCommentSpeechHook comments;
    private final Method keyboardModel;
    private final Method service;
    private final Method snapshot;
    private final Method source;
    private final Consumer<String> log;
    private LocalOptions options;
    private Context context;
    private boolean closed;
    private final ThreadLocal<Boolean> updatingClickable = new ThreadLocal<>();

    private TikTokHashLongPressVoiceHook(NativeCommentSpeechHook comments, Method keyboardModel,
                                         Method service, Method snapshot, Method source,
                                         LocalOptions options, Consumer<String> log) {
        this.comments = comments;
        this.keyboardModel = keyboardModel;
        this.service = service;
        this.snapshot = snapshot;
        this.source = source;
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    static TikTokHashLongPressVoiceHook install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, LocalOptions options,
                                                NativeCommentSpeechHook comments,
                                                Consumer<String> log) throws Exception {
        if (comments == null) throw new IllegalStateException("TikTok native comment speech unavailable");
        Method bind = HostTargets.method(report, "tiktok_comment_keyboard_bind", loader);
        Method model = HostTargets.method(report, "tiktok_comment_keyboard_model", loader);
        Field editor = HostTargets.field(model.getDeclaringClass(), "LLJZ");
        Field send = HostTargets.field(model.getDeclaringClass(), "LLLIIIIL");
        TikTokHashLongPressVoiceHook hook = new TikTokHashLongPressVoiceHook(comments, model,
                HostTargets.method(report, "tiktok_comment_keyboard_service", loader),
                HostTargets.method(report, "tiktok_comment_input_snapshot", loader),
                HostTargets.method(report, "tiktok_comment_keyboard_source", loader), options, log);
        if (!EditText.class.isAssignableFrom(editor.getType()) || !View.class.isAssignableFrom(send.getType())
                || !model.getDeclaringClass().isAssignableFrom(bind.getDeclaringClass())) {
            throw new IllegalStateException("TikTok comment keyboard ownership changed");
        }
        try {
            hook.add(framework, bind, "Bind", chain -> {
                Object result = chain.proceed();
                try {
                    Object owner = chain.getThisObject();
                    Object rawSend = send.get(owner);
                    Object rawEditor = editor.get(owner);
                    Object rawRoot = chain.getArg(0);
                    if (rawSend instanceof View && rawEditor instanceof EditText && rawRoot instanceof View
                            && descendant((View) rawSend, (View) rawRoot) && descendant((EditText) rawEditor, (View) rawRoot)) {
                        hook.bind((View) rawSend, owner, (EditText) rawEditor);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    hook.safeLog("TikTok long-press binding unavailable: " + failure.getClass().getSimpleName());
                }
                return result;
            });
            for (Method target : new Method[]{View.class.getDeclaredMethod("performLongClick"),
                    View.class.getDeclaredMethod("performLongClick", float.class, float.class)}) {
                hook.add(framework, target, "LongClick" + target.getParameterCount(), hook::longClick);
            }
            hook.add(framework, View.class.getDeclaredMethod("setLongClickable", boolean.class), "Clickable", chain -> {
                Binding binding = hook.bindings.get(chain.getThisObject());
                if (binding == null || Boolean.TRUE.equals(hook.updatingClickable.get())) return chain.proceed();
                binding.nativeLongClickable = (Boolean) chain.getArg(0);
                if (!hook.closed && hook.options.enabled(HashLongPressVoiceHook.OPTION)) {
                    Object[] arguments = chain.getArgs().toArray(); arguments[0] = true;
                    return chain.proceed(arguments);
                }
                return chain.proceed();
            });
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    private void bind(View send, Object owner, EditText editor) {
        Binding old = bindings.get(send);
        if (old != null && old.owner.get() == owner && old.editor.get() == editor) return;
        Binding binding = new Binding(owner, editor, old == null ? send.isLongClickable() : old.nativeLongClickable);
        bindings.put(send, binding);
        setClickable(send, binding);
    }

    private Object longClick(XposedInterface.Chain chain) throws Throwable {
        View send = (View) chain.getThisObject();
        Binding binding = bindings.get(send);
        EditText editor = binding == null ? null : binding.editor.get();
        String draft = editor == null || editor.getText() == null ? "" : editor.getText().toString();
        if (closed || binding == null || !HashLongPressVoiceHook.handles(options, draft)) return chain.proceed();
        try {
            if (!send.isShown() || !editor.isShown() || !send.hasWindowFocus() || draft.substring(1).trim().isEmpty()) return true;
            String[] expected = key(binding);
            WeakReference<View> weakSend = new WeakReference<>(send);
            HashVoiceGesture gesture = new HashVoiceGesture(draft, expected[0], expected[1], expected[2],
                    () -> current(binding, weakSend.get(), draft, expected),
                    () -> weakSend.get() != null && weakSend.get().hasWindowFocus(),
                    () -> restore(binding, weakSend.get(), draft, expected));
            if (!comments.armLongPress(gesture)) { toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m102, "评论语音正在处理或此草稿已接管")); return true; }
            try {
                if (!send.performClick()) { gesture.cancel(); toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m052, "原生发送入口未就绪，草稿已保留")); }
            } finally { gesture.restoreAfterNativeClick(); }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("TikTok long-press target changed: " + failure.getClass().getSimpleName());
            toast(com.tokai.module.i18n.UiText.safeGet(context, com.tokai.module.R.string.toast_ui_m048, "语音入口不可用，草稿已保留"));
        }
        return true;
    }

    private String[] key(Binding binding) throws ReflectiveOperationException {
        Object owner = binding.owner.get();
        if (owner == null) throw new IllegalStateException("Comment keyboard detached");
        Object context = source.invoke(owner);
        Object work = HostTargets.read(HostTargets.read(context, "commonModel"), "aweme");
        Object aid = HostTargets.invoke(work, "getAid");
        Object input = snapshot.invoke(keyboardModel.invoke(owner), service.invoke(owner), null);
        Object reply = HostTargets.read(input, "LJIIIIZZ");
        Object cid = reply == null ? "" : HostTargets.invoke(reply, "getCid");
        if (!(aid instanceof String) || ((String) aid).isEmpty() || !(cid instanceof String)) {
            throw new IllegalStateException("Missing native comment identity");
        }
        return new String[]{(String) aid, "", (String) cid};
    }
    private boolean current(Binding binding, View send, String draft, String[] expected) {
        EditText editor = binding.editor.get();
        if (closed || !options.enabled(HashLongPressVoiceHook.OPTION) || send == null || editor == null
                || bindings.get(send) != binding || !send.isShown() || !editor.isShown()
                || !draft.contentEquals(editor.getText())) return false;
        try { return java.util.Arrays.equals(expected, key(binding)); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    private void restore(Binding binding, View send, String draft, String[] expected) {
        EditText editor = binding.editor.get();
        if (closed || send == null || editor == null || editor.length() != 0 || !send.isShown() || !editor.isShown()) return;
        try {
            if (java.util.Arrays.equals(expected, key(binding))) { editor.setText(draft); editor.setSelection(editor.length()); }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    private static boolean descendant(View child, View root) {
        for (View current = child; current != null;) {
            if (current == root) return true;
            ViewParent parent = current.getParent(); current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
    private void add(XposedInterface framework, Method target, String id, XposedInterface.Hooker interceptor) {
        XposedInterface.HookHandle handle = framework.hook(target).setId("TokAi.HashLongPress.TikTok." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(interceptor);
        if (handle == null) throw new IllegalStateException("Empty TikTok long-press hook"); handles.add(handle);
    }
    void setContext(Context context) { this.context = context == null ? null : context.getApplicationContext(); }
    void setOptions(LocalOptions options) {
        this.options = options;
        for (Map.Entry<View, Binding> entry : bindingSnapshot()) setClickable(entry.getKey(), entry.getValue());
    }
    private void setClickable(View view, Binding binding) {
        updatingClickable.set(Boolean.TRUE);
        try { view.setLongClickable(!closed && options.enabled(HashLongPressVoiceHook.OPTION) || binding.nativeLongClickable); }
        finally { updatingClickable.remove(); }
    }
    private void toast(String message) { if (context != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); }
    private void safeLog(String message) { try { log.accept(message); } catch (RuntimeException ignored) { } }
    @Override public void close() {
        closed = true;
        for (Map.Entry<View, Binding> entry : bindingSnapshot()) setClickable(entry.getKey(), entry.getValue());
        for (XposedInterface.HookHandle handle : handles) { try { handle.unhook(); } catch (Throwable ignored) { } }
        handles.clear(); bindings.clear();
    }
    private List<Map.Entry<View, Binding>> bindingSnapshot() {
        synchronized (bindings) { return new ArrayList<>(bindings.entrySet()); }
    }
    private static final class Binding {
        final WeakReference<Object> owner;
        final WeakReference<EditText> editor;
        boolean nativeLongClickable;
        Binding(Object owner, EditText editor, boolean nativeLongClickable) {
            this.owner = new WeakReference<>(owner); this.editor = new WeakReference<>(editor); this.nativeLongClickable = nativeLongClickable;
        }
    }
}
