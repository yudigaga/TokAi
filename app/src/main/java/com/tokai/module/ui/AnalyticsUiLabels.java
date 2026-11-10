package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;
import com.tokai.module.i18n.ModuleLocale;
import com.tokai.module.analytics.AnalyticsSnapshot.Rank;
import com.tokai.module.analytics.PortraitMetrics;
import com.tokai.module.analytics.PortraitSnapshot;
import com.tokai.module.analytics.PortraitSnapshot.DisplayFact;
import com.tokai.module.analytics.AnalyticsAiAnalysis;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display only. Stored facts, provider output, names, and statistics stay unchanged. */
final class AnalyticsUiLabels {
    private AnalyticsUiLabels() { }
    private static String n(Object value) { return NumberFormat.getIntegerInstance(ModuleLocale.locale()).format(value); }
    private static String pct(double value) {
        NumberFormat format = NumberFormat.getPercentInstance(ModuleLocale.locale());
        format.setMinimumFractionDigits(1); format.setMaximumFractionDigits(1); return format.format(value);
    }
    static String date(Object value) {
        try { return LocalDate.parse(value.toString()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(ModuleLocale.locale())); }
        catch (RuntimeException ignored) { return value.toString(); }
    }
    static String rank(Rank value) {
        if (value.stableId.startsWith("summary:tail:")) {
            try { return UiText.get(R.string.analytics_ui_tail_group, n(Integer.parseInt(value.stableId.substring(13)))); }
            catch (RuntimeException ignored) { return value.label; }
        }
        int colon = value.stableId.indexOf(':');
        return colon < 0 ? value.label : dimension(value.stableId.substring(0, colon), value.stableId.substring(colon + 1), value.label);
    }
    static String dimension(String kind, String id, String fallback) {
        if (id == null) return fallback;
        int resource = 0;
        if ("type".equals(kind)) switch (id) {
            case "short_video": resource = R.string.analytics_ui_type_short_video; break;
            case "long_video": resource = R.string.analytics_ui_type_long_video; break;
            case "live_photo": resource = R.string.analytics_ui_type_live_photo; break;
            case "image": resource = R.string.analytics_ui_type_image; break;
            case "live": resource = R.string.analytics_ui_type_live; break;
            case "unknown": resource = R.string.analytics_ui_type_unknown; break;
        }
        else if ("feature_source".equals(kind)) switch (id) {
            case "manual": resource = R.string.analytics_ui_source_manual; break;
            case "automatic": resource = R.string.analytics_ui_source_automatic; break;
            case "system": resource = R.string.analytics_ui_source_system; break;
            case "unknown": resource = R.string.analytics_ui_source_unknown; break;
        }
        else if ("stage".equals(kind)) switch (id) {
            case "entry": resource = R.string.analytics_ui_stage_entry; break;
            case "request": resource = R.string.analytics_ui_stage_request; break;
            case "accepted": resource = R.string.analytics_ui_stage_accepted; break;
            case "success": resource = R.string.analytics_ui_stage_success; break;
            case "failure": resource = R.string.analytics_ui_stage_failure; break;
            case "confirmed": resource = R.string.analytics_ui_stage_confirmed; break;
            case "unknown": resource = R.string.analytics_ui_stage_unknown; break;
        }
        else if ("feature".equals(kind)) switch (id) {
            case "auto_follow": resource = R.string.analytics_ui_feature_auto_follow; break;
            case "auto_like": resource = R.string.analytics_ui_feature_auto_like; break;
            case "auto_comment_sent": resource = R.string.analytics_ui_feature_auto_comment_sent; break;
            case "auto_reply_sent": resource = R.string.analytics_ui_feature_auto_reply_sent; break;
            case "auto_slide": resource = R.string.analytics_ui_feature_auto_slide; break;
            case "gesture_menu": resource = R.string.analytics_ui_feature_gesture_menu; break;
            case "view_finder": resource = R.string.analytics_ui_feature_view_finder; break;
            case "copy_text": resource = R.string.analytics_ui_feature_copy_text; break;
            case "save_media": resource = R.string.analytics_ui_feature_save_media; break;
            case "open_comments": resource = R.string.analytics_ui_feature_open_comments; break;
            case "gesture_speed": resource = R.string.analytics_ui_feature_gesture_speed; break;
            case "comment_tts": resource = R.string.analytics_ui_feature_comment_tts; break;
            case "download_queued": resource = R.string.analytics_ui_feature_download_queued; break;
            case "third_party_download": resource = R.string.analytics_ui_feature_third_party_download; break;
            case "feed_filter": resource = R.string.analytics_ui_feature_feed_filter; break;
        }
        return resource == 0 ? fallback : UiText.get(resource);
    }
    static Map<String, String> dayTypeIds(List<PortraitSnapshot.Day> days) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PortraitSnapshot.Day day : days) for (String label : day.types.keySet()) {
            String id = day.typeIds.getOrDefault(label, "");
            if (result.containsKey(label) && !result.get(label).equals(id)) result.put(label, "");
            else if (!result.containsKey(label)) result.put(label, id);
        }
        return result;
    }
    static String dayType(List<PortraitSnapshot.Day> days, String label) {
        return dimension("type", dayTypeIds(days).get(label), label);
    }
    static String evidenceLevel(PortraitSnapshot data) {
        int id;
        switch (data.evidenceId) {
            case "none": id = R.string.analytics_ui_evidence_none; break;
            case "small": id = R.string.analytics_ui_evidence_small; break;
            case "cross_day": id = R.string.analytics_ui_evidence_cross_day; break;
            case "sufficient": id = R.string.analytics_ui_evidence_sufficient; break;
            default: return data.evidenceLevel;
        }
        return UiText.get(id);
    }
    static String insightTitle(PortraitSnapshot.Insight item) {
        int id;
        switch (item.id) {
            case "content_world": id = R.string.analytics_ui_fact_content_world_title; break;
            case "content_shape": id = R.string.analytics_ui_fact_content_shape_title; break;
            case "daily_rhythm": id = R.string.analytics_ui_fact_daily_rhythm_title; break;
            case "familiarity": id = R.string.analytics_ui_fact_familiarity_title; break;
            case "tool_style": id = R.string.analytics_ui_fact_tool_style_title; break;
            case "change": id = R.string.analytics_ui_fact_change_title; break;
            case "topic_change": id = R.string.analytics_ui_fact_topic_change_title; break;
            case "observed_attention": id = R.string.analytics_ui_fact_observed_attention_title; break;
            default: return item.title;
        }
        return UiText.get(id);
    }
    static String insightBody(PortraitSnapshot.Insight item) {
        DisplayFact f = item.display; if (f.size() == 0) return item.body;
        switch (f.id) {
            case "content_world": return UiText.get(R.string.analytics_ui_fact_content_world_body, n(f.arg(0)), n(f.arg(1)),
                UiText.get(((Number) f.arg(5)).intValue() == 0 ? R.string.analytics_ui_fact_world_insufficient
                    : ((Number) f.arg(5)).intValue() == 1 ? R.string.analytics_ui_fact_world_authors_even : R.string.analytics_ui_fact_world_topics_even));
            case "content_shape": return UiText.get(R.string.analytics_ui_fact_content_shape_body,
                f.arg(0) == null ? UiText.get(R.string.analytics_ui_unidentified) : rank((Rank) f.arg(0)), pct(((Number) f.arg(1)).doubleValue()), n(f.arg(2)));
            case "daily_rhythm": return UiText.get(R.string.analytics_ui_fact_daily_rhythm_body, n(f.arg(0)), pct(((Number) f.arg(1)).doubleValue()), pct(((Number) f.arg(2)).doubleValue()));
            case "familiarity": return UiText.get(R.string.analytics_ui_fact_familiarity_body, n(f.arg(0)), pct(PortraitMetrics.ratio(((Number) f.arg(0)).longValue(), ((Number) f.arg(1)).longValue())));
            case "tool_style": return UiText.get(R.string.analytics_ui_fact_tool_style_body, n(f.arg(0)), n(f.arg(1)), n(f.arg(2)));
            case "change": {
                PortraitSnapshot.Comparison c = (PortraitSnapshot.Comparison) f.arg(0);
                return UiText.get(R.string.analytics_ui_fact_change_body, n(c.currentViews), n(c.currentFeatures), n(c.previousViews), n(c.previousFeatures),
                    UiText.get(c.previousComplete ? R.string.analytics_ui_fact_change_today_incomplete : R.string.analytics_ui_fact_change_previous_incomplete));
            }
            case "topic_change": {
                PortraitSnapshot.Comparison c = (PortraitSnapshot.Comparison) f.arg(0);
                return UiText.get(R.string.analytics_ui_fact_topic_change_body, leadingTopic(c.currentTopics), leadingTopic(c.previousTopics),
                    UiText.get(c.previousComplete ? R.string.analytics_ui_fact_topic_change_complete : R.string.analytics_ui_fact_topic_change_incomplete));
            }
            case "observed_attention": return UiText.get(R.string.analytics_ui_fact_observed_attention_body, n(f.arg(0)), n(((Number) f.arg(1)).longValue() / 1000));
            default: return item.body;
        }
    }
    private static String leadingTopic(List<Rank> ranks) {
        return ranks.isEmpty() ? UiText.get(R.string.analytics_ui_no_topic_records)
            : UiText.get(R.string.analytics_ui_fact_topic_count, rank(ranks.get(0)), n(ranks.get(0).count));
    }
    static String insightEvidence(PortraitSnapshot.Insight item) {
        DisplayFact f = item.display; if (f.size() == 0) return item.evidence;
        switch (f.id) {
            case "content_world": return UiText.get(R.string.analytics_ui_fact_content_world_evidence, pct(((Number) f.arg(2)).doubleValue()), pct(((Number) f.arg(3)).doubleValue()), n(f.arg(4)));
            case "content_shape": return UiText.get(R.string.analytics_ui_fact_content_shape_evidence, n(f.arg(3)), n(f.arg(4)), f.arg(0) == null ? UiText.get(R.string.analytics_ui_unidentified) : rank((Rank) f.arg(0)));
            case "daily_rhythm": return UiText.get(R.string.analytics_ui_fact_daily_rhythm_evidence, n(f.arg(3)));
            case "familiarity": return UiText.get(R.string.analytics_ui_fact_familiarity_evidence, n(f.arg(0)), n(f.arg(1)), n(f.arg(2)));
            case "tool_style": return UiText.get(R.string.analytics_ui_fact_tool_style_evidence, n(f.arg(3)));
            case "change": {
                PortraitSnapshot.Comparison c = (PortraitSnapshot.Comparison) f.arg(0);
                return UiText.get(R.string.analytics_ui_fact_change_evidence, date(c.currentStart), date(c.currentEnd), date(c.previousStart), date(c.previousEnd));
            }
            case "topic_change": {
                PortraitSnapshot.Comparison c = (PortraitSnapshot.Comparison) f.arg(0);
                return UiText.get(R.string.analytics_ui_fact_topic_change_evidence, date(c.previousStart), date(c.previousEnd), date(c.currentStart), date(c.currentEnd));
            }
            case "observed_attention": return UiText.get(R.string.analytics_ui_fact_observed_attention_evidence);
            default: return item.evidence;
        }
    }
    static String insightLimitations(PortraitSnapshot.Insight item) {
        if (item.display.size() == 0) return item.limitations;
        switch (item.id) {
            case "content_world": return UiText.get(R.string.analytics_ui_fact_content_world_limit);
            case "content_shape": return UiText.get(R.string.analytics_ui_fact_content_shape_limit);
            case "daily_rhythm": return UiText.get(R.string.analytics_ui_fact_daily_rhythm_limit);
            case "familiarity": return UiText.get(R.string.analytics_ui_fact_familiarity_limit);
            case "tool_style": return UiText.get(R.string.analytics_ui_fact_tool_style_limit);
            case "change": return comparisonLimitation((PortraitSnapshot.Comparison) item.display.arg(0));
            case "topic_change": return UiText.get(R.string.analytics_ui_fact_topic_change_limit, comparisonLimitation((PortraitSnapshot.Comparison) item.display.arg(0)));
            case "observed_attention": return UiText.get(R.string.analytics_ui_fact_observed_attention_limit);
            default: return item.limitations;
        }
    }
    static String comparisonLimitation(PortraitSnapshot.Comparison c) {
        return "local_calendar_window".equals(c.displayId) ? UiText.get(c.previousComplete
            ? R.string.analytics_ui_comparison_limit_complete : R.string.analytics_ui_comparison_limit_incomplete) : c.limitation;
    }
    static List<String> coverage(PortraitSnapshot data) {
        if (data.displayCoverage.isEmpty()) return data.coverage;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < data.displayCoverage.size(); i++) {
            DisplayFact f = data.displayCoverage.get(i);
            switch (f.id) {
                case "window": result.add(UiText.get(R.string.analytics_ui_coverage_window, date(f.arg(0)), date(f.arg(1)))); break;
                case "retention": result.add(UiText.get(R.string.analytics_ui_coverage_retention)); break;
                case "visibility": result.add(UiText.get(R.string.analytics_ui_coverage_visibility)); break;
                case "topics": result.add(UiText.get(R.string.analytics_ui_coverage_topics)); break;
                case "authors": result.add(UiText.get(R.string.analytics_ui_coverage_authors, n(f.arg(0)), n(f.arg(1)))); break;
                case "sources": result.add(UiText.get(R.string.analytics_ui_coverage_sources)); break;
                case "joint": result.add(UiText.get(R.string.analytics_ui_coverage_joint)); break;
                case "earliest": result.add(UiText.get(R.string.analytics_ui_coverage_earliest, date(f.arg(0)))); break;
                case "dropped": result.add(UiText.get(R.string.analytics_ui_coverage_dropped, n(f.arg(0)))); break;
                default: if (i < data.coverage.size()) result.add(data.coverage.get(i));
            }
        }
        return result;
    }
    static String jointLeft(PortraitSnapshot.Joint item) {
        if ("topic_hours".equals(item.display.id)) return UiText.get(R.string.analytics_ui_hour_label, n(item.display.arg(0)));
        if ("type_actions".equals(item.display.id)) return dimension("type", (String) item.display.arg(0), item.left);
        return item.left;
    }
    static String jointRight(PortraitSnapshot.Joint item) {
        if (!"type_actions".equals(item.display.id)) return item.right;
        // Unknown dimensions retain the whole original fact rather than partially rewriting it.
        String feature = dimension("feature", (String) item.display.arg(1), null);
        String source = dimension("feature_source", (String) item.display.arg(2), null);
        String stage = dimension("stage", (String) item.display.arg(3), null);
        return feature == null || source == null || stage == null ? item.right
            : UiText.get(R.string.analytics_ui_three_labels, feature, source, stage);
    }
    static String feedback(String stableValue) {
        switch (stableValue) {
            case "符合": return UiText.get(R.string.analytics_ui_feedback_yes);
            case "部分符合": return UiText.get(R.string.analytics_ui_feedback_partial);
            case "不符合": return UiText.get(R.string.analytics_ui_feedback_no);
            default: return stableValue;
        }
    }
    static String failure(AnalyticsAiAnalysis.AnalysisFailure failure) {
        switch (failure.kind()) {
            case HTTP: return UiText.get(R.string.analytics_ui_failure_http, failure.httpStatus());
            case TIMEOUT: return UiText.get(R.string.analytics_ui_failure_timeout);
            case EMPTY: return UiText.get(R.string.analytics_ui_failure_empty);
            case OVERLONG: return UiText.get(R.string.analytics_ui_failure_overlong);
            case FORMAT: return UiText.get(R.string.analytics_ui_failure_format);
            case INCOMPLETE: return UiText.get(R.string.analytics_ui_failure_incomplete);
            case INPUT_TOO_LARGE: return UiText.get(R.string.analytics_ui_failure_input_too_large);
            case PREPARE: return UiText.get(R.string.analytics_ui_failure_prepare);
            default: return UiText.get(R.string.analytics_ui_failure_transport);
        }
    }
    static String shareText(AnalyticsAiAnalysis.Result ai) {
        StringBuilder out = new StringBuilder();
        if (ai.hasPortrait()) out.append(ai.portraitTitle).append('\n').append(ai.portrait)
            .append("\n\n").append(UiText.get(R.string.analytics_ui_share_contrasts)).append('\n').append(ai.contrasts)
            .append("\n\n").append(UiText.get(R.string.analytics_ui_share_questions)).append('\n').append(ai.questions);
        int[] titles = {R.string.analytics_ui_browsing_overview, R.string.analytics_ui_browsing_trend,
            R.string.analytics_ui_post_type_share, R.string.analytics_ui_creator_ranking, R.string.analytics_ui_topic_ranking,
            R.string.analytics_ui_feature_ranking, R.string.analytics_ui_browsing_hours};
        String[] bodies = {ai.overview, ai.trend, ai.types, ai.authors, ai.topics, ai.features, ai.hours};
        for (int i = 0; i < titles.length; i++) {
            if (out.length() > 0) out.append("\n\n");
            out.append(UiText.get(titles[i])).append('\n').append(bodies[i]);
        }
        return out.toString();
    }
}
