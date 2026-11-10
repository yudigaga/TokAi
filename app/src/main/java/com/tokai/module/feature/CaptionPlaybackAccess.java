package com.tokai.module.feature;

import android.view.View;
import com.tokai.module.scan.HostFeatureSupport;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Reads the current panel's clock and native subtitle exclusions without changing host state. */
final class CaptionPlaybackAccess implements AutoCloseable {
    final Method selected, resumed, paused, unselected, pageSelected;
    private final Method awemeGetter, currentHolder, playerGetter, positionGetter, mediaGetter, sourceAid;
    private final Method aidGetter, videoGetter, metadataGetter, translatedPlayback, burnedIn, foreignOriginal, lite, kevaGetter, pendingRemoval;
    private final Field rootField, panelField, captionItems;
    private final Class<?> holderType, awemeType;
    private final HostScanReport report;
    private final ClassLoader loader;
    private CaptionVisibilityGuard visibility;

    CaptionPlaybackAccess(HostScanReport report, ClassLoader loader) throws Exception {
        if (!HostFeatureSupport.isTikTok(report)) throw new IllegalStateException("TikTok caption binding required");
        this.report = report;
        this.loader = loader;
        selected = HostTargets.method(report, "feed_holder_selected", loader);
        pageSelected = HostTargets.method(report, "feed_holder_page_selected", loader);
        resumed = HostTargets.method(report, "component_player_playing", loader);
        paused = HostTargets.method(report, "component_player_pause", loader);
        unselected = HostTargets.method(report, "tiktok_caption_holder_unselected", loader);
        awemeGetter = HostTargets.method(report, "feed_aweme_accessor", loader);
        holderType = selected.getDeclaringClass();
        awemeType = awemeGetter.getReturnType();
        for (Method callback : new Method[]{pageSelected, resumed, paused, unselected, awemeGetter}) {
            if (callback.getDeclaringClass() != holderType) throw changed();
        }
        require(selected, false, void.class, int.class);
        require(pageSelected, false, void.class, int.class);
        require(resumed, false, void.class, String.class);
        require(paused, false, void.class, String.class);
        require(unselected, false, void.class, boolean.class);
        require(awemeGetter, false, awemeType);
        if (awemeType.isPrimitive() || awemeType == Object.class
                || HostTargets.type(report, "feed_holder_main_root", loader) != holderType
                || HostTargets.type(report, "tiktok_caption_holder_panel", loader) != holderType) throw changed();
        rootField = HostTargets.field(holderType, "u");
        panelField = HostTargets.field(holderType, "LLLZLZ");
        currentHolder = HostTargets.method(report, "auto_slide_current_holder", loader);
        playerGetter = HostTargets.method(report, "adv_gesture_panel_player", loader);
        Class<?> panelType = currentHolder.getDeclaringClass();
        if (Modifier.isStatic(rootField.getModifiers()) || !View.class.isAssignableFrom(rootField.getType())
                || Modifier.isStatic(panelField.getModifiers()) || !panelField.getType().isAssignableFrom(panelType)
                || playerGetter.getDeclaringClass() != panelType
                || !currentHolder.getReturnType().isAssignableFrom(holderType)) throw changed();
        require(currentHolder, false, currentHolder.getReturnType());
        require(playerGetter, false, playerGetter.getReturnType());
        positionGetter = HostTargets.method(report, "tiktok_caption_player_position", loader);
        mediaGetter = HostTargets.method(report, "tiktok_caption_player_media", loader);
        sourceAid = HostTargets.method(report, "tiktok_caption_playback_aid", loader);
        require(positionGetter, false, long.class);
        require(mediaGetter, false, sourceAid.getDeclaringClass());
        require(sourceAid, false, String.class);
        if (mediaGetter.getDeclaringClass() != positionGetter.getDeclaringClass()
                || !positionGetter.getDeclaringClass().isAssignableFrom(playerGetter.getReturnType())) throw changed();
        aidGetter = HostTargets.method(report, "tiktok_caption_aweme_id", loader);
        translatedPlayback = HostTargets.method(report, "tiktok_caption_translated_playback", loader);
        videoGetter = HostTargets.method(report, "tiktok_caption_aweme_video", loader);
        metadataGetter = HostTargets.method(report, "tiktok_caption_video_metadata", loader);
        Class<?> metadataType = HostTargets.type(report, "tiktok_caption_caption_items", loader);
        require(aidGetter, false, String.class);
        require(translatedPlayback, false, boolean.class);
        require(videoGetter, false, metadataGetter.getDeclaringClass());
        require(metadataGetter, false, metadataType);
        if (aidGetter.getDeclaringClass() != awemeType || videoGetter.getDeclaringClass() != awemeType
                || translatedPlayback.getDeclaringClass() != awemeType) throw changed();
        captionItems = HostTargets.field(metadataType, "captionList");
        if (Modifier.isStatic(captionItems.getModifiers()) || !List.class.isAssignableFrom(captionItems.getType())) throw changed();
        burnedIn = HostTargets.method(report, "tiktok_caption_burned_in_gate", loader);
        foreignOriginal = HostTargets.method(report, "tiktok_caption_foreign_original_gate", loader);
        lite = HostTargets.method(report, "tiktok_caption_lite_gate", loader);
        require(burnedIn, true, boolean.class, awemeType, Boolean.class);
        require(foreignOriginal, true, boolean.class, awemeType, Boolean.class);
        require(lite, true, boolean.class, awemeType);
        kevaGetter = HostTargets.method(report, "tiktok_caption_keva_service", loader);
        pendingRemoval = HostTargets.method(report, "tiktok_caption_pending_removal", loader);
        require(kevaGetter, true, kevaGetter.getReturnType());
        require(pendingRemoval, false, boolean.class, String.class);
        if (!pendingRemoval.getDeclaringClass().isAssignableFrom(kevaGetter.getReturnType())) throw changed();
    }

