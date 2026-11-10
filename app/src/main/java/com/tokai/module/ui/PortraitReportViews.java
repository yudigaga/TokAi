package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;
import com.tokai.module.i18n.ModuleLocale;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tokai.module.analytics.AnalyticsSnapshot.Rank;
import com.tokai.module.analytics.PortraitSnapshot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** All geometry and summaries are local; AI text is attached by the owning dialog. */
final class PortraitReportViews {
    private static final int TEXT = 0xFF1F272D, MUTED = 0xFF69737A, ACCENT = 0xFF008579;
    private static final int[] COLORS = {0xFF1683EE, 0xFF54B76A, 0xFF9869D8,
            0xFFF5A33C, 0xFFE66C9B, 0xFF89959F, 0xFF1FA8AA, 0xFFB48B56, 0xFF65758C};
    interface Host {
        LinearLayout section(String key, String title);
        void analysis(LinearLayout section, String key);
        default Button button(Context context) { return new Button(context); }
        default View controlView(View control) { return control; }
    }
    private final Context context;
    private final PortraitSnapshot data;
    private final Host host;
    private final boolean paper;
    PortraitReportViews(Context context, PortraitSnapshot data, Host host) {
        this(context, data, host, false);
    }
    private PortraitReportViews(Context context, PortraitSnapshot data, Host host, boolean paper) {
        this.context = context; this.data = data; this.host = host;
        this.paper = paper;
    }
    /** A fresh, noninteractive report tree; never shares a View with the dialog. */
    static void appendPaper(Context context, PortraitSnapshot data, Host host) {
        PortraitReportViews report = new PortraitReportViews(context, data, host, true);
        report.overview();
        if (!data.days.isEmpty()) { report.calendar(); report.typeBands(); }
        if (!data.topicTiles.isEmpty() || data.topicCount > 0) report.topics();
        if (!data.authorPareto.isEmpty() || data.authorCount > 0) report.authors();
        boolean rhythm = false;
        for (long[] day : data.weekdayHours) for (long count : day) if (count > 0) rhythm = true;
        if (rhythm) report.rhythm();
        if (data.comparison != null && !data.comparison.currentStart.isEmpty()) report.comparison();
        if (!data.featureSources.isEmpty() || !data.topicHours.isEmpty() || !data.typeActions.isEmpty()
                || data.capturedVisits > 0 || data.finishedVisits > 0 || data.visibleMillis > 0) report.associations();
    }

    void overview() {
        LinearLayout card = host.section("portrait", UiText.get(R.string.analytics_ui_portrait_heading));
        card.setTag("analytics.portrait.overview");
        add(card, UiText.get(R.string.analytics_ui_portrait_subtitle), 13, MUTED, false);
        add(card, UiText.get(R.string.analytics_ui_evidence_period, AnalyticsUiLabels.evidenceLevel(data), AnalyticsUiLabels.date(data.startDate), AnalyticsUiLabels.date(data.endDate)), 13, MUTED, false);
        if (data.insights.isEmpty()) {
            add(card, UiText.get(R.string.analytics_ui_portrait_insufficient), 14, TEXT, false);
        }
        for (PortraitSnapshot.Insight insight : data.insights) {
            heading(card, AnalyticsUiLabels.insightTitle(insight));
            add(card, AnalyticsUiLabels.insightBody(insight), 14, TEXT, false);
            disclosure(card, paper ? UiText.get(R.string.analytics_ui_evidence_heading) : UiText.get(R.string.analytics_ui_view_evidence), AnalyticsUiLabels.insightEvidence(insight) + "\n" + AnalyticsUiLabels.insightLimitations(insight),
                    "analytics.portrait.evidence." + insight.id);
        }
        disclosure(card, UiText.get(R.string.analytics_ui_portrait_scope_title), UiText.get(R.string.analytics_ui_portrait_reading_boundary, String.join("\n\n", AnalyticsUiLabels.coverage(data))), "analytics.portrait.coverage");
        host.analysis(card, "portrait");
    }

    void charts() {
        calendar(); typeBands(); topics(); authors(); rhythm(); comparison(); associations();
    }

