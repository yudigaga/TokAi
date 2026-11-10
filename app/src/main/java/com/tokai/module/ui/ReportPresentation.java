package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;
import com.tokai.module.i18n.ModuleLocale;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tokai.module.analytics.AnalyticsAiAnalysis;
import com.tokai.module.analytics.AnalyticsSnapshot;
import com.tokai.module.analytics.AnalyticsSnapshot.Rank;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Independent paper layout. It owns no Activity, controls, requests, or on-screen Views. */
final class ReportPresentation {
    static final int LOGICAL_WIDTH = 540;
    private static final int INK = 0xFF182C3A, MUTED = 0xFF61727E, TEAL = 0xFF087F80;
    private final Context context;
    private final LinearLayout paper;
    private int sectionNumber;

    static LinearLayout build(Context application, AnalyticsSnapshot data, String range, long generatedAt,
            AnalyticsAiAnalysis.Result ai, String model, String analyzedRange, long analyzedAt) {
        Context owner = application.getApplicationContext();
        Configuration configuration = new Configuration(owner.getResources().getConfiguration());
        configuration.densityDpi = 160; configuration.fontScale = 1f;
        configuration.setLocale(ModuleLocale.locale());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        ReportPresentation report = new ReportPresentation(owner.createConfigurationContext(configuration));
        report.cover(range, generatedAt);
        report.metrics(data);
        LinearLayout scope = report.section(UiText.get(R.string.analytics_ui_record_overview), UiText.get(R.string.analytics_ui_local_recorded_events));
        report.body(scope, UiText.get(R.string.analytics_ui_paper_record_boundary));
        if (data.advertisementViews > 0) report.body(scope, UiText.get(R.string.analytics_ui_advertisement_views, number(data.advertisementViews)));
        if (data.totalViews == 0 && data.totalFeatureUses == 0) report.body(scope, UiText.get(R.string.analytics_ui_paper_no_records));
        PortraitReportViews.appendPaper(report.context, data.portrait, new PortraitReportViews.Host() {
                @Override public LinearLayout section(String key, String title) { return report.section(title, UiText.get(R.string.analytics_ui_local_charts_evidence)); }
                @Override public void analysis(LinearLayout section, String key) { /* One complete AI section follows. */ }
        });
        if (data.portrait.days.isEmpty()) {
            LinearLayout trend = report.section(UiText.get(R.string.analytics_ui_browsing_trend), UiText.get(R.string.analytics_ui_local_daily_statistics));
            if (data.trend.isEmpty()) report.body(trend, UiText.get(R.string.analytics_ui_paper_no_trend));
            else {
                trend.addView(new Trend(report.context, data.trend), new LinearLayout.LayoutParams(-1, 150));
                for (AnalyticsSnapshot.Bucket point : data.trend) report.small(trend, UiText.get(R.string.analytics_ui_paper_label_count, point.label, number(point.count)));
            }
            LinearLayout types = report.ranking(UiText.get(R.string.analytics_ui_post_types), data.types, UiText.get(R.string.analytics_ui_paper_no_types));
            report.small(types, UiText.get(R.string.analytics_ui_paper_type_boundary));
            LinearLayout hours = report.section(UiText.get(R.string.analytics_ui_daily_exposure_hours), UiText.get(R.string.analytics_ui_local_time_statistics));
            for (int hour = 0; hour < data.hours.length; hour++) {
                if (data.hours[hour] > 0) report.bar(hours, String.format(ModuleLocale.locale(), "%02d:00—%02d:00", hour, (hour + 1) % 24),
                        data.hours[hour], maximum(data.hours));
            }
            report.small(hours, UiText.get(R.string.analytics_ui_hours_boundary));
        }
        if (!coveredBy(data.authors, data.portrait.authorPareto)) report.ranking(UiText.get(R.string.analytics_ui_creator_ranking), data.authors, UiText.get(R.string.analytics_ui_paper_no_creators));
        if (!coveredBy(data.topics, data.portrait.topicTiles)) report.ranking(UiText.get(R.string.analytics_ui_topic_ranking), data.topics, UiText.get(R.string.analytics_ui_paper_no_topics));
        report.ranking(UiText.get(R.string.analytics_ui_feature_ranking), data.features, UiText.get(R.string.analytics_ui_paper_no_features));
        LinearLayout recent = report.section(UiText.get(R.string.analytics_ui_recent_records), UiText.get(R.string.analytics_ui_local_recent_summaries));
        if (data.recent.isEmpty()) report.body(recent, UiText.get(R.string.analytics_ui_paper_no_recent));
        for (AnalyticsSnapshot.Recent item : data.recent) {
            report.body(recent, item.label);
            report.small(recent, date(item.timestamp, "MM-dd HH:mm") + "  ·  " + ("feature".equals(item.kind) ? UiText.get(R.string.analytics_ui_module_feature) : UiText.get(R.string.analytics_ui_browse_post)));
        }
        LinearLayout history = report.section(UiText.get(R.string.analytics_ui_usage_history), UiText.get(R.string.analytics_ui_local_retained_range));
        report.body(history, UiText.get(R.string.analytics_ui_paper_active_streak, number(data.activeDays), number(data.currentStreak)));
        report.small(history, data.firstSeen > 0 ? UiText.get(R.string.analytics_ui_first_retained_record, date(data.firstSeen, "yyyy-MM-dd HH:mm")) : UiText.get(R.string.analytics_ui_no_first_record_time));
        report.small(history, UiText.get(R.string.analytics_ui_activity_boundary));
        report.analysis(ai, model, analyzedRange, analyzedAt);
        LinearLayout boundary = report.section(UiText.get(R.string.analytics_ui_source_reading_boundaries), UiText.get(R.string.analytics_ui_about_report));
        report.body(boundary, UiText.get(R.string.analytics_ui_report_source_boundary));
        report.small(boundary, UiText.get(R.string.analytics_ui_report_ai_boundary));
        report.small(boundary, UiText.get(R.string.analytics_ui_report_share_boundary));
        report.small(report.paper, UiText.get(R.string.analytics_ui_report_footer, range));
        return report.paper;
    }

