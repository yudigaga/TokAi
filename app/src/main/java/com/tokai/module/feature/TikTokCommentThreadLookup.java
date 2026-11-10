package com.tokai.module.feature;

import com.tokai.module.scan.HostScanReport;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Resolves a notification hint only through native read APIs and rechecks the exact returned thread. */
final class TikTokCommentThreadLookup implements AutoCloseable {
    private final Method rootFetch;
    private final Method replyFetch;
    private final Method subscribe;
    private final Method cloneComment;
    private final Field providerSingleton;
    private final Field providerApi;
    private final Object emptyContext;
    private final Class<?> responseClass;
    private final Class<?> commentClass;
    private final Map<String, Roots> cache = new LinkedHashMap<>();
    private long cacheAccount;
    private volatile boolean closed;

    TikTokCommentThreadLookup(HostScanReport report, ClassLoader loader) throws Exception {
        rootFetch = HostTargets.method(report, "tiktok_comment_thread_roots", loader);
        replyFetch = HostTargets.method(report, "tiktok_comment_thread_replies", loader);
        subscribe = HostTargets.method(report, "tiktok_comment_observable_subscribe", loader);
        cloneComment = HostTargets.method(report, "tiktok_comment_clone", loader);
        Class<?> provider = HostTargets.type(report, "tiktok_comment_thread_provider", loader);
        providerSingleton = HostTargets.field(provider, "LIZIZ");
        providerApi = HostTargets.field(provider, "LIZ");
        Class<?> empty = HostTargets.type(report, "tiktok_comment_empty_context", loader);
        emptyContext = HostTargets.field(empty, "INSTANCE").get(null);
        responseClass = HostTargets.type(report, "tiktok_comment_thread_response", loader);
        commentClass = HostTargets.type(report, "comment_model", loader);
        if (providerApi.getType() != rootFetch.getDeclaringClass()
                || rootFetch.getDeclaringClass() != replyFetch.getDeclaringClass()) {
            throw new IllegalStateException("TikTok comment read provider changed");
        }
        requireKeys(rootFetch, "aweme_id", "cursor", "count", "insert_ids");
        requireKeys(replyFetch, "comment_id", "item_id", "cursor", "count", "insert_ids");
    }