    private void calendar() {
        LinearLayout card = host.section("trend", UiText.get(R.string.analytics_ui_calendar_title));
        List<PortraitSnapshot.Day> shown = data.days.subList(Math.max(0, data.days.size() - 84), data.days.size());
        TextView selected = add(card, paper ? UiText.get(R.string.analytics_ui_calendar_paper_hint) : UiText.get(R.string.analytics_ui_calendar_select_hint), 13, MUTED, false);
        selected.setTag("analytics.portrait.calendar.selection");
        chart(card, new CalendarChart(context, shown, value -> select(selected, value)),
                "calendar", Math.max(108, 38 + (int) Math.ceil((shown.size() + 6) / 7.0) * 22));
        add(card, UiText.get(R.string.analytics_ui_calendar_boundary, (data.days.size() > 84 ? UiText.get(R.string.analytics_ui_calendar_last84) : "")), 13, MUTED, false);
        host.analysis(card, "trend");
    }

    private void typeBands() {
        LinearLayout card = host.section("types", UiText.get(R.string.analytics_ui_type_bands_title));
        List<Band> bands = new ArrayList<>();
        Map<String, Integer> colors = new LinkedHashMap<>();
        int step = Math.max(1, (int) Math.ceil(data.days.size() / 6.0));
        for (int start = 0; start < data.days.size(); start += step) {
            int end = Math.min(start + step, data.days.size());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (int index = start; index < end; index++) {
                for (Map.Entry<String, Long> type : data.days.get(index).types.entrySet()) {
                    counts.merge(type.getKey(), type.getValue(), Long::sum);
                    if (!colors.containsKey(type.getKey())) colors.put(type.getKey(), COLORS[colors.size() % COLORS.length]);
                }
            }
            String from = shortDate(data.days.get(start).date), to = shortDate(data.days.get(end - 1).date);
            bands.add(new Band(from.equals(to) ? from : from + "—" + to, counts));
        }
        TextView selected = add(card, paper ? UiText.get(R.string.analytics_ui_type_bands_paper_hint) : UiText.get(R.string.analytics_ui_type_bands_select_hint), 13, MUTED, false);
        selected.setTag("analytics.portrait.types.selection");
        chart(card, new TypeChart(context, bands, colors, AnalyticsUiLabels.dayTypeIds(data.days), value -> select(selected, value)),
                "types", Math.max(76, bands.size() * 52));
        for (Map.Entry<String, Integer> item : colors.entrySet()) legend(card, AnalyticsUiLabels.dayType(data.days, item.getKey()), item.getValue());
        add(card, UiText.get(R.string.analytics_ui_type_bands_boundary), 13, MUTED, false);
        host.analysis(card, "types");
    }

    private void topics() {
        LinearLayout card = host.section("topics", paper ? UiText.get(R.string.analytics_ui_topic_paper_title) : UiText.get(R.string.analytics_ui_topic_map_title));
        if (data.topicTiles.isEmpty()) add(card, UiText.get(R.string.analytics_ui_topics_unidentified), 14, MUTED, false);
        else {
            TextView selected = add(card, paper ? UiText.get(R.string.analytics_ui_topic_map_paper_hint) : UiText.get(R.string.analytics_ui_topic_map_select_hint), 13, MUTED, false);
            selected.setTag("analytics.portrait.topics.selection");
            chart(card, new TopicChart(context, data.topicTiles, value -> select(selected, value)), "topics", 230);
            for (int index = 0; index < data.topicTiles.size(); index++) {
                Rank rank = data.topicTiles.get(index);
                legend(card, UiText.get(R.string.analytics_ui_label_count, AnalyticsUiLabels.rank(rank), number(rank.count)), COLORS[index % COLORS.length]);
            }
        }
        add(card, UiText.get(R.string.analytics_ui_topics_boundary, number(data.topicCount), number(data.topicOccurrences)), 13, MUTED, false);
        host.analysis(card, "topics");
    }

    private void authors() {
        LinearLayout card = host.section("authors", paper ? UiText.get(R.string.analytics_ui_author_paper_title) : UiText.get(R.string.analytics_ui_author_curve_title));
        if (data.authorPareto.isEmpty()) add(card, UiText.get(R.string.analytics_ui_authors_unidentified), 14, MUTED, false);
        else {
            TextView selected = add(card, UiText.get(R.string.analytics_ui_author_curve_hint), 13, MUTED, false);
            selected.setTag("analytics.portrait.authors.selection");
            chart(card, new AuthorChart(context, data.authorPareto, value -> select(selected, value)), "authors", 185);
            for (int index = 0; index < data.authorPareto.size(); index++) {
                Rank rank = data.authorPareto.get(index);
                add(card, UiText.get(R.string.analytics_ui_ranked_label_count, (index + 1), AnalyticsUiLabels.rank(rank), number(rank.count)), 13, MUTED, false);
            }
        }
        add(card, UiText.get(R.string.analytics_ui_authors_boundary, number(data.authorCount), pct(data.crossDayRepeatShare)), 13, MUTED, false);
        host.analysis(card, "authors");
    }

