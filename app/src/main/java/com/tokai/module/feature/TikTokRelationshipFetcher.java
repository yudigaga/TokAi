package com.tokai.module.feature;

import com.tokai.module.scan.HostScanReport;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Calls the host's existing relationship service and preserves the full-page policy. */
final class TikTokRelationshipFetcher implements FollowListPager.Fetcher {
    private final Class<?> provider;
    private final Method following, followers, timeout, first;

    TikTokRelationshipFetcher(ClassLoader loader, HostScanReport report) throws Exception {
        provider = HostTargets.type(report, "tiktok_relation_provider", loader);
        following = HostTargets.method(report, "tiktok_relation_following", loader);
        followers = HostTargets.method(report, "tiktok_relation_followers", loader);
        timeout = HostTargets.method(report, "tiktok_relation_timeout", loader);
        first = HostTargets.method(report, "tiktok_relation_blocking_first", loader);
        requireEndpoint(following, "/aweme/v1/user/following/list/");
        requireEndpoint(followers, "/aweme/v1/user/follower/list/");
    }

    @Override public Object fetch(FollowListPager.Kind kind, Map<String,String> query) throws Exception {
        Method request = kind == FollowListPager.Kind.FOLLOWING ? following : followers;
        Object service = provider.getField("LIZJ").get(null);
        if (!request.getDeclaringClass().isInstance(service)) throw new IllegalStateException("TikTok relationship service unavailable");
        Object result = request.invoke(service, arguments(kind, query));
        Object bounded = timeout.invoke(result, 20L, TimeUnit.SECONDS);
        return response(first.invoke(bounded));
    }

    static Object[] arguments(FollowListPager.Kind kind, Map<String,String> query) {
        Object[] common = new Object[]{query.get("user_id"), query.get("sec_user_id"),
                Long.parseLong(query.get("max_time")), Integer.parseInt(query.get("count")),
                Integer.parseInt(query.get("offset")), Integer.parseInt(query.get("source_type")),
                Integer.parseInt(query.get("address_book_access")), query.getOrDefault("store_page", "")};
        Object[] result = java.util.Arrays.copyOf(common, kind == FollowListPager.Kind.FOLLOWING ? 11 : 9);
        if (kind == FollowListPager.Kind.FOLLOWING) { result[8] = 0; result[9] = ""; result[10] = ""; }
        else result[8] = "";
        return result;
    }

    static Page response(Object raw) {
        Object items = HostTargets.read(raw, "items");
        if (!(items instanceof List<?>)) throw new IllegalStateException("TikTok relationship response has no list");
        Page result = new Page();
        result.items = (List<?>) items;
        result.statusCode = number(raw, "status_code").intValue();
        result.errorCode = number(raw, "error_code").intValue();
        Object more = HostTargets.read(raw, "hasMore");
        if (!(more instanceof Boolean)) more = HostTargets.read(raw, "isHasMore");
        if (!(more instanceof Boolean)) throw new IllegalStateException("TikTok relationship hasMore changed");
        result.hasMore = (Boolean) more;
        result.minTime = number(raw, "minTime").longValue();
        result.maxTime = number(raw, "maxTime").longValue();
        result.offset = number(raw, "offset").intValue();
        Object token = HostTargets.read(raw, "nextPageToken");
        if (token != null && !(token instanceof String)) throw new IllegalStateException("TikTok relationship page token changed");
        result.storePage = token == null ? "" : (String) token;
        Object count = HostTargets.read(raw, "vcdCount");
        result.vcdCount = count instanceof Number ? ((Number) count).intValue() : 0;
        return result;
    }

    private static Number number(Object raw, String field) {
        Object value = HostTargets.read(raw, field);
        if (!(value instanceof Number)) throw new IllegalStateException("TikTok relationship field changed: " + field);
        return (Number) value;
    }

    private static void requireEndpoint(Method method, String expected) throws Exception {
        for (Annotation annotation : method.getAnnotations()) {
            if ("X.04yA".equals(annotation.annotationType().getName())
                    && expected.equals(annotation.annotationType().getMethod("value").invoke(annotation))) return;
        }
        throw new IllegalStateException("TikTok relationship endpoint changed");
    }

    /** Field aliases consumed by the unchanged FollowListPager, including its opaque cursor token. */
    public static final class Page {
        public int statusCode, errorCode, offset, vcdCount;
        public boolean hasMore;
        public long minTime, maxTime;
        public String storePage;
        public List<?> items;
    }
}
