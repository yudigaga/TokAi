package com.tokai.module.ui;

import com.tokai.module.R;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Spinner;
import android.widget.TextView;

import com.tokai.module.config.SettingsStore;
import com.tokai.module.diagnostics.DebugLog;
import com.tokai.module.diagnostics.DebugLogView;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One bounded viewer for the host log, available from either settings process. */
final class DebugLogDialog implements AutoCloseable {
    static final int DISPLAY_CHARACTERS = 128 * 1024;
    private static final ThreadPoolExecutor FILES = new ThreadPoolExecutor(1, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), action -> {
                Thread thread = new Thread(action, "TokAi-debug-export"); thread.setDaemon(true); return thread;
            });
    interface Source {
        void read(Consumer<Snapshot> callback);
        void enabled(boolean enabled, Consumer<Boolean> callback);
        void clear(Consumer<Boolean> callback);
        void export(Consumer<Snapshot> callback);
    }
    static final class Snapshot {
        final boolean enabled, available;
        final String text;
        Snapshot(boolean enabled, boolean available, String text) {
            this.enabled = enabled; this.available = available; this.text = text == null ? "" : text;
        }
    }
    interface Exporter {
        AutoCloseable choose(Activity activity, Consumer<Uri> callback);
        void write(Uri uri, String text, BooleanSupplier valid) throws Exception;
        default void delete(Uri uri) { }
    }

    private final SettingsStore store;
    private final Source source;
    private final Exporter exporter;
    private final Executor worker;
    private final Activity activity;
    private final Context context;
    private final boolean miuix;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AlertDialog dialog;
    private final CompoundButton enabled;
    private final TextView status, logText, summary;
    private final EditText search;
    private final Spinner category;
    private final Button refreshButton, clearButton, copyButton, exportButton, entriesButton;
    private volatile boolean closed;
    private volatile long generation;
    private boolean busy, binding, available;
    private String displayed = "";
    private DebugLogView index = DebugLogView.parse("");
    private DebugLogView.Selection filtered = index.select(DebugLogView.Category.ALL, "");
    private final Runnable filterChanged = this::renderSelection;
    private AutoCloseable selection;
    private TokAiLog.Operation exportTrace;
    private Button ownExportButton;
    private AlertDialog entryPicker, entryDetails;

    DebugLogDialog(Context context, SettingsStore store, boolean miuix) {
        this(context, store, miuix, source(context), exporter(context), FILES);
    }

    DebugLogDialog(Context base, SettingsStore store, boolean miuix, Source source,
                   Exporter exporter, Executor worker) {
        this.context = ModuleTheme.themedContext(base);
        this.miuix = miuix;
        this.activity = activity(base);
        this.store = store; this.source = source; this.exporter = exporter; this.worker = worker;
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        body.setPadding(padding, 0, padding, padding);
        body.addView(text(com.tokai.module.i18n.UiText.get(R.string.automation_ui_debug_note),
                "debug.logs.note", 14), matchWrap());
        enabled = miuix ? MiuixFormControls.checkbox(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_debug_enabled), false, "全局调试日志") : new Switch(context);
        enabled.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_debug_enabled)); enabled.setTag("debug.logs.enabled");
        enabled.setChecked(store.getBoolean(DebugLog.KEY, false)); enabled.setEnabled(false);
        body.addView(MiuixFormControls.viewFor(enabled), matchWrap());
        enabled.setOnCheckedChangeListener((button, checked) -> { if (!binding) changeEnabled(checked); });
        LinearLayout first = row(body);
        refreshButton = button(first, miuix, com.tokai.module.i18n.UiText.get(R.string.automation_ui_refresh), "debug.logs.refresh", this::refresh);
        clearButton = button(first, miuix, com.tokai.module.i18n.UiText.get(R.string.automation_ui_clear), "debug.logs.clear", this::clear);
        LinearLayout second = row(body);
        copyButton = button(second, miuix, com.tokai.module.i18n.UiText.get(R.string.automation_ui_copy), "debug.logs.copy", this::copy);
        exportButton = button(second, miuix, com.tokai.module.i18n.UiText.get(R.string.automation_ui_export), "debug.logs.export", this::export);
        if (com.tokai.module.BuildConfig.APPLICATION_ID.equals(context.getPackageName())) {
            ownExportButton = button(row(body), miuix, com.tokai.module.i18n.UiText.get(R.string.automation_ui_export_module_logs), "debug.logs.export-own", this::exportOwn);
        }
        LinearLayout filters = row(body);
        category = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        category.setTag("debug.logs.category");
        List<String> categories = new ArrayList<>();
        for (DebugLogView.Category value : DebugLogView.Category.values()) categories.add(value.displayLabel());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, categories);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(adapter);
        filters.addView(MiuixFormControls.viewFor(category), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        entriesButton = button(filters, miuix, com.tokai.module.i18n.UiText.get(R.string.automation_ui_view_entries), "debug.logs.entries", this::showEntries);
        search = miuix ? MiuixFormControls.input(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_search_logs), "", false, "搜索日志") : new EditText(context);
        search.setTag("debug.logs.search"); search.setSingleLine(true);
        search.setHint(com.tokai.module.i18n.UiText.get(R.string.automation_ui_search_logs_hint));
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        search.setTextSize(14);
        body.addView(MiuixFormControls.viewFor(search), matchWrap());
        summary = text("", "debug.logs.summary", 12);
        summary.setTextColor(0xFF69737A); body.addView(summary, matchWrap());
        status = text(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reading_logs), "debug.logs.status", 13);
        status.setTextColor(0xFF69737A); body.addView(status, matchWrap());
        TextView range = text(com.tokai.module.i18n.UiText.get(R.string.automation_ui_debug_range_hint),
                "debug.logs.range", 12);
        range.setTextColor(0xFF69737A); body.addView(range, matchWrap());
        logText = text("", "debug.logs.text", 12);
        logText.setTypeface(Typeface.MONOSPACE); logText.setTextIsSelectable(true);
        body.addView(logText, matchWrap());
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("debug.logs.scroll"); scroll.addView(body);
        dialog = new MiuixDialogBuilder(context, miuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_debug_logs))
                .setView(scroll).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_close), null).create();
        dialog.setOnShowListener(ignored -> {
            if (activity != null) HostDialogBounds.install(activity, dialog, false);
            else ModuleTheme.bind(dialog.getWindow().getDecorView());
            refresh();
        });
        dialog.setOnDismissListener(ignored -> close());
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { renderSelection(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                main.removeCallbacks(filterChanged); main.postDelayed(filterChanged, 150);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        controls();
    }

    AlertDialog dialog() { return dialog; }

    private void refresh() {
        if (closed || busy) return;
        long token = start(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reading_logs));
        try {
            source.read(snapshot -> {
                if (!current(token)) return;
                busy = false; available = snapshot != null && snapshot.available;
                if (snapshot != null) {
                    binding = true; enabled.setChecked(snapshot.enabled); binding = false;
                }
                index = DebugLogView.parse(available ? tail(snapshot.text, DISPLAY_CHARACTERS) : "");
                renderSelection();
                status.setText(available ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_refreshed) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_host_logs_unavailable));
                controls();
            });
        } catch (RuntimeException unavailable) { finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_read_logs_failed)); }
    }

    private void changeEnabled(boolean value) {
        if (closed || busy || !available) return;
        long token = start(com.tokai.module.i18n.UiText.get(R.string.automation_ui_saving_switch));
        try {
            source.enabled(value, success -> {
                if (!current(token)) return;
                busy = false;
                if (!Boolean.TRUE.equals(success)) status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_host_switch_failed));
                controls();
                if (Boolean.TRUE.equals(success)) {
                    try { store.putBoolean(DebugLog.KEY, value); }
                    catch (RuntimeException unavailable) {
                        status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_local_switch_save_failed));
                        return;
                    }
                    refresh();
                }
                else { binding = true; enabled.setChecked(!value); binding = false; }
            });
        } catch (RuntimeException unavailable) {
            binding = true; enabled.setChecked(!value); binding = false;
            finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_switch_save_failed));
        }
    }

    private void clear() {
        if (closed || busy || !available) return;
        long token = start(com.tokai.module.i18n.UiText.get(R.string.automation_ui_clearing_logs));
        try {
            source.clear(success -> {
                if (!current(token)) return;
                busy = false;
                if (Boolean.TRUE.equals(success)) { index = DebugLogView.parse(""); renderSelection(); refresh(); }
                else { status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_clear_failed)); controls(); }
            });
        } catch (RuntimeException unavailable) { finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_clear_failed)); }
    }

    private void copy() {
        main.removeCallbacks(filterChanged); renderSelection();
        if (closed || busy || !available || displayed.isEmpty()) return;
        copy(displayed, com.tokai.module.i18n.UiText.get(R.string.automation_ui_clipboard_logs), com.tokai.module.i18n.UiText.get(R.string.automation_ui_filtered_logs_copied));
    }

    private void copy(String text, String label, String success) {
        if (closed) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException();
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            status.setText(success);
        } catch (RuntimeException unavailable) { status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_copy_failed)); }
    }

    private void renderSelection() {
        if (closed) return;
        int position = category.getSelectedItemPosition();
        DebugLogView.Category[] categories = DebugLogView.Category.values();
        DebugLogView.Category selected = position < 0 || position >= categories.length ? categories[0] : categories[position];
        filtered = index.select(selected, search.getText().toString());
        displayed = filtered.text;
        logText.setText(!available ? "" : displayed.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_no_matching_logs) : displayed);
        summary.setText(available ? filtered.displaySummary() : "");
        controls();
    }

    private void showEntries() {
        main.removeCallbacks(filterChanged); renderSelection();
        if (closed || busy || !available || filtered.entries.isEmpty()) return;
        if (entryPicker != null) entryPicker.dismiss();
        List<DebugLogView.Entry> recent = new ArrayList<>();
        for (int i = filtered.entries.size() - 1; i >= 0 && recent.size() < 100; i--) recent.add(filtered.entries.get(i));
        String[] labels = new String[recent.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = recent.get(i).displayTitle();
        AlertDialog picker = new MiuixDialogBuilder(context, miuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_log_entries_count, labels.length))
                .setItems(labels, (ignored, which) -> showEntry(recent.get(which)))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_close), null).create();
        entryPicker = picker;
        picker.setOnDismissListener(ignored -> { if (entryPicker == picker) entryPicker = null; });
        showChild(picker);
    }

    private void showEntry(DebugLogView.Entry entry) {
        if (closed) return;
        if (entryDetails != null) entryDetails.dismiss();
        TextView content = text(formatEntry(entry.text), "debug.logs.entry.text", 12);
        content.setTypeface(Typeface.MONOSPACE); content.setTextIsSelectable(true);
        int padding = dp(16); content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(context); scroll.addView(content);
        AlertDialog detail = new MiuixDialogBuilder(context, miuix).setTitle(entry.displayTitle()).setView(scroll)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_copy_entry), (ignored, which) -> copy(entry.text, com.tokai.module.i18n.UiText.get(R.string.automation_ui_clipboard_entry), com.tokai.module.i18n.UiText.get(R.string.automation_ui_entry_copied)))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_close), null).create();
        entryDetails = detail;
        detail.setOnDismissListener(ignored -> { if (entryDetails == detail) entryDetails = null; });
        showChild(detail);
    }

    private void showChild(AlertDialog child) {
        if (activity != null) child.setOwnerActivity(activity);
        child.setOnShowListener(ignored -> {
            if (activity != null) HostDialogBounds.install(activity, child, false);
            else ModuleTheme.bind(child.getWindow().getDecorView());
        });
        child.show();
    }

    static String formatEntry(String text) {
        int marker = text.indexOf("json=");
        if (marker < 0) return text;
        String json = text.substring(marker + 5).trim();
        try {
            org.json.JSONTokener reader = new org.json.JSONTokener(json);
            Object parsed = reader.nextValue();
            if (reader.nextClean() != 0) return text;
            if (parsed instanceof org.json.JSONObject) json = ((org.json.JSONObject) parsed).toString(2);
            else if (parsed instanceof org.json.JSONArray) json = ((org.json.JSONArray) parsed).toString(2);
            else return text;
            return text.substring(0, marker + 5) + "\n" + json;
        } catch (org.json.JSONException | RuntimeException | StackOverflowError invalid) { return text; }
    }

    private void export() {
        if (closed || busy || !available || activity == null) return;
        TokAiLog.Operation trace = TokAiLog.begin("diagnostics", "export_logs");
        exportTrace = trace;
        long token = start(com.tokai.module.i18n.UiText.get(R.string.automation_ui_preparing_logs));
        try {
            trace.stage("snapshot_read", "source_available=" + available);
            source.export(trace.wrapConsumer(snapshot -> {
                if (!current(token)) return;
                if (snapshot == null || !snapshot.available) { trace.finish(TokAiLog.Outcome.FAILED, "log_source_unavailable", ""); finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_read_full_logs_failed)); return; }
                choose(token, snapshot.text, trace);
            }));
        } catch (RuntimeException unavailable) { trace.error("snapshot_read", unavailable); trace.finish(TokAiLog.Outcome.FAILED, "log_export_failed", ""); finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_export_failed)); }
    }
    private void exportOwn() {
        if (closed || busy || activity == null) return;
        TokAiLog.Operation trace = TokAiLog.begin("diagnostics", "export_module_process_logs");
        exportTrace = trace; long token = start(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reading_own_logs));
        DebugLog.exportOwnProcess(context, trace.wrapConsumer(text -> {
            if (!current(token)) return;
            if (DebugLog.UNAVAILABLE_TEXT.equals(text)) {
                trace.finish(TokAiLog.Outcome.FAILED, "module_log_source_unavailable", "host_channel_unchanged=true");
                finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_own_logs_unavailable)); return;
            }
            choose(token, text, trace);
        }));
    }

    private void choose(long token, String data, TokAiLog.Operation trace) {
        status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_log_destination));
        AtomicBoolean delivered = new AtomicBoolean();
        AutoCloseable pending;
        try {
            trace.stage("destination_picker", "snapshot_chars=" + data.length());
            pending = exporter.choose(activity, trace.wrapConsumer(uri -> {
                if (!delivered.compareAndSet(false, true) || !current(token)) return;
                selection = null;
                if (uri == null) { trace.finish(TokAiLog.Outcome.CANCELLED, "user_cancelled", ""); finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_export_cancelled)); return; }
                status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_exporting_logs));
                try {
                    worker.execute(trace.wrap(() -> {
                        String result;
                        try {
                            trace.stage("write_snapshot", "bytes=" + data.getBytes(StandardCharsets.UTF_8).length);
                            exporter.write(uri, data, () -> current(token)); result = com.tokai.module.i18n.UiText.get(R.string.automation_ui_logs_exported);
                            trace.finish(TokAiLog.Outcome.SUCCESS, "log_stream_closed", "export_write_returned=true;readback_verified=false;snapshot_captured_before_export_completion=true");
                        }
                        catch (Exception failure) { trace.error("write_snapshot", failure); trace.finish(current(token) ? TokAiLog.Outcome.FAILED : TokAiLog.Outcome.CANCELLED, "log_export_write_failed", ""); result = com.tokai.module.i18n.UiText.get(R.string.automation_ui_export_destination_failed); }
                        String message = result;
                        main.post(() -> finish(token, message));
                    }));
                } catch (RuntimeException unavailable) {
                    trace.finish(TokAiLog.Outcome.FAILED, "export_queue_rejected", "cleanup_requested=true");
                    Thread cleanup = new Thread(() -> exporter.delete(uri), "TokAi-debug-cleanup");
                    cleanup.setDaemon(true); cleanup.start();
                    finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_export_busy));
                }
            }));
        } catch (RuntimeException unavailable) { trace.error("destination_picker", unavailable); trace.finish(TokAiLog.Outcome.FAILED, "log_picker_unavailable", ""); finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_picker_unavailable)); return; }
        if (!delivered.get() && current(token)) {
            selection = pending;
            if (pending == null) { trace.finish(TokAiLog.Outcome.FAILED, "log_picker_unavailable", ""); finish(token, com.tokai.module.i18n.UiText.get(R.string.automation_ui_picker_retry_settings)); }
        } else close(pending);
    }

    private long start(String message) {
        busy = true; status.setText(message); controls(); return ++generation;
    }
    private boolean current(long token) { return !closed && generation == token; }
    private void finish(long token, String message) {
        if (!current(token)) return;
        exportTrace = null;
        busy = false; status.setText(message); controls();
    }
    private void controls() {
        if (ownExportButton != null) ownExportButton.setEnabled(!closed && !busy);
        enabled.setEnabled(!closed && !busy && available);
        refreshButton.setEnabled(!closed && !busy);
        clearButton.setEnabled(!closed && !busy && available);
        copyButton.setEnabled(!closed && !busy && available && !displayed.isEmpty());
        exportButton.setEnabled(!closed && !busy && available && activity != null);
        entriesButton.setEnabled(!closed && !busy && available && !filtered.entries.isEmpty());
        category.setEnabled(!closed && available);
        search.setEnabled(!closed && available);
    }
    @Override public void close() {
        if (closed) return;
        if (exportTrace != null) { exportTrace.finish(TokAiLog.Outcome.CANCELLED, "log_dialog_closed", ""); exportTrace = null; }
        closed = true; generation++; main.removeCallbacks(filterChanged); close(selection); selection = null;
        if (entryPicker != null) entryPicker.dismiss();
        if (entryDetails != null) entryDetails.dismiss();
    }
    private static void close(AutoCloseable resource) {
        if (resource != null) try { resource.close(); } catch (Exception ignored) { }
    }

    static String tail(String value, int maximum) {
        if (value == null || maximum <= 0) return "";
        if (value.length() <= maximum) return value;
        String prefix = com.tokai.module.i18n.UiText.get(R.string.automation_ui_earlier_logs_omitted);
        if (maximum <= prefix.length()) prefix = "";
        int start = value.length() - (maximum - prefix.length());
        if (Character.isLowSurrogate(value.charAt(start))) start++;
        return prefix + value.substring(start);
    }
    private static Source source(Context context) {
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        return new Source() {
            public void read(Consumer<Snapshot> callback) {
                DebugLog.readSnapshot(owner, DISPLAY_CHARACTERS,
                        value -> callback.accept(new Snapshot(value.enabled, value.available, value.text)));
            }
            public void enabled(boolean value, Consumer<Boolean> callback) { DebugLog.updateEnabled(owner, value, callback); }
            public void clear(Consumer<Boolean> callback) { DebugLog.clear(owner, callback); }
            public void export(Consumer<Snapshot> callback) {
                DebugLog.export(owner, value -> callback.accept(new Snapshot(false,
                        value != null && !DebugLog.UNAVAILABLE_TEXT.equals(value), value)));
            }
        };
    }
    private static Exporter exporter(Context context) {
        Context app = context.getApplicationContext();
        Context owner = app == null ? context : app;
        return new Exporter() {
            public AutoCloseable choose(Activity activity, Consumer<Uri> callback) {
                String name = "TokAi-debug-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".log";
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                return HostConfigFilePicker.launch(activity, intent, callback);
            }
            public void write(Uri uri, String text, BooleanSupplier valid) throws Exception {
                boolean complete = false;
                try {
                    if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) throw new IOException("unavailable");
                    if (!valid.getAsBoolean()) throw new IOException("cancelled");
                    try (OutputStream output = owner.getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IOException("unavailable");
                        byte[] data = text.getBytes(StandardCharsets.UTF_8);
                        for (int offset = 0; offset < data.length; offset += 8192) {
                            if (!valid.getAsBoolean()) throw new IOException("cancelled");
                            output.write(data, offset, Math.min(8192, data.length - offset));
                        }
                        output.flush();
                        if (!valid.getAsBoolean()) throw new IOException("cancelled");
                    }
                    if (!valid.getAsBoolean()) throw new IOException("cancelled");
                    complete = true;
                } finally { if (!complete) delete(uri); }
            }
            public void delete(Uri uri) {
                try { DocumentsContract.deleteDocument(owner.getContentResolver(), uri); } catch (Exception ignored) { }
            }
        };
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(context);
        // Weighted Compose controls need their allocated width; baseline probing measures them unbounded.
        row.setBaselineAligned(false);
        parent.addView(row, matchWrap());
        return row;
    }
    private Button button(LinearLayout row, boolean miuix, String label, String tag, Runnable action) {
        Button button = miuix ? MiuixFormControls.button(context) : new Button(context);
        button.setText(label); button.setTag(tag); button.setOnClickListener(ignored -> action.run());
        row.addView(MiuixFormControls.viewFor(button), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1)); return button;
    }
    private TextView text(String value, String tag, int size) {
        TextView text = new TextView(context); text.setText(value); text.setTag(tag); text.setTextSize(size);
        text.setTextColor(0xFF1F272D); text.setPadding(0, dp(8), 0, dp(8)); return text;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break; context = next;
        }
        return null;
    }
}