    private void rhythm() {
        LinearLayout card = host.section("hours", UiText.get(R.string.analytics_ui_rhythm_title));
        TextView selected = add(card, paper ? UiText.get(R.string.analytics_ui_rhythm_paper_hint) : UiText.get(R.string.analytics_ui_rhythm_select_hint), 13, MUTED, false);
        selected.setTag("analytics.portrait.rhythm.selection");
        chart(card, new RhythmChart(context, data.weekdayHours, value -> select(selected, value)), "rhythm", 220);
        add(card, UiText.get(R.string.analytics_ui_rhythm_boundary, pct(data.nightShare), pct(data.weekendShare)), 13, MUTED, false);
        host.analysis(card, "hours");
    }

    private void comparison() {
        LinearLayout card = host.section("changes", UiText.get(R.string.analytics_ui_comparison_title));
        PortraitSnapshot.Comparison comparison = data.comparison;
        if (comparison == null) { add(card, UiText.get(R.string.analytics_ui_no_comparable_period), 14, MUTED, false); return; }
        add(card, UiText.get(R.string.analytics_ui_comparison_dates, AnalyticsUiLabels.date(comparison.currentStart), AnalyticsUiLabels.date(comparison.currentEnd), AnalyticsUiLabels.date(comparison.previousStart), AnalyticsUiLabels.date(comparison.previousEnd)), 13, MUTED, false);
        if (!comparison.currentComplete || !comparison.previousComplete) {
            add(card, UiText.get(R.string.analytics_ui_coverage_incomplete), 14, TEXT, true);
        }
        TextView selected = add(card, UiText.get(R.string.analytics_ui_comparison_chart_hint), 13, MUTED, false);
        selected.setTag("analytics.portrait.comparison.selection");
        chart(card, new CompareChart(context, comparison, value -> select(selected, value)), "comparison", 155);
        add(card, AnalyticsUiLabels.comparisonLimitation(comparison), 13, MUTED, false);
        topicChanges(card, comparison);
        if (data.weekComparison != null) periodEvidence(card, "最近 7 天", UiText.get(R.string.analytics_ui_last7days), data.weekComparison);
        if (data.monthComparison != null) periodEvidence(card, "最近 30 天", UiText.get(R.string.analytics_ui_last30days), data.monthComparison);
        host.analysis(card, "changes");
    }

    private void topicChanges(LinearLayout card, PortraitSnapshot.Comparison comparison) {
        heading(card, UiText.get(R.string.analytics_ui_topic_migration_title));
        List<Rank> current = namedTopics(comparison.currentTopics);
        List<Rank> previous = namedTopics(comparison.previousTopics);
        if (current.isEmpty() || previous.isEmpty()) {
            String missing = current.isEmpty() && previous.isEmpty() ? UiText.get(R.string.analytics_ui_both_periods) : current.isEmpty() ? UiText.get(R.string.analytics_ui_current_period) : UiText.get(R.string.analytics_ui_previous_period);
            TextView empty = add(card, UiText.get(R.string.analytics_ui_missing_topic_period, missing), 13, MUTED, false);
            empty.setTag("analytics.portrait.comparison.topics.empty");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < Math.min(3, current.size()); index++) labels.add(current.get(index).label);
        for (int index = 0; index < Math.min(3, previous.size()) && labels.size() < 5; index++) {
            String label = previous.get(index).label;
            if (!labels.contains(label)) labels.add(label);
        }
        for (int index = 0; index < labels.size(); index++) {
            String name = labels.get(index);
            TextView row = add(card, name + "\n" + rankDescription(previous, name) + "  →  "
                    + rankDescription(current, name), 13, MUTED, false);
            row.setTag("analytics.portrait.comparison.topics.row." + index);
        }
        add(card, UiText.get(R.string.analytics_ui_topic_migration_boundary), 13, MUTED, false);
    }

    private static List<Rank> namedTopics(List<Rank> ranks) {
        List<Rank> result = new ArrayList<>();
        for (Rank rank : ranks) if (!rank.label.startsWith("其他（")) result.add(rank);
        return result;
    }

