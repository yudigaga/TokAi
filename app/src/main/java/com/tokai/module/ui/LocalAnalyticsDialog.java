package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;
import com.tokai.module.i18n.ModuleLocale;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.TextView;

import com.tokai.module.analytics.AnalyticsSnapshot;
import com.tokai.module.analytics.AnalyticsSnapshot.Bucket;
import com.tokai.module.analytics.AnalyticsSnapshot.Rank;
import com.tokai.module.analytics.AnalyticsSnapshot.Recent;
import com.tokai.module.analytics.LocalAnalytics;
import com.tokai.module.analytics.AnalyticsAiAnalysis;
import com.tokai.module.analytics.LocalPortraitContext;
import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.config.AnalyticsAiSettings;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

/** Shared settings controls for the local report. Workers never retain the host Activity. */
public final class LocalAnalyticsDialog implements AutoCloseable {
    private static final int TEXT = 0xFF1F272D, MUTED = 0xFF69737A, LINE = 0xFFE2E7E8;
    private static final int ACCENT = 0xFF008579;
    private static final int[] RANGES = {1, 7, 30, 90, 0};
    private static String[] rangeLabels() { return new String[]{UiText.get(R.string.analytics_ui_today), UiText.get(R.string.analytics_ui_range_7_days), UiText.get(R.string.analytics_ui_range_30_days), UiText.get(R.string.analytics_ui_range_90_days), UiText.get(R.string.analytics_ui_range_all)}; }
    private static final int[] PALETTE = {0xFF1683EE, 0xFF54B76A, 0xFF9869D8,
            0xFFF5A33C, 0xFFE66C9B, 0xFF89959F};

    interface Source {
        boolean isEnabled();
        void setEnabled(boolean enabled);
        AnalyticsSnapshot snapshot(int days);
        void clear();
        default LocalPortraitContext.State portraitContext() { return null; }
        default void purpose(String value) { }
        default void feedback(String value) { }
    }

    interface AnalysisService {
        boolean isConfigured();
        String modelLabel();
        Request prepare(AnalyticsSnapshot snapshot, int days);
        default Request prepare(AnalyticsSnapshot snapshot, int days, String purpose) { return prepare(snapshot, days); }
        interface Request {
            String modelLabel();
            String run() throws Exception;
            void cancel();
        }
    }

    private static final AnalysisService NO_ANALYSIS = new AnalysisService() {
        @Override public boolean isConfigured() { return false; }
        @Override public String modelLabel() { return UiText.get(R.string.analytics_ui_no_model); }
        @Override public Request prepare(AnalyticsSnapshot snapshot, int days) {
            throw new IllegalStateException(UiText.get(R.string.analytics_ui_no_model));
        }
    };

    private final Context context;
    private final Activity activity;
    private final boolean miuix;
    private final Dialog dialog;
    private final LinearLayout root;
    private final LinearLayout report;
    private final Source source;
    private final Executor worker;
    private final AnalysisService analysisService;
    private final Executor analysisWorker;
    private final SettingsStore analysisSettings;
    private final Consumer<String> diagnostics;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CompoundButton recording;
    private final TextView status;
    private final Button refresh;
    private final Button clear;
    private final Button purposeButton;
    private final Button feedbackButton;
    private LocalPortraitContext.State currentContext;
    private AlertDialog contextDialog;
    private final List<Button> ranges = new ArrayList<>();
    private Spinner rangeSelector;
    private AlertDialog clearConfirmation;
    private volatile boolean closed;
    private int generation;
    private int days = 7;
    private boolean binding;
    private boolean enabled;
    private AnalyticsSnapshot currentSnapshot;
    private LinearLayout analysisBody;
    private AnalysisOperation analysisOperation;
    private int analysisGeneration;
    private AnalyticsAiAnalysis.Result analysisResult;
    private final Map<String, LinearLayout> chartAnalysisSlots = new LinkedHashMap<>();
    private final Map<String, Boolean> expandedSections = new LinkedHashMap<>();
    private final Map<String, ReportSection> reportSections = new LinkedHashMap<>();
    private String analysisError;
    private String analyzedModel;
    private long analyzedAt;
    private int analyzedDays;
    private Button imageShare;
    private TextView imageShareNote;
    private ImageOperation imageOperation;
    private AlertDialog exportChoiceDialog;
    private Dialog analysisSettingsDialog;

    public LocalAnalyticsDialog(Context context) {
        this(context, source(context), executor("TokAi-analytics-report"));
    }

    public LocalAnalyticsDialog(Context context, SettingsStore settings) {
        this(context, settings, false);
    }

    public LocalAnalyticsDialog(Context context, SettingsStore settings, boolean miuix) {
        this(context, settings, miuix, null);
    }

    public LocalAnalyticsDialog(Context context, SettingsStore settings, boolean miuix,
                                Consumer<String> diagnostics) {
        this(context, source(context), executor("TokAi-analytics-report"),
                analysisService(settings, diagnostics), executor("TokAi-analytics-analysis"), miuix, diagnostics, settings);
    }

