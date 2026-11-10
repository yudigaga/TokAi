package com.tokai.module.feature;

import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TikTokCommentThreadLookupTest {
    private TikTokCommentThreadLookup lookup;
    private ApiImpl api;
    @Before public void setUp() throws Exception {
        api = new ApiImpl(); Provider.LIZIZ = new Provider(); Provider.LIZIZ.LIZ = api;
        lookup = new TikTokCommentThreadLookup(report(), getClass().getClassLoader());
    }

    @Test public void sparseDirectedNotificationRequiresExactServerRootBeforeCopyingItsParent() throws Exception {
        Comment hint = incoming("received", "200", "");
        Comment root = comment("root", "100", "own body");
        api.roots.items = Collections.singletonList(root);
        Comment originalIncoming = incoming("received", "200", "");
        List<String> images = Collections.singletonList("media-identity"); originalIncoming.imageList = images;
        api.replies.items = Collections.singletonList(originalIncoming); api.replies.rootCid = "root";
        CommentReplyEvent event = lookup.resolve("notice", hint, new Work(), 300, 100, () -> true);
        assertNotNull(event);
        assertEquals("root", event.rootId);
        Comment copy = (Comment) event.targetComment;
        assertNotSame(originalIncoming, copy);
        assertEquals("root", copy.rootCommentId);
        assertEquals(2, copy.commentType);
        assertSame(images, copy.imageList);
        assertEquals("", originalIncoming.rootCommentId);
        assertEquals(0, originalIncoming.commentType);
        assertTrue(api.disposed);
        assertEquals(1, api.rootCalls);
        assertEquals(1, api.replyCalls);
    }

    @Test public void wrongSenderOrMissingServerRootCannotAuthorizeSparseParentRestoration() throws Exception {
        Comment hint = incoming("received", "200", "");
        api.roots.items = Collections.singletonList(comment("root", "100", "own body"));
        api.replies.items = Collections.singletonList(incoming("received", "201", ""));
        api.replies.rootCid = "root";
        assertNull(lookup.resolve("notice", hint, new Work(), 300, 100, () -> true));
        api.replies.items = Collections.singletonList(incoming("received", "200", ""));
        api.replies.rootCid = "";
        assertNull(lookup.resolve("notice", hint, new Work(), 300, 100, () -> true));
    }

    @Test public void nativeReplyThreadCanProveOwnNestedParentUnderAnotherAuthorsRoot() throws Exception {
        Comment hint = incoming("received", "200", "root"); hint.replyToReplyId = "my-nested";
        Comment foreignRoot = comment("root", "500", "outer body");
        Comment ownNested = comment("my-nested", "100", "my nested body"); ownNested.rootCommentId = "root";
        foreignRoot.replyComments = Collections.singletonList(ownNested);
        api.roots.items = Collections.singletonList(foreignRoot);
        Comment returned = incoming("received", "200", "root"); returned.replyToReplyId = "my-nested";
        api.replies.items = Collections.singletonList(returned); api.replies.rootCid = "root";
        CommentReplyEvent event = lookup.resolve("notice", hint, new Work(), 300, 100, () -> true);
        assertNotNull(event);
        assertEquals("my nested body", event.originalText);
        assertSame(returned, event.targetComment);
        assertEquals("root", event.rootId);
    }

    @Test public void crossWorkReturnedCommentAndCancelledLookupNeverBecomeEvents() throws Exception {
        api.roots.items = Collections.singletonList(comment("root", "100", "own body"));
        Comment otherWork = incoming("received", "200", "root"); otherWork.awemeId = "other-work";
        api.replies.items = Collections.singletonList(otherWork); api.replies.rootCid = "root";
        assertNull(lookup.resolve("notice", incoming("received", "200", "root"), new Work(), 300, 100, () -> true));
        int calls = api.rootCalls;
        assertThrows(CancellationException.class, () -> lookup.resolve("notice", incoming("received", "200", "root"),
                new Work(), 300, 100, () -> false));
        assertEquals(calls, api.rootCalls);
    }

    static HostScanReport report() throws Exception {
        List<RuleScanResult> rows = new ArrayList<>();
        add(rows, "tiktok_comment_thread_roots", Api.class.getMethod("roots", String.class, long.class, int.class, String.class));
        add(rows, "tiktok_comment_thread_replies", Api.class.getMethod("replies", String.class, String.class, long.class,
                int.class, String.class, Continuation.class));
        add(rows, "tiktok_comment_observable_subscribe", Observable.class.getMethod("subscribe", Observer.class));
        add(rows, "tiktok_comment_clone", Comment.class.getMethod("clone"));
        for (Object[] pair : new Object[][]{{"tiktok_comment_thread_provider", Provider.class},
                {"tiktok_comment_empty_context", Empty.class}, {"tiktok_comment_thread_response", Response.class},
                {"comment_model", Comment.class}}) {
            rows.add(new RuleScanResult((String) pair[0], "comment_reply", Collections.singletonList(
                    new HostCandidate("fixture.apk", "classes.dex", HostTargets.descriptor((Class<?>) pair[1]), null, null))));
        }
        return new HostScanReport("fixture", rows, false);
    }
    private static void add(List<RuleScanResult> rows, String id, Method method) {
        rows.add(new RuleScanResult(id, "comment_reply", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }
    private static Comment comment(String cid, String uid, String body) {
        Comment comment = new Comment(); comment.cid = cid; comment.user = new User(uid); comment.text = body; return comment;
    }
    private static Comment incoming(String cid, String uid, String root) {
        Comment comment = comment(cid, uid, "received body"); comment.rootCommentId = root; comment.replyToUserId = "100"; return comment;
    }
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface Query { String value(); }
    public interface Api {
        Observable roots(@Query("aweme_id") String aid, @Query("cursor") long cursor,
                         @Query("count") int count, @Query("insert_ids") String ids);
        Object replies(@Query("comment_id") String root, @Query("item_id") String aid,
                       @Query("cursor") long cursor, @Query("count") int count,
                       @Query("insert_ids") String ids, Continuation continuation);
    }
    public static final class ApiImpl implements Api {
        final Response roots = new Response(), replies = new Response();
        int rootCalls, replyCalls; boolean disposed;
        public Observable roots(String aid, long cursor, int count, String ids) {
            rootCalls++;
            assertEquals("work", aid); assertTrue(ids.contains("received"));
            return new Observable(roots, () -> disposed = true);
        }
        public Object replies(String root, String aid, long cursor, int count, String ids, Continuation continuation) {
            replyCalls++; assertSame(Empty.INSTANCE, continuation.getContext());
            continuation.resumeWith(replies); return new Object();
        }
    }
    public static final class Provider { public static Provider LIZIZ; public Api LIZ; }
    public static final class Empty { public static final Object INSTANCE = new Object(); }
    public interface Continuation { Object getContext(); void resumeWith(Object result); }
    public interface Disposable { void dispose(); }
    public interface Observer {
        void onSubscribe(Disposable disposable); void onNext(Object value); void onError(Throwable error); void onComplete();
    }
    public static final class Observable {
        final Response response; final Disposable disposable;
        Observable(Response response, Disposable disposable) { this.response = response; this.disposable = disposable; }
        public void subscribe(Observer observer) { observer.onSubscribe(disposable); observer.onNext(response); observer.onComplete(); }
    }
    public static final class Response {
        public int status_code; public List<Comment> items = Collections.emptyList();
        public boolean isHasMore; public long cursor; public String rootCid = "";
    }
    public static final class Work { public String aid = "work"; }
    public static final class User { public String uid, nickname = "name"; User(String uid) { this.uid = uid; } }
    public static final class Comment implements Cloneable {
        public String cid, text, awemeId = "work", rootCommentId = "", replyToReplyId = "", replyToUserId = "";
        public User user; public List<?> imageList; public List<Comment> replyComments; public int commentType;
        public void setCommentType(int value) { commentType = value; }
        @Override public Comment clone() { try { return (Comment) super.clone(); } catch (CloneNotSupportedException impossible) { throw new AssertionError(impossible); } }
    }
}
