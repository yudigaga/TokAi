package com.tokai.module.feature;

import android.view.View;
import android.widget.EditText;
import com.tokai.module.config.ExtendedSettings;
import com.tokai.module.config.FeatureSettings;
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

/** Binds the same manual-send draft feature to TikTok's Assem input architecture. */
final class TikTokMessageDraftHook implements AutoCloseable {
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, WeakReference<Object>> sessions = new WeakHashMap<>();
    private final Map<Object, WeakReference<EditText>> editors = new WeakHashMap<>();
    private final Map<EditText, String> prefilled = new WeakHashMap<>();
    private final Method editorGetter;
    private final Method textSetter;
    private final Method conversationGetter;
    private final Method singleChat;
    private final Consumer<String> log;
    private volatile FeatureSettings features;
    private volatile ExtendedSettings settings;
    private volatile boolean closed;

    private TikTokMessageDraftHook(Method editorGetter, Method textSetter, Method conversationGetter,
                                   Method singleChat, FeatureSettings features, ExtendedSettings settings,
                                   Consumer<String> log) {
        this.editorGetter = editorGetter;
        this.textSetter = textSetter;
        this.conversationGetter = conversationGetter;
        this.singleChat = singleChat;
        this.features = features;
        this.settings = settings;
        this.log = log == null ? ignored -> { } : log;
    }

    static TikTokMessageDraftHook install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, FeatureSettings features,
                                          ExtendedSettings settings, Consumer<String> log) throws Exception {
        Method factory = HostTargets.method(report, "tiktok_message_input_factory", loader);
        Method bind = HostTargets.method(report, "auto_message_editor_bind", loader);
        Method editorGetter = HostTargets.method(report, "auto_message_editor_getter", loader);
        Method textSetter = HostTargets.method(report, "auto_message_text_api", loader);
        Method conversationGetter = HostTargets.method(report, "auto_message_session_id", loader);
        Method single = HostTargets.method(report, "tiktok_message_single_chat", loader);
        Method lazyValue = HostTargets.method(report, "tiktok_message_lazy_value", loader);
        Field inputVM = HostTargets.field(bind.getDeclaringClass(), "LLJLILLLLZIIL");
        if (factory.getParameterCount() != 9 || factory.getParameterTypes()[4] != conversationGetter.getDeclaringClass()
                || single.getDeclaringClass() != conversationGetter.getDeclaringClass()
                || single.getReturnType() != boolean.class || bind.getParameterCount() != 1
                || bind.getParameterTypes()[0] != View.class || editorGetter.getReturnType() != EditText.class
                || textSetter.getParameterCount() != 1 || textSetter.getParameterTypes()[0] != CharSequence.class
                || textSetter.getReturnType() != void.class || inputVM.getType() != lazyValue.getDeclaringClass()) {
            throw new IllegalStateException("TikTok draft input contract changed");
        }
        TikTokMessageDraftHook hook = new TikTokMessageDraftHook(editorGetter, textSetter,
                conversationGetter, single, features, settings, log);
        try {
            hook.add(framework.hook(factory).setId("TokAi.MessageDraft.Session")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Object result = chain.proceed();
                        Object ability = chain.getArg(0);
                        Object session = chain.getArg(4);
                        synchronized (hook.sessions) {
                            hook.sessions.put(ability, new WeakReference<>(session));
                        }
                        hook.schedule(ability);
                        return result;
                    }));
            hook.add(framework.hook(bind).setId("TokAi.MessageDraft.Editor")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object ability = lazyValue.invoke(inputVM.get(chain.getThisObject()));
                            Object editor = editorGetter.invoke(ability);
                            if (editor instanceof EditText) {
                                synchronized (hook.editors) {
                                    hook.editors.put(ability, new WeakReference<>((EditText) editor));
                                }
                                hook.schedule(ability);
                            }
                        } catch (ReflectiveOperationException | RuntimeException failure) {
                            hook.log.accept("TikTok draft editor unavailable: " + failure.getMessage());
                        }
                        return result;
                    }));
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    private void add(XposedInterface.HookHandle handle) {
        if (handle == null) throw new IllegalStateException("Empty TikTok draft hook");
        handles.add(handle);
    }

    void setSettings(FeatureSettings features, ExtendedSettings settings) {
        this.features = features;
        this.settings = settings;
    }

    private void schedule(Object ability) {
        if (closed || ability == null) return;
        try {
            Object value = editorGetter.invoke(ability);
            if (!(value instanceof EditText)) return;
            EditText editor = (EditText) value;
            WeakReference<Object> weakAbility = new WeakReference<>(ability);
            editor.post(() -> apply(weakAbility.get(), editor));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private void apply(Object ability, EditText editor) {
        FeatureSettings currentFeatures = features;
        ExtendedSettings currentSettings = settings;
        if (closed || ability == null || currentFeatures == null || currentSettings == null
                || !currentFeatures.automationEnabled || !currentFeatures.autoMessageEnabled
                || currentSettings.autoMessageText.isEmpty() || !editor.isAttachedToWindow()
                || !editor.isShown() || !editor.isEnabled()) return;
        try {
            Object session;
            synchronized (sessions) {
                WeakReference<Object> reference = sessions.get(ability);
                session = reference == null ? null : reference.get();
            }
            if (session == null || !Boolean.TRUE.equals(singleChat.invoke(session))
                    || editorGetter.invoke(ability) != editor) return;
            Object rawId = conversationGetter.invoke(session);
            if (!(rawId instanceof String) || ((String) rawId).trim().isEmpty()) return;
            String id = (String) rawId;
            synchronized (prefilled) {
                if (id.equals(prefilled.get(editor)) || editor.getText() == null
                        || editor.getText().length() != 0) return;
                editor.setSelection(0);
                textSetter.invoke(ability, currentSettings.autoMessageText);
                if (currentSettings.autoMessageText.contentEquals(editor.getText())) {
                    prefilled.put(editor, id);
                    log.accept("TikTok native draft inserted; sending remains manual");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            log.accept("Could not prefill TikTok native draft: " + failure.getMessage());
        }
    }

    @Override public void close() {
        closed = true;
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        handles.clear();
        synchronized (sessions) { sessions.clear(); }
        synchronized (editors) { editors.clear(); }
        synchronized (prefilled) { prefilled.clear(); }
    }
}