    private static ExecutorService executor(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    static AnalysisService analysisService(SettingsStore settings, Consumer<String> diagnostics) {
        Consumer<String> sink = diagnosticSink(diagnostics);
        return new AnalysisService() {
            @Override public boolean isConfigured() { return AnalyticsAiAnalysis.isConfigured(settings); }
            @Override public String modelLabel() { return AnalyticsAiAnalysis.isConfigured(settings) ? AnalyticsAiAnalysis.modelLabel(settings) : UiText.get(R.string.analytics_ui_no_model); }
            @Override public Request prepare(AnalyticsSnapshot snapshot, int days) {
                return prepare(snapshot, days, "");
            }
            @Override public Request prepare(AnalyticsSnapshot snapshot, int days, String purpose) {
                AnalyticsAiAnalysis.AnalysisRequest request = AnalyticsAiAnalysis.prepare(settings, snapshot, days, purpose, sink);
                return new Request() {
                    @Override public String modelLabel() { return request.modelLabel(); }
                    @Override public String run() throws Exception { return request.run(); }
                    @Override public void cancel() { request.cancel(); }
                };
            }
        };
    }

    private static Source source(Context context) {
        Context app = context.getApplicationContext();
        return new Source() {
            private LocalAnalytics store() { return LocalAnalytics.get(app); }
            @Override public boolean isEnabled() { return store().isEnabled(); }
            @Override public void setEnabled(boolean value) { store().setEnabled(value); }
            @Override public AnalyticsSnapshot snapshot(int days) { return store().snapshot(days); }
            @Override public void clear() { store().clear(); LocalPortraitContext.clear(app); }
            @Override public LocalPortraitContext.State portraitContext() { return LocalPortraitContext.current(app); }
            @Override public void purpose(String value) { LocalPortraitContext.setPurpose(app, value); }
            @Override public void feedback(String value) { LocalPortraitContext.setFeedback(app, value); }
        };
    }

    LocalAnalyticsDialog(Context base, Source source, Executor worker) {
        this(base, source, worker, NO_ANALYSIS, executor("TokAi-analytics-analysis"));
    }

    LocalAnalyticsDialog(Context base, Source source, Executor worker,
                         AnalysisService analysisService, Executor analysisWorker) {
        this(base, source, worker, analysisService, analysisWorker, false);
    }

    LocalAnalyticsDialog(Context base, Source source, Executor worker,
                         AnalysisService analysisService, Executor analysisWorker, boolean miuix) {
        this(base, source, worker, analysisService, analysisWorker, miuix, null);
    }

    LocalAnalyticsDialog(Context base, Source source, Executor worker,
                         AnalysisService analysisService, Executor analysisWorker, boolean miuix,
                         Consumer<String> diagnostics) {
        this(base, source, worker, analysisService, analysisWorker, miuix, diagnostics, null);
    }

    LocalAnalyticsDialog(Context base, Source source, Executor worker,
                         AnalysisService analysisService, Executor analysisWorker, boolean miuix,
                         Consumer<String> diagnostics, SettingsStore analysisSettings) {
        this.context = ModuleTheme.themedContext(base);
        this.activity = activity(base);
        this.miuix = miuix && activity != null;
        this.source = source;
        this.worker = worker;
        this.analysisService = analysisService;
        this.analysisWorker = analysisWorker;
        this.analysisSettings = analysisSettings;
        this.diagnostics = diagnosticSink(diagnostics);
        root = column();
        root.setTag("analytics.root");

        ScrollView scroll = new ScrollView(context);
        scroll.setTag("analytics.scroll");
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout body = column();
        body.setTag("analytics.body");
        body.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, matchWrap());

        LinearLayout privacy = card(body);
        recording = this.miuix ? MiuixFormControls.checkbox(context, UiText.get(R.string.analytics_ui_enable_recording), false) : new Switch(context);
        recording.setTag("analytics.recording");
        recording.setText(UiText.get(R.string.analytics_ui_enable_recording));
        recording.setTextSize(16);
        recording.setTextColor(TEXT);
        recording.setMinHeight(dp(48));
        recording.setEnabled(false);
        recording.setContentDescription(UiText.get(R.string.analytics_ui_enable_recording_description));
        privacy.addView(controlView(recording), matchWrap());
        TextView localNote = text(UiText.get(R.string.analytics_ui_local_recording_note), 13, MUTED, false);
        localNote.setPadding(0, 0, 0, dp(4));
        privacy.addView(localNote, matchWrap());
        ReportSection details = new ReportSection(UiText.get(R.string.analytics_ui_recording_notes), "settings", false);
        details.setSummary(UiText.get(R.string.analytics_ui_optional_purpose_feedback));
        privacy.addView(details, matchWrap());
        TextView note = text(UiText.get(R.string.analytics_ui_recording_privacy), 13, MUTED, false);
        note.setLineSpacing(dp(3), 1);
        details.addView(note, matchWrap());
        purposeButton = button(UiText.get(R.string.analytics_ui_purpose_optional), "analytics.purpose", this::editPurpose);
        details.addView(controlView(purposeButton), matchWrap());
        feedbackButton = button(UiText.get(R.string.analytics_ui_feedback_unset), "analytics.feedback", this::editFeedback);
        details.addView(controlView(feedbackButton), matchWrap());
        recording.setOnCheckedChangeListener((view, checked) -> {
            if (!binding) load(checked, false);
        });

        if (this.miuix) {
            rangeSelector = MiuixFormControls.spinner(context);
            rangeSelector.setTag("analytics.range.selector");
            rangeSelector.setContentDescription(UiText.get(R.string.analytics_ui_statistics_range));
            rangeSelector.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, rangeLabels()));
            rangeSelector.setSelection(1);
            rangeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position < 0 || position >= RANGES.length || days == RANGES[position]) return;
                    days = RANGES[position];
                    load(null, false);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            LinearLayout filter = card(body);
            filter.addView(controlView(rangeSelector), matchWrap());
        } else {
            HorizontalScrollView filterScroll = new HorizontalScrollView(context);
            filterScroll.setHorizontalScrollBarEnabled(false);
            filterScroll.setFillViewport(true);
            LinearLayout filterRow = new LinearLayout(context);
            for (int index = 0; index < RANGES.length; index++) {
                final int range = RANGES[index];
                Button filter = button(rangeLabels()[index], "analytics.range." + range, () -> {
                    days = range;
                    updateRanges();
                    load(null, false);
                });
                filter.setMinWidth(dp(56));
                filter.setTextSize(13);
                filter.setPadding(dp(9), 0, dp(9), 0);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
                if (index > 0) params.leftMargin = dp(5);
                filterRow.addView(filter, params);
                ranges.add(filter);
            }
            filterScroll.addView(filterRow, new HorizontalScrollView.LayoutParams(-1, -2));
            body.addView(filterScroll, matchWrap());
            }
        updateRanges();
        status = text(UiText.get(R.string.analytics_ui_loading_records), 13, MUTED, false);
        status.setTag("analytics.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(12), 0, dp(4));
        body.addView(status, matchWrap());
        LinearLayout actions = new LinearLayout(context);
        refresh = button(UiText.get(R.string.analytics_ui_refresh), "analytics.refresh", () -> load(null, false));
        clear = button(UiText.get(R.string.analytics_ui_clear_records), "analytics.clear", this::confirmClear);
        actions.addView(controlView(refresh), new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        clearParams.leftMargin = dp(8);
        actions.addView(controlView(clear), clearParams);
        body.addView(actions, matchWrap());
        report = column();
        report.setTag("analytics.report");
        body.addView(report, matchWrap());
        dialog = new MiuixDialogBuilder(context, this.miuix).setTitle(UiText.get(R.string.analytics_ui_analytics_title))
                .setView(root).setNegativeButton(UiText.get(R.string.analytics_ui_close), null).create();
        if (activity != null) dialog.setOwnerActivity(activity);
        dialog.setOnShowListener(ignored -> {
            diagnostic("ui_open", null, 0);
            if (!this.miuix && activity != null) HostDialogBounds.install(activity, dialog, true);
            load(null, false);
        });
        dialog.setOnDismissListener(ignored -> close());
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { close(); }
        });
        ModuleTheme.bind(root);
    }

    public Dialog dialog() { return dialog; }

    private static Consumer<String> diagnosticSink(Consumer<String> sink) {
        return message -> {
            try {
                String safe = "[analytics] " + message;
                if (sink == null) TokAiLog.legacy(4, "TokAi.Analytics", safe, null);
                else sink.accept(safe);
            } catch (RuntimeException ignored) { /* Diagnostics must not affect host UI or requests. */ }
        };
    }

    private void diagnostic(String stage, AnalyticsAiAnalysis.AnalysisFailure.Kind kind, int status) {
        diagnostics.accept("stage=" + stage + " kind=" + (kind == null ? "NONE" : kind.name())
                + " status=" + status + " chars=0");
    }

    private void updateRanges() {
        for (int index = 0; index < ranges.size(); index++) {
            Button button = ranges.get(index);
            boolean selected = days == RANGES[index];
            button.setSelected(selected);
            button.setTextColor(selected ? ACCENT : MUTED);
            button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackground(rounded(selected ? 0xFFE7F5F2 : 0xFFEEEFF3, 22));
            button.setContentDescription(UiText.get(selected ? R.string.analytics_ui_range_selected : R.string.analytics_ui_range_view_statistics, rangeLabels()[index]));
        }
        ModuleTheme.bind(root);
    }

    /** Work captures only application-backed storage and a weak view reference. */
    private void load(Boolean nextEnabled, boolean erase) {
        load(nextEnabled, erase, null);
    }

    private void load(Boolean nextEnabled, boolean erase, Consumer<Source> contextChange) {
        if (closed) return;
        cancelImageExport();
        // A new snapshot must never be paired with an interpretation of older chart values.
        resetAnalysis();
        int request = ++generation;
        int range = days;
        recording.setEnabled(false);
        refresh.setEnabled(false);
        clear.setEnabled(false);
        purposeButton.setEnabled(false);
        feedbackButton.setEnabled(false);
        status.setText(erase ? UiText.get(R.string.analytics_ui_clearing_records) : UiText.get(R.string.analytics_ui_loading_records));
        currentSnapshot = null;
        analysisBody = null;
        chartAnalysisSlots.clear();
        reportSections.clear();
        report.removeAllViews();
        Source data = source;
        Handler dispatcher = main;
        WeakReference<LocalAnalyticsDialog> target = new WeakReference<>(this);
        worker.execute(() -> {
            AnalyticsSnapshot snapshot = null;
            boolean recordingEnabled = false;
            boolean failed = false;
            LocalPortraitContext.State localContext = null;
            try {
                if (contextChange != null) contextChange.accept(data);
                if (nextEnabled != null) data.setEnabled(nextEnabled);
                if (erase) data.clear();
                snapshot = data.snapshot(range);
                localContext = data.portraitContext();
            } catch (RuntimeException failure) {
                failed = true;
            }
            try {
                // Storage may disable collection after a write/read failure.
                recordingEnabled = data.isEnabled();
            } catch (RuntimeException failure) {
                failed = true;
            }
            AnalyticsSnapshot result = snapshot;
            boolean state = recordingEnabled;
            boolean error = failed;
            LocalPortraitContext.State resultContext = localContext;
            dispatcher.post(() -> {
                LocalAnalyticsDialog page = target.get();
                if (page != null && !page.closed && request == page.generation) {
                    page.loaded(result, state, error, resultContext);
                }
            });
        });
    }

    private void loaded(AnalyticsSnapshot snapshot, boolean state, boolean error,
                        LocalPortraitContext.State localContext) {
        recording.setEnabled(true);
        refresh.setEnabled(true);
        clear.setEnabled(true);
        currentContext = localContext;
        purposeButton.setEnabled(true);
        feedbackButton.setEnabled(true);
        purposeButton.setText(localContext == null || localContext.purpose.isEmpty() ? UiText.get(R.string.analytics_ui_purpose_optional) : UiText.get(R.string.analytics_ui_purpose_provided));
        feedbackButton.setText(UiText.get(R.string.analytics_ui_last_feedback, (localContext == null || localContext.feedback.isEmpty() ? UiText.get(R.string.analytics_ui_not_provided) : AnalyticsUiLabels.feedback(localContext.feedback))));
        binding = true;
        enabled = state;
        recording.setChecked(enabled);
        binding = false;
        if (error || snapshot == null) {
            status.setText(UiText.get(R.string.analytics_ui_records_load_failed));
            return;
        }
        status.setText(UiText.get(R.string.analytics_ui_recording_status, (enabled ? UiText.get(R.string.analytics_ui_recording_active) : UiText.get(R.string.analytics_ui_recording_off)), (days == 0 ? UiText.get(R.string.analytics_ui_all_retained_days) : days == 1 ? UiText.get(R.string.analytics_ui_today) : UiText.get(R.string.analytics_ui_recent_days, days))));
        render(snapshot);
        ModuleTheme.bind(root);
    }

    private void confirmClear() {
        if (closed || clearConfirmation != null) return;
        clearConfirmation = new MiuixDialogBuilder(context, miuix)
                .setTitle(UiText.get(R.string.analytics_ui_clear_all_title))
                .setMessage(UiText.get(R.string.analytics_ui_clear_all_warning))
                .setNegativeButton(UiText.get(R.string.analytics_ui_cancel), null)
                .setPositiveButton(UiText.get(R.string.analytics_ui_clear_all), (ignored, which) -> load(null, true))
                .create();
        clearConfirmation.setOnDismissListener(ignored -> clearConfirmation = null);
        clearConfirmation.setOnShowListener(ignored -> {
            if (!miuix && activity != null) HostDialogBounds.install(activity, clearConfirmation, true);
        });
        clearConfirmation.show();
    }

    private void editPurpose() {
        if (closed || contextDialog != null) return;
        EditText input = miuix ? MiuixFormControls.input(context, UiText.get(R.string.analytics_ui_purpose_title),
                currentContext == null ? "" : currentContext.purpose, false, "我的使用目的") : new EditText(context);
        input.setTag("analytics.purpose.input");
        input.setMinLines(3); input.setMaxLines(6); input.setTextSize(14);
        input.setHint(UiText.get(R.string.analytics_ui_purpose_hint));
        input.setText(currentContext == null ? "" : currentContext.purpose);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(240)});
        contextDialog = new MiuixDialogBuilder(context, miuix)
                .setTitle(UiText.get(R.string.analytics_ui_purpose_title)).setMessage(UiText.get(R.string.analytics_ui_purpose_explanation))
                .setView(controlView(input)).setNegativeButton(UiText.get(R.string.analytics_ui_cancel), null)
                .setPositiveButton(UiText.get(R.string.analytics_ui_save), (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    load(null, false, data -> data.purpose(value));
                }).create();
        showContextDialog();
    }

    private void editFeedback() {
        if (closed || contextDialog != null) return;
        String[] values = {"符合", "部分符合", "不符合", ""};
        String[] labels = {UiText.get(R.string.analytics_ui_feedback_yes), UiText.get(R.string.analytics_ui_feedback_partial), UiText.get(R.string.analytics_ui_feedback_no), UiText.get(R.string.analytics_ui_clear_feedback)};
        contextDialog = new MiuixDialogBuilder(context, miuix)
                .setTitle(UiText.get(R.string.analytics_ui_feedback_question))
                .setItems(labels, (dialog, index) -> {
                    String value = index == 3 ? "" : values[index];
                    saveFeedback(value);
                }).setNegativeButton(UiText.get(R.string.analytics_ui_cancel), null).create();
        showContextDialog();
    }

    /** Rating describes the already displayed result; it must not invalidate that result. */
    private void saveFeedback(String value) {
        if (closed) return;
        int request = generation;
        feedbackButton.setEnabled(false);
        Source data = source;
        Handler dispatcher = main;
        WeakReference<LocalAnalyticsDialog> target = new WeakReference<>(this);
        worker.execute(() -> {
            LocalPortraitContext.State savedContext = null;
            boolean failed = false;
            try {
                data.feedback(value);
                savedContext = data.portraitContext();
            } catch (RuntimeException failure) {
                failed = true;
            }
            LocalPortraitContext.State result = savedContext;
            boolean error = failed;
            dispatcher.post(() -> {
                LocalAnalyticsDialog page = target.get();
                if (page == null || page.closed || page.generation != request) return;
                page.feedbackButton.setEnabled(true);
                if (error) {
                    page.feedbackButton.setText(UiText.get(R.string.analytics_ui_feedback_save_unconfirmed));
                } else {
                    if (result != null) page.currentContext = result;
                    page.feedbackButton.setText(UiText.get(R.string.analytics_ui_last_feedback, (value.isEmpty() ? UiText.get(R.string.analytics_ui_not_provided) : AnalyticsUiLabels.feedback(value))));
                }
            });
        });
    }

    private void showContextDialog() {
        AlertDialog shown = contextDialog;
        if (shown == null) return;
        shown.setOnDismissListener(ignored -> { if (contextDialog == shown) contextDialog = null; });
        shown.setOnShowListener(ignored -> {
            if (!miuix && activity != null) HostDialogBounds.install(activity, shown, true);
            if (shown.getWindow() != null) ModuleTheme.bind(shown.getWindow().getDecorView());
        });
        shown.show();
    }

    private void render(AnalyticsSnapshot data) {
        currentSnapshot = data;
        report.removeAllViews();
        chartAnalysisSlots.clear();
        reportSections.clear();
        boolean portrait = !data.portrait.days.isEmpty();
        PortraitReportViews portraitViews = new PortraitReportViews(context, data.portrait, new PortraitReportViews.Host() {
            @Override public LinearLayout section(String key, String title) { return LocalAnalyticsDialog.this.section(key, title); }
            @Override public void analysis(LinearLayout section, String key) { attachAnalysis(section, key); }
            @Override public Button button(Context ignored) {
                return miuix ? MiuixFormControls.button(context) : new Button(context);
            }
            @Override public View controlView(View control) { return LocalAnalyticsDialog.this.controlView(control); }
        });
        if (portrait) portraitViews.overview();
        LinearLayout overview = section("overview", UiText.get(R.string.analytics_ui_browsing_overview));
        metric(overview, UiText.get(R.string.analytics_ui_views), data.totalViews);
        metric(overview, UiText.get(R.string.analytics_ui_unique_works), data.uniqueWorks);
        metric(overview, UiText.get(R.string.analytics_ui_feature_uses), data.totalFeatureUses);
        if (data.advertisementViews > 0) metric(overview, UiText.get(R.string.analytics_ui_ad_posts), data.advertisementViews);
        caption(overview, UiText.get(R.string.analytics_ui_overview_boundary));
        if (data.totalViews == 0 && data.totalFeatureUses == 0) {
            TextView empty = text(enabled ? UiText.get(R.string.analytics_ui_empty_recording_active)
                    : UiText.get(R.string.analytics_ui_empty_recording_off), 14, MUTED, false);
            empty.setTag("analytics.empty");
            overview.addView(empty, matchWrap());
        }
        attachAnalysis(overview, "overview");
        if (portrait) {
            portraitViews.charts();
            attachAnalysis(ranking("features", UiText.get(R.string.analytics_ui_feature_top10), data.features, UiText.get(R.string.analytics_ui_no_feature_usage)), "features");
        } else {
        LinearLayout trend = section("trend", UiText.get(R.string.analytics_ui_browsing_trend));
        TextView trendValue = text(UiText.get(R.string.analytics_ui_trend_selection_hint), 13, ACCENT, true);
        trendValue.setTag("analytics.trend.selection");
        trend.addView(trendValue, matchWrap());
        if (data.trend.isEmpty()) caption(trend, UiText.get(R.string.analytics_ui_no_trend_data));
        else {
            TrendChart chart = new TrendChart(context, data.trend, index -> {
                Bucket point = data.trend.get(index);
                trendValue.setText(UiText.get(R.string.analytics_ui_label_count, point.label, number(point.count)));
                trendValue.announceForAccessibility(trendValue.getText());
            });
            chart.setTag("analytics.trend.chart");
            trend.addView(chart, new LinearLayout.LayoutParams(-1, dp(180)));
            long max = 0;
            String label = "";
            for (Bucket item : data.trend) if (item.count >= max) { max = item.count; label = item.label; }
            caption(trend, UiText.get(R.string.analytics_ui_trend_summary, number(data.totalViews), label, number(max)));
        }
        attachAnalysis(trend, "trend");

        LinearLayout types = section("types", UiText.get(R.string.analytics_ui_post_type_share));
        long typeTotal = total(data.types);
        if (typeTotal > 0) {
            DonutChart donut = new DonutChart(context, data.types);
            donut.setTag("analytics.types.chart");
            types.addView(donut, new LinearLayout.LayoutParams(-1, dp(210)));
            int index = 0;
            for (Rank type : data.types) {
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                View swatch = new View(context);
                swatch.setBackground(rounded(PALETTE[index++ % PALETTE.length], 3));
                row.addView(swatch, new LinearLayout.LayoutParams(dp(9), dp(9)));
                TextView label = text(AnalyticsUiLabels.rank(type), 13, TEXT, false);
                label.setPadding(dp(8), dp(7), dp(4), dp(7));
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                TextView value = text(number(type.count) + " · " + percent(type.count, typeTotal), 13, MUTED, false);
                row.addView(value, new LinearLayout.LayoutParams(-2, -2));
                row.setContentDescription(UiText.get(R.string.analytics_ui_label_count_share, AnalyticsUiLabels.rank(type), number(type.count), percent(type.count, typeTotal)));
                types.addView(row, matchWrap());
            }
        } else caption(types, UiText.get(R.string.analytics_ui_no_post_type_data));
        caption(types, UiText.get(R.string.analytics_ui_post_type_boundary));
        attachAnalysis(types, "types");
        attachAnalysis(ranking("authors", UiText.get(R.string.analytics_ui_author_top10), data.authors, UiText.get(R.string.analytics_ui_no_author_records)), "authors");
        attachAnalysis(ranking("topics", UiText.get(R.string.analytics_ui_topic_top10), data.topics, UiText.get(R.string.analytics_ui_no_topic_rank_records)), "topics");
        attachAnalysis(ranking("features", UiText.get(R.string.analytics_ui_feature_top10), data.features, UiText.get(R.string.analytics_ui_no_feature_usage)), "features");

        LinearLayout hours = section("hours", UiText.get(R.string.analytics_ui_browsing_hours));
        HourChart histogram = new HourChart(context, data.hours);
        histogram.setTag("analytics.hours.chart");
        hours.addView(histogram, new LinearLayout.LayoutParams(-1, dp(180)));
        int peakHour = 0;
        for (int index = 1; index < data.hours.length; index++) if (data.hours[index] > data.hours[peakHour]) peakHour = index;
        caption(hours, data.totalViews == 0 ? UiText.get(R.string.analytics_ui_no_hour_data) : UiText.get(R.string.analytics_ui_peak_hour_caption, String.format(ModuleLocale.locale(), "%02d:00", peakHour), number(data.hours[peakHour])));
        attachAnalysis(hours, "hours");
        }

        LinearLayout recent = section("recent", UiText.get(R.string.analytics_ui_recent_records));
        if (data.recent.isEmpty()) caption(recent, UiText.get(R.string.analytics_ui_no_recent_records));
        for (Recent event : data.recent) {
            TextView label = text(event.label, 14, TEXT, false);
            label.setPadding(0, dp(8), 0, dp(2));
            recent.addView(label, matchWrap());
            caption(recent, formatDate(event.timestamp, "MM-dd HH:mm") + " · "
                    + ("feature".equals(event.kind) ? UiText.get(R.string.analytics_ui_module_feature) : UiText.get(R.string.analytics_ui_browse_post)));
            View divider = new View(context);
            divider.setBackgroundColor(LINE);
            recent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }

        LinearLayout history = section("history", UiText.get(R.string.analytics_ui_usage_history));
        metric(history, UiText.get(R.string.analytics_ui_active_days), data.activeDays);
        metric(history, UiText.get(R.string.analytics_ui_active_streak), data.currentStreak);
        TextView first = text(UiText.get(R.string.analytics_ui_first_record, (data.firstSeen == 0 ? UiText.get(R.string.analytics_ui_none) : formatDate(data.firstSeen, "yyyy/MM/dd"))), 14, TEXT, true);
        history.addView(first, matchWrap());
        caption(history, UiText.get(R.string.analytics_ui_activity_boundary));
        LinearLayout analysis = section("analysis", UiText.get(R.string.analytics_ui_ai_chart_analysis));
        analysis.setTag("analytics.analysis.card");
        analysisBody = column();
        analysis.addView(analysisBody, matchWrap());
        updateSectionSummaries(data);
        renderAnalysis();
    }

    private void renderAnalysis() {
        if (closed || analysisBody == null) return;
        analysisBody.removeAllViews();
        renderAnalysisSettings();
        if (analysisResult != null) {
            caption(analysisBody, UiText.get(R.string.analytics_ui_analysis_metadata, rangeLabel(analyzedDays), analyzedModel, formatDate(analyzedAt, "MM-dd HH:mm")));
            TextView result = text(AnalyticsUiLabels.shareText(analysisResult), 14, TEXT, false);
            result.setTag("analytics.analysis.result");
            result.setTextIsSelectable(true);
            result.setLineSpacing(dp(4), 1);
            result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            analysisBody.addView(result, matchWrap());
            Button expand = button(UiText.get(R.string.analytics_ui_expand_ai_sections), "analytics.analysis.expand", () -> {
                for (Map.Entry<String, LinearLayout> entry : chartAnalysisSlots.entrySet()) {
                    ReportSection section = reportSections.get(entry.getKey());
                    if (section != null && entry.getValue().getChildCount() > 0) section.setExpanded(true);
                }
            });
            analysisBody.addView(controlView(expand), new LinearLayout.LayoutParams(-1, dp(52)));
            Button share = button(UiText.get(R.string.analytics_ui_share_analysis_text), "analytics.analysis.share", this::shareAnalysis);
            analysisBody.addView(controlView(share), new LinearLayout.LayoutParams(-1, dp(52)));
        } else {
            boolean configured;
            String model;
            try {
                configured = analysisService.isConfigured();
                model = analysisOperation == null ? analysisService.modelLabel() : analyzedModel;
            } catch (RuntimeException failure) {
                configured = false;
                model = UiText.get(R.string.analytics_ui_no_model);
            }
            caption(analysisBody, UiText.get(R.string.analytics_ui_analysis_consent, model));
            if (analysisOperation != null) {
                TextView progress = text(UiText.get(R.string.analytics_ui_requesting_analysis), 14, ACCENT, false);
                progress.setTag("analytics.analysis.progress");
                progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                analysisBody.addView(progress, matchWrap());
                analysisBody.addView(controlView(button(UiText.get(R.string.analytics_ui_cancel_analysis), "analytics.analysis.cancel", () -> {
                    resetAnalysis();
                    analysisError = UiText.get(R.string.analytics_ui_analysis_cancelled);
                    renderAnalysis();
                })), new LinearLayout.LayoutParams(-1, dp(52)));
            } else {
                if (analysisError != null) {
                    TextView error = text(analysisError, 13, MUTED, false);
                    error.setTag("analytics.analysis.error");
                    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    analysisBody.addView(error, matchWrap());
                }
                boolean hasData = currentSnapshot != null
                        && (currentSnapshot.totalViews > 0 || currentSnapshot.totalFeatureUses > 0);
                if (!configured) caption(analysisBody, UiText.get(R.string.analytics_ui_choose_available_model));
                else if (!hasData) caption(analysisBody, UiText.get(R.string.analytics_ui_no_analysis_data));
                Button request = button(analysisError == null ? UiText.get(R.string.analytics_ui_request_analysis) : UiText.get(R.string.analytics_ui_retry_analysis),
                        "analytics.analysis.request", this::requestAnalysis);
                request.setEnabled(configured && hasData);
                analysisBody.addView(controlView(request), new LinearLayout.LayoutParams(-1, dp(52)));
            }
        }
        imageShareNote = text(UiText.get(R.string.analytics_ui_export_explanation, (Build.VERSION.SDK_INT >= 29 ? UiText.get(R.string.analytics_ui_export_default_locations)
                : UiText.get(R.string.analytics_ui_choose_report_location_sentence))), 13, MUTED, false);
        imageShareNote.setTag("analytics.report.share.note");
        analysisBody.addView(imageShareNote, matchWrap());
        imageShare = button(UiText.get(R.string.analytics_ui_export_share_report), "analytics.report.share.image", this::shareReportImage);
        imageShare.setEnabled(currentSnapshot != null && analysisOperation == null && imageOperation == null);
        analysisBody.addView(controlView(imageShare), new LinearLayout.LayoutParams(-1, dp(52)));
        ModuleTheme.bind(root);
    }

    private boolean canEditAnalysisSettings() {
        return !closed && analysisSettings != null && analysisOperation == null;
    }

    private void renderAnalysisSettings() {
        if (analysisSettings == null) return;
        Button model = button(UiText.get(R.string.analytics_ui_model_profile_preview, analysisModelPreview()), "analytics.analysis.model", this::chooseAnalysisModel);
        model.setEnabled(canEditAnalysisSettings());
        analysisBody.addView(controlView(model), matchWrap());
        String savedPrompt = analysisSettings.getString(AnalyticsAiSettings.SECONDARY_PROMPT, "");
        boolean custom = savedPrompt != null && !savedPrompt.trim().isEmpty();
        Button prompt = button(UiText.get(R.string.analytics_ui_secondary_prompt_preview, (custom ? UiText.get(R.string.analytics_ui_customized) : UiText.get(R.string.analytics_ui_default))),
                "analytics.analysis.prompt", this::editAnalysisPrompt);
        prompt.setEnabled(canEditAnalysisSettings());
        analysisBody.addView(controlView(prompt), matchWrap());
        Button reset = button(UiText.get(R.string.analytics_ui_restore_secondary_prompt), "analytics.analysis.prompt.reset", () -> {
            if (!canEditAnalysisSettings()) return;
            analysisSettings.putString(AnalyticsAiSettings.SECONDARY_PROMPT, "");
            analysisSettingsChanged();
        });
        reset.setEnabled(canEditAnalysisSettings() && custom);
        analysisBody.addView(controlView(reset), matchWrap());
        caption(analysisBody, UiText.get(R.string.analytics_ui_analysis_settings_note));
    }

    private String analysisModelPreview() {
        String selected = analysisSettings.getString(AnalyticsAiSettings.MODEL_ID, "");
        try {
            AiModelConfig config = AnalyticsAiSettings.models(analysisSettings).selected();
            return (selected == null || selected.isEmpty() ? UiText.get(R.string.analytics_ui_follow_global_label, analysisModelLabel(config)) : analysisModelLabel(config));
        } catch (RuntimeException ignored) {
            return selected == null || selected.isEmpty() ? UiText.get(R.string.analytics_ui_global_no_active_model) : UiText.get(R.string.analytics_ui_selected_profile_unavailable);
        }
    }

    private static String analysisModelLabel(AiModelConfig config) {
        return UiText.get(R.string.analytics_ui_model_label, config.name, config.modelName, (config.streaming() ? UiText.get(R.string.analytics_ui_streaming) : UiText.get(R.string.analytics_ui_non_streaming)));
    }

    private void chooseAnalysisModel() {
        if (!canEditAnalysisSettings() || analysisSettingsDialog != null) return;
        AiModelSettings settings;
        try { settings = AiModelSettings.read(analysisSettings); }
        catch (RuntimeException ignored) {
            analysisError = UiText.get(R.string.analytics_ui_model_profiles_unreadable);
            renderAnalysis();
            return;
        }
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        ids.add(""); labels.add(UiText.get(R.string.analytics_ui_follow_global_model));
        for (AiModelConfig config : settings.configs) {
            ids.add(config.id); labels.add(analysisModelLabel(config));
        }
        int selected = ids.indexOf(analysisSettings.getString(AnalyticsAiSettings.MODEL_ID, ""));
        Dialog chooser = new MiuixDialogBuilder(context, miuix).setTitle(UiText.get(R.string.analytics_ui_analysis_model_title))
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (shown, which) -> {
                    if (canEditAnalysisSettings()) {
                        analysisSettings.putString(AnalyticsAiSettings.MODEL_ID, ids.get(which));
                        analysisSettingsChanged();
                    }
                    shown.dismiss();
                }).setNegativeButton(UiText.get(R.string.analytics_ui_cancel), null).create();
        showAnalysisSettingsDialog(chooser);
    }

    private void editAnalysisPrompt() {
        if (!canEditAnalysisSettings() || analysisSettingsDialog != null) return;
        PromptEditorDialog editor = new PromptEditorDialog(context, UiText.get(R.string.analytics_ui_analysis_prompt_title),
                AnalyticsAiSettings.secondaryPrompt(analysisSettings), value -> {
                    if (!canEditAnalysisSettings()) return;
                    analysisSettings.putString(AnalyticsAiSettings.SECONDARY_PROMPT, value);
                    analysisSettingsChanged();
                }, miuix);
        showAnalysisSettingsDialog(editor);
    }

    private void showAnalysisSettingsDialog(Dialog shown) {
        analysisSettingsDialog = shown;
        shown.setOnDismissListener(ignored -> {
            if (analysisSettingsDialog == shown) analysisSettingsDialog = null;
        });
        if (shown instanceof AlertDialog) shown.setOnShowListener(ignored -> {
            if (!miuix && activity != null) HostDialogBounds.install(activity, shown, true);
            if (shown.getWindow() != null) ModuleTheme.bind(shown.getWindow().getDecorView());
        });
        shown.show();
    }

    private void analysisSettingsChanged() {
        cancelImageExport();
        resetAnalysis();
        showChartAnalyses();
    }

    private void attachAnalysis(LinearLayout chartCard, String key) {
        chartCard.setTag("analytics.chartcard." + key);
        LinearLayout slot = column();
        slot.setTag("analytics.analysis.section." + key);
        chartCard.addView(slot, matchWrap());
        chartAnalysisSlots.put(key, slot);
        renderChartAnalysis(key, slot);
    }

    private void renderChartAnalysis(String key, LinearLayout slot) {
        slot.removeAllViews();
        if (analysisResult == null) return;
        if (("portrait".equals(key) || "changes".equals(key) || "associations".equals(key)) && !analysisResult.hasPortrait()) return;
        TextView heading = text(UiText.get(R.string.analytics_ui_ai_interpretation), 13, ACCENT, true);
        heading.setPadding(0, dp(16), 0, dp(4));
        slot.addView(heading, matchWrap());
        caption(slot, UiText.get(R.string.analytics_ui_chart_analysis_at, formatDate(analyzedAt, "MM-dd HH:mm")));
        String content;
        switch (key) {
            case "overview": content = analysisResult.overview; break;
            case "trend": content = analysisResult.trend; break;
            case "types": content = analysisResult.types; break;
            case "authors": content = analysisResult.authors; break;
            case "topics": content = analysisResult.topics; break;
            case "features": content = analysisResult.features; break;
            case "hours": content = analysisResult.hours; break;
            case "portrait": content = analysisResult.portraitTitle + "\n\n" + analysisResult.portrait; break;
            case "changes": content = analysisResult.contrasts; break;
            case "associations": content = analysisResult.questions; break;
            default: return;
        }
        TextView result = text(content, 14, TEXT, false);
        result.setTag("analytics.analysis.text." + key);
        result.setTextIsSelectable(true);
        result.setLineSpacing(dp(4), 1);
        slot.addView(result, matchWrap());
        ReportSection section = reportSections.get(key);
        if (section != null) section.setHasAnalysis(true);
    }

    private void showChartAnalyses() {
        ScrollView scroll = root.findViewWithTag("analytics.scroll");
        int previousScroll = scroll.getScrollY();
        // Update each existing chart in place: its selection and accessibility focus survive.
        for (Map.Entry<String, LinearLayout> entry : chartAnalysisSlots.entrySet()) {
            renderChartAnalysis(entry.getKey(), entry.getValue());
        }
        renderAnalysis();
        scroll.post(() -> { if (!closed) scroll.scrollTo(0, previousScroll); });
    }

    private void requestAnalysis() {
        if (closed || analysisOperation != null || analysisResult != null || currentSnapshot == null
                || currentSnapshot.totalViews == 0 && currentSnapshot.totalFeatureUses == 0) return;
        cancelImageExport();
        AnalysisService.Request request;
        try {
            if (!analysisService.isConfigured()) throw new AnalyticsAiAnalysis.AnalysisFailure(
                    AnalyticsAiAnalysis.AnalysisFailure.Kind.PREPARE);
            request = analysisService.prepare(currentSnapshot, days, currentContext == null ? "" : currentContext.purpose);
            analyzedModel = request.modelLabel();
        } catch (AnalyticsAiAnalysis.AnalysisFailure failure) {
            analysisError = AnalyticsUiLabels.failure(failure);
            diagnostic("ui_failed", failure.kind(), failure.httpStatus());
            renderAnalysis();
            return;
        } catch (RuntimeException failure) {
            analysisError = AnalyticsUiLabels.failure(new AnalyticsAiAnalysis.AnalysisFailure(
                    AnalyticsAiAnalysis.AnalysisFailure.Kind.PREPARE));
            diagnostic("ui_failed", AnalyticsAiAnalysis.AnalysisFailure.Kind.PREPARE, 0);
            renderAnalysis();
            return;
        }
        int token = ++analysisGeneration;
        int range = days;
        analyzedDays = range;
        analyzedAt = System.currentTimeMillis();
        analysisError = null;
        AnalysisOperation operation = new AnalysisOperation(request);
        analysisOperation = operation;
        diagnostic("ui_request", null, 0);
        renderAnalysis();
        Handler dispatcher = main;
        WeakReference<LocalAnalyticsDialog> target = new WeakReference<>(this);
        analysisWorker.execute(() -> {
            if (operation.cancelled) return;
            AnalyticsAiAnalysis.Result response = null;
            String safeError = null;
            AnalyticsAiAnalysis.AnalysisFailure.Kind failureKind = null;
            int failureStatus = 0;
            try {
                String answer = operation.request.run();
                try {
                    response = AnalyticsAiAnalysis.parseResult(answer);
                } catch (RuntimeException malformed) {
                    // A response was received; parsing failure is not a network/configuration error.
                    throw new AnalyticsAiAnalysis.AnalysisFailure(AnalyticsAiAnalysis.AnalysisFailure.Kind.FORMAT);
                }
            } catch (AnalyticsAiAnalysis.AnalysisFailure failure) {
                safeError = AnalyticsUiLabels.failure(failure);
                failureKind = failure.kind();
                failureStatus = failure.httpStatus();
            } catch (Exception ignored) {
                // Provider errors can contain request bodies or credentials; never display them.
                safeError = UiText.get(R.string.analytics_ui_analysis_incomplete_retry);
                failureKind = AnalyticsAiAnalysis.AnalysisFailure.Kind.TRANSPORT;
            }
            if (operation.cancelled) return;
            AnalyticsAiAnalysis.Result result = response;
            String error = safeError;
            AnalyticsAiAnalysis.AnalysisFailure.Kind kind = failureKind;
            int httpStatus = failureStatus;
            dispatcher.post(() -> {
                LocalAnalyticsDialog page = target.get();
                if (page == null || page.closed || page.analysisGeneration != token || page.days != range
                        || page.analysisOperation != operation || operation.cancelled) return;
                page.analysisOperation = null;
                page.analysisResult = result;
                page.analysisError = result == null ? error : null;
                page.diagnostic(result == null ? "ui_failed" : "ui_complete", kind, httpStatus);
                page.showChartAnalyses();
            });
        });
    }

    private void shareAnalysis() {
        if (closed || analysisResult == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, UiText.get(R.string.analytics_ui_profile_chart_analysis))
                .putExtra(Intent.EXTRA_TEXT, UiText.get(R.string.analytics_ui_share_analysis_body, rangeLabel(analyzedDays), formatDate(analyzedAt, "yyyy-MM-dd HH:mm"), AnalyticsUiLabels.shareText(analysisResult)));
        Intent chooser = Intent.createChooser(send, UiText.get(R.string.analytics_ui_share_ai_analysis));
        if (activity == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooser);
            LocalAnalytics.feature("analytics_share", "打开分析分享");
        }
        catch (RuntimeException failure) {
            if (analysisBody != null) caption(analysisBody, UiText.get(R.string.analytics_ui_share_text_unavailable));
        }
    }

    /** Builds a separate paper report without mutating or measuring the live dialog. */
    ReportImageExport.Document captureReportImage(int width, int pageHeight) {
        if (currentSnapshot == null) throw new IllegalStateException(UiText.get(R.string.analytics_ui_load_report_first));
        long generatedAt = System.currentTimeMillis();
        View paper = ReportPresentation.build(context.getApplicationContext(), currentSnapshot, rangeLabel(days), generatedAt,
                analysisResult, analyzedModel, rangeLabel(analyzedDays), analyzedAt);
        return ReportImageExport.capture(paper, UiText.get(R.string.analytics_ui_report_generated_at, rangeLabel(days), formatDate(generatedAt, "yyyy-MM-dd HH:mm")), false, width, pageHeight, ReportPresentation.LOGICAL_WIDTH);
    }

    private void shareReportImage() {
        if (closed || currentSnapshot == null || analysisOperation != null || imageOperation != null || exportChoiceDialog != null) return;
        exportChoiceDialog = new MiuixDialogBuilder(context, miuix).setTitle(UiText.get(R.string.analytics_ui_export_share_report))
                .setItems(new String[]{UiText.get(R.string.analytics_ui_long_png), UiText.get(R.string.analytics_ui_pdf_document)}, (ignored, selected) -> {
                    exportChoiceDialog = null;
                    if (selected >= 0 && selected < 2) exportReport(selected == 0
                            ? ReportImageExport.Format.LONG_PNG : ReportImageExport.Format.PDF);
                }).setNegativeButton(UiText.get(R.string.analytics_ui_cancel), null).create();
        AlertDialog shown = exportChoiceDialog;
        shown.setOnDismissListener(ignored -> { if (exportChoiceDialog == shown) exportChoiceDialog = null; });
        shown.show();
        if (!miuix && activity != null) HostDialogBounds.install(activity, shown, true);
    }

    private void exportReport(ReportImageExport.Format format) {
        if (closed || currentSnapshot == null || analysisOperation != null || imageOperation != null) return;
        ImageOperation operation = new ImageOperation(format); imageOperation = operation;
        imageShare.setEnabled(false); imageShareNote.setText(UiText.get(R.string.analytics_ui_laying_out_report));
        ReportImageExport.Document document;
        try { document = captureReportImage(ReportImageExport.WIDTH, ReportImageExport.PAGE_HEIGHT); }
        catch (RuntimeException | OutOfMemoryError failure) { imageFailed(operation); return; }
        if (Build.VERSION.SDK_INT >= 29) saveReportImages(operation, document, null);
        else {
            imageShareNote.setText(UiText.get(R.string.analytics_ui_choose_export_location, (format == ReportImageExport.Format.PDF ? UiText.get(R.string.analytics_ui_pdf_document) : UiText.get(R.string.analytics_ui_long_png))));
            operation.picker = HostConfigFilePicker.launch(activity, ReportImageShare.destination(format), uri -> {
                operation.picker = null;
                if (closed || imageOperation != operation || operation.cancelled) return;
                if (uri == null) {
                    imageOperation = null; imageShare.setEnabled(true); imageShareNote.setText(UiText.get(R.string.analytics_ui_export_cancelled_no_file));
                } else saveReportImages(operation, document, uri);
            });
            if (operation.picker == null && imageOperation == operation) imageFailed(operation);
        }
    }

    private void saveReportImages(ImageOperation operation, ReportImageExport.Document document, Uri destination) {
        Context application = context.getApplicationContext();
        WeakReference<LocalAnalyticsDialog> target = new WeakReference<>(this);
        Handler dispatcher = main;
        try { worker.execute(() -> {
            ReportImageShare.Saved saved = null;
            try {
                saved = ReportImageShare.save(application, document, operation.format, destination, () -> !operation.cancelled);
                operation.saved = saved;
                if (operation.cancelled) { saved.delete(); return; }
            } catch (Exception | OutOfMemoryError failure) {
                boolean tooLong = failure instanceof ReportImageExport.LongPngLimitException;
                dispatcher.post(() -> { LocalAnalyticsDialog page = target.get(); if (page != null) page.imageFailed(operation, tooLong); });
                return;
            }
            ReportImageShare.Saved ready = saved;
            dispatcher.post(() -> {
                LocalAnalyticsDialog page = target.get();
                if (page == null || page.closed || page.imageOperation != operation || operation.cancelled) { ReportImageShare.cleanup(ready); return; }
                page.imageOperation = null; operation.saved = null;
                ready.handOff(); // Host onPause may close the report as soon as the chooser is launched.
                try {
                    Intent chooser = ready.chooser();
                    if (page.activity == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    page.context.startActivity(chooser);
                    LocalAnalytics.feature("analytics_share", "打开分析分享");
                    if (!page.closed) {
                        page.imageShare.setEnabled(true);
                        page.imageShareNote.setText(operation.format == ReportImageExport.Format.PDF
                                ? UiText.get(R.string.analytics_ui_pdf_generated, document.pdfPageCount()) : UiText.get(R.string.analytics_ui_png_generated));
                    }
                } catch (RuntimeException failure) {
                    ready.failedHandOff();
                    if (!page.closed) { page.imageShare.setEnabled(true); page.imageShareNote.setText(UiText.get(R.string.analytics_ui_share_report_unavailable)); }
                }
            });
        }); } catch (RuntimeException unavailable) { imageFailed(operation); }
    }

    private void imageFailed(ImageOperation operation) {
        imageFailed(operation, false);
    }

    private void imageFailed(ImageOperation operation, boolean tooLong) {
        if (closed || imageOperation != operation || operation.cancelled) return;
        imageOperation = null; imageShare.setEnabled(true);
        imageShareNote.setText(tooLong ? UiText.get(R.string.analytics_ui_prefer_pdf_for_long_report)
                : UiText.get(R.string.analytics_ui_report_export_failed));
    }

    private void cancelImageExport() {
        if (exportChoiceDialog != null) exportChoiceDialog.dismiss();
        exportChoiceDialog = null;
        ImageOperation operation = imageOperation; imageOperation = null;
        if (operation == null) return;
        operation.cancelled = true;
        if (operation.picker != null) try { operation.picker.close(); } catch (Exception ignored) { }
        ReportImageShare.Saved saved = operation.saved;
        if (saved != null) ReportImageShare.cleanup(saved);
    }

    private static final class ImageOperation {
        final ReportImageExport.Format format;
        ImageOperation(ReportImageExport.Format format) { this.format = format; }
        volatile boolean cancelled;
        volatile ReportImageShare.Saved saved;
        AutoCloseable picker;
    }

    private void resetAnalysis() {
        analysisGeneration++;
        AnalysisOperation operation = analysisOperation;
        analysisOperation = null;
        if (operation != null) operation.cancel();
        analysisResult = null;
        analysisError = null;
        analyzedModel = null;
        analyzedAt = 0;
    }

    private static String rangeLabel(int days) {
        return days == 0 ? UiText.get(R.string.analytics_ui_all_retained_days) : days == 1 ? UiText.get(R.string.analytics_ui_today) : UiText.get(R.string.analytics_ui_recent_days, days);
    }

    private static final class AnalysisOperation {
        final AnalysisService.Request request;
        volatile boolean cancelled;
        AnalysisOperation(AnalysisService.Request request) { this.request = request; }
        void cancel() {
            cancelled = true;
            try { request.cancel(); } catch (RuntimeException ignored) { }
        }
    }

    private LinearLayout ranking(String key, String title, List<Rank> entries, String empty) {
        LinearLayout card = section(key, title);
        if (entries.isEmpty()) { caption(card, empty); return card; }
        long max = 1;
        for (Rank item : entries) max = Math.max(max, item.count);
        for (int index = 0; index < Math.min(10, entries.size()); index++) {
            Rank item = entries.get(index);
            LinearLayout line = new LinearLayout(context);
            line.setGravity(Gravity.TOP);
            TextView label = text((index + 1) + "  " + AnalyticsUiLabels.rank(item), 13, TEXT, false);
            label.setPadding(0, dp(7), dp(10), dp(5));
            line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView count = text(number(item.count), 13, MUTED, true);
            count.setPadding(0, dp(7), 0, dp(5));
            line.addView(count, new LinearLayout.LayoutParams(-2, -2));
            card.addView(line, matchWrap());
            Bar bar = new Bar(context, item.count / (float) max, PALETTE[index % PALETTE.length]);
            bar.setContentDescription(UiText.get(R.string.analytics_ui_label_count_accessibility, AnalyticsUiLabels.rank(item), number(item.count)));
            card.addView(bar, new LinearLayout.LayoutParams(-1, dp(7)));
        }
        return card;
    }

    private void metric(LinearLayout parent, String label, long count) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 14, MUTED, false);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(number(count), 22, TEXT, true);
        value.setPadding(dp(8), dp(4), 0, dp(4));
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        row.setContentDescription(UiText.get(R.string.analytics_ui_label_value, label, number(count)));
        parent.addView(row, matchWrap());
    }

    private LinearLayout section(String key, String title) {
        ReportSection section = new ReportSection(title, key, "overview".equals(key) || "analysis".equals(key));
        decorateCard(section);
        reportSections.put(key, section);
        report.addView(section, cardParams());
        return section;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        decorateCard(card);
        parent.addView(card, cardParams());
        return card;
    }

    private void decorateCard(LinearLayout card) {
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(rounded(Color.WHITE, 12));
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        return params;
    }

    private void updateSectionSummaries(AnalyticsSnapshot data) {
        for (Map.Entry<String, ReportSection> item : reportSections.entrySet()) {
            String summary;
            switch (item.getKey()) {
                case "portrait": summary = UiText.get(R.string.analytics_ui_portrait_summary, AnalyticsUiLabels.evidenceLevel(data.portrait), number(data.portrait.authorCount), number(data.portrait.topicCount)); break;
                case "trend": summary = UiText.get(R.string.analytics_ui_views_days_summary, number(data.totalViews), number(data.activeDays)); break;
                case "types": summary = data.types.isEmpty() ? UiText.get(R.string.analytics_ui_no_type_records) : AnalyticsUiLabels.rank(data.types.get(0))
                        + " · " + percent(data.types.get(0).count, total(data.types)); break;
                case "authors": summary = leading(data.authors, UiText.get(R.string.analytics_ui_most_encountered_prefix), UiText.get(R.string.analytics_ui_no_author_records)); break;
                case "topics": summary = leading(data.topics, UiText.get(R.string.analytics_ui_leading_topic_prefix), UiText.get(R.string.analytics_ui_no_topic_records)); break;
                case "features": summary = UiText.get(R.string.analytics_ui_feature_summary, number(data.totalFeatureUses), (data.features.isEmpty() ? "" : " · " + AnalyticsUiLabels.rank(data.features.get(0)))); break;
                case "hours": {
                    int peak = 0;
                    for (int index = 1; index < data.hours.length; index++) if (data.hours[index] > data.hours[peak]) peak = index;
                    summary = data.totalViews == 0 ? UiText.get(R.string.analytics_ui_no_hour_records) : UiText.get(R.string.analytics_ui_peak_summary, String.format(ModuleLocale.locale(), "%02d:00", peak), number(data.hours[peak]));
                    break;
                }
                case "changes": summary = data.portrait.comparison == null ? UiText.get(R.string.analytics_ui_no_previous_comparison)
                        : !data.portrait.comparison.currentComplete || !data.portrait.comparison.previousComplete
                        ? UiText.get(R.string.analytics_ui_comparison_incomplete_summary) : UiText.get(R.string.analytics_ui_view_period_changes); break;
                case "associations": summary = UiText.get(R.string.analytics_ui_segments_summary, number(data.portrait.capturedVisits), number(data.portrait.finishedVisits)); break;
                case "recent": summary = data.recent.isEmpty() ? UiText.get(R.string.analytics_ui_no_recent_records) : UiText.get(R.string.analytics_ui_latest_record, data.recent.get(0).label); break;
                case "history": summary = UiText.get(R.string.analytics_ui_active_streak_summary, number(data.activeDays), number(data.currentStreak)); break;
                default: summary = "";
            }
            item.getValue().setSummary(summary);
        }
    }

    private static String leading(List<Rank> ranks, String prefix, String empty) {
        return ranks.isEmpty() ? empty : UiText.get(R.string.analytics_ui_leading_rank, prefix, AnalyticsUiLabels.rank(ranks.get(0)), number(ranks.get(0).count));
    }

    /** Only the details collapse; the card heading and evidence-backed short summary stay visible. */
    private final class ReportSection extends LinearLayout {
        private final String key;
        private final String title;
        private final boolean alwaysOpen;
        private final LinearLayout details;
        private final LinearLayout header;
        private final TextView heading;
        private final TextView summary;
        private final Button toggle;
        private boolean expanded;
        private boolean hasAnalysis;
        private String shortSummary = "";

        ReportSection(String title, String key, boolean alwaysOpen) {
            super(LocalAnalyticsDialog.this.context);
            this.key = key;
            this.title = title;
            this.alwaysOpen = alwaysOpen;
            setOrientation(VERTICAL);
            header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            heading = text(title, 16, TEXT, true);
            heading.setTag("analytics.section.heading." + key);
            heading.setAccessibilityHeading(true);
            heading.setPadding(0, dp(6), dp(4), dp(6));
            header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            if (alwaysOpen) {
                toggle = null;
            } else {
                toggle = button(UiText.get(R.string.analytics_ui_expand), "analytics.section.toggle." + key, () -> setExpanded(!expanded));
                header.addView(controlView(toggle), new LinearLayout.LayoutParams(dp(76), dp(52)));
                header.setOnClickListener(ignored -> setExpanded(!expanded));
            }
            summary = text("", 13, MUTED, false);
            summary.setTag("analytics.section.summary." + key);
            summary.setMaxLines(2);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            summary.setLineSpacing(dp(2), 1);
            summary.setPadding(0, 0, 0, dp(4));
            details = column();
            details.setTag("analytics.section.body." + key);
            details.setPadding(0, dp(6), 0, 0);
            super.addView(header, -1, matchWrap());
            super.addView(summary, -1, matchWrap());
            super.addView(details, -1, matchWrap());
            setExpanded(alwaysOpen || Boolean.TRUE.equals(expandedSections.get(key)));
        }

        @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
            // Existing chart factories keep attaching to their own card; only the detail body is hidden.
            if (details == null) super.addView(child, index, params);
            else details.addView(child, index, params);
        }

        void setExpanded(boolean value) {
            expanded = alwaysOpen || value;
            expandedSections.put(key, expanded);
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (toggle != null) {
                toggle.setText(expanded ? UiText.get(R.string.analytics_ui_collapse) : UiText.get(R.string.analytics_ui_expand));
                toggle.setSelected(expanded);
                toggle.setContentDescription(UiText.get(expanded ? R.string.analytics_ui_expanded_collapse_label : R.string.analytics_ui_collapsed_expand_label, title));
                heading.setContentDescription(UiText.get(expanded ? R.string.analytics_ui_expanded_label : R.string.analytics_ui_collapsed_label, title));
                header.setContentDescription(toggle.getContentDescription());
            }
        }

        void setSummary(String value) { shortSummary = value; refreshSummary(); }
        void setHasAnalysis(boolean value) { hasAnalysis = value; refreshSummary(); }
        private void refreshSummary() {
            summary.setText(!hasAnalysis ? shortSummary : shortSummary.isEmpty() ? UiText.get(R.string.analytics_ui_contains_ai) : UiText.get(R.string.analytics_ui_summary_with_ai, shortSummary));
            summary.setContentDescription(summary.getText());
            summary.setVisibility(summary.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void caption(LinearLayout parent, String value) {
        TextView text = text(value, 13, MUTED, false);
        text.setLineSpacing(dp(3), 1);
        text.setPadding(0, dp(6), 0, dp(6));
        parent.addView(text, matchWrap());
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button button(String label, String tag, Runnable action) {
        Button button = miuix ? MiuixFormControls.button(context) : new Button(context);
        button.setTag(tag);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(ACCENT);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable surface = rounded(0xFFE7F5F2, 12);
        surface.setStroke(dp(1), 0xFFC9E5DF);
        button.setBackground(surface);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private View controlView(View control) { return MiuixFormControls.viewFor(control); }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private static long total(List<Rank> ranks) {
        long result = 0;
        for (Rank item : ranks) result += item.count;
        return result;
    }
    private static String percent(long count, long total) {
        return String.format(ModuleLocale.locale(), "%.2f%%", total == 0 ? 0 : count * 100.0 / total);
    }
    private static String number(long count) { return String.format(ModuleLocale.locale(), "%,d", count); }
    private static String formatDate(long timestamp, String pattern) {
        return new SimpleDateFormat(pattern, ModuleLocale.locale()).format(new Date(timestamp));
    }
    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelImageExport();
        resetAnalysis();
        generation++;
        main.removeCallbacksAndMessages(null);
        // A confirmed clear or opt-out must finish even if the report closes immediately.
        if (worker instanceof ExecutorService) ((ExecutorService) worker).shutdown();
        if (analysisWorker instanceof ExecutorService) ((ExecutorService) analysisWorker).shutdownNow();
        if (clearConfirmation != null) clearConfirmation.dismiss();
        clearConfirmation = null;
        if (contextDialog != null) contextDialog.dismiss();
        contextDialog = null;
        if (analysisSettingsDialog != null) analysisSettingsDialog.dismiss();
        analysisSettingsDialog = null;
    }

    private abstract static class Chart extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        Chart(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        float d(float value) { return value * density; }
        int color(int light) { return ModuleTheme.color(getContext(), light); }
        void label(Canvas canvas, String value, float x, float y, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color(MUTED));
            paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextAlign(align);
            canvas.drawText(value, x, y, paint);
        }
        void line(Canvas canvas, float x1, float y1, float x2, float y2) {
            paint.setColor(color(LINE));
            paint.setStrokeWidth(d(1));
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
        @Override protected void onConfigurationChanged(Configuration config) {
            super.onConfigurationChanged(config);
            invalidate();
        }
    }

    private static final class Bar extends Chart {
        private final float fraction;
        private final int barColor;
        Bar(Context context, float fraction, int barColor) {
            super(context);
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.barColor = barColor;
        }
        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(color(0xFFEEEFF3));
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), d(4), d(4), paint);
            paint.setColor(barColor);
            canvas.drawRoundRect(0, 0, getWidth() * fraction, getHeight(), d(4), d(4), paint);
        }
    }

    private static final class TrendChart extends Chart {
        private final List<Bucket> values;
        private final IntConsumer selection;
        private final Path linePath = new Path();
        private int selected = -1;
        private float downX, downY;
        TrendChart(Context context, List<Bucket> values, IntConsumer selection) {
            super(context);
            this.values = values;
            this.selection = selection;
            setClickable(true);
            setFocusable(true);
            StringBuilder summary = new StringBuilder(UiText.get(R.string.analytics_ui_trend_accessibility));
            for (Bucket value : values) summary.append(UiText.get(R.string.analytics_ui_chart_count_entry, value.label, number(value.count)));
            setContentDescription(summary.toString());
        }
        @Override protected void onDraw(Canvas canvas) {
            if (values.isEmpty()) return;
            long max = 1;
            for (Bucket value : values) max = Math.max(max, value.count);
            paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            float left = Math.min(getWidth() * .38f, Math.max(d(32), paint.measureText(number(max)) + d(8)));
            float right = getWidth() - d(9), top = d(18), bottom = getHeight() - d(30);
            float width = Math.max(1, right - left);
            for (int row = 0; row <= 2; row++) {
                float y = top + (bottom - top) * row / 2;
                line(canvas, left, y, right, y);
                label(canvas, number(Math.round(max * (2 - row) / 2.0)), left - d(6), y + d(4), Paint.Align.RIGHT);
            }
            linePath.reset();
            for (int index = 0; index < values.size(); index++) {
                float x = values.size() == 1 ? left + width / 2 : left + width * index / (values.size() - 1);
                float y = bottom - (bottom - top) * values.get(index).count / max;
                if (index == 0) linePath.moveTo(x, y); else linePath.lineTo(x, y);
            }
            paint.setColor(color(ACCENT));
            paint.setStrokeWidth(d(2.5f));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(linePath, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int index = 0; index < values.size(); index++) {
                if (values.size() > 31 && index != selected) continue;
                float x = values.size() == 1 ? left + width / 2 : left + width * index / (values.size() - 1);
                float y = bottom - (bottom - top) * values.get(index).count / max;
                canvas.drawCircle(x, y, d(index == selected ? 5 : 2.5f), paint);
            }
            label(canvas, values.get(0).label, left, getHeight() - d(8), Paint.Align.LEFT);
            if (values.size() > 1) label(canvas, values.get(values.size() - 1).label, right, getHeight() - d(8), Paint.Align.RIGHT);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY) < d(20)) {
                    float fraction = (event.getX() - d(42)) / Math.max(1, getWidth() - d(51));
                    selected = Math.max(0, Math.min(values.size() - 1, Math.round(fraction * (values.size() - 1))));
                    if (selected >= 0) { selection.accept(selected); invalidate(); }
                    // Accessibility services invoke performClick directly to cycle through points.
                    super.performClick();
                }
                return true;
            }
            return event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        }
        @Override public boolean performClick() {
            super.performClick();
            if (values.isEmpty()) return false;
            selected = (selected + 1) % values.size();
            selection.accept(selected);
            invalidate();
            return true;
        }
    }

    private static final class DonutChart extends Chart {
        private final List<Rank> values;
        private final RectF bounds = new RectF();
        DonutChart(Context context, List<Rank> values) {
            super(context);
            this.values = values;
            StringBuilder summary = new StringBuilder(UiText.get(R.string.analytics_ui_type_share_accessibility));
            long total = total(values);
            for (Rank item : values) summary.append(UiText.get(R.string.analytics_ui_chart_share_entry, AnalyticsUiLabels.rank(item), number(item.count), percent(item.count, total)));
            setContentDescription(summary.toString());
        }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * .39f;
            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            long total = total(values);
            float angle = -90;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(radius * .32f);
            for (int index = 0; index < values.size(); index++) {
                float sweep = total == 0 ? 0 : 360f * values.get(index).count / total;
                paint.setColor(PALETTE[index % PALETTE.length]);
                // Do not discard a small nonzero category by imposing a minimum slice gap.
                canvas.drawArc(bounds, angle, sweep, false, paint);
                angle += sweep;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(25 * getResources().getDisplayMetrics().scaledDensity);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setColor(color(TEXT));
            String count = number(total);
            if (paint.measureText(count) > radius * 1.4f) paint.setTextSize(paint.getTextSize() * radius * 1.4f / paint.measureText(count));
            canvas.drawText(count, cx, cy + d(2), paint);
            label(canvas, UiText.get(R.string.analytics_ui_views), cx, cy + d(24), Paint.Align.CENTER);
        }
    }

    private static final class HourChart extends Chart {
        private final long[] counts;
        HourChart(Context context, long[] counts) {
            super(context);
            this.counts = counts;
            StringBuilder summary = new StringBuilder(UiText.get(R.string.analytics_ui_hour_distribution_accessibility));
            for (int hour = 0; hour < counts.length; hour++) summary.append(UiText.get(R.string.analytics_ui_chart_hour_entry, number(hour), number(counts[hour])));
            setContentDescription(summary.toString());
        }
        @Override protected void onDraw(Canvas canvas) {
            long max = 1;
            for (long count : counts) max = Math.max(max, count);
            paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            float left = Math.min(getWidth() * .38f, Math.max(d(32), paint.measureText(number(max)) + d(8)));
            float top = d(16), bottom = getHeight() - d(28), right = getWidth() - d(4);
            for (int row = 0; row <= 2; row++) {
                float y = top + (bottom - top) * row / 2;
                line(canvas, left, y, right, y);
                label(canvas, number(Math.round(max * (2 - row) / 2.0)), left - d(5), y + d(4), Paint.Align.RIGHT);
            }
            float unit = (right - left) / 24;
            for (int hour = 0; hour < Math.min(24, counts.length); hour++) {
                paint.setColor(PALETTE[1]);
                float height = (bottom - top) * counts[hour] / max;
                canvas.drawRoundRect(left + unit * hour + d(1), bottom - height,
                        left + unit * (hour + 1) - d(1), bottom, d(2), d(2), paint);
                if (hour % 6 == 0 || hour == 23) label(canvas, String.valueOf(hour), left + unit * (hour + .5f),
                        getHeight() - d(7), Paint.Align.CENTER);
            }
        }
    }
}