    private static String rankDescription(List<Rank> ranks, String name) {
        for (int index = 0; index < ranks.size(); index++) if (ranks.get(index).label.equals(name)) {
            return UiText.get(R.string.analytics_ui_rank_description, (index + 1), number(ranks.get(index).count));
        }
        return UiText.get(R.string.analytics_ui_not_summarized);
    }

    private void periodEvidence(LinearLayout card, String stableLabel, String label, PortraitSnapshot.Comparison item) {
        disclosure(card, UiText.get(R.string.analytics_ui_period_evidence_title, label), UiText.get(R.string.analytics_ui_period_evidence_body, AnalyticsUiLabels.date(item.currentStart), AnalyticsUiLabels.date(item.currentEnd), number(item.currentViews), number(item.currentFeatures), AnalyticsUiLabels.date(item.previousStart), AnalyticsUiLabels.date(item.previousEnd), number(item.previousViews), number(item.previousFeatures), AnalyticsUiLabels.comparisonLimitation(item)),
                "analytics.portrait.period." + stableLabel);
    }

    private void associations() {
        LinearLayout card = host.section("associations", UiText.get(R.string.analytics_ui_associations_title));
        if (!data.featureSources.isEmpty()) {
            heading(card, UiText.get(R.string.analytics_ui_feature_sources));
            for (Rank source : data.featureSources) add(card, UiText.get(R.string.analytics_ui_label_count, AnalyticsUiLabels.rank(source), number(source.count)), 13, MUTED, false);
        }
        if (data.topicHours.isEmpty() && data.typeActions.isEmpty()) {
            add(card, UiText.get(R.string.analytics_ui_no_associations), 14, MUTED, false);
        }
        if (!data.topicHours.isEmpty()) {
            heading(card, UiText.get(R.string.analytics_ui_topic_hour_associations));
            joint(card, data.topicHours, "topicHours");
            add(card, UiText.get(R.string.analytics_ui_topic_hour_boundary), 13, MUTED, false);
        }
        if (!data.typeActions.isEmpty()) {
            heading(card, UiText.get(R.string.analytics_ui_type_action_associations));
            joint(card, data.typeActions, "typeActions");
        }
        add(card, UiText.get(R.string.analytics_ui_visible_segments_detail, number(data.capturedVisits), number(data.finishedVisits), duration(data.visibleMillis)), 13, MUTED, false);
        host.analysis(card, "associations");
    }

    private void joint(LinearLayout card, List<PortraitSnapshot.Joint> joints, String key) {
        int count = Math.min(8, joints.size());
        for (int index = 0; index < count; index++) {
            PortraitSnapshot.Joint item = joints.get(index);
            TextView row = add(card, UiText.get(R.string.analytics_ui_joint_count, AnalyticsUiLabels.jointLeft(item), AnalyticsUiLabels.jointRight(item), number(item.count)), 13, TEXT, false);
            row.setTag("analytics.portrait." + key + "." + index);
        }
    }

