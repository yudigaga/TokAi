package com.tokai.module.feature;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** Captures original work audio and owns only caption preparation files. */
final class CaptionAudioInput {
    private static final long MAX_DURATION_MILLIS = 600_000;
    private static final long MAX_OUTPUT_BYTES = 24L * 1024 * 1024;
    private static final String AAC = "audio/mp4a-latm";

    static final class Source {
        final String aid;
        final String url;
        final long durationMillis;

        Source(String aid, String url, long durationMillis) {
            this.aid = aid;
            this.url = url;
            this.durationMillis = durationMillis;
        }
    }

    static final class Prepared implements AutoCloseable {
        final File file;
        final long durationMillis;

        private Prepared(File file, long durationMillis) {
            this.file = file;
            this.durationMillis = durationMillis;
        }

        @Override public void close() throws IOException {
            Files.deleteIfExists(file.toPath());
        }
    }

    static Source capture(Object aweme) throws Exception {
        Object type = value(aweme, "aweme_type", "getAwemeType");
        if (!(type instanceof Number) || ((Number) type).intValue() != 0
                || Boolean.TRUE.equals(DownloadActionHook.jsonValue(aweme, "is_ads"))
                || Boolean.TRUE.equals(HostTargets.invoke(aweme, "isAd"))
                || Boolean.TRUE.equals(HostTargets.invoke(aweme, "getIsAdAweme"))
                || Boolean.TRUE.equals(HostTargets.invoke(aweme, "isLive"))
                || Boolean.TRUE.equals(HostTargets.invoke(aweme, "getIsPlayingTranslatedVideo"))
                || DownloadActionHook.jsonValue(aweme, "live_room") != null
                || value(aweme, "room", "getLiveRoom") != null) {
            throw new IllegalArgumentException("该作品不支持字幕识别");
        }
        Object rawAid = value(aweme, "aweme_id", "getAid");
        Object video = HostTargets.invoke(aweme, "getVideo");
        if (video == null) video = DownloadActionHook.jsonValue(aweme, "video");
        Object duration = value(video, "duration", "getDuration");
        if (!(rawAid instanceof String) || ((String) rawAid).trim().isEmpty()
                || !(duration instanceof Number)) {
            throw new IllegalArgumentException("作品或视频时长不可用");
        }
        long durationMillis = ((Number) duration).longValue();
        validateDuration(durationMillis);
        String url = originalAudio(video);
        if (url == null) url = DownloadActionHook.findPlaybackVideoUrl(aweme);
        if (!AutomationCommentMedia.isVideoUrl(url)) throw new IllegalArgumentException("作品音频地址不可用");
        return new Source((String) rawAid, url, durationMillis);
    }

    static Prepared prepare(Context context, Source source, MediaReadCancellation token) throws Exception {
        if (token == null) throw new IllegalArgumentException("字幕任务缺少取消令牌");
        token.start();
        if (context == null || source == null || source.aid == null || source.aid.trim().isEmpty()
                || !AutomationCommentMedia.isVideoUrl(source.url)) {
            throw new IllegalArgumentException("字幕音频来源不可用");
        }
        validateDuration(source.durationMillis);
        File directory = new File(context.getCacheDir(), "tokai-caption-asr");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建字幕音频缓存");
        Context downloadContext = new ContextWrapper(context) {
            @Override public File getCacheDir() { return directory; }
        };
        File input = null;
        Prepared prepared = null;
        try {
            input = AutomationCommentMedia.downloadVideo(downloadContext, source.url, token);
            token.check();
            prepared = encode(input, directory, token);
            Files.deleteIfExists(input.toPath());
            input = null;
            token.check();
            return prepared;
        } catch (Exception failure) {
            if (prepared != null) prepared.close();
            token.check();
            throw failure;
        } finally {
            if (input != null) Files.deleteIfExists(input.toPath());
        }
    }

    private static Object value(Object target, String field, String getter) {
        Object value = DownloadActionHook.jsonValue(target, field);
        return value != null ? value : HostTargets.invoke(target, getter);
    }