    Object aweme(Object holder) throws ReflectiveOperationException {
        return holderType.isInstance(holder) ? awemeGetter.invoke(holder) : null;
    }

    View root(Object holder) throws ReflectiveOperationException {
        return holderType.isInstance(holder) ? (View) rootField.get(holder) : null;
    }

    boolean isCurrent(Object holder) {
        try {
            Object panel = panel(holder);
            return panel != null && currentHolder.invoke(panel) == holder;
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    CaptionVisibilityGuard installVisibility(XposedInterface framework) throws Exception {
        if (visibility != null) throw new IllegalStateException("Caption visibility already installed");
        visibility = CaptionVisibilityGuard.install(framework, report, loader);
        return visibility;
    }

    boolean unblocked(Object holder) { return visibility != null && isCurrent(holder) && visibility.unblocked(holder); }

    @Override public void close() { if (visibility != null) visibility.close(); }

    long positionMillis(Object holder) {
        try {
            Object panel = panel(holder);
            if (panel == null || currentHolder.invoke(panel) != holder) return -1;
            Object work = aweme(holder);
            if (work == null) return -1;
            String aid = (String) aidGetter.invoke(work);
            Object player = playerGetter.invoke(panel);
            if (aid == null || aid.isEmpty() || player == null || !aid.equals(source(player))) return -1;
            long position = (Long) positionGetter.invoke(player);
            // A selected holder can precede its playback; reject clocks from a replaced source or player.
            if (position < 0 || panel(holder) != panel || currentHolder.invoke(panel) != holder
                    || playerGetter.invoke(panel) != player || !aid.equals(aidGetter.invoke(aweme(holder)))
                    || !aid.equals(source(player))) return -1;
            return position;
        } catch (ReflectiveOperationException | RuntimeException failure) { return -1; }
    }

    /** True also means native suppression or unknown metadata: neither authorizes new ASR. */
    boolean hasNativeCaptions(Object work) {
        if (!awemeType.isInstance(work)) return true;
        try {
            if (!Boolean.FALSE.equals(translatedPlayback.invoke(work))) return true;
            Object video = videoGetter.invoke(work);
            if (video == null) return true;
            Object metadata = metadataGetter.invoke(video);
            if (metadata != null) {
                List<?> items = (List<?>) captionItems.get(metadata);
                if (items != null && !items.isEmpty()) return true;
            }
            String aid = (String) aidGetter.invoke(work);
            if (aid == null || aid.isEmpty() || !Boolean.FALSE.equals(lite.invoke(null, work))
                    || !Boolean.FALSE.equals(burnedIn.invoke(null, work, null))
                    || !Boolean.FALSE.equals(foreignOriginal.invoke(null, work, null))) return true;
            Object keva = kevaGetter.invoke(null);
            return keva == null || !Boolean.FALSE.equals(pendingRemoval.invoke(keva, aid));
        } catch (ReflectiveOperationException | RuntimeException failure) { return true; }
    }

    private Object panel(Object holder) throws IllegalAccessException {
        if (!holderType.isInstance(holder)) return null;
        Object panel = panelField.get(holder);
        return currentHolder.getDeclaringClass().isInstance(panel) ? panel : null;
    }

    private String source(Object player) throws ReflectiveOperationException {
        Object media = mediaGetter.invoke(player);
        return media == null ? null : (String) sourceAid.invoke(media);
    }

    private static void require(Method method, boolean isStatic, Class<?> returns, Class<?>... parameters) {
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != returns
                || !Arrays.equals(method.getParameterTypes(), parameters)) throw changed();
    }

    private static IllegalStateException changed() {
        return new IllegalStateException("TikTok caption playback relationship changed");
    }
}
