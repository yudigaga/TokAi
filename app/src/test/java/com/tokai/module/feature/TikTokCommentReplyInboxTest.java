package com.tokai.module.feature;

import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TikTokCommentReplyInboxTest {
    private TikTokCommentReplyInbox inbox;
    @Before public void setUp() throws Exception {
        Provider.LIZ = new ApiImpl();
        Accounts.service = new Account();
        Constructor<TikTokCommentReplyInbox> constructor = TikTokCommentReplyInbox.class.getDeclaredConstructor(
                HostScanReport.class, ClassLoader.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        inbox = constructor.newInstance(report(), getClass().getClassLoader(), (java.util.function.Consumer<String>) ignored -> { });
    }

    @Test public void onlyDirectedNotificationsWithProvenOwnParentsBecomeReplyEvents() {
        Notice valid = notice("new", 300);
        CommentReplyEvent event = inbox.extract(valid, 100);
        assertNotNull(event);
        assertSame(valid.commentNotice.comment, event.targetComment);
        assertSame(valid.commentNotice.aweme, event.work);
        assertEquals("root", event.rootId);
        assertEquals("own body", event.originalText);
        assertNull("An ordinary comment UI/model is never an incoming notification", inbox.extract(valid.commentNotice.comment, 100));
        valid.commentNotice.replyComment.user.uid = "other";
        assertNull(inbox.extract(valid, 100));
        valid = notice("new", 300); valid.commentNotice.comment.replyToUserId = "other";
        assertNull(inbox.extract(valid, 100));
        valid = notice("new", 300); valid.commentNotice.comment.user.uid = "100";
        assertNull(inbox.extract(valid, 100));
        valid = notice("new", 300); valid.commentNotice.aweme.aid = "other-work";
        assertNull(inbox.extract(valid, 100));
        valid = notice("new", 300); valid.commentNotice.comment.rootCommentId = "";
        assertNull(inbox.extract(valid, 100));
    }

    @Test public void baselineIsReadOnlyAndScanKeepsCapturedNotificationTargets() throws Exception {
        ApiImpl api = (ApiImpl) Provider.LIZ;
        api.next = response(false, notice("old", 200));
        assertEquals(200, inbox.baseline(100, () -> true));
        assertEquals(Collections.singletonList("old"), inbox.baselineIds());
        assertEquals(0, Request.last.markRead);
        assertTrue(api.json.contains("\"is_mark_read\":0"));
        Notice newer = notice("new", 201);
        api.next = response(false, notice("old", 200), newer);
        List<CommentReplyEvent> events = inbox.scan(100, 200, () -> true);
        assertEquals(2, events.size()); // The parent ledger deduplicates equal-watermark baseline IDs.
        assertSame(newer.commentNotice.comment, events.get(1).targetComment);
        assertEquals(201, inbox.scannedThrough());
        assertEquals(0, Request.last.markRead);
    }

    @Test public void stalledPaginationCannotCreateAPartialBaselineOrAdvanceWatermark() throws Exception {
        ApiImpl api = (ApiImpl) Provider.LIZ;
        api.next = response(true, notice("repeated", 200));
        assertThrows(IllegalStateException.class, () -> inbox.baseline(100, () -> true));
        assertTrue(inbox.baselineIds().isEmpty());
        assertEquals(0, inbox.scannedThrough());
        assertEquals(4, api.calls);
        assertEquals(0, Request.last.markRead);
    }

    @Test public void explicitlyDirectedSparseReplyKeepsItsWatermarkDiscoverable() throws Exception {
        ApiImpl api = (ApiImpl) Provider.LIZ;
        Notice unresolved = notice("sparse", 220);
        unresolved.commentNotice.replyComment = null;
        api.next = response(false, notice("complete", 210), unresolved);
        assertEquals(1, inbox.scan(100, 200, () -> true).size());
        assertEquals(219, inbox.scannedThrough());
    }

    @Test public void templateNoticeUsesOnlyNativeExtraDataAndResolvesItsExactOwnThread() throws Exception {
        TikTokCommentThreadLookupTest.ApiImpl thread = new TikTokCommentThreadLookupTest.ApiImpl();
        TikTokCommentThreadLookupTest.Provider.LIZIZ = new TikTokCommentThreadLookupTest.Provider();
        TikTokCommentThreadLookupTest.Provider.LIZIZ.LIZ = thread;
        TikTokCommentThreadLookupTest.Comment root = new TikTokCommentThreadLookupTest.Comment();
        root.cid = "root"; root.text = "own body"; root.user = new TikTokCommentThreadLookupTest.User("100");
        thread.roots.items = Collections.singletonList(root);
        TikTokCommentThreadLookupTest.Comment sparse = new TikTokCommentThreadLookupTest.Comment();
        sparse.cid = "received"; sparse.text = "received body";
        sparse.user = new TikTokCommentThreadLookupTest.User("200"); sparse.replyToUserId = "100";
        thread.replies.items = Collections.singletonList(sparse); thread.replies.rootCid = "root";
        List<RuleScanResult> rules = new ArrayList<>(report().getRuleResults());
        rules.addAll(TikTokCommentThreadLookupTest.report().getRuleResults());
        Constructor<TikTokCommentReplyInbox> constructor = TikTokCommentReplyInbox.class.getDeclaredConstructor(
                HostScanReport.class, ClassLoader.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        TikTokCommentReplyInbox templateInbox = constructor.newInstance(new HostScanReport("fixture", rules, false),
                getClass().getClassLoader(), (java.util.function.Consumer<String>) ignored -> { });
        try {
            Notice notice = new Notice(); notice.templateNotice = new Template();
            notice.templateNotice.createTime = 301L; notice.templateNotice.nidStr = "template-native";
            notice.templateNotice.extraData = new Extra(); notice.templateNotice.extraData.comment = sparse;
            Object work = new TikTokCommentThreadLookupTest.Work(); notice.templateNotice.extraData.aweme = work;
            ApiImpl api = (ApiImpl) Provider.LIZ; api.next = response(false, notice);
            List<CommentReplyEvent> events = templateInbox.scan(100, 200, () -> true);
            assertEquals(1, events.size());
            assertEquals("template-native", events.get(0).noticeId);
            assertEquals("root", events.get(0).rootId);
            assertSame(work, events.get(0).work);
            assertNotSame(sparse, events.get(0).targetComment);
            assertEquals("", sparse.rootCommentId);
            assertEquals(301, templateInbox.scannedThrough());
            assertEquals(0, Request.last.markRead);
        } finally { templateInbox.close(); }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>();
        add(rows, "tiktok_notice_fetch", Api.class.getMethod("fetch", String.class, int.class));
        add(rows, "media_account_service", Accounts.class.getMethod("userService"));
        add(rows, "media_current_user", Account.class.getMethod("getCurUser"));
        for (Object[] type : new Object[][]{{"tiktok_notice_provider", Provider.class}, {"tiktok_notice_request", Request.class},
                {"tiktok_notice_groups", Groups.class}, {"tiktok_notice_lists_response", Response.class},
                {"tiktok_notice_page", Page.class}, {"tiktok_notice_model", Notice.class},
                {"tiktok_notice_comment", Payload.class}, {"comment_model", Comment.class},
                {"tiktok_notice_template", Template.class}, {"tiktok_notice_template_extra", Extra.class}}) {
            rows.add(new RuleScanResult((String) type[0], "comment_reply", Collections.singletonList(
                    new HostCandidate("fixture.apk", "classes.dex", HostTargets.descriptor((Class<?>) type[1]), null, null))));
        }
        return new HostScanReport("fixture", rows, false);
    }
    private static void add(List<RuleScanResult> rows, String id, Method method) {
        rows.add(new RuleScanResult(id, "comment_reply", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                method.getName(), HostTargets.descriptor(method)))));
    }
    private static Response response(boolean more, Notice... notices) {
        Response response = new Response(); Page page = new Page();
        page.items = Arrays.asList(notices); page.hasMore = more; page.maxTime = 200; page.minTime = 100;
        response.notices = Collections.singletonList(page); return response;
    }
    private static Notice notice(String cid, long time) {
        Notice notice = new Notice(); notice.nid = "notice-" + cid; notice.createTime = time;
        Payload payload = notice.commentNotice = new Payload();
        payload.aweme = new Work(); payload.comment = new Comment();
        payload.comment.cid = cid; payload.comment.text = "received body"; payload.comment.rootCommentId = "root";
        payload.comment.replyToUserId = "100"; payload.comment.user = new User("200");
        payload.replyComment = new Comment(); payload.replyComment.cid = "root";
        payload.replyComment.text = "own body"; payload.replyComment.user = new User("100");
        return notice;
    }
    public interface Api { Future<Response> fetch(String json, int scenario); }
    public static final class ApiImpl implements Api {
        Response next; String json; int calls;
        public Future<Response> fetch(String json, int scenario) { this.json = json; calls++; return CompletableFuture.completedFuture(next); }
    }
    public static final class Provider { public static Api LIZ; }
    public static final class Groups { public static int LIZJ = 7; }
    public static final class Request {
        static Request last; final int markRead;
        public Request(int group, long max, long min, int count, int markRead) { this.markRead = markRead; last = this; }
        public String toReqStr() { return "[{\"is_mark_read\":" + markRead + "}]"; }
        public void setCursor(String cursor) { }
    }
    public static final class Response { public int status_code; public List<Page> notices; }
    public static final class Page {
        public int group = 7; public List<Notice> items; public boolean hasMore;
        public long maxTime, minTime; public String cursor = "same";
    }
    public static final class Notice { public String nid; public long createTime; public Payload commentNotice; public Template templateNotice; }
    public static final class Template { public Long createTime; public String nidStr; public Extra extraData; }
    public static final class Extra { public boolean isAwemeInvalid; public Object comment, aweme; }
    public static final class Payload {
        public boolean isAwemeInvalid; public Work aweme;
        public Comment comment, replyComment, level1Comment;
    }
    public static final class Comment {
        public String cid, text, awemeId = "work", rootCommentId = "", replyToReplyId = "", replyToUserId = "";
        public User user;
    }
    public static final class Work { public String aid = "work"; }
    public static final class User {
        public String uid, nickname = "author"; User(String uid) { this.uid = uid; }
        public String getUid() { return uid; }
    }
    public static final class Account {
        final User user = new User("100"); public User getCurUser() { return user; }
        public boolean isLogin() { return true; }
    }
    public static final class Accounts { static Account service; public static Account userService() { return service; } }
}
