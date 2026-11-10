package com.tokai.module.ui;

import android.os.Bundle;
import com.tokai.module.scan.HostFeatureCatalog;
import com.tokai.module.R;
import com.tokai.module.i18n.UiText;

/** Module UI only; HostAdaptationDiagnostics.describe remains the raw diagnostic protocol. */
public final class CommonUiDiagnostics {
    private CommonUiDiagnostics() { }
    public static String describe(Bundle snapshot) {
        StringBuilder detail = new StringBuilder(UiText.get(R.string.common_ui_m186));
        long scanMillis = snapshot.getLong("host_scan_millis", -1);
        long heapBytes = snapshot.getLong("host_java_heap_used_bytes", -1);
        if (snapshot.getBoolean("host_version_available")) {
            detail.append(snapshot.getString("host_version_name", UiText.get(R.string.common_ui_m015)))
                    .append(" / ").append(snapshot.getLong("host_version_code"));
            if (snapshot.getLong("host_update_version_code", -1) >= 0) {
                detail.append(UiText.get(R.string.common_ui_m014, snapshot.getLong("host_update_version_code")));
            }
        } else detail.append(UiText.get(R.string.common_ui_m187));
        if (snapshot.getBoolean("host_adaptation_static_verified")) {
            detail.append(UiText.get(R.string.common_ui_m016, snapshot.getString("host_version_name", UiText.get(R.string.common_ui_m015))));
        } else if (!snapshot.getBoolean("host_version_profile_matched")) {
            detail.append(UiText.get(R.string.common_ui_m188));
        } else {
            detail.append(UiText.get(R.string.common_ui_m017, snapshot.getString("host_version_name", UiText.get(R.string.common_ui_m015))));
        }
        if (scanMillis >= 0) {
            detail.append(UiText.get(R.string.common_ui_m020, snapshot.getBoolean("cache_hit") ? UiText.get(R.string.common_ui_m018) : UiText.get(R.string.common_ui_m019), scanMillis));
        }
        if (heapBytes >= 0) detail.append(UiText.get(R.string.common_ui_m021, heapBytes / (1024 * 1024)));
        int resourceCount = snapshot.getInt("host_adaptation_resource_count");
        detail.append(UiText.get(R.string.common_ui_m022, resourceCount));
        int mismatches = snapshot.getInt("feed_ui_resource_mismatches");
        if (mismatches > 0) detail.append(UiText.get(R.string.common_ui_m023, mismatches));
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            String prefix = "capability." + feature.id;
            String gaps = snapshot.getString(prefix + ".rule_gaps", "");
            if (!gaps.isEmpty()) {
                detail.append(UiText.get(R.string.common_ui_m024, featureLabel(feature.id, feature.label), gaps));
            }
            String failure = snapshot.getString(prefix + ".installation_failure", "");
            if (!failure.isEmpty()) {
                detail.append(UiText.get(R.string.common_ui_m025, featureLabel(feature.id, feature.label), failure));
            } else if (gaps.isEmpty() && snapshot.containsKey(prefix + ".installed")
                    && !snapshot.getBoolean(prefix + ".installed")) {
                detail.append(UiText.get(R.string.common_ui_m026, featureLabel(feature.id, feature.label)));
            }
        }
        return detail.append(UiText.get(R.string.common_ui_m189)).toString();
    }
    private static String featureLabel(String id, String raw) {
        switch (id) {
            case "feed_filter": return UiText.get(R.string.common_ui_m027);
            case "download": return UiText.get(R.string.common_ui_m028);
            case "top_tabs": return UiText.get(R.string.common_ui_m029);
            case "feed_ui_video": return UiText.get(R.string.common_ui_m030);
            case "feed_ui_image": return UiText.get(R.string.common_ui_m031);
            case "home_navigation": return UiText.get(R.string.common_ui_m032);
            case "refresh_confirm": return UiText.get(R.string.common_ui_m033);
            case "auto_like": return UiText.get(R.string.common_ui_m034);
            case "auto_follow": return UiText.get(R.string.common_ui_m035);
            case "auto_play": return UiText.get(R.string.common_ui_m036);
            case "auto_comment": return UiText.get(R.string.common_ui_m037);
            case "auto_message": return UiText.get(R.string.common_ui_m038);
            case "local_analytics": return UiText.get(R.string.common_ui_m039);
            case "media_model": return UiText.get(R.string.common_ui_m040);
            case "native_auto_reply": return UiText.get(R.string.common_ui_m041);
            case "comment_reply": return UiText.get(R.string.common_ui_m042);
            case "comment_tools": return UiText.get(R.string.common_ui_m043);
            case "remove_splash_ads": return UiText.get(R.string.common_ui_m044);
            case "hide_camera_plus": return UiText.get(R.string.common_ui_m045);
            case "prevent_insert_video": return UiText.get(R.string.common_ui_m046);
            case "hide_native_captions": return UiText.get(R.string.common_ui_m047);
            case "force_native_captions": return UiText.get(R.string.common_ui_m048);
            case "caption_generation": return UiText.get(R.string.common_ui_m049);
            case "disable_data_saver": return UiText.get(R.string.common_ui_m050);
            case "short_progress": return UiText.get(R.string.common_ui_m051);
            case "block_long_press_speed": return UiText.get(R.string.common_ui_m052);
            case "spoof_profile": return UiText.get(R.string.common_ui_m053);
            case "recall_background": return UiText.get(R.string.common_ui_m054);
            case "comment_enhancements": return UiText.get(R.string.common_ui_m055);
            case "comment_voice": return UiText.get(R.string.common_ui_m056);
            case "comment_tts": return UiText.get(R.string.common_ui_m057);
            case "dm_speech": return UiText.get(R.string.common_ui_m058);
            default: return raw;
        }
    }
}
