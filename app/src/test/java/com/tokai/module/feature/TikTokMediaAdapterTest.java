package com.tokai.module.feature;

import X.TikTokMediaUrl;
import X.TikTokPhotoPage;
import X.TikTokPhotoInfo;
import X.TikTokMediaVideo;
import X.TikTokMediaWork;
import com.tokai.module.config.LocalOptions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokMediaAdapterTest {
    @Test public void acceptsNativeRegionalCdnModelsAndRejectsUrlAuthoritySpoofs() {
        for (String url : new String[]{"https://p16-sign-sg.tiktokcdn.com/p.jpg",
                "https://p16-common.tiktokcdn-us.com/p.jpg", "https://p16-pu-ie.tiktokcdn-eu.com/p.jpg",
                "https://v16.tiktokv.com/v.mp4", "https://sf19-draftcdn-sg.ibytedtos.com/p.jpg"}) {
            assertTrue(url, AutomationImages.isMediaUrl(url)); assertTrue(url, AutomationCommentMedia.isVideoUrl(url));
        }
        for (String url : new String[]{"http://p16-sign-sg.tiktokcdn.com/p.jpg",
                "https://p16-sign-sg.tiktokcdn.com.evil.test/p.jpg", "https://tiktokcdn.com@evil.test/p.jpg",
                "https://name:secret@p16-common.tiktokcdn-us.com/p.jpg", "https://p16-common.tiktokcdn-us.com:444/p.jpg",
                "https://eviltiktokcdn.com/p.jpg", "https://127.0.0.1/p.jpg", "https://api.tiktokv.com/p.jpg"}) {
            assertFalse(url, AutomationImages.isMediaUrl(url)); assertFalse(url, AutomationCommentMedia.isVideoUrl(url));
        }
    }

    @Test public void aiCommentImagesAndDownloadGalleryUseTheSameCapturedNativePhotoOrder() {
        TikTokMediaWork work = gallery("https://p16-sign-sg.tiktokcdn.com/first.jpg", "https://p16-common.tiktokcdn-us.com/second.webp");
        assertEquals(List.of("https://p16-sign-sg.tiktokcdn.com/first.jpg", "https://p16-common.tiktokcdn-us.com/second.webp"),AutomationCommentMedia.imageUrls(work));
        List<DownloadActionHook.MediaAsset> downloads = DownloadActionHook.mediaAssets(work,false,new LocalOptions(Collections.emptyMap()));
        assertEquals(2,downloads.size()); assertEquals(AutomationCommentMedia.imageUrls(work).get(0),downloads.get(0).url);
        assertTrue(downloads.get(1).image); assertTrue(downloads.get(1).name.endsWith(".webp"));
    }

    @Test public void mappedVideoFieldsAndUrlsUseTheRealObfuscatedSerializedNameAnnotation() {
        TikTokMediaWork work = new TikTokMediaWork(); work._video = new TikTokMediaVideo();
        work._video.playAddr = new TikTokMediaUrl(List.of("https://v16.tiktokv.com/native.mp4"));
        work._video.thumbnail = new TikTokMediaUrl(List.of("https://p16-sign-sg.tiktokcdn.com/cover.jpg"));
        assertSame(work._video,DownloadActionHook.jsonValue(work,"video"));
        assertEquals("native-work",DownloadActionHook.jsonValue(work,"item_id"));
        assertEquals("https://v16.tiktokv.com/native.mp4",AutomationCommentMedia.videoUrl(work));
        assertEquals(List.of("https://p16-sign-sg.tiktokcdn.com/cover.jpg"),AutomationCommentMedia.imageUrls(work));
    }

    @Test public void metadataUsesNativePredicatesAndDoesNotBorrowDouyinTypeNumbers() {
        TikTokMediaWork work = gallery("https://p16-sign-sg.tiktokcdn.com/p.jpg"); work.advertisement = true; work.kind = 150;
        AnalyticsAwemeReader.Metadata image = AnalyticsAwemeReader.read(work);
        assertNotNull(image); assertEquals("image",image.type); assertTrue(image.advertisement);
        assertEquals("creator-id",image.authorId); assertEquals("Creator",image.authorName);
        work.photoInfo = null; work.kind = 68;
        assertEquals("unknown",AnalyticsAwemeReader.read(work).type);
        work.kind = 101; assertEquals("unknown",AnalyticsAwemeReader.read(work).type);
        work.liveRoom = new Object(); assertEquals("live",AnalyticsAwemeReader.read(work).type);
        work.liveRoom = null; work.kind = 145; work._video = new TikTokMediaVideo(); work._video.videoLength = 600_000;
        assertEquals("long_video",AnalyticsAwemeReader.read(work).type);
    }

    @Test public void invalidNativeGalleryCannotSmuggleAnUnrelatedSourceIntoAiInput() {
        TikTokMediaWork work = gallery("https://p16-sign-sg.tiktokcdn.com/one.jpg","https://unrelated.test/two.jpg");
        assertThrows(IllegalArgumentException.class,()->AutomationCommentMedia.imageUrls(work));
    }
    private static TikTokMediaWork gallery(String... urls) {
        TikTokMediaWork work = new TikTokMediaWork();
        work.photoInfo = new TikTokPhotoInfo(Arrays.stream(urls).map(url->new TikTokPhotoPage(new TikTokMediaUrl(List.of(url)))).toList());
        return work;
    }
}
