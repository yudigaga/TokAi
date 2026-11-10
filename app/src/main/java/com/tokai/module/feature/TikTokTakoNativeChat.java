package com.tokai.module.feature;

import android.view.View;

import com.tokai.module.scan.HostScanReport;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;

/** Scan-bound native objects only. It neither creates views nor calls a host network API. */
final class TikTokTakoNativeChat {
    final Method request, dispatch, pageVm, pageCreated, pageResume, pageStop, pageDestroy, vmClear;
    final Method pageView, getMix, setMix, copyMessage, next, failed, complete;
    final Field vmState, operationState, stateOperator, operatorConversation, conversationId;
    private final Method accountService, currentUser, uid;
    private final Class<?> messageType, mixType, observerType, backendType;
    private final Constructor<?> flow, message, mix, sender, chunk;

    TikTokTakoNativeChat(HostScanReport report, ClassLoader loader) throws Exception {
        request = HostTargets.method(report, "tiktok_tako_request", loader);
        pageVm = HostTargets.method(report, "tiktok_tako_page_vm", loader);
        pageCreated = HostTargets.method(report, "tiktok_tako_page_created", loader);
        pageResume = HostTargets.method(report, "tiktok_tako_page_resume", loader);
        pageStop = HostTargets.method(report, "tiktok_tako_page_stop", loader);
        pageDestroy = HostTargets.method(report, "tiktok_tako_page_destroy", loader);
        vmClear = HostTargets.method(report, "tiktok_tako_vm_clear", loader);
        Class<?> flowType = HostTargets.type(report, "tiktok_tako_flow", loader);
        Class<?> chunkType = HostTargets.type(report, "tiktok_tako_chunk", loader);
        messageType = HostTargets.type(report, "tiktok_tako_message", loader);
        mixType = HostTargets.type(report, "tiktok_tako_mix_feed", loader);
        Class<?> senderType = HostTargets.type(report, "tiktok_tako_sender", loader);
        Class<?>[] arguments = request.getParameterTypes();
        if (!Modifier.isStatic(request.getModifiers()) || request.getReturnType() != Object.class || arguments.length != 3) {
            throw new IllegalStateException("Tako native request signature changed");
        }
        require(arguments[0], "LIZ", int.class);
        require(arguments[0], "LJ", String.class);
        require(arguments[0], "LJIILJJIL", long.class);
        require(arguments[1], "LIZIZ", int.class);
        require(arguments[1], "LIZJ", messageType);
        operationState = HostTargets.field(arguments[1], "LJIILLIIL");
        vmState = HostTargets.field(vmClear.getDeclaringClass(), "LLLIIIL");
        if (operationState.getType() != vmState.getType() || pageVm.getReturnType() != vmClear.getDeclaringClass()
                || pageVm.getParameterCount() != 0 || vmClear.getParameterCount() != 0) {
            throw new IllegalStateException("Tako native page and conversation relationship changed");
        }
        Class<?> pageType = pageVm.getDeclaringClass();
        if (pageCreated.getDeclaringClass() != pageType || pageResume.getDeclaringClass() != pageType
                || pageStop.getDeclaringClass() != pageType || pageDestroy.getDeclaringClass() != pageType) {
            throw new IllegalStateException("Tako native page lifecycle owners changed");
        }
        pageView = pageType.getMethod("getView");
        if (!View.class.isAssignableFrom(pageView.getReturnType())) throw new IllegalStateException("Tako page view changed");
        stateOperator = HostTargets.field(vmState.getType(), "LJIILL");
        dispatch = HostTargets.method(report, "tiktok_tako_dispatch_stream", loader);
        if (Modifier.isStatic(dispatch.getModifiers()) || dispatch.getDeclaringClass() != stateOperator.getType()
                || dispatch.getReturnType() != void.class || dispatch.getParameterCount() != 1
                || dispatch.getParameterTypes()[0] != arguments[1]) {
            throw new IllegalStateException("Tako native stop dispatch relationship changed");
        }
        operatorConversation = HostTargets.field(stateOperator.getType(), "LJII");
        conversationId = require(operatorConversation.getType(), "LIZIZ", String.class);
        backendType = HostTargets.field(flowType, "LL").getType();
        if (!backendType.isInterface() || backendType.getDeclaredMethods().length != 1) {
            throw new IllegalStateException("Tako stream backend changed");
        }
        Method subscribe = backendType.getDeclaredMethods()[0];
        if (!"LIZ".equals(subscribe.getName()) || subscribe.getParameterCount() != 1 || subscribe.getReturnType() != void.class) {
            throw new IllegalStateException("Tako stream subscription changed");
        }
        observerType = subscribe.getParameterTypes()[0];
        if (!observerType.isInterface()) throw new IllegalStateException("Tako stream observer changed");
        next = observerType.getMethod("onNext", Object.class);
        failed = observerType.getMethod("onFailed", Throwable.class);
        complete = observerType.getMethod("onComplete");
        flow = flowType.getConstructor(backendType);
        message = messageType.getConstructor();
        mix = mixType.getConstructor();
        sender = senderType.getConstructor(int.class, long.class, long.class, boolean.class, String.class);
        getMix = messageType.getMethod("LJIIIZ");
        setMix = messageType.getMethod("LJIIJ", mixType);
        copyMessage = messageType.getMethod("LJ");
        if (getMix.getReturnType() != mixType || copyMessage.getReturnType() != messageType) {
            throw new IllegalStateException("Tako native message access changed");
        }
        require(messageType, "sender", senderType);
        require(messageType, "msgType", int.class);
        require(messageType, "uuid", String.class);
        require(messageType, "conversationId", String.class);
        require(messageType, "LIZIZ", int.class);
        require(mixType, "text", String.class);
        require(mixType, "status", int.class);
        require(mixType, "cardType", int.class);
        Constructor<?> found = null;
        for (Constructor<?> candidate : chunkType.getConstructors()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length == 16 && types[0] == int.class && types[1] == messageType
                    && types[5] == int.class && types[6] == long.class && types[10] == int.class
                    && types[11] == boolean.class && types[12] == boolean.class && types[15] == Integer.class) {
                if (found != null) throw new IllegalStateException("Ambiguous Tako native chunk constructor");
                found = candidate;
            }
        }
        if (found == null) throw new NoSuchMethodException("Tako native chunk constructor");
        chunk = found;
        accountService = HostTargets.method(report, "media_account_service", loader);
        currentUser = HostTargets.method(report, "media_current_user", loader);
        uid = currentUser.getReturnType().getMethod("getUid");
        if (!Modifier.isStatic(accountService.getModifiers()) || accountService.getParameterCount() != 0
                || currentUser.getParameterCount() != 0 || uid.getReturnType() != String.class) {
            throw new IllegalStateException("Tako native account access changed");
        }
    }

    Object flow(Consumer<Object> subscribe) throws ReflectiveOperationException {
        Object backend = Proxy.newProxyInstance(backendType.getClassLoader(), new Class<?>[]{backendType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return "TokAi native Tako stream";
            }
            if (!"LIZ".equals(method.getName()) || args == null || args.length != 1 || !observerType.isInstance(args[0])) {
                throw new IllegalStateException("Unexpected native Tako subscription");
            }
            subscribe.accept(args[0]);
            return null;
        });
        return flow.newInstance(backend);
    }

    String account() throws ReflectiveOperationException {
        Object service = invoke(accountService, null);
        if (!currentUser.getDeclaringClass().isInstance(service)) return "";
        Object user = invoke(currentUser, service);
        return user == null ? "" : String.valueOf(invoke(uid, user));
    }

    String conversation(Object state) throws ReflectiveOperationException {
        Object operator = state == null ? null : stateOperator.get(state);
        Object conversation = operator == null ? null : operatorConversation.get(operator);
        return conversation == null ? "" : text(conversationId.get(conversation));
    }

    boolean standard(Object dto) throws ReflectiveOperationException {
        return ((Long) read(dto, "LJIILJJIL")) == 1L;
    }

    Object sendMessage(Object operation) throws ReflectiveOperationException { return read(operation, "LIZJ"); }

    String prompt(Object operation) throws ReflectiveOperationException {
        int op = (Integer) read(operation, "LIZIZ");
        if (op != 1 && op != 9 && op != 1007) {
            throw new IllegalArgumentException("当前 Tako 操作暂不支持模块 AI，请使用文字提问");
        }
        Object sent = sendMessage(operation);
        int type = (Integer) read(sent, "msgType");
        if (type != 1) throw new IllegalArgumentException("当前 Tako 输入暂不支持模块 AI，请使用文字提问");
        Object content = invoke(getMix, sent);
        if (!mixType.isInstance(content)) throw new IllegalArgumentException("当前 Tako 文字内容不可用");
        for (String field : new String[]{"images", "videos", "videoList"}) {
            try {
                Object values = read(content, field);
                if (values instanceof Collection && !((Collection<?>) values).isEmpty()) {
                    throw new IllegalArgumentException("Tako 图片、视频和语音暂不支持模块 AI，请使用纯文字");
                }
            } catch (NoSuchFieldException absent) {
                if (!"videoList".equals(field)) throw absent;
            }
        }
        if (read(content, "mediaSendCard") != null) throw new IllegalArgumentException("Tako 媒体暂不支持模块 AI，请使用纯文字");
        String prompt = text(read(content, "text"));
        if (prompt.trim().isEmpty()) throw new IllegalArgumentException("Tako 提问不能为空");
        return prompt;
    }

    void acknowledgement(Object observer, Object operation) throws ReflectiveOperationException {
        Object copy = invoke(copyMessage, sendMessage(operation));
        write(copy, "LIZIZ", 2); // Local module acceptance; serverStatus and server IDs stay native.
        emit(observer, 1, copy, 0);
    }

    void answer(Object observer, Object operation, String answer) throws Exception {
        Object question = sendMessage(operation);
        Object reply = message.newInstance();
        for (String field : new String[]{"conversationId", "subConversationId", "sessionId", "subSessionId", "botId", "actionId"}) {
            write(reply, field, read(question, field));
        }
        write(reply, "uuid", "tokai-" + UUID.randomUUID());
        write(reply, "msgId", "");
        write(reply, "replyUuid", read(question, "uuid"));
        write(reply, "replyMsgId", read(question, "msgId"));
        write(reply, "sender", sender.newInstance(2, 0L, (Long) read(question, "botId"), false, ""));
        write(reply, "msgType", 2);
        write(reply, "LIZIZ", 6);
        write(reply, "LIZLLL", System.currentTimeMillis());
        Object content = mix.newInstance();
        write(content, "text", answer);
        write(content, "cardType", 0);
        write(content, "status", 2);
        invoke(setMix, reply, content);
        write(reply, "msgContent", new JSONObject().put("text", answer).put("card_type", 0).put("status", 2).toString());
        emit(observer, 3, reply, 1);
        emit(observer, 6, null, 2);
        invoke(complete, observer);
    }

    private void emit(Object observer, int event, Object message, int index) throws ReflectiveOperationException {
        Object value = chunk.newInstance(event, message, null, null, null, index, 0L, null, null, null,
                0, false, false, null, null, null);
        invoke(next, observer, value);
    }

    static Object read(Object owner, String field) throws ReflectiveOperationException {
        return HostTargets.field(owner.getClass(), field).get(owner);
    }
    static void write(Object owner, String field, Object value) throws ReflectiveOperationException {
        HostTargets.field(owner.getClass(), field).set(owner, value);
    }
    static String text(Object value) { return value instanceof String ? (String) value : ""; }
    private static Field require(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        Field field = HostTargets.field(owner, name);
        if (field.getType() != type) throw new IllegalStateException("Tako field relationship changed: " + name);
        return field;
    }
    static Object invoke(Method method, Object owner, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
