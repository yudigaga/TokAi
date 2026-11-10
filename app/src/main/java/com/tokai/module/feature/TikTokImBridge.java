package com.tokai.module.feature;

import android.util.SparseArray;
import com.tokai.module.scan.HostScanReport;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Adapts the verified TikTok IM service graph; construction never submits a message. */
public final class TikTokImBridge implements NativeImTransport {
    private final ClassLoader loader;
    private final Method clientFactory, uid, conversationModel, all, find, findAsync, send;
    private final Class<?> clientType, serviceType, messageType, callbackType, eventSuccess, eventFailure;
    private final Constructor<?> target, legacyContent, params, options, builder;
    private final Method builderConversation, builderType, builderContent, builderBuild, serialize;
    private final Object business;
    private final Method mediaSend;
    private final TikTokAudioPayload audioPayload;

    TikTokImBridge(HostScanReport report, ClassLoader loader) throws Exception {
        this.loader = loader;
        clientFactory = HostTargets.method(report, "tiktok_im_client_factory", loader);
        clientType = HostTargets.type(report, "tiktok_im_client_services", loader);
        serviceType = HostTargets.type(report, "tiktok_im_core_services", loader);
        uid = HostTargets.method(report, "tiktok_im_uid", loader);
        conversationModel = HostTargets.method(report, "tiktok_im_conversation_model", loader);
        all = HostTargets.method(report, "adv_im_all_conversations", loader);
        find = HostTargets.method(report, "adv_im_conversation", loader);
        findAsync = HostTargets.method(report, "adv_im_conversation_async", loader);
        send = HostTargets.method(report, "tiktok_im_send", loader);
        messageType = HostTargets.type(report, "tiktok_im_message_model", loader);
        callbackType = HostTargets.type(report, "tiktok_im_send_callback", loader);
        eventSuccess = HostTargets.type(report, "tiktok_im_send_success", loader);
        eventFailure = HostTargets.type(report, "tiktok_im_send_failure", loader);
        Class<?> businessType = clientFactory.getParameterTypes()[0];
        business = businessType.getField("TIKTOK_SOCIAL_IM").get(null);
        Class<?> targetType = HostTargets.type(report, "tiktok_im_send_target", loader);
        Class<?> contentType = HostTargets.type(report, "tiktok_im_legacy_content", loader);
        Class<?> paramsType = HostTargets.type(report, "tiktok_im_send_params", loader);
        Class<?> optionsType = HostTargets.type(report, "tiktok_im_send_options", loader);
        target = targetType.getConstructor(String.class);
        legacyContent = contentType.getConstructor(String.class);
        Class<?> byteString = Class.forName("okio.ByteString", false, loader);
        params = paramsType.getConstructor(Class.forName("X.14gU", false, loader), int.class,
                Class.forName("X.14dU", false, loader), String.class, String.class, Map.class, Map.class,
                byteString, byteString, SparseArray.class, List.class,
                Class.forName("com.bytedance.im.core.proto.ReferenceInfo", false, loader), int.class);
        options = optionsType.getConstructor();
        Class<?> builderTypeClass = HostTargets.type(report, "tiktok_im_builder", loader);
        builder = builderTypeClass.getConstructor(long.class, String.class);
        builderConversation = HostTargets.method(report, "adv_im_builder_conversation", loader);
        builderType = HostTargets.method(report, "adv_im_builder_type", loader);
        builderContent = HostTargets.method(report, "adv_im_builder_content", loader);
        builderBuild = HostTargets.method(report, "adv_im_builder_build", loader);
        Class<?> gson = Class.forName("com.google.gson.Gson", false, loader);
        serialize = HostGsonBridge.serializerMethod(gson);
        mediaSend = HostTargets.method(report, "tiktok_im_send_media", loader);
        audioPayload = new TikTokAudioPayload(report, loader);
    }

    private Object services() throws ReflectiveOperationException {
        Object client = clientFactory.invoke(null, business);
        if (!clientType.isInstance(client)) throw new IllegalStateException("TikTok IM services unavailable");
        Object core = clientType.getField("LIZJ").get(client);
        if (!serviceType.isInstance(core)) throw new IllegalStateException("TikTok IM core services changed");
        return core;
    }