    synchronized CommentReplyEvent resolve(String noticeId, Object hint, Object work, long arrival,
                                            long account, BooleanSupplier valid) throws Exception {
        return resolve(noticeId, hint, work, arrival, account, valid,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
    }

    synchronized CommentReplyEvent resolve(String noticeId, Object hint, Object work, long arrival,
                                            long account, BooleanSupplier valid, long roundDeadline) throws Exception {
        check(valid);
        String aid = text(work, "aid"), incomingId = text(hint, "cid"), me = Long.toString(account);
        String sender = text(HostTargets.read(hint, "user"), "uid");
        if (aid.isEmpty() || incomingId.isEmpty() || sender.isEmpty() || me.equals(sender)) return null;
        String recipient = text(hint, "replyToUserId");
        if (!recipient.isEmpty() && !me.equals(recipient)) return null;
        if (cacheAccount != account) { cacheAccount = account; cache.clear(); }
        long deadline = Math.min(roundDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
        Roots roots = cache.get(aid);
        if (roots == null || System.nanoTime() - roots.at > TimeUnit.SECONDS.toNanos(60)) {
            List<Object> values = new ArrayList<>();
            long cursor = 0;
            for (int page = 0; page < 5; page++) {
                check(valid, deadline);
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("aweme_id", aid); parameters.put("cursor", cursor); parameters.put("count", 20);
                parameters.put("insert_ids", "[\"" + safeId(incomingId) + "\"]");
                Object response = request(rootFetch, parameters, valid, deadline);
                List<?> items = items(response);
                values.addAll(items);
                Object more = HostTargets.read(response, "isHasMore");
                if (!Boolean.TRUE.equals(more)) break;
                long next = number(response, "cursor");
                if (next <= cursor) throw new IllegalStateException("TikTok own-comment lookup stalled");
                cursor = next;
            }
            roots = new Roots(values, System.nanoTime());
            cache.put(aid, roots);
            int retained = 0;
            for (Roots stored : cache.values()) retained += stored.values.size();
            while (cache.size() > 1 && retained > 100) {
                Roots removed = cache.remove(cache.keySet().iterator().next());
                retained -= removed.values.size();
            }
        }
        List<Object> flattened = new ArrayList<>();
        for (Object root : roots.values) {
            if (!commentClass.isInstance(root)) continue;
            flattened.add(root);
            Object replies = HostTargets.read(root, "replyComments");
            if (replies instanceof List<?>) for (Object reply : (List<?>) replies) if (commentClass.isInstance(reply)) flattened.add(reply);
        }
        for (Object incoming : flattened) {
            CommentReplyEvent event = matched(noticeId, hint, work, incoming, flattened, null, arrival, account, false);
            if (event != null) return event;
        }
        int checked = 0;
        String hintedRoot = id(text(hint, "rootCommentId"));
        for (Object root : flattened) {
            boolean ownRoot = me.equals(text(HostTargets.read(root, "user"), "uid"));
            String rootId = text(root, "cid");
            if (rootId.isEmpty() || !id(text(root, "rootCommentId")).isEmpty()
                    || !hintedRoot.isEmpty() && !hintedRoot.equals(rootId)
                    || !ownRoot && hintedRoot.isEmpty()) continue;
            if (++checked > 8) break;
            long cursor = 0;
            for (int page = 0; page < 3; page++) {
                check(valid, deadline);
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("comment_id", rootId); parameters.put("item_id", aid);
                parameters.put("cursor", cursor); parameters.put("count", 20);
                parameters.put("top_ids", "[\"" + safeId(incomingId) + "\"]");
                parameters.put("insert_ids", "[\"" + safeId(incomingId) + "\"]");
                Object response = request(replyFetch, parameters, valid, deadline);
                String responseRoot = text(response, "rootCid");
                if (!responseRoot.isEmpty() && !rootId.equals(responseRoot)) throw new IllegalStateException("TikTok thread root changed");
                List<Object> context = new ArrayList<>(flattened);
                context.addAll(items(response));
                for (Object incoming : items(response)) {
                    CommentReplyEvent event = matched(noticeId, hint, work, incoming, context,
                            root, arrival, account, rootId.equals(responseRoot));
                    if (event != null) return event;
                }
                if (!Boolean.TRUE.equals(HostTargets.read(response, "isHasMore"))) break;
                long next = number(response, "cursor");
                if (next <= cursor) throw new IllegalStateException("TikTok reply-list lookup stalled");
                cursor = next;
            }
        }
        return null;
    }

    private CommentReplyEvent matched(String noticeId, Object hint, Object work, Object incoming,
                                       List<Object> candidates, Object requestedRoot, long arrival,
                                       long account, boolean rootBound) throws Exception {
        if (!commentClass.isInstance(incoming) || !text(hint, "cid").equals(text(incoming, "cid"))) return null;
        String aid = text(work, "aid"), me = Long.toString(account);
        String sender = text(HostTargets.read(incoming, "user"), "uid");
        if (sender.isEmpty() || !sender.equals(text(HostTargets.read(hint, "user"), "uid")) || me.equals(sender)) return null;
        for (Object model : new Object[]{hint, incoming}) {
            String repeated = text(model, "awemeId"), recipient = text(model, "replyToUserId");
            if (!repeated.isEmpty() && !aid.equals(repeated) || !recipient.isEmpty() && !me.equals(recipient)) return null;
        }
        String root = id(text(incoming, "rootCommentId")), direct = id(text(incoming, "replyToReplyId"));
        String parent = direct.isEmpty() ? root : direct;
        Object own = null;
        for (Object candidate : candidates) {
            if (!commentClass.isInstance(candidate) || !parent.equals(text(candidate, "cid"))) continue;
            if (!me.equals(text(HostTargets.read(candidate, "user"), "uid"))) return null;
            own = candidate;
        }
        if (own == null && parent.isEmpty() && requestedRoot != null && rootBound
                && me.equals(text(incoming, "replyToUserId"))
                && me.equals(text(HostTargets.read(requestedRoot, "user"), "uid"))) {
            own = requestedRoot;
            root = text(own, "cid");
            Object copy = cloneComment.invoke(incoming);
            if (copy == incoming || !commentClass.isInstance(copy) || !text(incoming, "cid").equals(text(copy, "cid"))) return null;
            HostTargets.field(commentClass, "rootCommentId").set(copy, root);
            HostTargets.field(commentClass, "replyToReplyId").set(copy, "0");
            commentClass.getMethod("setCommentType", int.class).invoke(copy, 2);
            incoming = copy;
        }
        if (own == null) return null;
        String ownAid = text(own, "awemeId"), ownText = text(own, "text").trim(), body = text(incoming, "text").trim();
        if (!ownAid.isEmpty() && !aid.equals(ownAid) || ownText.isEmpty()
                || body.isEmpty() && !AutomationReplyMedia.hasCommentImages(incoming)) return null;
        String hintedRoot = id(text(hint, "rootCommentId")), hintedDirect = id(text(hint, "replyToReplyId"));
        if (!hintedRoot.isEmpty() && !hintedRoot.equals(root) || !hintedDirect.isEmpty() && !hintedDirect.equals(direct)) return null;
        if (root.isEmpty()) root = parent;
        return new CommentReplyEvent(noticeId, aid, text(incoming, "cid"), root, sender,
                text(HostTargets.read(incoming, "user"), "nickname"), body, ownText, arrival, work, incoming, true);
    }

    private Object request(Method method, Map<String, Object> values, BooleanSupplier valid, long deadline) throws Exception {
        check(valid, deadline);
        Object api = providerApi.get(providerSingleton.get(null));
        Object[] arguments = arguments(method, values);
        CompletableFuture<Object> received = new CompletableFuture<>();
        AtomicReference<Object> disposable = new AtomicReference<>();
        boolean suspend = method == replyFetch;
        if (suspend) {
            Class<?> continuation = method.getParameterTypes()[arguments.length - 1];
            arguments[arguments.length - 1] = Proxy.newProxyInstance(continuation.getClassLoader(), new Class<?>[]{continuation},
                    (proxy, call, input) -> {
                        if ("getContext".equals(call.getName())) return emptyContext;
                        if ("resumeWith".equals(call.getName())) {
                            Object failure = HostTargets.read(input[0], "exception");
                            if (failure instanceof Throwable) received.completeExceptionally((Throwable) failure);
                            else received.complete(input[0]);
                        }
                        return objectMethod(proxy, call.getName(), input);
                    });
            Object immediate = method.invoke(api, arguments);
            if (responseClass.isInstance(immediate)) received.complete(immediate);
        } else {
            Object observable = method.invoke(api, arguments);
            Class<?> observer = subscribe.getParameterTypes()[0];
            Object callback = Proxy.newProxyInstance(observer.getClassLoader(), new Class<?>[]{observer},
                    (proxy, call, input) -> {
                        switch (call.getName()) {
                            case "onSubscribe": disposable.set(input[0]); return null;
                            case "onNext": received.complete(input[0]); return null;
                            case "onError": received.completeExceptionally((Throwable) input[0]); return null;
                            case "onComplete": if (!received.isDone()) received.completeExceptionally(new IllegalStateException("Empty native comment list")); return null;
                            default: return objectMethod(proxy, call.getName(), input);
                        }
                    });
            subscribe.invoke(observable, callback);
        }
        try {
            while (true) {
                check(valid, deadline);
                try {
                    Object response = received.get(200, TimeUnit.MILLISECONDS);
                    if (!responseClass.isInstance(response) || number(response, "status_code") != 0) {
                        throw new IllegalStateException("TikTok thread response failed");
                    }
                    return response;
                } catch (TimeoutException pending) { }
            }
        } finally {
            Object subscription = disposable.get();
            if (subscription != null) {
                Method onSubscribe = null;
                for (Method candidate : subscribe.getParameterTypes()[0].getMethods()) {
                    if ("onSubscribe".equals(candidate.getName()) && candidate.getParameterCount() == 1) {
                        if (onSubscribe != null) throw new IllegalStateException("Ambiguous native subscription");
                        onSubscribe = candidate;
                    }
                }
                if (onSubscribe == null) throw new IllegalStateException("Missing native subscription");
                onSubscribe.getParameterTypes()[0].getMethod("dispose").invoke(subscription);
            }
            if (!received.isDone()) received.cancel(true);
        }
    }

    private static Object[] arguments(Method method, Map<String, Object> values) throws Exception {
        Class<?>[] types = method.getParameterTypes(); Annotation[][] annotations = method.getParameterAnnotations();
        Object[] arguments = new Object[types.length];
        for (int i = 0; i < arguments.length; i++) {
            String key = key(annotations[i]);
            if (key != null && values.containsKey(key)) arguments[i] = values.get(key);
            else if (types[i] == boolean.class) arguments[i] = false;
            else if (types[i] == int.class) arguments[i] = 0;
            else if (types[i] == long.class) arguments[i] = 0L;
            else if (types[i] == float.class) arguments[i] = 0f;
            else if (types[i] == String.class) arguments[i] = "";
            else if (List.class.isAssignableFrom(types[i])) arguments[i] = Collections.emptyList();
        }
        return arguments;
    }
    private static String key(Annotation[] annotations) throws Exception {
        for (Annotation annotation : annotations) {
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                if (value instanceof String) return (String) value;
            } catch (NoSuchMethodException notQuery) { }
        }
        return null;
    }
    private static void requireKeys(Method method, String... expected) throws Exception {
        List<String> keys = new ArrayList<>(); for (Annotation[] annotations : method.getParameterAnnotations()) keys.add(key(annotations));
        for (String field : expected) if (!keys.contains(field)) throw new IllegalStateException("Missing native query field: " + field);
    }
    private List<?> items(Object response) {
        Object value = HostTargets.read(response, "items");
        if (!(value instanceof List<?>)) throw new IllegalStateException("TikTok thread list unavailable");
        return (List<?>) value;
    }
    private void check(BooleanSupplier valid) { if (closed || !valid.getAsBoolean()) throw new CancellationException(); }
    private void check(BooleanSupplier valid, long deadline) throws TimeoutException {
        check(valid); if (System.nanoTime() >= deadline) throw new TimeoutException("TikTok native thread lookup timed out");
    }
    private static Object objectMethod(Object proxy, String name, Object[] args) {
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == args[0];
        if ("toString".equals(name)) return "TokAi.NativeCommentRead";
        return null;
    }
    private static String text(Object model, String field) { Object value = HostTargets.read(model, field); return value instanceof String ? (String) value : ""; }
    private static String id(String value) { return "0".equals(value) ? "" : value; }
    private static long number(Object model, String field) { Object value = HostTargets.read(model, field); return value instanceof Number ? ((Number) value).longValue() : -1; }
    private static String safeId(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid native comment identifier"); return value;
    }
    @Override public void close() { closed = true; cache.clear(); }
    private static final class Roots {
        final List<Object> values; final long at;
        Roots(List<Object> values, long at) { this.values = values; this.at = at; }
    }
}