    private ReportPresentation(Context context) {
        this.context = context; paper = column(); paper.setTag("report.paper");
        paper.setBackgroundColor(0xFFFFFFFF); paper.setPadding(34, 36, 34, 30);
        paper.setLayoutParams(new LinearLayout.LayoutParams(LOGICAL_WIDTH, -2));
    }

    private void cover(String range, long generatedAt) {
        TextView eyebrow = text(UiText.get(R.string.analytics_ui_report_eyebrow), 11, TEAL, true);
        eyebrow.setLetterSpacing(.12f); paper.addView(eyebrow, wrap());
        TextView title = text(UiText.get(R.string.analytics_ui_my_local_report), 29, INK, true);
        title.setPadding(0, 14, 0, 9); paper.addView(title, wrap());
        TextView subtitle = text(UiText.get(R.string.analytics_ui_report_subtitle), 14, MUTED, false); paper.addView(subtitle, wrap());
        TextView metadata = text(UiText.get(R.string.analytics_ui_paper_report_metadata, range, date(generatedAt, "yyyy-MM-dd HH:mm")), 12, MUTED, false);
        metadata.setPadding(0, 18, 0, 20); paper.addView(metadata, wrap());
        View rule = new View(context); rule.setBackgroundColor(TEAL); paper.addView(rule, new LinearLayout.LayoutParams(52, 3));
    }

