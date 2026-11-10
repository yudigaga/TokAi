package com.tokai.module.feature;

import android.os.Handler;
import android.os.Looper;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Uses TikTok's native permission, photo-control, upload and comment publication pipeline. */
final class TikTokNativeCommentPublisher implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<Object, Pending> requests = Collections.synchronizedMap(new IdentityHashMap<>());
    // A retired publisher still has no panel; late UI callbacks must remain isolated without retaining it.
    private final Map<Object, Boolean> ownedPublishers = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final Map<Object, String> ownedInputs = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final Constructor<?> viewModel;
    private final Constructor<?> source;
    private final Constructor<?> common;
    private final Constructor<?> input;
    private final Constructor<?> audioModel;
    private final Method submit;
    private final Method clear;
    private final Method initialize;
    private final Constructor<?> dispatcher;
    private final Method lifecycleReference;
    private final Constructor<?> lifecycleRegistry;
    private final Method lifecycleEvent;
    private final Method lifecycleGetter;
    private final Object createLifecycleEvent;
    private final Object destroyLifecycleEvent;
    private final Field sourceCommon;
    private final Field commonWork;
    private final Consumer<String> log;
    private volatile boolean closed;

    private TikTokNativeCommentPublisher(HostScanReport report, ClassLoader loader,
                                          Consumer<String> log) throws Exception {
        submit = HostTargets.method(report, "tiktok_comment_publish_checked", loader);
        clear = HostTargets.method(report, "tiktok_comment_publisher_clear", loader);
        initialize = report.getResults().containsKey("tiktok_comment_initialize")
                ? HostTargets.method(report, "tiktok_comment_initialize", loader) : null;
        dispatcher = initialize == null ? null
                : HostTargets.type(report, "tiktok_comment_dispatcher", loader).getConstructor();
        viewModel = submit.getDeclaringClass().getConstructor();
        lifecycleReference = HostTargets.method(report, "tiktok_comment_publisher_lifecycle", loader);
        Class<?> registryType = HostTargets.type(report, "tiktok_comment_lifecycle_registry", loader);
        lifecycleEvent = HostTargets.method(report, "tiktok_comment_lifecycle_event", loader);
        // Resolve native AndroidX types through the host; module lifecycle instances are incompatible.
        Constructor<?> registry = null;
        for (Constructor<?> candidate : registryType.getConstructors()) {
            if (candidate.getParameterCount() != 1
                    || !candidate.getParameterTypes()[0].isAssignableFrom(viewModel.getDeclaringClass())) continue;
            if (registry != null) throw new IllegalStateException("Ambiguous native lifecycle owner constructor");
            registry = candidate;
        }
        if (registry == null || !lifecycleEvent.getDeclaringClass().isAssignableFrom(registryType)) {
            throw new IllegalStateException("TikTok native publisher lifecycle contract changed");
        }
        lifecycleRegistry = registry;
        lifecycleGetter = registry.getParameterTypes()[0].getMethod("getLifecycle");
        createLifecycleEvent = nativeLifecycleEvent(lifecycleEvent, "ON_CREATE");
        destroyLifecycleEvent = nativeLifecycleEvent(lifecycleEvent, "ON_DESTROY");
        Class<?> sourceType = HostTargets.method(report, "tiktok_comment_source", loader).getReturnType();
        source = sourceType.getConstructor();
        sourceCommon = HostTargets.field(sourceType, "commonModel");
        common = sourceCommon.getType().getConstructor();
        commonWork = HostTargets.field(common.getDeclaringClass(), "aweme");
        input = submit.getParameterTypes()[0].getConstructor();
        Class<?> audioType = HostTargets.type(report, "tiktok_comment_audio_model", loader);
        audioModel = audioType.getConstructor(String.class, long.class, List.class, String.class,
                long.class, String.class, String.class, String.class,
                HostTargets.field(audioType, "videoModelStruct").getType());
        this.log = log == null ? ignored -> { } : log;
        if (submit.getParameterCount() != 3 || submit.getReturnType() != void.class
                || sourceCommon.getType() != commonWork.getDeclaringClass()) {
            throw new IllegalStateException("TikTok native publisher contract changed");
        }
    }

    static TikTokNativeCommentPublisher install(XposedInterface framework, HostScanReport report,
                                                  ClassLoader loader, Consumer<String> log, String namespace) throws Exception {
        TikTokNativeCommentPublisher publisher = new TikTokNativeCommentPublisher(report, loader, log);
        try {
            Method source = HostTargets.method(report, "tiktok_comment_source", loader);
            publisher.add(framework.hook(source).setId(namespace + ".TikTok.Source")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Pending owned = publisher.requests.get(chain.getThisObject());
                        return owned == null ? chain.proceed() : owned.source;
                    }));
            if (publisher.initialize != null) {
                Method prepared = HostTargets.method(report, "tiktok_comment_publisher_prepared", loader);
                publisher.add(framework.hook(prepared).setId(namespace + ".TikTok.HeadlessPrepared")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            // Owned publishers have no comment panel/shared UI view models to subscribe to.
                            return publisher.ownedPublishers.containsKey(chain.getThisObject()) ? null : chain.proceed();
                        }));
            }
            Method uiStatus = HostTargets.method(report, "tiktok_comment_publish_ui_status", loader);
            publisher.add(framework.hook(uiStatus).setId(namespace + ".TikTok.HeadlessUiStatus")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        // This relay queues comment-panel/shared-VM work; native publishing callbacks still run.
                        Object owner = chain.getThisObject();
                        if (!publisher.ownedPublishers.containsKey(owner)) return chain.proceed();
                        publisher.safeLog("TikTok comment panel status isolated; owned=true; owner_ref="
                                + Integer.toHexString(System.identityHashCode(owner)));
                        return null;
                    }));
            Method dispatch = HostTargets.method(report, "tiktok_comment_publish_dispatch", loader);
            publisher.add(framework.hook(dispatch).setId(namespace + ".TikTok.NativeDispatch")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Pending owned = publisher.requests.get(chain.getThisObject());
                        if (owned != null && chain.getArg(1) == owned.input) owned.dispatched = true;
                        return chain.proceed();
                    }));
            Method success = HostTargets.method(report, "tiktok_comment_publish_success", loader);
            publisher.add(framework.hook(success).setId(namespace + ".TikTok.NativeSuccess")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Pending owned = publisher.requests.get(chain.getThisObject());
                        Object response = chain.getArg(0);
                        try { return chain.proceed(); }
                        finally {
                            if (owned != null) publisher.main.post(() -> publisher.completed(owned,
                                    publisher.responseStatus(response, owned)));
                        }
                    }));
            Method failure = HostTargets.method(report, "tiktok_comment_publish_failure", loader);
            publisher.add(framework.hook(failure).setId(namespace + ".TikTok.NativeFailure")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Pending owned = publisher.requests.get(chain.getThisObject());
                        Object comment = chain.getArg(1);
                        boolean matches = owned != null && owned.aid.equals(HostTargets.read(comment, "awemeId"));
                        try { return chain.proceed(); }
                        finally {
                            if (matches) publisher.main.post(() -> publisher.completed(owned, "failed"));
                        }
                    }));
            for (String id : new String[]{"tiktok_comment_initialize", "tiktok_comment_initialize_dispatch",
                    "tiktok_comment_publish_checked", "tiktok_comment_publish_input",
                    "tiktok_comment_create_comment", "tiktok_comment_publish_dispatch",
                    "tiktok_comment_publish_success", "tiktok_comment_publish_failure",
                    "tiktok_comment_publish_ui_status", "tiktok_comment_publish_failure_state",
                    "tiktok_comment_upload_job", "tiktok_comment_success_job", "tiktok_comment_failure_job"}) {
                if (!report.getResults().containsKey(id)) continue;
                Method caller = HostTargets.method(report, id, loader);
                boolean deoptimized = framework.deoptimize(caller);
                publisher.safeLog("TikTok native comment caller deoptimization: rule=" + id
                        + ";confirmed=" + deoptimized);
            }
            return publisher;
        } catch (Throwable failure) {
            publisher.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    private void add(XposedInterface.HookHandle handle) {
        if (handle == null) throw new IllegalStateException("Empty TikTok comment hook");
        handles.add(handle);
    }

    boolean ownsPublish(Object model) {
        String text = ownedInputs.get(model);
        return text != null && text.contentEquals(String.valueOf(HostTargets.read(model, "LIZ")));
    }

    void send(Object work, String aid, String text, SpeechPreparation.Audio audio, CommentReplyEvent reply,
              BooleanSupplier valid, BooleanSupplier beforeNativeSend,
              Runnable accepted, Consumer<String> result) {
        main.post(() -> {
            if (closed || !valid.getAsBoolean()) { result.accept("cancelled"); return; }
            Object owner = null;
            Pending pending = null;
            boolean invoked = false;
            try {
                if (work == null || aid == null || !aid.equals(HostTargets.invoke(work, "getAid"))) {
                    throw new IllegalArgumentException("TikTok comment origin changed");
                }
                if (reply != null && !replyTargetValid(reply)) {
                    throw new IllegalArgumentException("TikTok comment reply target changed");
                }
                Object context = source.newInstance();
                Object commonModel = sourceCommon.get(context);
                if (commonModel == null) commonModel = common.newInstance();
                commonWork.set(commonModel, work);
                sourceCommon.set(context, commonModel);
                Object model = input.newInstance();
                String output = audio == null ? text : "";
                if (audio == null && (output == null || output.trim().isEmpty())) {
                    throw new IllegalArgumentException("Empty comment text");
                }
                HostTargets.field(model.getClass(), "LIZ").set(model, output);
                HostTargets.field(model.getClass(), "LJIILLIIL").set(model, work);
                if (reply != null) HostTargets.field(model.getClass(), "LJIIIIZZ").set(model, reply.targetComment);
                if (audio != null) {
                    if (!audio.file.isFile() || audio.file.length() == 0 || audio.duration <= 0) {
                        throw new IllegalArgumentException("Invalid native comment audio");
                    }
                    String extension = audio.file.getName();
                    int dot = extension.lastIndexOf('.');
                    extension = dot < 0 ? "" : extension.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                    Object recording = audioModel.newInstance(audio.file.getAbsolutePath(), audio.duration,
                            CommentAudioWaveform.nativeFallback(), java.util.UUID.randomUUID().toString(),
                            audio.file.length(), extension, null, null, null);
                    HostTargets.field(model.getClass(), "LIZLLL").set(model, Collections.singletonList(recording));
                }
                owner = viewModel.newInstance();
                pending = new Pending(owner, context, model, aid, reply, result);
                requests.put(owner, pending);
                ownedPublishers.put(owner, Boolean.TRUE);
                ownedInputs.put(model, output);
                bindLifecycle(pending);
                if (initialize != null) {
                    Class<?> transform = initialize.getParameterTypes()[1];
                    Object identity = Proxy.newProxyInstance(transform.getClassLoader(), new Class<?>[]{transform},
                            (proxy, method, arguments) -> {
                                if ("invoke".equals(method.getName())) return arguments[0];
                                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                                if ("equals".equals(method.getName())) return proxy == arguments[0];
                                return "TokAi.Comment.IdentityState";
                            });
                    initialize.invoke(owner, dispatcher.newInstance(), identity);
                    if (!Boolean.TRUE.equals(HostTargets.read(owner, "_initialized"))) {
                        throw new IllegalStateException("TikTok native comment publisher did not initialize");
                    }
                }
                if (lifecycleGetter.invoke(owner) != pending.lifecycle) {
                    throw new IllegalStateException("TikTok native publisher lifecycle binding changed");
                }
                if (closed || !valid.getAsBoolean() || !beforeNativeSend.getAsBoolean()) {
                    requests.remove(owner);
                    release(pending);
                    result.accept("cancelled");
                    return;
                }
                invoked = true;
                // KS0 performs TikTok's account/permission and photo checks before i33 publishes.
                submit.invoke(owner, model, null, null);
                if (!pending.dispatched) {
                    completed(pending, "failed");
                    retireLater(pending);
                    return;
                }
                accepted.run();
                Pending token = pending;
                main.postDelayed(() -> unconfirmed(token), 30_000);
                retireLater(token);
                safeLog("TikTok native comment dispatch observed; server result pending");
            } catch (Exception failure) {
                if (pending != null && invoked && failure instanceof InvocationTargetException) {
                    // An invoked native publisher can throw after starting a network/upload job.
                    accepted.run();
                    unconfirmed(pending);
                    retireLater(pending);
                } else {
                    if (owner != null) {
                        requests.remove(owner);
                        if (pending != null) release(pending);
                        else try { clear.invoke(owner); } catch (ReflectiveOperationException | RuntimeException ignored) { }
                    }
                    result.accept("failed");
                }
                safeLog("TikTok native comment submission: " + failure.getClass().getSimpleName());
            }
        });
    }

    private void completed(Pending pending, String status) {
        if (closed || pending.completed) return;
        if ("submitted".equals(status)) { unconfirmed(pending); return; }
        pending.completed = true;
        pending.result.accept(status);
    }

    private void unconfirmed(Pending pending) {
        if (closed || pending.completed || pending.unconfirmed) return;
        pending.unconfirmed = true;
        pending.result.accept("submitted");
    }

    private void retireLater(Pending pending) {
        main.postDelayed(() -> {
            if (!requests.remove(pending.owner, pending)) return;
            release(pending);
        }, 120_000);
    }

    private static Object nativeLifecycleEvent(Method method, String name) {
        if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isEnum()) {
            throw new IllegalStateException("TikTok native lifecycle event type changed");
        }
        for (Object event : method.getParameterTypes()[0].getEnumConstants()) {
            if (((Enum<?>) event).name().equals(name)) return event;
        }
        throw new IllegalStateException("Missing native lifecycle event: " + name);
    }

    private void bindLifecycle(Pending pending) throws ReflectiveOperationException {
        pending.lifecycle = lifecycleRegistry.newInstance(pending.owner);
        lifecycleReference.invoke(pending.owner, new WeakReference<>(pending.lifecycle));
        lifecycleEvent.invoke(pending.lifecycle, createLifecycleEvent);
        pending.lifecycleCreated = true;
        if (lifecycleGetter.invoke(pending.owner) != pending.lifecycle) {
            throw new IllegalStateException("TikTok native publisher lifecycle was not bound");
        }
        safeLog("TikTok native comment lifecycle bound; before_initialize=true; state=CREATED; owner_ref="
                + Integer.toHexString(System.identityHashCode(pending.owner)) + "; registry="
                + pending.lifecycle.getClass().getName());
    }

    private void release(Pending pending) {
        if (pending.released) return;
        pending.released = true;
        try {
            if (pending.lifecycleCreated) lifecycleEvent.invoke(pending.lifecycle, destroyLifecycleEvent);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("TikTok native comment lifecycle cleanup: " + failure.getClass().getSimpleName());
        } finally {
            try { clear.invoke(pending.owner); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                safeLog("TikTok native comment publisher cleanup: " + failure.getClass().getSimpleName());
            }
            pending.lifecycle = null;
        }
    }

    private String responseStatus(Object response, Pending pending) {
        if (!NativeCommentPublisher.validResponse(response, pending.aid)) {
            Object status = HostTargets.read(response, "status_code");
            return status instanceof Number && ((Number) status).intValue() != 0 ? "failed" : "submitted";
        }
        if (pending.reply == null) return "sent";
        Object comment = HostTargets.read(response, "comment");
        Object root = HostTargets.read(comment, "rootCommentId");
        Object direct = HostTargets.read(comment, "replyToReplyId");
        return pending.reply.rootId.equals(root) && (pending.reply.rootId.equals(pending.reply.incomingId)
                || pending.reply.incomingId.equals(direct)) ? "sent" : "submitted";
    }

    private static boolean replyTargetValid(CommentReplyEvent reply) {
        if (reply.targetComment == null || !reply.incomingId.equals(HostTargets.read(reply.targetComment, "cid"))) return false;
        Object aid = HostTargets.read(reply.targetComment, "awemeId");
        if (!reply.aid.equals(aid) && !(reply.nativeWorkBound && (aid == null || "".equals(aid)))) return false;
        Object root = HostTargets.read(reply.targetComment, "rootCommentId");
        String expected = root instanceof String && !((String) root).isEmpty() && !"0".equals(root)
                ? (String) root : reply.incomingId;
        return reply.rootId.equals(expected);
    }

    private void safeLog(String message) { try { log.accept(message); } catch (RuntimeException ignored) { } }

    @Override public void close() {
        closed = true;
        if (Looper.myLooper() == Looper.getMainLooper()) closeOnMain();
        else main.post(this::closeOnMain);
    }

    private void closeOnMain() {
        List<Pending> pending;
        synchronized (requests) {
            pending = new ArrayList<>(requests.values());
            requests.clear();
        }
        for (Pending owned : pending) release(owned);
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (Throwable ignored) { }
        }
        handles.clear();
        ownedPublishers.clear();
        ownedInputs.clear();
    }

    private static final class Pending {
        final Object owner;
        final Object source;
        final Object input;
        final String aid;
        final CommentReplyEvent reply;
        final Consumer<String> result;
        boolean dispatched;
        boolean completed;
        boolean unconfirmed;
        Object lifecycle;
        boolean lifecycleCreated;
        boolean released;
        Pending(Object owner, Object source, Object input, String aid, CommentReplyEvent reply,
                Consumer<String> result) {
            this.owner = owner;
            this.source = source;
            this.input = input;
            this.aid = aid;
            this.reply = reply;
            this.result = result;
        }
    }
}
