package com.tokai.module.feature;

import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reads TikTok's notification API with is_mark_read=0; ordinary comment list/UI data is never an event. */
final class TikTokCommentReplyInbox implements CommentReplyHook.Source {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 50;
    private final ClassLoader loader;
    private final Consumer<String> log;
    private final Method fetch;
    private final Field api;
    private final Constructor<?> request;
    private final Method serialize;
    private final Method setCursor;
    private final Field groupConfig;
    private final Class<?> responseClass;
    private final Class<?> pageClass;
    private final Class<?> noticeClass;
    private final Class<?> payloadClass;
    private final Class<?> commentClass;
    private final Method accountService;
    private final Method currentUser;
    private final Class<?> templateClass;
    private final Class<?> templateExtraClass;
    private final TikTokCommentThreadLookup threadLookup;
    private XposedInterface.HookHandle handle;
    private volatile boolean closed;
    private volatile long scannedThrough;
    private volatile List<String> baselineIds = Collections.emptyList();

    private TikTokCommentReplyInbox(HostScanReport report, ClassLoader loader, Consumer<String> log) throws Exception {
        this.loader = loader;
        this.log = log == null ? ignored -> { } : log;
        fetch = HostTargets.method(report, "tiktok_notice_fetch", loader);
        Class<?> provider = HostTargets.type(report, "tiktok_notice_provider", loader);
        api = HostTargets.field(provider, "LIZ");
        Class<?> model = HostTargets.type(report, "tiktok_notice_request", loader);
        request = model.getConstructor(int.class, long.class, long.class, int.class, int.class);
        serialize = model.getMethod("toReqStr");
        setCursor = model.getMethod("setCursor", String.class);
        groupConfig = HostTargets.field(HostTargets.type(report, "tiktok_notice_groups", loader), "LIZJ");
        responseClass = HostTargets.type(report, "tiktok_notice_lists_response", loader);
        pageClass = HostTargets.type(report, "tiktok_notice_page", loader);
        noticeClass = HostTargets.type(report, "tiktok_notice_model", loader);
        payloadClass = HostTargets.type(report, "tiktok_notice_comment", loader);
        commentClass = HostTargets.type(report, "comment_model", loader);
        accountService = HostTargets.method(report, "media_account_service", loader);
        currentUser = HostTargets.method(report, "media_current_user", loader);
        templateClass = report.getResults().containsKey("tiktok_notice_template")
                ? HostTargets.type(report, "tiktok_notice_template", loader) : null;
        templateExtraClass = templateClass == null ? null : HostTargets.type(report, "tiktok_notice_template_extra", loader);
        threadLookup = report.getResults().containsKey("tiktok_comment_thread_roots")
                ? new TikTokCommentThreadLookup(report, loader) : null;
        if (!Future.class.isAssignableFrom(fetch.getReturnType()) || fetch.getParameterCount() != 2
                || fetch.getParameterTypes()[0] != String.class || fetch.getParameterTypes()[1] != int.class
                || api.getType() != fetch.getDeclaringClass() || groupConfig.getType() != int.class) {
            throw new IllegalStateException("TikTok notification read contract changed");
        }
    }

