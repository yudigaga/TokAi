package com.tokai.module.feature;

import com.tokai.module.config.FeatureSettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Replays the TikTok-only response/model differences without claiming a phone callback. */
public final class TikTokFeedAdFilterHookTest {
    public static final class Api {
        public Object postFetchRecommendFeedV2Call() { return null; }
        public static Page materialized(Page page) { return page; }
        public static Page legacy(Page page) { return page; }
    }
    public static final class Raw { public String LIZ; Raw(String url) { LIZ = url; } }
    public static final class Wrapper { public static Object success(Object body, Raw raw) { return body; } }
    public static final class Statistics { public long diggCount = 500; }
    public static final class Video { public int videoLength = 60000; }
    public static final class CardInsertInfo {
        int type;
        CardInsertInfo(int type) { this.type = type; }
        public int getCardType() { return type; }
    }
    public static final class NativeCards {
        public static boolean isCard(Aweme item) { return item.type == 104 || item.type == 105; }
    }
    public static final class WrongClassifier {
        public static boolean isCard(Object item) { return true; }
    }
    public static final class WrongCardModel { public int getCardType() { return 120; } }
    public static final class Anchor { public String schema = "aweme://mini_game/play"; }
    public static final class Aweme {
        public Object anchorInfo;
        public String desc = "ordinary";
        public Statistics statistics = new Statistics();
        public Video _video = new Video();
        boolean ad; Object live; Object photo; int type; CardInsertInfo card;
        public boolean isAd() { return ad; }
        public Object getRoomFeedCellStruct() { return live; }
        public Object getPhotoModeImageInfo() { return photo; }
        public int getAwemeType() { return type != 0 ? type : photo == null ? 0 : 150; }
        public Video getVideo() { return _video; }
        public CardInsertInfo getCardInsertInfo() { return card; }
    }
    public static final class Page {
        public List<Aweme> items;
        public List<Aweme> preloadAds;
        Page(Aweme... items) { this.items = Arrays.asList(items); preloadAds = this.items; }
    }

