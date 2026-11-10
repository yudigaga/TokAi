package com.tokai.module.scan;

/** Distinguishes an intentionally undeclared host capability from a failed declared rule. */
public final class HostFeatureSupport {
    public static final String UNAVAILABLE_PREFIX = "Unsupported host rules: ";
    private HostFeatureSupport() { }

    public static boolean isTikTok(HostScanReport report) {
        return report != null && report.getResults().containsKey("tiktok_feed_materialized");
    }

    public static String undeclaredRules(HostScanReport report, String... rules) {
        if (report == null) return ""; // No report is an installation error, not an unsupported declaration.
        for (String rule : rules) {
            RuleScanResult result = report.getResults().get(rule);
            if (result != null && result.getStatus() != RuleScanResult.Status.UNIQUE) return "";
        }
        StringBuilder missing = new StringBuilder();
        for (String rule : rules) if (!report.getResults().containsKey(rule)) {
            if (missing.length() > 0) missing.append(",");
            missing.append(rule);
        }
        return missing.length() == 0 ? "" : UNAVAILABLE_PREFIX + missing;
    }

    public static boolean isUnavailable(String reason) {
        return reason != null && reason.startsWith(UNAVAILABLE_PREFIX);
    }

    /** These settings have no declared target in the strictly bound TikTok 47.5.3 profile. */
    public static String settingUnavailableReason(String key) {
        switch (key) {
            case "remove_teen_dialog": case "prevent_skip_hidden":
            case "hide_recommendation_merchants": case "hide_product_cards": case "hide_hotspot_rank_cards":
                return "当前 TikTok " + HostAdaptation.VERIFIED_VERSION_NAME + " 尚未适配此功能，已保留原配置。";
            case "profile_mutual_count":
                return "当前 TikTok 尚未适配互关数量显示，已保留原配置。";
            case "short_progress":
                return "放宽原生可拖动进度条的短视频时长样式限制；仍保留宿主显示和拖动条件，不强制提供进度条。";
            default: return "";
        }
    }
}