    private void metrics(AnalyticsSnapshot data) {
        LinearLayout grid = column(); LinearLayout.LayoutParams margin = wrap(); margin.topMargin = 25; paper.addView(grid, margin);
        String[] labels = {UiText.get(R.string.analytics_ui_exposure_views), UiText.get(R.string.analytics_ui_independent_works), UiText.get(R.string.analytics_ui_feature_triggers), UiText.get(R.string.analytics_ui_active_days)};
        long[] values = {data.totalViews, data.uniqueWorks, data.totalFeatureUses, data.activeDays};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(context);
            for (int col = 0; col < 2; col++) {
                int index = row * 2 + col;
                LinearLayout card = column(); card.setPadding(18, 15, 18, 15); card.setBackground(background(0xFFF1F7F8, 0, 10));
                card.addView(text(labels[index], 12, MUTED, false), wrap());
                TextView value = text(number(values[index]), 28, INK, true); value.setPadding(0, 5, 0, 0); card.addView(value, wrap());
                LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(0, -2, 1); box.bottomMargin = 10;
                if (col == 0) box.rightMargin = 10; line.addView(card, box);
            }
            grid.addView(line, wrap());
        }
    }

    private LinearLayout section(String title, String source) {
        LinearLayout block = column(); LinearLayout.LayoutParams params = wrap(); params.topMargin = 25;
        paper.addView(block, params);
        LinearLayout heading = column(); heading.setTag("report.keepTogether");
        TextView index = text(String.format(ModuleLocale.locale(), "%02d  /  %s", ++sectionNumber, source), 10, TEAL, true);
        index.setLetterSpacing(.04f); heading.addView(index, wrap());
        TextView name = text(title, 20, INK, true); name.setPadding(0, 5, 0, 10); heading.addView(name, wrap());
        View divider = new View(context); divider.setBackgroundColor(0xFFDDE8EB); heading.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        block.addView(heading, wrap());
        return block;
    }

    private LinearLayout ranking(String title, List<Rank> entries, String empty) {
        LinearLayout block = section(title, UiText.get(R.string.analytics_ui_local_top10));
        if (entries.isEmpty()) { body(block, empty); return block; }
        long max = 1; for (Rank rank : entries) max = Math.max(max, rank.count);
        for (int index = 0; index < Math.min(10, entries.size()); index++) {
            Rank rank = entries.get(index); bar(block, (index + 1) + "  " + AnalyticsUiLabels.rank(rank), rank.count, max);
        }
        return block;
    }

    private void bar(LinearLayout block, String label, long count, long max) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.TOP); row.setPadding(0, 12, 0, 5);
        row.addView(text(label, 13, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(number(count), 13, MUTED, true); value.setPadding(10, 0, 0, 0); row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        block.addView(row, wrap());
        View chart = new View(context) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setColor(0xFFEEF3F5); canvas.drawRoundRect(0, 0, getWidth(), 4, 2, 2, paint);
                paint.setColor(TEAL); canvas.drawRoundRect(0, 0, getWidth() * count / Math.max(1f, max), 4, 2, 2, paint);
            }
        };
        block.addView(chart, new LinearLayout.LayoutParams(-1, 5));
    }

    private void analysis(AnalyticsAiAnalysis.Result ai, String model, String range, long at) {
        LinearLayout section = section(UiText.get(R.string.analytics_ui_ai_interpretation), UiText.get(R.string.analytics_ui_ai_source_label));
        if (ai == null) { body(section, UiText.get(R.string.analytics_ui_no_ai_export)); return; }
        small(section, UiText.get(R.string.analytics_ui_paper_analysis_metadata, range, model, date(at, "yyyy-MM-dd HH:mm")));
        if (ai.hasPortrait()) {
            aiText(section, ai.portraitTitle, ai.portrait);
            aiText(section, UiText.get(R.string.analytics_ui_period_contrast), ai.contrasts); aiText(section, UiText.get(R.string.analytics_ui_questions_to_check), ai.questions);
        }
        String[] titles = {UiText.get(R.string.analytics_ui_overall_observation), UiText.get(R.string.analytics_ui_browsing_trend), UiText.get(R.string.analytics_ui_content_mix), UiText.get(R.string.analytics_ui_creator_exposure), UiText.get(R.string.analytics_ui_topic_exposure), UiText.get(R.string.analytics_ui_feature_usage), UiText.get(R.string.analytics_ui_time_rhythm)};
        String[] values = {ai.overview, ai.trend, ai.types, ai.authors, ai.topics, ai.features, ai.hours};
        for (int index = 0; index < titles.length; index++) aiText(section, titles[index], values[index]);
    }
    private void aiText(LinearLayout section, String title, String value) {
        TextView heading = text(title, 15, TEAL, true); heading.setTag("report.keepWithNext");
        heading.setPadding(0, 16, 0, 3); section.addView(heading, wrap());
        body(section, value);
    }
    private void body(LinearLayout parent, String value) { TextView t = text(value, 13, INK, false); t.setPadding(0, 10, 0, 2); parent.addView(t, wrap()); }
    private void small(LinearLayout parent, String value) { TextView t = text(value, 11, MUTED, false); t.setPadding(0, 9, 0, 2); parent.addView(t, wrap()); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false); text.setLineSpacing(3, 1.08f);
        text.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return text;
    }
    private LinearLayout column() { LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL); return column; }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private static GradientDrawable background(int color, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(radius);
        if (stroke != 0) background.setStroke(1, stroke); return background;
    }
    private static String number(long value) { return String.format(ModuleLocale.locale(), "%,d", value); }
    private static long maximum(long[] values) { long maximum = 1; for (long value : values) maximum = Math.max(maximum, value); return maximum; }
    private static boolean coveredBy(List<Rank> ranks, List<Rank> shown) {
        if (ranks.isEmpty()) return false;
        for (int i = 0; i < Math.min(10, ranks.size()); i++) {
            Rank rank = ranks.get(i); boolean found = false;
            for (Rank item : shown) if (rank.label.equals(item.label) && rank.count == item.count) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }
    private static String date(long value, String format) { return new SimpleDateFormat(format, ModuleLocale.locale()).format(new Date(value)); }

    private static final class Trend extends View {
        private final List<AnalyticsSnapshot.Bucket> points;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path();
        Trend(Context context, List<AnalyticsSnapshot.Bucket> points) { super(context); this.points = points; }
        @Override protected void onDraw(Canvas canvas) {
            float left = 8, top = 14, bottom = getHeight() - 22, width = getWidth() - 16;
            long max = 1; for (AnalyticsSnapshot.Bucket point : points) max = Math.max(max, point.count);
            paint.setStrokeWidth(1); paint.setColor(0xFFE2EBEF);
            for (int line = 0; line < 4; line++) { float y = top + (bottom - top) * line / 3; canvas.drawLine(left, y, left + width, y, paint); }
            line.reset();
            for (int i = 0; i < points.size(); i++) {
                float x = left + width * i / Math.max(1, points.size() - 1), y = bottom - (bottom - top) * points.get(i).count / max;
                if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
            }
            paint.setColor(TEAL); paint.setStrokeWidth(2.5f); paint.setStyle(Paint.Style.STROKE); canvas.drawPath(line, paint); paint.setStyle(Paint.Style.FILL);
        }
    }
}
