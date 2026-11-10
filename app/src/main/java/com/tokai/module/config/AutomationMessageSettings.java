package com.tokai.module.config;

import com.tokai.module.feature.AutomationScopePolicy;
import com.tokai.module.feature.ContentScopePolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/** An editing session for reply/comment settings; closing it invalidates late control callbacks. */
public final class AutomationMessageSettings implements SettingsStore {
    public enum ValidationIssue {
        DAILY_LIMIT("请输入 0 至 1000000 的整数"),
        COOLDOWN("请输入 0 至 525600 的整数"),
        DELAY("延迟应为 2 至 3600 秒"),
        NOTICE("提示超时应为 2 至 120 秒"),
        THREAD_LIMIT("每条评论最多回复 1 至 100 次"),
        TIP("发送提示应为 2 至 120 秒"),
        SCOPE("无效的范围设置");
        final String legacyMessage;
        ValidationIssue(String legacyMessage) { this.legacyMessage = legacyMessage; }
    }
    public static final class ValidationException extends IllegalArgumentException {
        public final ValidationIssue issue;
        ValidationException(ValidationIssue issue) { super(issue.legacyMessage); this.issue = issue; }
    }

    public static final int DEFAULT_NOTICE_SECONDS = 3;
    private final SettingsStore store;
    private final Runnable changed;
    private boolean closed;

    public AutomationMessageSettings(SettingsStore store, Runnable changed) {
        this.store = store;
        this.changed = changed == null ? () -> { } : changed;
    }

    @Override public FeatureSettings load() { return store.load(); }
    @Override public String getString(String key, String fallback) { return store.getString(key, fallback); }
    @Override public boolean getBoolean(String key, boolean fallback) { return store.getBoolean(key, fallback); }
    @Override public boolean isInHost() { return store.isInHost(); }
    @Override public void close() { closed = true; }

    @Override public void save(FeatureSettings settings) {
        if (closed) return;
        store.save(settings);
        changed.run();
    }

    @Override public void putBoolean(String key, boolean value) {
        if (closed) return;
        store.putBoolean(key, value);
        changed.run();
    }

    @Override public void putString(String key, String value) {
        if (closed) return;
        store.putString(key, validated(key, value));
        changed.run();
    }

    public void setEnabled(String kind, boolean value) {
        if (!"comment_reply".equals(kind)) requireKind(kind);
        if (closed) return;
        if ("comment_reply".equals(kind)) {
            putBoolean(LocalOptions.COMMENT_REPLY_ENABLED, value);
            return;
        }
        boolean comment = "comment".equals(kind);
        putBoolean(comment ? "native_auto_comment" : "native_auto_reply", value);
        FeatureSettings current = load();
        if (!comment && !value) return;
        save(new FeatureSettings(current.downloadEnabled, current.removeWatermark,
                current.feedFilterEnabled, current.hideAds, current.hideLives, current.hideImagePosts,
                current.hideRecommendationCards, current.hideRecommendationMerchants,
                current.keywordFilterEnabled, current.keyword, current.minimumDurationEnabled,
                current.minimumDurationSeconds, current.minimumLikesEnabled, current.minimumLikeCount,
                value || current.automationEnabled, current.autoLikeEnabled, current.autoFollowEnabled,
                comment ? value : current.autoCommentEnabled, comment ? current.autoMessageEnabled : false));
    }

    /** Read every identity/filter input before deriving any effective scope or legacy migration. */
    public LocalOptions scope(String kind) {
        requireKind(kind);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String suffix : new String[]{"_scope_mode", "_scope_relations", "_scope_keywords", "_scope_fields",
                "_filter_mode", "_filter_keywords", "_filter_fields"}) {
            String key = kind + suffix;
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        String targets = "reply".equals(kind) ? "reply_targets" : "comment_scope_users";
        values.put(targets, store.getString(targets, ""));
        values.put("reply_global", store.getBoolean("reply_global", false));
        return new LocalOptions(values);
    }

    public void prepareContentFilter(String kind) {
        requireKind(kind);
        if (closed) return;
        LocalOptions legacy = scope(kind);
        if (!legacy.text(kind + "_filter_mode", "").isEmpty()) return;
        String fields = ContentScopePolicy.fields(kind, legacy);
        String keywords = ContentScopePolicy.keywords(kind, legacy);
        String filter = ContentScopePolicy.filterMode(kind, legacy);
        String scope = ContentScopePolicy.scopeMode(kind, legacy);
        // Preserve the original legacy keys; only initialize the separate effective gates.
        store.putString(kind + "_filter_fields", fields);
        store.putString(kind + "_filter_keywords", keywords);
        store.putString(kind + "_filter_mode", filter);
        store.putString(kind + "_scope_mode", scope);
        changed.run();
    }

    private static void requireKind(String kind) {
        if (!"reply".equals(kind) && !"comment".equals(kind)) throw new IllegalArgumentException("未知自动化类型");
    }

    private static String validated(String key, String value) {
        if (!key.startsWith("reply_") && !key.startsWith("comment_")) return value;
        if (key.endsWith("_daily_limit")) return integer(value, 0, 1000000, ValidationIssue.DAILY_LIMIT, true);
        if (key.endsWith("_user_cooldown_minutes")) return integer(value, 0, 525600, ValidationIssue.COOLDOWN, true);
        if (key.endsWith("_delay_seconds")) return integer(value, 2, 3600, ValidationIssue.DELAY, false);
        if (key.endsWith("_notice_seconds")) return integer(value, 2, 120, ValidationIssue.NOTICE, false);
        if ("comment_reply_thread_limit".equals(key)) return integer(value, 1, 100, ValidationIssue.THREAD_LIMIT, true);
        if ("comment_reply_tip_seconds".equals(key)) return integer(value, 2, 120, ValidationIssue.TIP, true);
        if (key.endsWith("_scope_mode")) choice(value, "all", "whitelist", "blacklist");
        if (key.endsWith("_filter_mode")) choice(value, "off", "include", "exclude");
        if ("reply_group_mode".equals(key)) choice(value, "all", "selected", "mentions");
        if (key.endsWith("_scope_relations")) {
            for (String item : AutomationScopePolicy.targets(value)) choice(item, "following", "followers", "strangers");
        }
        if (key.endsWith("_filter_fields")) {
            for (String item : AutomationScopePolicy.targets(value)) {
                if (key.startsWith("reply_")) choice(item, "author", "caption");
                else choice(item, "author", "caption", "title");
            }
        }
        return value;
    }

    private static void choice(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw new ValidationException(ValidationIssue.SCOPE);
    }

    private static String integer(String value, int minimum, int maximum, ValidationIssue message, boolean normalize) {
        String text = value == null ? "" : value.trim();
        try {
            if (!text.matches("[0-9]+")) throw new NumberFormatException();
            int parsed = Integer.parseInt(text);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return normalize ? Integer.toString(parsed) : text;
        } catch (NumberFormatException invalid) { throw new ValidationException(message); }
    }
}