    static TikTokCommentReplyInbox install(XposedInterface framework, HostScanReport report,
                                            ClassLoader loader, Consumer<String> log,
                                            Runnable incomingSignal) throws Exception {
        TikTokCommentReplyInbox inbox = new TikTokCommentReplyInbox(report, loader, log);
        try {
            Method received = HostTargets.method(report, "tiktok_notice_response_received", loader);
            inbox.handle = framework.hook(received).setId("TokAi.CommentReply.TikTokNoticeResponse")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Object result = chain.proceed();
                        if (!inbox.closed) {
                            try { incomingSignal.run(); }
                            catch (RuntimeException unavailable) {
                                try { inbox.log.accept("TikTok notification scan signal unavailable"); }
                                catch (RuntimeException ignored) { }
                            }
                        }
                        return result;
                    });
            if (inbox.handle == null) throw new IllegalStateException("Empty TikTok notification response hook");
            return inbox;
        } catch (Throwable failure) {
            inbox.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    @Override public synchronized long baseline(long account, BooleanSupplier valid) throws Exception {
        Read read = read(account, 0, true, valid);
        baselineIds = new ArrayList<>(read.ids);
        scannedThrough = read.newest;
        return read.newest > 0 ? read.newest : System.currentTimeMillis() / 1000;
    }
    @Override public List<String> baselineIds() { return new ArrayList<>(baselineIds); }
    @Override public long scannedThrough() { return scannedThrough; }
    @Override public long watermarkCeiling() { return scannedThrough; }
    @Override public synchronized List<CommentReplyEvent> scan(long account, long since, BooleanSupplier valid) throws Exception {
        Read read = read(account, since, false, valid);
        scannedThrough = Math.max(since, Math.min(read.newest, read.watermarkCeiling));
        List<CommentReplyEvent> events = new ArrayList<>(read.events.values());
        events.sort(Comparator.comparingLong(event -> event.createdAt));
        return events;
    }

    private Read read(long account, long since, boolean baseline, BooleanSupplier valid) throws Exception {
        requireAccount(account, valid);
        int group = groupConfig.getInt(null);
        Read read = new Read();
        long max = 0, min = 0;
        String cursor = "";
        Set<String> cursors = new HashSet<>();
        int stalled = 0;
        long lookupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (int number = 0; number < MAX_PAGES; number++) {
            requireAccount(account, valid);
            // MultiNotice's last argument is @SerializedName("is_mark_read"). Never use the UI's value 1.
            Object model = request.newInstance(group, max, min, PAGE_SIZE, 0);
            if (!cursor.isEmpty()) setCursor.invoke(model, cursor);
            String json = (String) serialize.invoke(model); // Native toReqStr serializes the singleton LIST.
            Object rawFuture = fetch.invoke(api.get(null), json, 0);
            if (!(rawFuture instanceof Future<?>)) throw new IllegalStateException("TikTok notification future unavailable");
            Future<?> future = (Future<?>) rawFuture;
            Object response = await(future, valid);
            requireAccount(account, valid);
            if (!responseClass.isInstance(response) || number(response, "status_code") != 0) {
                throw new IllegalStateException("TikTok notification response failed");
            }
            Object pages = HostTargets.read(response, "notices");
            if (!(pages instanceof List<?>)) throw new IllegalStateException("TikTok notification pages unavailable");
            Object page = null;
            for (Object item : (List<?>) pages) {
                if (pageClass.isInstance(item) && number(item, "group") == group) {
                    if (page != null) throw new IllegalStateException("Ambiguous TikTok notification group");
                    page = item;
                }
            }
            if (page == null) throw new IllegalStateException("TikTok notification group missing");
            Object list = HostTargets.read(page, "items");
            if (!(list instanceof List<?>)) throw new IllegalStateException("TikTok notification list unavailable");
            int added = 0;
            boolean reached = false;
            for (Object notice : (List<?>) list) {
                if (!noticeClass.isInstance(notice)) throw new IllegalStateException("TikTok notification model changed");
                Object payload = payload(notice);
                long arrival = arrival(notice);
                read.newest = Math.max(read.newest, arrival);
                if (arrival > 0 && arrival < since) reached = true;
                if (payload == null) continue;
                Object incoming = HostTargets.read(payload, "comment");
                String cid = string(incoming, "cid");
                if (!cid.isEmpty() && read.ids.add(cid)) added++;
                if (baseline || arrival < since) continue;
                CommentReplyEvent event = extract(notice, account);
                if (event == null && threadLookup != null && arrival >= since
                        && !cid.isEmpty() && !Boolean.TRUE.equals(HostTargets.read(payload, "isAwemeInvalid"))) {
                    Object work = HostTargets.read(payload, "aweme");
                    if (work != null) event = threadLookup.resolve(noticeId(notice), incoming, work,
                            arrival, account, valid, lookupDeadline);
                    requireAccount(account, valid);
                }
                if (event != null) read.events.putIfAbsent(event.incomingId, event);
                else if (arrival > 0 && unresolvedDirectedReply(payload, account)) {
                    // Preserve discoverability of a sparse, explicitly directed reply instead of silently acknowledging it.
                    read.watermarkCeiling = Math.min(read.watermarkCeiling, arrival - 1);
                }
            }
            boolean more = Boolean.TRUE.equals(HostTargets.read(page, "hasMore"));
            if (!more) return read;
            long nextMax = number(page, "maxTime"), nextMin = number(page, "minTime");
            String nextCursor = string(page, "cursor");
            boolean progressed = cursors.add(nextMax + ":" + nextMin + ":" + nextCursor);
            stalled = progressed || added > 0 ? 0 : stalled + 1;
            if (stalled >= 3) throw new IllegalStateException("TikTok notification pagination stalled");
            max = nextMax; min = nextMin; cursor = nextCursor;
        }
        throw new IllegalStateException("TikTok notification pagination limit reached");
    }

    /** Only a directed notification with a bound work and a proven own parent becomes an event. */
    CommentReplyEvent extract(Object notice, long account) {
        if (!noticeClass.isInstance(notice)) return null;
        Object payload = payload(notice);
        if (payload == null || Boolean.TRUE.equals(HostTargets.read(payload, "isAwemeInvalid"))) return null;
        Object incoming = HostTargets.read(payload, "comment");
        Object work = HostTargets.read(payload, "aweme");
        if (!commentClass.isInstance(incoming) || work == null) return null;
        String aid = string(work, "aid"), incomingId = string(incoming, "cid"), me = Long.toString(account);
        String incomingAid = string(incoming, "awemeId");
        Object sender = HostTargets.read(incoming, "user");
        String senderId = string(sender, "uid"), recipient = string(incoming, "replyToUserId");
        if (aid.isEmpty() || incomingId.isEmpty() || senderId.isEmpty() || me.equals(senderId)
                || !incomingAid.isEmpty() && !aid.equals(incomingAid)
                || !recipient.isEmpty() && !me.equals(recipient)) return null;
        String root = id(string(incoming, "rootCommentId"));
        String direct = id(string(incoming, "replyToReplyId"));
        String parent = direct.isEmpty() ? root : direct;
        if (parent.isEmpty()) return null;
        Object original = null;
        for (String name : new String[]{"replyComment", "level1Comment"}) {
            Object candidate = HostTargets.read(payload, name);
            if (!commentClass.isInstance(candidate) || !parent.equals(string(candidate, "cid"))) continue;
            Object user = HostTargets.read(candidate, "user");
            String ownAid = string(candidate, "awemeId");
            if (!me.equals(string(user, "uid")) || !ownAid.isEmpty() && !aid.equals(ownAid)) return null;
            original = candidate;
        }
        if (original == null) return null;
        String text = string(incoming, "text").trim(), ownText = string(original, "text").trim();
        long created = arrival(notice);
        if (text.isEmpty() && !AutomationReplyMedia.hasCommentImages(incoming) || ownText.isEmpty() || created <= 0) return null;
        if (root.isEmpty()) root = id(string(original, "rootCommentId"));
        if (root.isEmpty()) root = parent;
        return new CommentReplyEvent(noticeId(notice), aid, incomingId, root, senderId,
                string(sender, "nickname"), text, ownText, created, work, incoming, true);
    }

    /** General template notices carry native Comment/Aweme objects in NoticeExtraData. */
    private Object payload(Object notice) {
        Object legacy = HostTargets.read(notice, "commentNotice");
        if (payloadClass.isInstance(legacy)) return legacy;
        Object template = HostTargets.read(notice, "templateNotice");
        if (templateClass == null || !templateClass.isInstance(template)) return null;
        Object extra = HostTargets.read(template, "extraData");
        return templateExtraClass.isInstance(extra) && commentClass.isInstance(HostTargets.read(extra, "comment")) ? extra : null;
    }
    private long arrival(Object notice) {
        long value = seconds(number(notice, "createTime"));
        if (value > 0) return value;
        return seconds(number(HostTargets.read(notice, "templateNotice"), "createTime"));
    }
    private String noticeId(Object notice) {
        String value = string(notice, "nid");
        if (!value.isEmpty()) return value;
        Object template = HostTargets.read(notice, "templateNotice");
        value = string(template, "nidStr");
        if (!value.isEmpty()) return value;
        Object raw = HostTargets.read(template, "nid");
        return raw instanceof Number ? raw.toString() : "";
    }

    private void requireAccount(long account, BooleanSupplier valid) throws ReflectiveOperationException {
        if (closed || !valid.getAsBoolean()) throw new CancellationException();
        Object service = accountService.invoke(null);
        Object user = currentUser.invoke(service);
        Object uid = HostTargets.invoke(user, "getUid");
        if (account <= 0 || !Long.toString(account).equals(uid)
                || !Boolean.TRUE.equals(HostTargets.invoke(service, "isLogin"))) throw new CancellationException();
    }
    private boolean unresolvedDirectedReply(Object payload, long account) {
        if (Boolean.TRUE.equals(HostTargets.read(payload, "isAwemeInvalid"))) return false;
        Object incoming = HostTargets.read(payload, "comment");
        if (!commentClass.isInstance(incoming) || !Long.toString(account).equals(string(incoming, "replyToUserId"))) return false;
        String work = string(HostTargets.read(payload, "aweme"), "aid"), repeated = string(incoming, "awemeId");
        if (work.isEmpty() || !repeated.isEmpty() && !work.equals(repeated)) return false;
        String sender = string(HostTargets.read(incoming, "user"), "uid");
        if (sender.isEmpty() || Long.toString(account).equals(sender)) return false;
        String root = id(string(incoming, "rootCommentId")), direct = id(string(incoming, "replyToReplyId"));
        String parent = direct.isEmpty() ? root : direct;
        if (parent.isEmpty()) return true;
        for (String field : new String[]{"replyComment", "level1Comment"}) {
            Object original = HostTargets.read(payload, field);
            if (commentClass.isInstance(original) && parent.equals(string(original, "cid"))) return false;
        }
        return true;
    }
    private Object await(Future<?> future, BooleanSupplier valid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            while (!closed && valid.getAsBoolean()) {
                if (System.nanoTime() >= deadline) throw new TimeoutException("TikTok notification request timed out");
                try { return future.get(200, TimeUnit.MILLISECONDS); }
                catch (TimeoutException pending) { }
            }
            throw new CancellationException();
        } finally { if (!future.isDone()) future.cancel(true); }
    }
    private static long number(Object model, String field) {
        Object value = HostTargets.read(model, field);
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }
    private static String string(Object model, String field) {
        Object value = HostTargets.read(model, field);
        return value instanceof String ? (String) value : "";
    }
    private static String id(String value) { return "0".equals(value) ? "" : value; }
    private static long seconds(long value) { return value >= 10_000_000_000L ? value / 1000 : value; }
    @Override public void close() {
        closed = true;
        if (threadLookup != null) threadLookup.close();
        if (handle != null) { try { handle.unhook(); } catch (Throwable ignored) { } handle = null; }
    }
    private static final class Read {
        final Set<String> ids = new LinkedHashSet<>();
        final Map<String, CommentReplyEvent> events = new LinkedHashMap<>();
        long newest;
        long watermarkCeiling = Long.MAX_VALUE;
    }
}
