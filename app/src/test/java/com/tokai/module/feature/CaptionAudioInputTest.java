package com.tokai.module.feature;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35,
        shadows = {CaptionAudioInputTest.Extractor.class, CaptionAudioInputTest.Muxer.class,
                CaptionAudioInputTest.Metadata.class})
public final class CaptionAudioInputTest {
    private static final String VIDEO = "https://v16.tiktokcdn.com/current.mp4";
    private static final String AUDIO = "https://v16.tiktokcdn.com/original.m4a?sign=a%2Fb+z&expires=123";
    private Context context;

    @Before public void resetMediaFixtures() {
        context = RuntimeEnvironment.getApplication();
        Extractor.format = MediaFormat.createAudioFormat("audio/mp4a-latm", 16000, 1);
        Extractor.format.setLong(MediaFormat.KEY_DURATION, 1_100_000);
        Extractor.positions = new long[]{100_000, 1_000_000};
        Extractor.onSample = null;
        Muxer.positions.clear();
        Muxer.format = null;
        Muxer.oversized = false;
        Metadata.duration = "1100";
    }

    @Test public void capturesRealAnnotatedAudioDtoBeforePlaybackAndPreservesSignedUrl() throws Exception {
        Work work = work();
        work.video.audio = List.of(audio(AUDIO, VIDEO, VIDEO));
        CaptionAudioInput.Source source = CaptionAudioInput.capture(work);

        assertEquals("work-1", source.aid);
        assertEquals(AUDIO, source.url);
        assertEquals(12_345, source.durationMillis);
        work.id = "other";
        work.video.audio.get(0).meta.urls.main = VIDEO;
        work.video.duration = 42;
        assertEquals("work-1", source.aid);
        assertEquals(AUDIO, source.url);
        assertEquals(12_345, source.durationMillis);
    }

    @Test public void capturesGetterSelectedVideoInsteadOfTheRawSerializedVideo() throws Exception {
        Work work = work();
        work.video.audio = List.of(audio(AUDIO, null, null));
        work.selectedVideo = new Video();
        work.selectedVideo.duration = 6789;
        String selectedAudio = "https://v16.tiktokcdn.com/getter-selected.m4a";
        work.selectedVideo.audio = List.of(audio(selectedAudio, null, null));

        CaptionAudioInput.Source source = CaptionAudioInput.capture(work);

        assertEquals(selectedAudio, source.url);
        assertEquals(6789, source.durationMillis);
    }