    public long getUid() throws ReflectiveOperationException {
        Object client = serviceType.getMethod("LJIIJ").invoke(services());
        return ((Number) uid.invoke(client)).longValue();
    }
    public Object getBeanCreateService() { return this; }
    public Object getConversationListModel() { return this; }

    private Object conversations() throws ReflectiveOperationException { return conversationModel.invoke(services()); }
    public Object getConversation(String id) throws ReflectiveOperationException { return find.invoke(conversations(), id); }
    public Object getAllConversationSync() throws ReflectiveOperationException {
        Object model = conversations();
        if (!all.getDeclaringClass().isInstance(model) && "X.1NGv".equals(model.getClass().getName())) {
            model = model.getClass().getMethod("LIZIZ").invoke(model);
        }
        return all.invoke(model);
    }
    public void getConversationAsync(String id, Object callback) throws ReflectiveOperationException {
        findAsync.invoke(conversations(), id, callback);
    }
    public Object createMessageBuilder() throws ReflectiveOperationException {
        // The native builder constructor fills sender and secSender. No fake conversation is created.
        Object account = Class.forName("X.0BAD", false, loader).getMethod("LIZ").invoke(null);
        String sec = (String) Class.forName("X.0BHC", false, loader).getMethod("LIZLLL").invoke(account);
        return builder.newInstance(getUid(), sec);
    }

    @Override public String json(Object content) throws ReflectiveOperationException {
        return (String) serialize.invoke(serialize.getDeclaringClass().getConstructor().newInstance(), content);
    }

    @Override public Submission prepare(Object message, Consumer<Boolean> completed) throws ReflectiveOperationException {
        if (!messageType.isInstance(message)) throw new IllegalArgumentException("TikTok message type");
        String id = (String) messageType.getMethod("getConversationId").invoke(message);
        int type = ((Number) messageType.getMethod("getMsgType").invoke(message)).intValue();
        String json = (String) messageType.getMethod("getContent").invoke(message);
        Object request = params.newInstance(target.newInstance(id), type, legacyContent.newInstance(json),
                "", "", Collections.emptyMap(), Collections.emptyMap(), null, null, null, null, null, 0);
        AtomicBoolean terminal = new AtomicBoolean();
        Object callback = callback(completed, terminal);
        Object sender = serviceType.getMethod("LJFF").invoke(services());
        return new Submission(send, sender, request, options.newInstance(), callback, completed, terminal);
    }

    @Override public Submission prepareAudio(String conversation, SpeechPreparation.Audio audio, Consumer<Boolean> completed)
            throws ReflectiveOperationException {
        Object request = audioPayload.prepare(conversation, audio);
        AtomicBoolean terminal = new AtomicBoolean();
        Object callback = callback(completed, terminal);
        Object sender = serviceType.getMethod("LJFF").invoke(services());
        return new Submission(mediaSend, sender, request, options.newInstance(), callback, completed, terminal);
    }

    private Object callback(Consumer<Boolean> completed, AtomicBoolean terminal) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                return "TokAiNativeSendEvents";
            }
            if ("onEvent".equals(method.getName()) && args != null && args.length == 1) {
                Boolean success = eventSuccess.isInstance(args[0]) ? Boolean.TRUE
                        : eventFailure.isInstance(args[0]) ? Boolean.FALSE : null;
                if (success != null && terminal.compareAndSet(false, true)) completed.accept(success);
            }
            return null;
        });
    }

    static void submit(Submission submission) throws ReflectiveOperationException {
        Object admission = submission.method.invoke(submission.sender, submission.params, submission.options, submission.callback);
        // Admission only schedules the native pipeline. Business success comes from terminal events.
        if (admission == null || Boolean.TRUE.equals(admission.getClass().getMethod("LIZJ").invoke(admission))) {
            if (submission.terminal.compareAndSet(false, true)) submission.completed.accept(false);
        }
    }

    static final class Submission {
        final Method method;
        final Object sender, params, options, callback;
        final Consumer<Boolean> completed;
        final AtomicBoolean terminal;
        Submission(Method method, Object sender, Object params, Object options, Object callback,
                   Consumer<Boolean> completed, AtomicBoolean terminal) {
            this.method = method; this.sender = sender; this.params = params; this.options = options; this.callback = callback;
            this.completed = completed; this.terminal = terminal;
        }
    }
}
