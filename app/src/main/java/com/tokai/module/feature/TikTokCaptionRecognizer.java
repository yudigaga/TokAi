package com.tokai.module.feature;

import com.tokai.module.scan.HostScanReport;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Uses normal host authorization, caption upload and HTTP callbacks; never edits an author's state. */
final class TikTokCaptionRecognizer implements AutoCloseable {
    private static final long MAX_AUDIO_BYTES = 64L * 1024 * 1024;
    private static final int MAX_CUES = 5000, MAX_TEXT_CHARS = 200_000;
    private final Binding host;
    private final ThreadPoolExecutor authWorker;
    private final AtomicReference<FutureTask<Object>> pendingAuth = new AtomicReference<>();
    private final AtomicReference<NativeLease> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(), busy = new AtomicBoolean();

    TikTokCaptionRecognizer(HostScanReport report, ClassLoader loader) throws Exception {
        host = new Binding(report, loader);
        authWorker = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "TokAi-caption-auth");
            thread.setDaemon(true);
            return thread;
        });
    }

    CaptionTranscript recognize(File preparedAudio, long durationMillis, MediaReadCancellation token) throws Exception {
        if (token == null) throw new IllegalArgumentException("字幕取消状态为空");
        check(token);
        if (preparedAudio == null || !preparedAudio.isFile() || preparedAudio.length() < 1
                || preparedAudio.length() > MAX_AUDIO_BYTES || durationMillis <= 0) {
            throw new IllegalArgumentException("字幕音轨为空、过大或时长无效");
        }
        if (!busy.compareAndSet(false, true)) throw failed("字幕识别任务正在执行");
        try {
            token.start();
            Object step = authorization(token);
            if (!host.step.isInstance(step) || host.stepCode.getInt(step) != 0) throw failed("TikTok 未提供字幕上传授权");
            Object data = host.stepData.get(step);
            if (!host.authModel.isInstance(data)) throw failed("TikTok 字幕上传授权类型无效");
            Object config = host.videoConfig.get(data);
            if (!host.uploadConfig.isInstance(config)) throw failed("TikTok 字幕上传配置为空");
            check(token);
            Object api = invoke(host.api, construct(host.factory));
            if (!host.submit.getDeclaringClass().isInstance(api)) throw failed("TikTok 字幕服务不可用");
            String tosKey = upload(preparedAudio, config, token);
            check(token);
            Object submitCall = invoke(host.submit, api, tosKey, 80, 1, "auto_language", true, false, null, false, false);
            Object submitted = result(http(submitCall, token));
            String taskId = identifier(host.taskId.get(submitted));
            check(token);
            Object queryCall = invoke(host.query, api, taskId);
            Object recognized = result(http(queryCall, token));
            return transcript(recognized, durationMillis, token);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            token.cancel();
            throw new CancellationException("字幕识别已取消");
        } finally {
            busy.set(false);
        }
    }

    private Object authorization(MediaReadCancellation token) throws Exception {
        FutureTask<Object> task = new FutureTask<>(() -> invoke(host.auth, null));
        pendingAuth.set(task);
        try {
            check(token);
            try { authWorker.execute(task); }
            catch (RejectedExecutionException busyWorker) { check(token); throw failed("TikTok 字幕授权服务忙"); }
            while (true) {
                check(token);
                try {
                    Object result = task.get(50, TimeUnit.MILLISECONDS);
                    check(token);
                    return result;
                } catch (TimeoutException pending) {
                    // The host helper owns a blocking Future.get; only its bounded worker can be interrupted.
                } catch (ExecutionException failure) {
                    throw failed("TikTok 字幕授权请求失败");
                }
            }
        } finally {
            task.cancel(true);
            pendingAuth.compareAndSet(task, null);
        }
    }

    private String upload(File audio, Object config, MediaReadCancellation token) throws Exception {
        check(token);
        Object uploader = construct(host.uploader, config, host.captionKind);
        NativeResult completion = new NativeResult();
        NativeLease lease = new NativeLease(uploader, host.uploadClose, completion);
        install(lease);
        try {
            Object listener = Proxy.newProxyInstance(host.listener.getClassLoader(), new Class<?>[]{host.listener}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (method.equals(host.networkCallback)) {
                if (completion.done.get() || closed.get()) return 0;
                try { return invoke(host.uploadPolicy, null, config, "SubtitleOriginalSoundUpload"); }
                catch (Throwable failure) {
                    completion.fail("TikTok 字幕上传网络检查失败");
                    // Host 1KI3 maps disconnected=false to 0; -1 instead disables its network judgement.
                    return 0;
                }
            }
            if (completion.done.get()) return null;
            try {
                if (method.equals(host.uploadCallback)) {
                    int event = (Integer) args[0];
                    if (event == 0) {
                        if (!host.uploadInfo.isInstance(args[2])) throw failed("TikTok 字幕上传结果类型无效");
                        completion.succeed(identifier(host.tosKey.get(args[2])));
                    } else if (event == 2) completion.fail("TikTok 字幕音轨上传失败");
                } else if (!host.uploadNotices.contains(method)) completion.fail("TikTok 字幕上传回调已变化");
            } catch (Throwable failure) {
                completion.fail("TikTok 字幕上传回调无效");
            }
            return null;
            });
            lease.run(host.uploadTag, token, "captions_audio");
            lease.run(host.uploadListener, token, listener);
            lease.run(host.uploadFile, token, audio.getAbsolutePath());
            lease.run(host.uploadStart, token);
            return (String) completion.await(token);
        } finally { release(lease); }
    }

    private Object http(Object call, MediaReadCancellation token) throws Exception {
        if (!host.call.isInstance(call)) throw failed("TikTok 字幕网络调用类型无效");
        NativeResult completion = new NativeResult();
        NativeLease lease = new NativeLease(call, host.callCancel, completion);
        install(lease);
        try {
            Object callback = Proxy.newProxyInstance(host.httpCallback.getClassLoader(), new Class<?>[]{host.httpCallback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if (completion.done.get()) return null;
            try {
                if (method.equals(host.httpSuccess)) {
                    if (args[0] != call || !host.httpResponse.isInstance(args[1])) throw failed("TikTok 字幕响应类型无效");
                    if (Boolean.TRUE.equals(invoke(host.httpIsSuccess, args[1]))) completion.succeed(host.responseBody.get(args[1]));
                    else completion.fail("TikTok 字幕网络请求失败");
                } else completion.fail("TikTok 字幕网络请求失败");
            } catch (Throwable failure) {
                completion.fail("TikTok 字幕网络回调无效");
            }
            return null;
            });
            lease.run(host.callEnqueue, token, callback);
            return completion.await(token);
        } finally { release(lease); }
    }

    private Object result(Object body) throws Exception {
        if (!host.body.isInstance(body)) throw failed("TikTok 字幕响应格式无效");
        if (host.bodyCode.getInt(body) != 0) throw failed("TikTok 字幕服务拒绝了请求");
        Object value = host.videoCaption.get(body);
        if (!host.result.isInstance(value)) throw failed("TikTok 字幕结果为空");
        return value;
    }

    private CaptionTranscript transcript(Object result, long durationMillis, MediaReadCancellation token) throws Exception {
        Object value = host.utterances.get(result);
        if (!(value instanceof List<?>)) throw failed("TikTok 字幕片段格式无效");
        List<?> utterances = (List<?>) value;
        if (utterances.size() > MAX_CUES) throw new IllegalArgumentException("字幕片段过多");
        List<CaptionTranscript.Cue> cues = new ArrayList<>(utterances.size());
        int textChars = 0;
        for (Object utterance : utterances) {
            check(token);
            if (!host.utterance.isInstance(utterance)) throw failed("TikTok 字幕片段类型无效");
            String text = (String) host.text.get(utterance);
            if (text == null || text.trim().isEmpty()) continue;
            textChars += text.length();
            if (textChars > MAX_TEXT_CHARS) throw new IllegalArgumentException("字幕文本过长");
            long start = host.startTime.getLong(utterance), end = host.endTime.getLong(utterance);
            if (end > durationMillis) throw new IllegalArgumentException("字幕时间超出音轨时长");
            // Verified Utterance values are milliseconds, unlike the editor's NLE microseconds.
            cues.add(new CaptionTranscript.Cue(start, end, text));
        }
        check(token);
        return new CaptionTranscript(cues);
    }

    private void check(MediaReadCancellation token) throws Exception {
        if (closed.get()) throw new CancellationException("字幕识别已关闭");
        token.check();
    }

    private void install(NativeLease lease) {
        if (!active.compareAndSet(null, lease)) {
            lease.close();
            throw failed("字幕识别资源仍在使用");
        }
    }

    private void release(NativeLease lease) { lease.close(); active.compareAndSet(lease, null); }

    @Override public void close() {
        closed.set(true);
        FutureTask<?> task = pendingAuth.get();
        if (task != null) task.cancel(true);
        NativeLease lease = active.get();
        if (lease != null) lease.close();
        authWorker.shutdownNow();
    }

    private final class NativeLease {
        final Object owner;
        final Method cancel;
        final NativeResult completion;
        boolean released;

        NativeLease(Object owner, Method cancel, NativeResult completion) {
            this.owner = owner; this.cancel = cancel; this.completion = completion;
        }

        synchronized void run(Method operation, MediaReadCancellation token, Object... args) throws Exception {
            check(token);
            if (released) throw new CancellationException("字幕原生调用已取消");
            invoke(operation, owner, args);
        }

        synchronized void close() {
            if (released) return;
            released = true;
            completion.done.set(true);
            try { invoke(cancel, owner); } catch (Exception ignored) { }
        }
    }

    private final class NativeResult {
        final AtomicBoolean done = new AtomicBoolean();
        final CountDownLatch ready = new CountDownLatch(1);
        Object value;
        IllegalStateException failure;

        void succeed(Object value) {
            if (!done.compareAndSet(false, true)) return;
            this.value = value;
            ready.countDown();
        }

        void fail(String message) {
            if (!done.compareAndSet(false, true)) return;
            failure = failed(message);
            ready.countDown();
        }

        Object await(MediaReadCancellation token) throws Exception {
            while (true) {
                check(token);
                if (ready.await(50, TimeUnit.MILLISECONDS)) {
                    check(token);
                    if (failure != null) throw failure;
                    return value;
                }
            }
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
        return "TokAiCaptionCallback";
    }

    private static String identifier(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > 2048
                || ((String) value).codePoints().anyMatch(Character::isISOControl)) throw failed("TikTok 字幕任务标识无效");
        return (String) value;
    }

    private static Object invoke(Method method, Object owner, Object... args) throws Exception {
        try { return method.invoke(owner, args); }
        catch (ReflectiveOperationException | RuntimeException failure) { throw failed("TikTok 字幕原生调用失败"); }
    }

    private static Object construct(Constructor<?> constructor, Object... args) throws Exception {
        try { return constructor.newInstance(args); }
        catch (ReflectiveOperationException | RuntimeException failure) { throw failed("TikTok 字幕原生组件不可用"); }
    }

    private static IllegalStateException failed(String message) { return new IllegalStateException(message); }

    private static final class Binding {
        final Method auth, api, submit, query, callEnqueue, callCancel, uploadPolicy;
        final Method uploadTag, uploadFile, uploadListener, uploadStart, uploadClose;
        final Method uploadCallback, networkCallback, httpSuccess, httpIsSuccess;
        final List<Method> uploadNotices;
        final Constructor<?> factory, uploader;
        final Class<?> step, authModel, uploadConfig, listener, uploadInfo, call, httpCallback, httpResponse, body, result, utterance;
        final Field stepCode, stepData, videoConfig, responseBody, bodyCode, videoCaption, taskId, utterances, startTime, endTime, text, tosKey;
        final Object captionKind;

        Binding(HostScanReport report, ClassLoader loader) throws Exception {
            auth = method(report, "auth", loader); api = method(report, "api", loader);
            submit = method(report, "submit", loader); query = method(report, "query", loader);
            callEnqueue = method(report, "call_enqueue", loader); callCancel = method(report, "call_cancel", loader);
            uploadPolicy = method(report, "upload_policy", loader);
            uploadTag = method(report, "upload_tag", loader); uploadFile = method(report, "upload_file", loader);
            uploadListener = method(report, "upload_listener", loader); uploadStart = method(report, "upload_start", loader);
            uploadClose = method(report, "upload_close", loader);
            step = type(report, "step", loader); authModel = type(report, "auth_model", loader);
            uploadConfig = type(report, "upload_config", loader); listener = type(report, "listener", loader);
            uploadInfo = type(report, "upload_info", loader); httpCallback = type(report, "http_callback", loader);
            httpResponse = type(report, "http_response", loader); body = type(report, "body", loader);
            result = type(report, "result", loader); utterance = type(report, "utterance", loader);
            Class<?> kind = type(report, "upload_kind", loader);
            call = callCancel.getDeclaringClass();
            if (auth.getDeclaringClass() != api.getDeclaringClass() || !listener.isInterface()
                    || !httpCallback.isInterface() || !kind.isEnum()) throw failed("TikTok 字幕组件关系已变化");
            signature(auth, true, step); signature(api, false, submit.getDeclaringClass());
            signature(submit, false, call, String.class, int.class, int.class, String.class, boolean.class,
                    boolean.class, String.class, boolean.class, boolean.class);
            signature(query, false, call, String.class);
            if (query.getDeclaringClass() != submit.getDeclaringClass() || callEnqueue.getDeclaringClass() != call) throw failed("TikTok 字幕接口关系已变化");
            signature(callEnqueue, false, void.class, httpCallback); signature(callCancel, false, void.class);
            signature(uploadPolicy, true, int.class, uploadConfig, String.class);
            Class<?> upload = uploadStart.getDeclaringClass();
            for (Method operation : new Method[]{uploadTag, uploadFile, uploadListener, uploadClose}) {
                if (operation.getDeclaringClass() != upload) throw failed("TikTok 字幕上传器关系已变化");
            }
            signature(uploadTag, false, void.class, String.class); signature(uploadFile, false, void.class, String.class);
            signature(uploadListener, false, void.class, listener); signature(uploadStart, false, void.class);
            signature(uploadClose, false, void.class);
            factory = api.getDeclaringClass().getConstructor(); uploader = upload.getConstructor(uploadConfig, kind);
            captionKind = kind.getField("CAPTION").get(null);
            if (!kind.isInstance(captionKind)) throw failed("TikTok 字幕上传类型已变化");
            uploadCallback = listener.getMethod("LIZLLL", int.class, long.class, uploadInfo);
            networkCallback = listener.getMethod("LJ");
            signature(uploadCallback, false, void.class, int.class, long.class, uploadInfo);
            signature(networkCallback, false, int.class);
            uploadNotices = List.of(listener.getMethod("LIZ"), listener.getMethod("LIZIZ"), listener.getMethod("LIZJ"));
            for (Method notice : uploadNotices) signature(notice, false, void.class);
            httpSuccess = httpCallback.getMethod("onResponse", call, httpResponse);
            signature(httpSuccess, false, void.class, call, httpResponse);
            signature(httpCallback.getMethod("onFailure", call, Throwable.class), false, void.class, call, Throwable.class);
            httpIsSuccess = httpResponse.getMethod("LIZJ");
            signature(httpIsSuccess, false, boolean.class);
            stepCode = field(step, "LIZ", int.class); stepData = field(step, "LIZJ", Object.class);
            field(step, "LIZIZ", String.class);
            videoConfig = field(authModel, "videoConfig", uploadConfig);
            responseBody = field(httpResponse, "LIZIZ", Object.class);
            bodyCode = field(body, "statusCode", int.class); videoCaption = field(body, "videoCaption", result);
            taskId = field(result, "id", String.class); utterances = field(result, "mList", List.class);
            startTime = field(utterance, "startTime", long.class); endTime = field(utterance, "endTime", long.class);
            text = field(utterance, "text", String.class); tosKey = field(uploadInfo, "mTosKey", String.class);
        }

        private static Method method(HostScanReport report, String id, ClassLoader loader) throws Exception {
            return HostTargets.method(report, "tiktok_caption_asr_" + id, loader);
        }
        private static Class<?> type(HostScanReport report, String id, ClassLoader loader) throws Exception {
            return HostTargets.type(report, "tiktok_caption_asr_" + id, loader);
        }
        private static void signature(Method method, boolean isStatic, Class<?> returns, Class<?>... parameters) {
            if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != returns
                    || !Arrays.equals(method.getParameterTypes(), parameters)) throw failed("TikTok 字幕方法合同已变化");
        }
        private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
            Field value = HostTargets.field(owner, name);
            if (Modifier.isStatic(value.getModifiers()) || value.getType() != type) throw failed("TikTok 字幕字段合同已变化");
            return value;
        }
    }
}