    @Test public void refusesTranslatedPlaybackEvenWhenTheRawVideoHasOriginalAudio() {
        Work work = work();
        work.video.audio = List.of(audio(AUDIO, null, null));
        work.playingTranslated = true;

        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(work));
    }

    @Test public void triesMainThenBackupThenFallbackWithinOriginalAudio() throws Exception {
        Work work = work();
        work.video.audio = List.of(audio("http://untrusted.test/audio", AUDIO, VIDEO));
        assertEquals(AUDIO, CaptionAudioInput.capture(work).url);
        work.video.audio.get(0).meta.urls.backup = "";
        assertEquals(VIDEO, CaptionAudioInput.capture(work).url);
        work.video.audio = java.util.Arrays.asList(null, audio(null, null, AUDIO));
        assertEquals(AUDIO, CaptionAudioInput.capture(work).url);
    }

    @Test public void fallsBackToPlaybackWithoutReadingBackgroundMusicOrTranslatedTts() throws Exception {
        Work work = work();
        work.music = new Music();
        work.music.play = new Urls(AUDIO);
        work.audio = new Translated();
        work.audio.tts = List.of(new Urls(AUDIO));
        assertEquals(VIDEO, CaptionAudioInput.capture(work).url);
        work.video.play = null;
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(work));
    }

    @Test public void rejectsNonVideoAdvertisementsAndLiveWorks() {
        for (int type : new int[]{2, 68, 101}) {
            Work work = work();
            work.type = type;
            assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(work));
        }
        Work ad = work();
        ad.ad = true;
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(ad));
        Work methodAd = work();
        methodAd.methodAd = true;
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(methodAd));
        Work live = work();
        live.room = new Object();
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(live));
    }

    @Test public void requiresAnAidAndPositiveDurationAtMostTenMinutes() throws Exception {
        for (long duration : new long[]{0, -1, 600_001}) {
            Work work = work();
            work.video.duration = duration;
            assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(work));
        }
        Work work = work();
        work.video.duration = 600_000;
        assertEquals(600_000, CaptionAudioInput.capture(work).durationMillis);
        work.video.duration = 1;
        assertEquals(1, CaptionAudioInput.capture(work).durationMillis);
        work.id = " ";
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(work));
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(null));
    }

    @Test public void rejectsUnsafePlaybackUrlsAndMissingVideo() {
        for (String url : new String[]{"http://v16.tiktokcdn.com/input.mp4", "file:///private/input.mp4",
                "https://v16.tiktokcdn.com.evil.test/a", "https://user:secret@v16.tiktokcdn.com/a",
                "https://v16.tiktokcdn.com:444/a"}) {
            Work work = work();
            work.video.play = new Urls(url);
            assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(work));
        }
        Work missing = work();
        missing.video = null;
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.capture(missing));
    }

    @Test public void cancelledPreparationDoesNotOpenNetworkOrCreateFiles() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaReadCancellation token = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            opened.incrementAndGet();
            throw new AssertionError("Cancelled input opened a connection");
        });
        token.cancel();
        assertThrows(CancellationException.class,
                () -> CaptionAudioInput.prepare(context, source(), token));
        assertEquals(0, opened.get());
    }

    @Test public void preparationStartsTheCancellationDeadlineBeforeReadingBytes() throws Exception {
        AtomicLong now = new AtomicLong(1);
        Response response = new Response() {
            @Override public int getResponseCode() { now.addAndGet(61_000_000_000L); return 200; }
        };
        MediaReadCancellation token = new MediaReadCancellation(60_000, now::get, uri -> response);
        assertThrows(TimeoutException.class, () -> CaptionAudioInput.prepare(context, source(), token));
        assertTrue(response.disconnected);
        assertNoOwnedFiles();
    }

    @Test public void revalidatesEveryRedirectBeforeOpeningTheDestination() throws Exception {
        List<URI> opened = new ArrayList<>();
        Response redirect = new Response() {
            @Override public int getResponseCode() { return 302; }
            @Override public String getHeaderField(String name) { return "https://evil.test/stolen"; }
        };
        MediaReadCancellation token = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            opened.add(uri);
            return redirect;
        });
        assertThrows(IllegalArgumentException.class, () -> CaptionAudioInput.prepare(context, source(), token));
        assertEquals(List.of(URI.create(AUDIO)), opened);
        assertTrue(redirect.disconnected);
        assertNoOwnedFiles();
    }

    @Test public void rejectsAnInputOver64MiBBeforeReadingItsBody() throws Exception {
        Response response = new Response() {
            @Override public long getContentLengthLong() { return 64L * 1024 * 1024 + 1; }
            @Override public InputStream getInputStream() { throw new AssertionError("Oversized body read"); }
        };
        assertThrows(IllegalArgumentException.class,
                () -> CaptionAudioInput.prepare(context, source(), token(response)));
        assertNoOwnedFiles();
    }

    @Test public void failedDownloadDeletesItsInputWithoutDeletingAdjacentFiles() throws Exception {
        File unrelated = new File(cache(), "another-task.keep");
        Files.write(unrelated.toPath(), new byte[]{7});
        Response response = new Response() {
            @Override public InputStream getInputStream() { return new InputStream() {
                @Override public int read() throws java.io.IOException { throw new java.io.IOException("broken input"); }
            }; }
        };
        try {
            assertThrows(java.io.IOException.class,
                    () -> CaptionAudioInput.prepare(context, source(), token(response)));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(unrelated.toPath()));
            assertEquals(List.of("another-task.keep"), List.of(cache().list()));
        } finally { Files.deleteIfExists(unrelated.toPath()); }
    }

    @Test public void extractsAacWithOriginalRateChannelsAndPtsAndDeletesOnlyItsOwnOutput() throws Exception {
        CaptionAudioInput.Prepared first = CaptionAudioInput.prepare(context, source(), token(new Response()));
        CaptionAudioInput.Prepared second = null;
        try {
            assertTrue(first.file.isFile());
            assertEquals(cache().getCanonicalFile(), first.file.getParentFile().getCanonicalFile());
            assertEquals(1100, first.durationMillis);
            assertEquals(16000, Muxer.format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            assertEquals(1, Muxer.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            assertEquals(List.of(100_000L, 1_000_000L), Muxer.positions);
            assertEquals(1, cache().list().length);
            second = CaptionAudioInput.prepare(context, source(), token(new Response()));
            first.close();
            first.close();
            assertFalse(first.file.exists());
            assertTrue(second.file.isFile());
            assertEquals(1, cache().list().length);
        } finally { first.close(); if (second != null) second.close(); }
        assertNoOwnedFiles();
    }

    @Test public void rejectsActualDurationOverTenMinutesEvenWhenDtoClaimsAShortClip() throws Exception {
        Metadata.duration = "600001";
        assertThrows(IllegalArgumentException.class,
                () -> CaptionAudioInput.prepare(context, source(), token(new Response())));
        assertNoOwnedFiles();
    }

    @Test public void rejectsOutputOver24MiBAndDeletesBothFiles() throws Exception {
        Muxer.oversized = true;
        assertThrows(IllegalArgumentException.class,
                () -> CaptionAudioInput.prepare(context, source(), token(new Response())));
        assertNoOwnedFiles();
    }

    @Test public void refusesUnsupportedAudioRateOrChannelsBeforeMuxing() throws Exception {
        for (int[] shape : new int[][]{{7999, 1}, {48001, 1}, {16000, 0}, {16000, 3}}) {
            Extractor.format = MediaFormat.createAudioFormat("audio/mp4a-latm", shape[0], shape[1]);
            assertThrows(IllegalArgumentException.class,
                    () -> CaptionAudioInput.prepare(context, source(), token(new Response())));
            assertNoOwnedFiles();
        }
    }

    @Test public void cancellingAudioExtractionDeletesInputAndPartialOutput() throws Exception {
        MediaReadCancellation token = token(new Response());
        Extractor.onSample = token::cancel;
        assertThrows(CancellationException.class, () -> CaptionAudioInput.prepare(context, source(), token));
        assertNoOwnedFiles();
    }

    private static Work work() {
        return new Gson().fromJson("{\"aweme_id\":\"work-1\",\"aweme_type\":0,\"video\":{\"duration\":12345,"
                + "\"play_addr\":{\"url_list\":[\"" + VIDEO + "\"]}}}", Work.class);
    }

    private static Audio audio(String main, String backup, String fallback) {
        Audio audio = new Audio();
        audio.meta = new Meta();
        audio.meta.urls = new OriginalUrls();
        audio.meta.urls.main = main;
        audio.meta.urls.backup = backup;
        audio.meta.urls.fallback = fallback;
        return audio;
    }

    private static CaptionAudioInput.Source source() { return new CaptionAudioInput.Source("work-1", AUDIO, 12345); }
    private static MediaReadCancellation token(Response response) {
        return new MediaReadCancellation(60_000, System::nanoTime, uri -> response);
    }
    private File cache() throws Exception {
        File directory = new File(context.getCacheDir(), "tokai-caption-asr");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("No test cache");
        return directory;
    }
    private void assertNoOwnedFiles() throws Exception { assertEquals(0, cache().list().length); }

    static final class Work {
        @SerializedName("aweme_id") String id;
        @SerializedName("aweme_type") int type;
        @SerializedName("is_ads") boolean ad;
        @SerializedName("live_room") Object room;
        @SerializedName("video") Video video;
        @SerializedName("music") Music music;
        @SerializedName("audio") Translated audio;
        transient boolean methodAd;
        transient boolean playingTranslated;
        transient Video selectedVideo;
        public boolean isAd() { return methodAd; }
        public boolean getIsPlayingTranslatedVideo() { return playingTranslated; }
        public Video getVideo() { return selectedVideo != null ? selectedVideo : video; }
    }
    static final class Video {
        @SerializedName("duration") long duration;
        @SerializedName("play_addr") Urls play;
        @SerializedName("bit_rate_audio") List<Audio> audio;
    }
    static final class Audio { @SerializedName("audio_meta") Meta meta; }
    static final class Meta { @SerializedName("url_list") OriginalUrls urls; }
    static final class OriginalUrls {
        @SerializedName("main_url") String main;
        @SerializedName("backup_url") String backup;
        @SerializedName("fallback_url") String fallback;
    }
    static final class Urls {
        @SerializedName("url_list") List<String> urls;
        Urls(String url) { urls = List.of(url); }
    }
    static final class Music { @SerializedName("play_url") Urls play; }
    static final class Translated { @SerializedName("TtsInfos") List<Urls> tts; }

    private static class Response extends HttpURLConnection {
        boolean disconnected;
        Response() throws Exception { super(new URL(AUDIO)); }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return 4; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[]{1, 2, 3, 4}); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    @Implements(MediaExtractor.class)
    public static final class Extractor {
        static MediaFormat format;
        static long[] positions;
        static Runnable onSample;
        private int index;
        @Implementation protected void __constructor__() { }
        @Implementation protected void setDataSource(FileDescriptor descriptor) { }
        @Implementation protected int getTrackCount() { return 1; }
        @Implementation protected MediaFormat getTrackFormat(int track) { return format; }
        @Implementation protected void selectTrack(int track) { }
        @Implementation protected long getSampleTime() { return index < positions.length ? positions[index] : -1; }
        @Implementation protected int getSampleFlags() { return 0; }
        @Implementation protected int readSampleData(ByteBuffer buffer, int offset) {
            if (onSample != null) onSample.run();
            if (index >= positions.length) return -1;
            buffer.put(offset, (byte) 1);
            return 1;
        }
        @Implementation protected boolean advance() { return ++index < positions.length; }
        @Implementation protected void release() { }
    }

    @Implements(MediaMuxer.class)
    public static final class Muxer {
        static final List<Long> positions = new ArrayList<>();
        static MediaFormat format;
        static boolean oversized;
        private File file;
        @Implementation protected void __constructor__(String path, int outputFormat) { file = new File(path); }
        @Implementation protected int addTrack(MediaFormat trackFormat) { format = trackFormat; return 0; }
        @Implementation protected void start() { }
        @Implementation protected void writeSampleData(int track, ByteBuffer buffer, MediaCodec.BufferInfo info) throws Exception {
            positions.add(info.presentationTimeUs);
            try (FileOutputStream output = new FileOutputStream(file, true)) { output.write(1); }
            if (oversized) try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                output.setLength(24L * 1024 * 1024 + 1);
            }
        }
        @Implementation protected void stop() { }
        @Implementation protected void release() { }
    }

    @Implements(MediaMetadataRetriever.class)
    public static final class Metadata {
        static String duration;
        @Implementation protected void __constructor__() { }
        @Implementation protected void setDataSource(FileDescriptor descriptor) { }
        @Implementation protected String extractMetadata(int key) { return duration; }
        @Implementation protected void release() { }
    }
}
