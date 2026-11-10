package com.tokai.module.feature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TikTokRelationshipFetcherTest {
    @Test public void nativePageTokenReachesTheSecondPositionalRequestWithoutPublishingPartialMembership() throws Exception {
        List<Object[]> nativeQueries = new ArrayList<>();
        AtomicInteger pages = new AtomicInteger();
        Map<String,String> users = FollowListPager.load(FollowListPager.Kind.FOLLOWING, "42", "sec42", (kind, query) -> {
            nativeQueries.add(TikTokRelationshipFetcher.arguments(kind, query));
            NativePage page = new NativePage();
            page.isHasMore = pages.getAndIncrement() == 0;
            page.minTime = 90; page.maxTime = 150; page.offset = 20;
            page.nextPageToken = "native-page-token";
            page.items = Arrays.asList(new NativeUser(page.isHasMore ? "10" : "20", 1, 0),
                    new NativeUser("99", 0, 0));
            return TikTokRelationshipFetcher.response(page);
        }, () -> true);
        assertEquals(2, users.size());
        assertFalse(users.containsKey("99"));
        assertEquals("42", nativeQueries.get(0)[0]);
        assertEquals("sec42", nativeQueries.get(0)[1]);
        assertEquals(11, nativeQueries.get(0).length);
        assertEquals(90L, nativeQueries.get(1)[2]);
        assertEquals(20, nativeQueries.get(1)[4]);
        assertEquals("native-page-token", nativeQueries.get(1)[7]);
    }

    @Test public void nativeErrorAndUnknownHasMoreNeverBecomeAnEmptySuccessfulList() {
        NativePage page = new NativePage(); page.status_code = 123;
        page.items = Arrays.asList(new NativeUser("10", 0, 1));
        assertThrows(IllegalStateException.class, () -> FollowListPager.load(FollowListPager.Kind.FOLLOWERS,
                "42", "sec42", (kind, query) -> TikTokRelationshipFetcher.response(page), () -> true));
        assertThrows(IllegalStateException.class, () -> TikTokRelationshipFetcher.response(new Object()));
    }

    public static final class NativePage {
        public int status_code, error_code, offset, vcdCount;
        public boolean isHasMore;
        public long minTime, maxTime;
        public String nextPageToken = "";
        public List<NativeUser> items;
    }
    public static final class NativeUser {
        public String uid, nickname = "native-user";
        public int followStatus, followerStatus;
        NativeUser(String id, int following, int follower) { uid = id; followStatus = following; followerStatus = follower; }
    }
}