    private static String originalAudio(Object video) {
        Object rates = value(video, "bit_rate_audio", "getBitRateAudio");
        if (rates instanceof Iterable<?>) for (Object rate : (Iterable<?>) rates) {
            Object meta = value(rate, "audio_meta", "getAudioMeta");
            Object urls = value(meta, "url_list", "getUrlList");
            for (String field : new String[]{"main_url", "backup_url", "fallback_url"}) {
                String getter = "main_url".equals(field) ? "getMainUrl"
                        : "backup_url".equals(field) ? "getBackupUrl" : "getFallbackUrl";
                Object url = value(urls, field, getter);
                if (url instanceof String && AutomationCommentMedia.isVideoUrl((String) url)) return (String) url;
            }
        }
        return null;
    }

    private static Prepared encode(File input, File directory, MediaReadCancellation token) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        File output = null;
        boolean succeeded = false;
        try (FileInputStream stream = new FileInputStream(input)) {
            token.check();
            extractor.setDataSource(stream.getFD());
            MediaFormat format = null;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                token.check();
                MediaFormat candidate = extractor.getTrackFormat(index);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    format = candidate;
                    extractor.selectTrack(index);
                    break;
                }
            }
            if (format == null) throw new IllegalArgumentException("作品没有可读取的音轨");
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (rate < 8000 || rate > 48000 || channels < 1 || channels > 2) {
                throw new IllegalArgumentException("音频采样格式不受支持");
            }
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                long durationUs = format.getLong(MediaFormat.KEY_DURATION);
                if (durationUs <= 0 || durationUs > MAX_DURATION_MILLIS * 1000) {
                    throw new IllegalArgumentException("实际音轨时长超过限制或无效");
                }
            }
            output = File.createTempFile("caption-", ".m4a", directory);
            if (AAC.equals(format.getString(MediaFormat.KEY_MIME))) remux(extractor, format, output, token);
            else transcode(extractor, format, rate, channels, output, token);
            token.check();
            checkOutput(output);
            long durationMillis = duration(output, token);
            token.check();
            succeeded = true;
            return new Prepared(output, durationMillis);
        } finally {
            try { extractor.release(); } catch (RuntimeException ignored) { }
            if (!succeeded && output != null) Files.deleteIfExists(output.toPath());
        }
    }

    private static void remux(MediaExtractor extractor, MediaFormat format, File output,
                              MediaReadCancellation token) throws Exception {
        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false;
        try {
            int track = muxer.addTrack(format);
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long bytes = 0;
            long firstTime = -1;
            long previousTime = -1;
            while (true) {
                token.check();
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                token.check();
                if (size < 0) break;
                long time = extractor.getSampleTime();
                int flags = extractor.getSampleFlags();
                if (time < 0 || time < previousTime || (flags & ~MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    throw new IllegalArgumentException("音轨时间戳或加密分片不受支持");
                }
                if (firstTime < 0) firstTime = time;
                if (time - firstTime > MAX_DURATION_MILLIS * 1000 || size > MAX_OUTPUT_BYTES - bytes) {
                    throw new IllegalArgumentException("音轨时长或体积超过限制");
                }
                previousTime = time;
                bytes += size;
                info.set(0, size, time, (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                buffer.position(0);
                buffer.limit(size);
                muxer.writeSampleData(track, buffer, info);
                token.check();
                if (output.length() > MAX_OUTPUT_BYTES) throw new IllegalArgumentException("字幕音频超过 24 MB");
                extractor.advance();
            }
            if (bytes == 0) throw new IllegalArgumentException("作品音轨为空");
            token.check();
            muxer.stop();
            started = false;
        } finally {
            if (started) try { muxer.stop(); } catch (RuntimeException ignored) { }
            try { muxer.release(); } catch (RuntimeException ignored) { }
        }
    }

    private static void transcode(MediaExtractor extractor, MediaFormat input, int rate, int channels,
                                  File output, MediaReadCancellation token) throws Exception {
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        try {
            token.check();
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, null, null, 0);
            decoder.start();
            MediaFormat format = MediaFormat.createAudioFormat(AAC, rate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 96000 * channels);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
            encoder = MediaCodec.createEncoderByType(AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo decoded = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoded = new MediaCodec.BufferInfo();
            boolean inputEnd = false, decodeEnd = false, encodeEnd = false;
            int track = -1, held = -1;
            long bytes = 0, firstTime = -1, lastInputTime = 0;
            while (!encodeEnd) {
                token.check();
                if (!inputEnd) {
                    int slot = decoder.dequeueInputBuffer(1000);
                    if (slot >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(slot);
                        if (buffer == null) throw new IllegalStateException("音频解码输入不可用");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        token.check();
                        if (size < 0) {
                            decoder.queueInputBuffer(slot, 0, 0, lastInputTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnd = true;
                        } else {
                            long time = extractor.getSampleTime();
                            int flags = extractor.getSampleFlags();
                            if (time < 0 || time < lastInputTime || (flags & ~MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                throw new IllegalArgumentException("音轨时间戳或加密分片不受支持");
                            }
                            if (firstTime < 0) firstTime = time;
                            if (time - firstTime > MAX_DURATION_MILLIS * 1000) {
                                throw new IllegalArgumentException("实际音轨超过 10 分钟");
                            }
                            lastInputTime = time;
                            decoder.queueInputBuffer(slot, 0, size, time, 0);
                            extractor.advance();
                        }
                    }
                }
                if (!decodeEnd) {
                    if (held < 0) {
                        held = decoder.dequeueOutputBuffer(decoded, 1000);
                        if (held == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            MediaFormat actual = decoder.getOutputFormat();
                            if (actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) != rate
                                    || actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels
                                    || actual.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                    && actual.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                                throw new IllegalArgumentException("解码后的 PCM 格式不受支持");
                            }
                            held = -1;
                        }
                    }
                    if (held >= 0) {
                        int slot = encoder.dequeueInputBuffer(1000);
                        if (slot >= 0) {
                            ByteBuffer target = encoder.getInputBuffer(slot);
                            if (target == null || target.capacity() < decoded.size) {
                                throw new IllegalStateException("PCM 缓冲区大小不受支持");
                            }
                            target.clear();
                            if (decoded.size > 0) {
                                ByteBuffer pcm = decoder.getOutputBuffer(held);
                                if (pcm == null) throw new IllegalStateException("音频解码输出不可用");
                                pcm.position(decoded.offset);
                                pcm.limit(decoded.offset + decoded.size);
                                target.put(pcm);
                            }
                            int flags = decoded.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                            encoder.queueInputBuffer(slot, 0, decoded.size, decoded.presentationTimeUs, flags);
                            decodeEnd = flags != 0;
                            decoder.releaseOutputBuffer(held, false);
                            held = -1;
                        }
                    }
                }
                token.check();
                int slot = encoder.dequeueOutputBuffer(encoded, 1000);
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IllegalStateException("音频输出格式重复变化");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (slot >= 0) {
                    if ((encoded.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encoded.size > 0) {
                        if (!muxing) throw new IllegalStateException("音频输出未就绪");
                        if (encoded.size > MAX_OUTPUT_BYTES - bytes) throw new IllegalArgumentException("字幕音频超过 24 MB");
                        bytes += encoded.size;
                        ByteBuffer buffer = encoder.getOutputBuffer(slot);
                        if (buffer == null) throw new IllegalStateException("音频编码输出不可用");
                        buffer.position(encoded.offset);
                        buffer.limit(encoded.offset + encoded.size);
                        muxer.writeSampleData(track, buffer, encoded);
                        if (output.length() > MAX_OUTPUT_BYTES) throw new IllegalArgumentException("字幕音频超过 24 MB");
                    }
                    encodeEnd = (encoded.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(slot, false);
                }
            }
            token.check();
            if (!muxing || bytes == 0) throw new IllegalArgumentException("作品音轨为空");
            muxer.stop();
            muxing = false;
        } finally {
            closeCodec(decoder);
            closeCodec(encoder);
            if (muxer != null) {
                if (muxing) try { muxer.stop(); } catch (RuntimeException ignored) { }
                try { muxer.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static void closeCodec(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop(); } catch (RuntimeException ignored) { }
        try { codec.release(); } catch (RuntimeException ignored) { }
    }

    private static long duration(File file, MediaReadCancellation token) throws Exception {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try (FileInputStream stream = new FileInputStream(file)) {
            token.check();
            metadata.setDataSource(stream.getFD());
            long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            validateDuration(duration);
            token.check();
            return duration;
        } finally { metadata.release(); }
    }

    private static void checkOutput(File output) {
        if (!output.isFile() || output.length() == 0 || output.length() > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("字幕音频为空或超过 24 MB");
        }
    }

    private static void validateDuration(long durationMillis) {
        if (durationMillis <= 0 || durationMillis > MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("字幕识别仅支持 10 分钟内的视频");
        }
    }

    private CaptionAudioInput() { }
}