    private void legend(LinearLayout card, String text, int color) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View swatch = new View(context); swatch.setBackgroundColor(color);
        ModuleTheme.preserveColors(swatch);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView label = new TextView(context); label.setText(text); label.setTextSize(13); label.setTextColor(TEXT);
        label.setIncludeFontPadding(false);
        label.setPadding(dp(8), dp(5), 0, dp(5));
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private void chart(LinearLayout parent, View view, String key, int height) {
        if (paper && view instanceof Chart) {
            ((Chart) view).paper = true;
            view.setClickable(false); view.setFocusable(false);
        }
        view.setTag("analytics.portrait." + key + ".chart");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        parent.addView(view, params);
    }
    private void heading(LinearLayout parent, String value) {
        TextView text = add(parent, value, 16, TEXT, true);
        if (paper) text.setTag("report.keepWithNext");
        text.setPadding(0, dp(12), 0, dp(4));
    }
    private TextView add(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setPadding(0, dp(4), 0, dp(4)); text.setLineSpacing(dp(2), 1);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2)); return text;
    }
    private void disclosure(LinearLayout card, String label, String content, String tag) {
        if (paper) {
            heading(card, label);
            TextView evidence = add(card, content, 13, MUTED, false);
            evidence.setTag(tag + ".text");
            return;
        }
        Button button = host.button(context); button.setText(label); button.setTextSize(14); button.setTextColor(ACCENT);
        button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setBackgroundColor(Color.TRANSPARENT); button.setTag(tag);
        card.addView(host.controlView(button), new LinearLayout.LayoutParams(-1, -2));
        TextView evidence = add(card, content, 13, MUTED, false); evidence.setVisibility(View.GONE);
        evidence.setTag(tag + ".text"); evidence.setTextIsSelectable(true);
        button.setContentDescription(UiText.get(R.string.analytics_ui_collapsed_label, label));
        button.setOnClickListener(view -> {
            boolean expand = evidence.getVisibility() != View.VISIBLE;
            evidence.setVisibility(expand ? View.VISIBLE : View.GONE);
            button.setContentDescription(UiText.get(expand ? R.string.analytics_ui_expanded_label : R.string.analytics_ui_collapsed_label, label));
        });
    }
    private static void select(TextView label, String value) { label.setText(value); label.announceForAccessibility(value); }
    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static String number(long value) { return String.format(ModuleLocale.locale(), "%,d", value); }
    private static String pct(double value) { return String.format(ModuleLocale.locale(), "%.1f%%", value * 100); }
    private static String shortDate(String value) { try { return LocalDate.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd", ModuleLocale.locale())); } catch (RuntimeException ignored) { return value; } }
    private static String duration(long millis) { return millis < 60_000 ? UiText.get(R.string.analytics_ui_duration_seconds, (millis / 1000)) : UiText.get(R.string.analytics_ui_duration_minutes, (millis / 60_000)); }
    private static long total(List<Rank> ranks) { long total = 0; for (Rank rank : ranks) total += rank.count; return total; }

    private static final class Band {
        final String label; final Map<String, Long> counts;
        Band(String label, Map<String, Long> counts) { this.label = label; this.counts = counts; }
    }
    private static final class Hit {
        final RectF rect; final String label;
        Hit(RectF rect, String label) { this.rect = rect; this.label = label; }
    }
    private abstract static class Chart extends View {
        boolean paper;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final List<Hit> hits = new ArrayList<>(); final Consumer<String> selection;
        int chosen = -1; float downX, downY;
        private Picture drawing;
        private int drawingWidth, drawingHeight;
        private float drawingDensity, drawingFontScale;
        private boolean drawingDark;

        // Data is immutable for this chart. Cache geometry, labels and accessibility hit targets
        // across selection redraws; record again on size, density, font scale or theme changes.
        @Override protected final void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float fontScale = getResources().getDisplayMetrics().scaledDensity;
            boolean dark = !paper && ModuleTheme.isDark(getContext());
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (drawing == null || drawingWidth != getWidth() || drawingHeight != getHeight()
                    || drawingDensity != density || drawingFontScale != fontScale || drawingDark != dark) {
                recordChart(density, fontScale, dark);
            }
            canvas.drawPicture(drawing);
            selection(canvas);
        }

        private void recordChart(float density, float fontScale, boolean dark) {
            Picture next = new Picture();
            Canvas recorded = next.beginRecording(getWidth(), getHeight());
            try { drawChart(recorded); }
            finally { next.endRecording(); }
            drawingWidth = getWidth(); drawingHeight = getHeight();
            drawingDensity = density; drawingFontScale = fontScale; drawingDark = dark;
            drawing = next;
        }

        abstract void drawChart(Canvas canvas);

        @Override protected void onDetachedFromWindow() {
            drawing = null;
            super.onDetachedFromWindow();
        }
        Chart(Context context, Consumer<String> selection) {
            super(context); this.selection = selection; setClickable(true); setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        float d(float value) { return value * getResources().getDisplayMetrics().density; }
        int color(int value) { return paper ? value : ModuleTheme.color(getContext(), value); }
        void label(Canvas canvas, String text, float x, float y, int color, float size, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(color == Color.WHITE ? Color.WHITE : color(color)); paint.setTextAlign(align);
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(text, x, y, paint);
        }
        void box(Canvas canvas, RectF bounds, int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawRoundRect(bounds, d(3), d(3), paint); }
        void hit(RectF bounds, String label) { hits.add(new Hit(new RectF(bounds), label)); }
        void describe(String title) {
            List<String> labels = new ArrayList<>();
            for (Hit hit : hits) labels.add(hit.label);
            setContentDescription(UiText.get(R.string.analytics_ui_chart_accessibility_summary, title, String.join("; ", labels)));
        }
        void selection(Canvas canvas) {
            if (chosen < 0 || chosen >= hits.size()) return;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d(2)); paint.setColor(color(TEXT));
            canvas.drawRoundRect(hits.get(chosen).rect, d(3), d(3), paint); paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean performClick() {
            super.performClick(); if (hits.isEmpty()) return false;
            chosen = (chosen + 1) % hits.size(); selection.accept(hits.get(chosen).label); invalidate(); return true;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (Math.abs(downX - event.getX()) + Math.abs(downY - event.getY()) < d(16)) {
                    for (int index = 0; index < hits.size(); index++) if (hits.get(index).rect.contains(event.getX(), event.getY())) {
                        chosen = index; selection.accept(hits.get(index).label); invalidate(); super.performClick(); break;
                    }
                }
                return true;
            }
            return event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        }
        @Override protected void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); invalidate(); }
        int intensity(long value, long max) {
            if (value <= 0) return color(0xFFEEEFF3);
            float strength = .18f + .82f * (float) Math.sqrt(value / (double) Math.max(1, max));
            int background = !paper && ModuleTheme.isDark(getContext()) ? 0xFF252B31 : 0xFFEAF4FE;
            return Color.rgb(Math.round(Color.red(background) * (1 - strength) + 22 * strength),
                    Math.round(Color.green(background) * (1 - strength) + 131 * strength),
                    Math.round(Color.blue(background) * (1 - strength) + 238 * strength));
        }
    }

    private static final class CalendarChart extends Chart {
        final List<PortraitSnapshot.Day> days;
        CalendarChart(Context context, List<PortraitSnapshot.Day> days, Consumer<String> selection) { super(context, selection); this.days = days; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); String[] labels = {UiText.get(R.string.analytics_ui_weekday_mon_short), UiText.get(R.string.analytics_ui_weekday_tue_short), UiText.get(R.string.analytics_ui_weekday_wed_short), UiText.get(R.string.analytics_ui_weekday_thu_short), UiText.get(R.string.analytics_ui_weekday_fri_short), UiText.get(R.string.analytics_ui_weekday_sat_short), UiText.get(R.string.analytics_ui_weekday_sun_short)};
            float cell = getWidth() / 7f; long max = 1; for (PortraitSnapshot.Day day : days) max = Math.max(max, day.views);
            for (int column = 0; column < 7; column++) label(canvas, labels[column], (column + .5f) * cell, d(15), MUTED, 11, Paint.Align.CENTER);
            int offset = days.isEmpty() ? 0 : LocalDate.parse(days.get(0).date).getDayOfWeek().getValue() - 1;
            for (int index = 0; index < days.size(); index++) {
                PortraitSnapshot.Day day = days.get(index); int slot = index + offset;
                float x = slot % 7 * cell, y = d(26 + slot / 7 * 22);
                RectF bounds = new RectF(x + d(3), y, x + cell - d(3), y + d(18));
                box(canvas, bounds, intensity(day.views, max));
                if (day.views == 0 && day.features == 0) {
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d(1)); paint.setColor(color(0xFFE2E7E8)); canvas.drawRoundRect(bounds, d(3), d(3), paint);
                }
                label(canvas, String.format(ModuleLocale.locale(), "%02d", LocalDate.parse(day.date).getDayOfMonth()), x + cell / 2, y + d(13), day.views > max / 2 ? Color.WHITE : TEXT, 10, Paint.Align.CENTER);
                // Preserve the exact ISO event date in selectable evidence across UI languages.
                hit(bounds, UiText.get(R.string.analytics_ui_calendar_day_hit, day.date, number(day.views), number(day.features), (day.views == 0 && day.features == 0 ? UiText.get(R.string.analytics_ui_no_retained_events_fragment) : "")));
            }
            describe(UiText.get(R.string.analytics_ui_calendar_chart_description));
        }
    }

    private static final class RhythmChart extends Chart {
        final long[][] values;
        RhythmChart(Context context, long[][] values, Consumer<String> selection) { super(context, selection); this.values = values; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); long max = 1; for (long[] day : values) for (long count : day) max = Math.max(max, count);
            String[] weekdays = {UiText.get(R.string.analytics_ui_weekday_mon), UiText.get(R.string.analytics_ui_weekday_tue), UiText.get(R.string.analytics_ui_weekday_wed), UiText.get(R.string.analytics_ui_weekday_thu), UiText.get(R.string.analytics_ui_weekday_fri), UiText.get(R.string.analytics_ui_weekday_sat), UiText.get(R.string.analytics_ui_weekday_sun)};
            paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
            float left = d(29);
            for (String weekday : weekdays) left = Math.max(left, paint.measureText(weekday) + d(6));
            float unit = Math.max(1, getWidth() - left) / 24;
            for (int day = 0; day < 7; day++) {
                label(canvas, weekdays[day], 0, d(41 + day * 25), MUTED, 10, Paint.Align.LEFT);
                for (int hour = 0; hour < 24; hour++) {
                    long count = day < values.length && hour < values[day].length ? values[day][hour] : 0;
                    RectF bounds = new RectF(left + hour * unit + d(.6f), d(27 + day * 25), left + (hour + 1) * unit - d(.6f), d(47 + day * 25));
                    box(canvas, bounds, intensity(count, max)); hit(bounds, UiText.get(R.string.analytics_ui_rhythm_hour_hit, weekdays[day], number(hour), number(count)));
                }
            }
            for (int hour = 0; hour < 24; hour += 6) label(canvas, UiText.get(R.string.analytics_ui_hour_label, number(hour)), left + hour * unit, d(15), MUTED, 10, Paint.Align.LEFT);
            describe(UiText.get(R.string.analytics_ui_rhythm_chart_description));
        }
    }

    private static final class TypeChart extends Chart {
        final List<Band> bands; final Map<String, Integer> colors; final Map<String, String> typeIds;
        TypeChart(Context context, List<Band> bands, Map<String, Integer> colors, Map<String, String> typeIds, Consumer<String> selection) { super(context, selection); this.bands = bands; this.colors = colors; this.typeIds = typeIds; }
        @Override void drawChart(Canvas canvas) {
            hits.clear();
            for (int index = 0; index < bands.size(); index++) {
                Band band = bands.get(index); long total = 0; for (long count : band.counts.values()) total += count;
                float y = d(index * 52 + 17);
                label(canvas, UiText.get(R.string.analytics_ui_label_count, band.label, number(total)), 0, y, MUTED, 11, Paint.Align.LEFT);
                box(canvas, new RectF(0, y + d(6), getWidth(), y + d(24)), color(0xFFEEEFF3));
                float x = 0;
                for (Map.Entry<String, Long> item : band.counts.entrySet()) {
                    if (item.getValue() <= 0 || total <= 0) continue;
                    float next = x + getWidth() * item.getValue() / (float) total;
                    RectF bounds = new RectF(x, y + d(6), next, y + d(24)); box(canvas, bounds, colors.get(item.getKey()));
                    hit(bounds, UiText.get(R.string.analytics_ui_type_band_hit, band.label, AnalyticsUiLabels.dimension("type", typeIds.get(item.getKey()), item.getKey()), number(item.getValue()), pct(item.getValue() / (double) total))); x = next;
                }
            }
            describe(UiText.get(R.string.analytics_ui_type_bands_description));
        }
    }

    private static final class TopicChart extends Chart {
        final List<Rank> ranks;
        TopicChart(Context context, List<Rank> ranks, Consumer<String> selection) { super(context, selection); this.ranks = ranks; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); long remaining = total(ranks); RectF available = new RectF(0, d(6), getWidth(), getHeight() - d(6));
            for (int index = 0; index < ranks.size(); index++) {
                Rank rank = ranks.get(index); if (rank.count <= 0 || remaining <= 0) continue;
                RectF tile = new RectF(available); float fraction = rank.count / (float) remaining;
                if (available.width() >= available.height()) { tile.right = tile.left + available.width() * fraction; available.left = tile.right; }
                else { tile.bottom = tile.top + available.height() * fraction; available.top = tile.bottom; }
                remaining -= rank.count; RectF paintBounds = new RectF(tile); paintBounds.inset(d(1.5f), d(1.5f));
                if (paintBounds.width() > 0 && paintBounds.height() > 0) box(canvas, paintBounds, COLORS[index % COLORS.length]);
                canvas.save(); canvas.clipRect(tile);
                paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
                String label = AnalyticsUiLabels.rank(rank); while (label.length() > 1 && paint.measureText(label) > tile.width() - d(10)) label = label.substring(0, label.length() - 1);
                if (!label.equals(AnalyticsUiLabels.rank(rank)) && label.length() > 1) label = label.substring(0, label.length() - 1) + "…";
                if (tile.width() > d(28) && tile.height() > d(24)) label(canvas, label, tile.centerX(), tile.centerY(), Color.WHITE, 11, Paint.Align.CENTER);
                if (tile.height() > d(46) && tile.width() > d(34)) label(canvas, number(rank.count), tile.centerX(), tile.centerY() + d(16), Color.WHITE, 11, Paint.Align.CENTER);
                canvas.restore(); hit(tile, UiText.get(R.string.analytics_ui_topic_occurrences, AnalyticsUiLabels.rank(rank), number(rank.count)));
            }
            describe(UiText.get(R.string.analytics_ui_topic_treemap_description));
        }
    }

    private static final class AuthorChart extends Chart {
        final List<Rank> ranks;
        AuthorChart(Context context, List<Rank> ranks, Consumer<String> selection) { super(context, selection); this.ranks = ranks; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); long total = total(ranks), accumulated = 0; float left = d(34), bottom = getHeight() - d(28), top = d(22);
            float width = getWidth() - left - d(10), lastX = left, lastY = bottom;
            for (int line = 0; line <= 2; line++) {
                float y = bottom - (bottom - top) * line / 2;
                paint.setColor(color(0xFFE2E7E8)); paint.setStrokeWidth(d(1)); canvas.drawLine(left, y, getWidth(), y, paint);
                label(canvas, String.format(ModuleLocale.locale(), "%d%%", line * 50), left - d(4), y + d(4), MUTED, 10, Paint.Align.RIGHT);
            }
            for (int index = 0; index < ranks.size(); index++) {
                Rank rank = ranks.get(index); accumulated += rank.count;
                float x = left + width * (index + 1) / Math.max(1, ranks.size()), y = bottom - (bottom - top) * accumulated / Math.max(1, total);
                paint.setColor(COLORS[0]); paint.setStrokeWidth(d(2.5f)); canvas.drawLine(lastX, lastY, x, y, paint); canvas.drawCircle(x, y, d(4), paint);
                label(canvas, String.valueOf(index + 1), x, bottom + d(18), MUTED, 10, Paint.Align.CENTER);
                hit(new RectF(x - d(10), top, x + d(10), bottom), UiText.get(R.string.analytics_ui_author_cumulative_hit, AnalyticsUiLabels.rank(rank), number(rank.count), pct(accumulated / (double) Math.max(1, total))));
                lastX = x; lastY = y;
            }
            describe(UiText.get(R.string.analytics_ui_author_curve_description));
        }
    }

    private static final class CompareChart extends Chart {
        final PortraitSnapshot.Comparison data;
        CompareChart(Context context, PortraitSnapshot.Comparison data, Consumer<String> selection) { super(context, selection); this.data = data; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); String[] labels = {UiText.get(R.string.analytics_ui_view_count), UiText.get(R.string.analytics_ui_feature_triggers)};
            long[] current = {data.currentViews, data.currentFeatures}, previous = {data.previousViews, data.previousFeatures};
            for (int index = 0; index < 2; index++) {
                float y = d(20 + index * 76), left = d(8), right = getWidth() - d(8);
                label(canvas, UiText.get(R.string.analytics_ui_compare_values, labels[index], number(previous[index]), number(current[index])), 0, y, MUTED, 11, Paint.Align.LEFT);
                long max = Math.max(1, Math.max(current[index], previous[index]));
                float oldX = left + (right - left) * previous[index] / max, newX = left + (right - left) * current[index] / max;
                paint.setStrokeWidth(d(3)); paint.setColor(color(0xFFE2E7E8)); canvas.drawLine(left, y + d(23), right, y + d(23), paint);
                paint.setColor(COLORS[0]); canvas.drawLine(oldX, y + d(23), newX, y + d(23), paint);
                paint.setColor(COLORS[5]); canvas.drawCircle(oldX, y + d(23), d(6), paint);
                paint.setColor(COLORS[0]); canvas.drawCircle(newX, y + d(23), d(4), paint);
                hit(new RectF(0, y - d(14), getWidth(), y + d(40)), UiText.get(R.string.analytics_ui_compare_hit, labels[index], number(previous[index]), number(current[index]), (!data.currentComplete || !data.previousComplete ? UiText.get(R.string.analytics_ui_incomplete_trend_fragment) : "")));
            }
            describe(UiText.get(R.string.analytics_ui_comparison_chart_description));
        }
    }
}