    @Test public void rawUrlFieldKeepsFilteringConfinedToMainFeed() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(true, false, false, false, false))) {
            Aweme keep = new Aweme(), ad = new Aweme(); ad.ad = true;
            Page page = new Page(keep, ad); List<Aweme> original = page.items;
            target.get().intercept(chain(page, "/aweme/v1/aweme/post/", false));
            assertSame(original, page.items);
            target.get().intercept(chain(page, "/aweme/v2/feed/", false));
            assertEquals(Collections.singletonList(keep), page.items);
            assertEquals(Collections.singletonList(keep), page.preloadAds);
        }
    }

    @Test public void nativeRoomAndPhotoModelsUseTheirPresenceInsteadOfDouyinBooleanMethods() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(false, true, true, false, false))) {
            Aweme keep = new Aweme(), live = new Aweme(), photo = new Aweme();
            live.live = new Object(); photo.photo = new Object();
            Page page = new Page(live, keep, photo);
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertEquals(Collections.singletonList(keep), page.items);
        }
    }

    @Test public void durationAndLikesReadTikTokVideoAndStatisticsFields() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(false, false, false, true, true))) {
            Aweme keep = new Aweme(), shortVideo = new Aweme(), unpopular = new Aweme();
            shortVideo._video.videoLength = 5000; unpopular.statistics.diggCount = 5;
            Page page = new Page(shortVideo, keep, unpopular);
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertEquals(Collections.singletonList(keep), page.items);
        }
    }

    @Test public void rejectedNativeResponseRestoresEveryOriginalList() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(true, false, false, false, false))) {
            Aweme keep = new Aweme(), ad = new Aweme(); ad.ad = true;
            Page page = new Page(keep, ad); List<Aweme> original = page.items;
            try { target.get().intercept(chain(page, "/aweme/v1/feed/", true)); fail(); }
            catch (IllegalStateException expected) { assertEquals("host rejected", expected.getMessage()); }
            assertSame(original, page.items); assertSame(original, page.preloadAds);
        }
    }

    @Test public void finalFeedModelHookFiltersTheProtobufConversionResult() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = FeedAdFilterHook.install(framework(target), report(true),
                getClass().getClassLoader(), settings(true, false, false, false, false), ignored -> { })) {
            Aweme keep = new Aweme(), ad = new Aweme(); ad.ad = true;
            Page converted = new Page(ad, keep);
            assertSame(converted, target.get().intercept(chain(converted, "/not-a-network-wrapper/", false)));
            assertEquals(Collections.singletonList(keep), converted.items);
        }
    }

    @Test public void disabledFinalModelHookForwardsTheSameModelAndLists() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = FeedAdFilterHook.install(framework(target), report(true),
                getClass().getClassLoader(), settings(true, false, false, false, false), ignored -> { })) {
            hook.setEnabled(false);
            Aweme keep = new Aweme(), ad = new Aweme(); ad.ad = true;
            Page converted = new Page(ad, keep); List<Aweme> original = converted.items;
            assertSame(converted, target.get().intercept(chain(converted, "/not-a-network-wrapper/", false)));
            assertSame(original, converted.items); assertSame(original, converted.preloadAds);
        }
    }

    @Test public void finalModelHostTransportFailurePreservesThrowableIdentityAndDoesNotFilter() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = FeedAdFilterHook.install(framework(target), report(true),
                getClass().getClassLoader(), settings(true, false, false, false, false), ignored -> { })) {
            java.io.IOException original = new java.io.IOException("private host URL and message");
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            XposedInterface.Chain failing = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                        if ("proceed".equals(method.getName())) { calls.incrementAndGet(); throw original; }
                        throw new AssertionError("Host failure must precede model inspection");
                    });
            try { target.get().intercept(failing); fail(); }
            catch (Throwable actual) { assertSame(original, actual); }
            assertEquals(1, calls.get());
            assertEquals(0, hook.getFilteredPages());
        }
    }

    @Test public void nativeCardTypesAreFilteredWithoutTreating145AsATikTokCard() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, cardSettings())) {
            Aweme ordinary = item(0), card = item(104), secondCard = item(105), oldDouyinType = item(145);
            Page page = new Page(card, ordinary, secondCard, oldDouyinType);
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertEquals(Arrays.asList(ordinary, oldDouyinType), page.items);
            assertEquals(page.items, page.preloadAds);
        }
    }

    @Test public void disabledCardAndGameOptionsPreserveNativeCardsAndOriginalLists() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(false, false, false, false, false))) {
            Aweme game = item(104); game.card = new CardInsertInfo(120);
            Page page = new Page(game, item(105)); List<Aweme> original = page.items;
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertSame(original, page.items);
            assertSame(original, page.preloadAds);
        }
    }

    @Test public void nativeGameCardRequiresBothAweme104AndCardType120() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(false, false, false, false, false))) {
            hook.setOptions(new LocalOptions(Collections.singletonMap("hide_mini_games", true)));
            Aweme game = item(104); game.card = new CardInsertInfo(120);
            Aweme neighbor = item(104); neighbor.card = new CardInsertInfo(119);
            Aweme wrongAweme = item(105); wrongAweme.card = new CardInsertInfo(120);
            Aweme missingInfo = item(104), ordinary = item(0);
            Page page = new Page(game, neighbor, wrongAweme, missingInfo, ordinary);
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertEquals(Arrays.asList(neighbor, wrongAweme, missingInfo, ordinary), page.items);
        }
    }

    @Test public void legacyGameAnchorStillMatchesAlongsideNativeGameCard() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(false, false, false, false, false))) {
            hook.setOptions(new LocalOptions(Collections.singletonMap("hide_mini_games", true)));
            Aweme ordinary = item(0), anchored = item(0); anchored.anchorInfo = new Anchor();
            Aweme game = item(104); game.card = new CardInsertInfo(120);
            Page page = new Page(anchored, game, ordinary);
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertEquals(Collections.singletonList(ordinary), page.items);
        }
    }

    @Test public void douyinProductAndHotspotTypesDoNotClassifyTikTokCards() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(false, false, false, false, false))) {
            java.util.Map<String, Object> options = new java.util.HashMap<>();
            options.put("hide_product_cards", true); options.put("hide_hotspot_rank_cards", true);
            hook.setOptions(new LocalOptions(options));
            Page page = new Page(item(104), item(112), item(9001), item(0));
            List<Aweme> original = page.items;
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertSame(original, page.items);
        }
    }

    @Test public void longVideoFilterUsesNativeDurationInsteadOfDouyinTypes() throws Throwable {
        AtomicReference<XposedInterface.Hooker> target = new AtomicReference<>();
        try (FeedAdFilterHook hook = install(target, settings(false, false, false, false, false))) {
            java.util.Map<String, Object> options = new java.util.HashMap<>();
            options.put("hide_long_videos", true); options.put("maximum_duration_seconds", 120);
            hook.setOptions(new LocalOptions(options));
            Aweme first = item(127), second = item(128), longVideo = item(0), ordinary = item(0);
            longVideo._video.videoLength = 200_000;
            Page page = new Page(first, longVideo, second, ordinary);
            target.get().intercept(chain(page, "/aweme/v1/feed/", false));
            assertEquals(Arrays.asList(first, second, ordinary), page.items);
        }
    }

    @Test public void cardClassifierMustAcceptExactlyTheNativeAwemeClass() throws Exception {
        assertThrows(IllegalStateException.class, () -> FeedAdFilterHook.install(
                framework(new AtomicReference<>()), report(false, true, false),
                getClass().getClassLoader(), cardSettings(), ignored -> { }));
    }

    @Test public void cardTypeGetterMustBelongToNativeCardInsertModel() throws Exception {
        assertThrows(IllegalStateException.class, () -> FeedAdFilterHook.install(
                framework(new AtomicReference<>()), report(false, false, true),
                getClass().getClassLoader(), cardSettings(), ignored -> { }));
    }

    private static Aweme item(int type) { Aweme item = new Aweme(); item.type = type; return item; }
    private static FeatureSettings cardSettings() {
        return new FeatureSettings(false, false, true, false, false, false, true, false,
                false, "", false, 0, false, 0, false, false, false, false, false);
    }

    private FeedAdFilterHook install(AtomicReference<XposedInterface.Hooker> target, FeatureSettings settings) throws Exception {
        return FeedAdFilterHook.install(framework(target), report(), getClass().getClassLoader(), settings, ignored -> { });
    }
    private static FeatureSettings settings(boolean ad, boolean live, boolean image, boolean duration, boolean likes) {
        return new FeatureSettings(false, false, true, ad, live, image, false, false, "",
                duration, 10, likes, 100, false, false, false, false, false);
    }
    private static HostScanReport report() throws Exception { return report(false); }
    private static HostScanReport report(boolean materialized) throws Exception { return report(materialized, false, false); }
    private static HostScanReport report(boolean materialized, boolean wrongMarker, boolean wrongCard) throws Exception {
        List<RuleScanResult> results = new ArrayList<>();
        method(results,"main_feed_api_v1",Api.class.getMethod("postFetchRecommendFeedV2Call"));
        method(results,"retrofit_success_response",Wrapper.class.getMethod("success",Object.class,Raw.class));
        type(results,"retrofit_response_url",Raw.class);
        method(results,"aweme_ad_flag",Aweme.class.getMethod("isAd"));
        method(results,"aweme_is_live",Aweme.class.getMethod("getRoomFeedCellStruct"));
        method(results,"aweme_is_image",Aweme.class.getMethod("getPhotoModeImageInfo"));
        method(results,"aweme_type",Aweme.class.getMethod("getAwemeType"));
        method(results,"tiktok_feed_native_card",wrongMarker
                ? WrongClassifier.class.getMethod("isCard", Object.class) : NativeCards.class.getMethod("isCard", Aweme.class));
        method(results,"tiktok_feed_card_insert",Aweme.class.getMethod("getCardInsertInfo"));
        method(results,"tiktok_feed_card_type",(wrongCard ? WrongCardModel.class : CardInsertInfo.class).getMethod("getCardType"));
        type(results,"main_feed_response_model",Page.class);
        type(results,"aweme_feed_attributes",Aweme.class);
        type(results,"aweme_statistics_digg_count",Statistics.class);
        if (materialized) {
            method(results,"tiktok_feed_materialized",Api.class.getMethod("materialized",Page.class));
            method(results,"tiktok_feed_legacy_materialized",Api.class.getMethod("legacy",Page.class));
        }
        return new HostScanReport("tiktok-fixture",results,false);
    }
    private static void method(List<RuleScanResult> out,String id,Method m) {
        out.add(new RuleScanResult(id,"feed_filter",Collections.singletonList(new HostCandidate("fixture.apk","classes.dex",
                HostTargets.descriptor(m.getDeclaringClass()),m.getName(),HostTargets.descriptor(m)))));
    }
    private static void type(List<RuleScanResult> out,String id,Class<?> type) {
        out.add(new RuleScanResult(id,"feed_filter",Collections.singletonList(new HostCandidate("fixture.apk","classes.dex",
                HostTargets.descriptor(type),"",""))));
    }
    private static XposedInterface framework(AtomicReference<XposedInterface.Hooker> target) {
        Object handle=Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),new Class<?>[]{XposedInterface.HookHandle.class},(p,m,a)->null);
        AtomicReference<Object> builder=new AtomicReference<>();
        builder.set(Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),new Class<?>[]{XposedInterface.HookBuilder.class},(p,m,a)->{
            if("intercept".equals(m.getName())) { target.set((XposedInterface.Hooker)a[0]); return handle; }
            return builder.get();
        }));
        return (XposedInterface)Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),new Class<?>[]{XposedInterface.class},(p,m,a)->"hook".equals(m.getName())?builder.get():null);
    }
    private static XposedInterface.Chain chain(Page page,String path,boolean fail) {
        Raw raw=new Raw("https://example.invalid"+path);
        return (XposedInterface.Chain)Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),new Class<?>[]{XposedInterface.Chain.class},(p,m,a)->{
            if("getArg".equals(m.getName()))return ((Integer)a[0])==0?page:raw;
            if("proceed".equals(m.getName())) { if(fail)throw new IllegalStateException("host rejected");return page; }
            throw new UnsupportedOperationException(m.getName());
        });
    }
}
