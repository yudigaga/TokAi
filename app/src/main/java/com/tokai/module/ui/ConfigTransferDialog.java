package com.tokai.module.ui;

import com.tokai.module.R;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tokai.module.config.ConfigTransfer;
import com.tokai.module.config.ConfigBackup;
import com.tokai.module.config.BackupLimitException;
import com.tokai.module.config.BackupValidationException;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Files are read before preview; only an explicit confirmation can apply a validated backup. */
final class ConfigTransferDialog implements AutoCloseable {
    interface Files {
        InputStream read(Uri uri) throws Exception;
        OutputStream write(Uri uri) throws Exception;
    }
    interface Picker {
        AutoCloseable launch(Activity activity, boolean export, String name, Consumer<Uri> selected);
    }
    private static ThreadPoolExecutor executor(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        });
    }
    private static final ThreadPoolExecutor WORKER = executor("TokAi-config-files", 8);
    private static final ThreadPoolExecutor CLOSER = executor("TokAi-config-close", 8);
    private final SettingsStore store;
    private final Context application;
    private final Runnable changed;
    private final Files files;
    private final Picker picker;
    private final Executor worker;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AlertDialog dialog;
    private final TextView status;
    private final TextView previewText;
    private final CheckBox secrets;
    private final CheckBox includeAnalytics;
    private final CheckBox restoreAnalytics;
    private final CheckBox replace;
    private final Button exportButton;
    private final Button importButton;
    private final Button confirmButton;
    private final Button cancelButton;
    private volatile boolean closed;
    private AutoCloseable selection;
    private long selectionGeneration;
    private Operation operation;
    private ConfigBackup.Preview preview;
    private String sourceJson;
    private String sourceFileSummary;

    ConfigTransferDialog(Context context, SettingsStore store, boolean miuix, Runnable changed) {
        this(context, store, miuix, changed, new Files() {
            @Override public InputStream read(Uri uri) throws Exception { return context.getContentResolver().openInputStream(uri); }
            @Override public OutputStream write(Uri uri) throws Exception { return context.getContentResolver().openOutputStream(uri, "wt"); }
        }, HostConfigFilePicker::launch, WORKER);
    }

    ConfigTransferDialog(Context baseContext, SettingsStore store, boolean miuix, Runnable changed,
                         Files files, Picker picker, Executor worker) {
        Context context = ModuleTheme.themedContext(baseContext);
        Context app = context.getApplicationContext();
        this.application = app == null ? context : app;
        this.store = store; this.changed = changed; this.files = files; this.picker = picker; this.worker = worker;
        this.activity = activity(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        body.setPadding(padding, 0, padding, padding);
        TextView note = text(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_note), "config.backup.note");
        body.addView(note, matchWrap());
        secrets = miuix ? MiuixFormControls.checkbox(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_include_api_keys), false, "导出包含 API 密钥") : new CheckBox(context);
        secrets.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_include_api_keys)); secrets.setChecked(false); secrets.setTag("config.backup.secrets");
        body.addView(MiuixFormControls.viewFor(secrets), matchWrap());
        TextView secretNote = text(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_secrets_hint), "config.backup.secretNote");
        body.addView(secretNote, matchWrap());
        includeAnalytics = miuix ? MiuixFormControls.checkbox(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_include_analytics), false, "包含本地分析数据") : new CheckBox(context);
        includeAnalytics.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_include_analytics)); includeAnalytics.setChecked(false);
        includeAnalytics.setTag("config.backup.includeAnalytics");
        body.addView(MiuixFormControls.viewFor(includeAnalytics), matchWrap());
        body.addView(text(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_analytics_hint), "config.backup.analyticsNote"), matchWrap());
        exportButton = button(context, miuix, body, com.tokai.module.i18n.UiText.get(R.string.automation_ui_export_config), "config.backup.export", () -> choose(true));
        replace = miuix ? MiuixFormControls.checkbox(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_replace_config), false, "覆盖当前配置") : new CheckBox(context);
        replace.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_replace_config)); replace.setChecked(false); replace.setTag("config.backup.replace");
        body.addView(MiuixFormControls.viewFor(replace), matchWrap());
        body.addView(text(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_replace_hint), "config.backup.modeNote"), matchWrap());
        replace.setOnCheckedChangeListener((button, checked) -> refreshMode());
        importButton = button(context, miuix, body, com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_config_file), "config.backup.import", () -> choose(false));
        status = text(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_transfer_action), "config.backup.status");
        body.addView(status, matchWrap());
        previewText = text(context, "", "config.backup.preview");
        previewText.setTextIsSelectable(true);
        body.addView(previewText, matchWrap());
        restoreAnalytics = miuix ? MiuixFormControls.checkbox(context, com.tokai.module.i18n.UiText.get(R.string.automation_ui_restore_analytics), false, "恢复本地分析数据") : new CheckBox(context);
        restoreAnalytics.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_restore_analytics)); restoreAnalytics.setChecked(false);
        restoreAnalytics.setTag("config.backup.restoreAnalytics");
        restoreAnalytics.setVisibility(View.GONE);
        body.addView(MiuixFormControls.viewFor(restoreAnalytics), matchWrap());
        restoreAnalytics.setOnCheckedChangeListener((button, checked) -> updatePreview());
        confirmButton = button(context, miuix, body, com.tokai.module.i18n.UiText.get(R.string.automation_ui_confirm_import), "config.backup.confirm", this::confirm);
        confirmButton.setEnabled(false);
        cancelButton = button(context, miuix, body, com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel_operation), "config.backup.cancel", () -> cancel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancelled_unchanged)));
        cancelButton.setEnabled(false);
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("config.backup.scroll");
        scroll.setFillViewport(false);
        scroll.addView(body);
        dialog = new MiuixDialogBuilder(context, miuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_config_backup))
                .setView(scroll).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_close), null).create();
        dialog.setOnDismissListener(ignored -> close());
    }

    AlertDialog dialog() { return dialog; }

    private void choose(boolean export) {
        if (!active() || operation != null || selection != null) return;
        preview = null; sourceJson = null; sourceFileSummary = null; previewText.setText(""); confirmButton.setEnabled(false);
        resetAnalyticsRestore();
        boolean includeSecrets = secrets.isChecked();
        boolean includeLocalAnalytics = includeAnalytics.isChecked();
        ConfigTransfer.Mode mode = mode();
        long selectedGeneration = ++selectionGeneration;
        busy(true);
        status.setText(export ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_backup_destination) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_choose_backup_file));
        AutoCloseable pending = picker.launch(activity, export, "TokAi-config-" + System.currentTimeMillis() + ".json", uri -> {
            if (!active() || selectedGeneration != selectionGeneration) return;
            selectionGeneration++; // One selection can deliver at most one result.
            selection = null;
            if (uri == null) { busy(false); status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_picker_cancelled)); return; }
            if (export) run(op -> {
                op.stage = Stage.EXPORT_DATA; op.trace.stage("export_data", "");
                byte[] data = ConfigBackup.export(application, store, includeSecrets, includeLocalAnalytics).getBytes(StandardCharsets.UTF_8);
                if (data.length > ConfigBackup.MAX_BYTES) throw new BackupLimitException(BackupLimitException.Section.FILE);
                op.check();
                op.stage = Stage.WRITE_FILE; op.trace.stage("write_file", "");
                try (OutputStream stream = op.attach(files.write(uri))) {
                    for (int offset = 0; offset < data.length; offset += 8192) {
                        op.check(); stream.write(data, offset, Math.min(8192, data.length - offset));
                    }
                    op.check(); stream.flush();
                } finally { op.detach(); }
                op.trace.stage("output_closed", "bytes=" + data.length + ";stream_closed=true;readback_verified=false");
                return null;
            }, com.tokai.module.i18n.UiText.get(R.string.automation_ui_exporting_config), includeLocalAnalytics ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_config_analytics_exported) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_config_exported));
            else run(op -> {
                op.stage = Stage.READ_FILE; op.trace.stage("read_file", "");
                byte[] data;
                try (InputStream stream = op.attach(files.read(uri))) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        op.check();
                        if (count > ConfigBackup.MAX_BYTES - output.size()) throw new BackupLimitException(BackupLimitException.Section.FILE);
                        output.write(buffer, 0, count);
                    }
                    data = output.toByteArray();
                } finally { op.detach(); }
                op.check();
                op.fileSummary = logRead(data);
                op.trace.stage("file_read", "bytes=" + data.length + ";file_contents_omitted=true");
                op.stage = Stage.DECODE_FILE; op.trace.stage("decode_file", "");
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
                op.importJson = json;
                op.stage = Stage.VALIDATE_DATA; op.trace.stage("validate_data", "");
                return ConfigBackup.inspect(store, json, mode);
            }, com.tokai.module.i18n.UiText.get(R.string.automation_ui_reading_validating), com.tokai.module.i18n.UiText.get(R.string.automation_ui_preview_confirmation_hint));
        });
        // Some adapters can deliver synchronously before launch returns its handle.
        if (active() && selectedGeneration == selectionGeneration) selection = pending;
        else closeSelection(pending);
        if (selectedGeneration == selectionGeneration && selection == null && operation == null) {
            selectionGeneration++;
            busy(false); status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_picker_unavailable));
        }
    }

    private ConfigTransfer.Mode mode() { return replace.isChecked() ? ConfigTransfer.Mode.REPLACE : ConfigTransfer.Mode.MERGE; }
    private void refreshMode() {
        if (!active() || operation != null || selection != null || sourceJson == null) return;
        String original = sourceJson;
        String originalFileSummary = sourceFileSummary;
        ConfigTransfer.Mode selected = mode();
        preview = null; previewText.setText("");
        run(op -> {
            op.check(); op.importJson = original; op.fileSummary = originalFileSummary;
            op.stage = Stage.VALIDATE_DATA; op.trace.stage("validate_data", "");
            return ConfigBackup.inspect(store, original, selected);
        }, com.tokai.module.i18n.UiText.get(R.string.automation_ui_checking_import_mode), com.tokai.module.i18n.UiText.get(R.string.automation_ui_preview_confirmation_hint));
    }

    private void confirm() {
        if (!active() || operation != null || preview == null) return;
        ConfigBackup.Preview accepted = preview;
        boolean restoreLocalAnalytics = accepted.includesAnalytics && restoreAnalytics.isChecked();
        preview = null;
        run(op -> {
            op.check();
            // This is the commit boundary. A confirmed atomic settings write may finish after close.
            op.applying = true;
            op.check();
            op.stage = Stage.APPLY_DATA; op.trace.stage("apply_data", "");
            ConfigBackup.apply(application, store, accepted, restoreLocalAnalytics);
            op.applied = true;
            return null;
        }, com.tokai.module.i18n.UiText.get(R.string.automation_ui_applying_backup), restoreLocalAnalytics
                ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_imported_analytics_replaced)
                : com.tokai.module.i18n.UiText.get(R.string.automation_ui_imported_analytics_unchanged));
        cancelButton.setEnabled(false);
    }

    private enum Stage { EXPORT_DATA, WRITE_FILE, READ_FILE, DECODE_FILE, VALIDATE_DATA, APPLY_DATA }
    private interface Work { ConfigBackup.Preview execute(Operation op) throws Exception; }
    private void run(Work work, String working, String success) {
        Operation op = new Operation();
        operation = op;
        busy(true); status.setText(working);
        op.future = new FutureTask<>(() -> {
            ConfigBackup.Preview result = null;
            boolean succeeded = false;
            try { op.check(); result = work.execute(op); op.check(); succeeded = true; }
            catch (Exception | LinkageError failure) {
                op.trace.error(op.stage.name().toLowerCase(java.util.Locale.ROOT), failure);
                op.trace.finish(op.cancelled || failure instanceof CancellationException ? TokAiLog.Outcome.CANCELLED : TokAiLog.Outcome.FAILED,
                        "backup_action_failed", "last_stage=" + op.stage + ";applying=" + op.applying + ";applied=" + op.applied);
                op.failureMessage = failureMessage(op.stage, failure);
                String validation = validationDetails(failure);
                if (!validation.isEmpty()) op.failureMessage = com.tokai.module.i18n.UiText.get(R.string.automation_ui_validation_details, op.failureMessage, validation);
                if (!op.cancelled && !(failure instanceof CancellationException)) {
                    // Provider messages and stack traces may contain document contents or credentials.
                    TokAiLog.legacy(5, "TokAi", "ConfigTransfer failed: stage="
                            + op.stage.name().toLowerCase(java.util.Locale.ROOT)
                            + "; error=" + failure.getClass().getSimpleName()
                            + (validation.isEmpty() ? "" : "; validation=" + validation), null);
                }
            }
            ConfigBackup.Preview ready = result;
            boolean done = succeeded;
            if (done) op.trace.finish(TokAiLog.Outcome.SUCCESS, op.applied ? "backup_applied" : ready != null ? "preview_validated" : "backup_output_closed",
                    "stage=" + op.stage + ";applied=" + op.applied + ";preview_ready=" + (ready != null) + ";ui_display=not_yet_confirmed");
            main.post(() -> {
                if (!active() || operation != op || op.cancelled) return;
                main.removeCallbacks(op.timeout);
                operation = null;
                busy(false);
                String message = done ? success : op.applying
                        ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_import_incomplete)
                        : op.failureMessage;
                status.setText(message + (op.fileSummary == null ? "" : "\n" + op.fileSummary));
                if (done && ready != null) {
                    sourceJson = op.importJson; sourceFileSummary = op.fileSummary; preview = ready;
                    restoreAnalytics.setVisibility(ready.includesAnalytics ? View.VISIBLE : View.GONE);
                    updatePreview();
                    cancelButton.setEnabled(true);
                }
                if (op.applied) {
                    try {
                        Toast.makeText(application, com.tokai.module.i18n.UiText.get(R.string.automation_ui_import_success), Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException unavailable) {
                        TokAiLog.legacy(5, "TokAi", "ConfigTransfer import_success_toast_unavailable", null);
                    }
                    sourceJson = null; sourceFileSummary = null;
                    resetAnalyticsRestore();
                    if (changed != null) changed.run();
                }
            });
            return null;
        });
        try { worker.execute(op.trace.wrap(op.future)); main.postDelayed(op.timeout, 60_000); }
        catch (java.util.concurrent.RejectedExecutionException unavailable) {
            op.trace.finish(TokAiLog.Outcome.FAILED, "backup_queue_rejected", "work_started=false");
            operation = null; busy(false); status.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_queue_busy));
        }
    }

    private static String logRead(byte[] data) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            char[] digits = "0123456789ABCDEF".toCharArray();
            char[] hex = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                hex[index * 2] = digits[(digest[index] >>> 4) & 15];
                hex[index * 2 + 1] = digits[digest[index] & 15];
            }
            String hash = new String(hex);
            TokAiLog.legacy(4, "TokAi", "ConfigTransfer read: bytes=" + data.length + "; sha256=" + hash, null);
            return com.tokai.module.i18n.UiText.get(R.string.automation_ui_file_digest, data.length, hash.substring(0, 12));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            // Diagnostics must not make otherwise readable data fail to import.
            TokAiLog.legacy(4, "TokAi", "ConfigTransfer read: bytes=" + data.length + "; sha256=unavailable", null);
            return com.tokai.module.i18n.UiText.get(R.string.automation_ui_file_digest_unavailable, data.length);
        }
    }

    private static String validationDetails(Throwable failure) {
        if (!(failure instanceof BackupValidationException)) return "";
        BackupValidationException validation = (BackupValidationException) failure;
        return validation.diagnosticCode() + (validation.field.isEmpty() ? "" : "; field=" + validation.field)
                + "; failureKind=" + validation.failureKind.name();
    }

    private static String failureMessage(Stage stage, Throwable failure) {
        if (failure instanceof BackupLimitException) {
            switch (((BackupLimitException) failure).section) {
                case CONFIG: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_limit_config);
                case ANALYTICS: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_limit_analytics);
                default: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_limit_file);
            }
        }
        if (failure instanceof LinkageError || failure instanceof BackupValidationException
                && ((BackupValidationException) failure).failureKind == BackupValidationException.FailureKind.LINKAGE)
            return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_compatibility_error);
        switch (stage) {
            case READ_FILE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_read_error);
            case WRITE_FILE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_write_error);
            case DECODE_FILE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_encoding_error);
            case VALIDATE_DATA: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_validation_error);
            default: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_generation_error);
        }
    }

    static String describe(ConfigTransfer.Preview value) {
        StringBuilder text = new StringBuilder(com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_counts, value.formatVersion, value.moduleVersion, value.includesSecrets ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_has_secrets) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_redacted), value.entryCount, value.changedCount, value.removedCount, value.redactedCount, value.missingSecretsCount))
                .append(value.mode == ConfigTransfer.Mode.REPLACE
                        ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_import_replace_description)
                        : com.tokai.module.i18n.UiText.get(R.string.automation_ui_import_merge_description))
                .append(com.tokai.module.i18n.UiText.get(R.string.automation_ui_import_immediate_hint))
                .append(com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_credentials_hint))
                .append(com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_paths_hint))
                .append(com.tokai.module.i18n.UiText.get(R.string.automation_ui_keys_not_shown));
        if (!value.changedKeys.isEmpty()) {
            text.append(com.tokai.module.i18n.UiText.get(R.string.automation_ui_changed_settings_heading));
            for (String key : value.changedKeys) text.append('\n').append(key);
        }
        return text.toString();
    }

    private boolean active() { return !closed && dialog.isShowing(); }
    private boolean hasChanges() {
        return preview != null && (preview.config.changedCount > 0 || preview.config.removedCount > 0
                || preview.includesAnalytics && restoreAnalytics.isChecked());
    }
    private void resetAnalyticsRestore() {
        restoreAnalytics.setChecked(false);
        restoreAnalytics.setVisibility(View.GONE);
    }
    private void updatePreview() {
        if (preview == null) { confirmButton.setEnabled(false); return; }
        String value = describe(preview.config);
        if (preview.includesAnalytics) value = com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_analytics_summary, value, MetadataUiText.displayAnalyticsSummary(preview.analyticsSummaryData, preview.analyticsSummary), restoreAnalytics.isChecked() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_analytics_replace_hint) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_analytics_keep_hint));
        else value += com.tokai.module.i18n.UiText.get(R.string.automation_ui_backup_no_analytics);
        previewText.setText(value);
        restoreAnalytics.setEnabled(operation == null && preview.includesAnalytics);
        confirmButton.setEnabled(operation == null && hasChanges());
    }
    private void busy(boolean value) {
        exportButton.setEnabled(!value); importButton.setEnabled(!value); secrets.setEnabled(!value);
        includeAnalytics.setEnabled(!value);
        replace.setEnabled(!value);
        restoreAnalytics.setEnabled(!value && preview != null && preview.includesAnalytics);
        confirmButton.setEnabled(!value && hasChanges());
        cancelButton.setEnabled(value);
    }
    private void cancel(String message) {
        if (!active() || operation != null && operation.applying) return;
        stop(); preview = null; sourceJson = null; sourceFileSummary = null; resetAnalyticsRestore(); previewText.setText(""); busy(false); status.setText(message);
    }
    private void stop() {
        selectionGeneration++; // A cancelled chooser cannot restart an operation through a late callback.
        AutoCloseable pending = selection; selection = null;
        closeSelection(pending);
        Operation old = operation; operation = null;
        if (old != null) main.removeCallbacks(old.timeout);
        if (old != null && !old.applying) {
            old.trace.finish(TokAiLog.Outcome.CANCELLED, "backup_cancelled_or_closed", "applying=false");
            old.cancelled = true;
            if (old.future != null) old.future.cancel(true);
            Closeable stream = old.stream;
            if (stream != null) try { CLOSER.execute(() -> closeQuietly(stream)); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
    }
    @Override public void close() { closed = true; stop(); preview = null; sourceJson = null; sourceFileSummary = null; }

    private final class Operation {
        final TokAiLog.Operation trace = TokAiLog.begin("config_transfer", "backup_action");
        volatile boolean cancelled;
        volatile boolean applying;
        volatile boolean applied;
        volatile Closeable stream;
        Stage stage = Stage.EXPORT_DATA;
        String failureMessage;
        String importJson;
        String fileSummary;
        FutureTask<Void> future;
        final Runnable timeout = () -> { if (operation == this && !applying) {
            trace.finish(TokAiLog.Outcome.TIMED_OUT, "backup_local_timeout", "applying=false;partial_file_possible=true");
            cancel(com.tokai.module.i18n.UiText.get(R.string.automation_ui_operation_timeout));
        } };
        void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException(); }
        <T extends Closeable> T attach(T value) {
            if (value == null) throw new IllegalStateException();
            stream = value;
            try { check(); return value; }
            catch (RuntimeException cancelled) { closeQuietly(value); stream = null; throw cancelled; }
        }
        void detach() { stream = null; }
    }

    private static void closeQuietly(Closeable value) { try { value.close(); } catch (Exception ignored) { } }
    private static void closeSelection(AutoCloseable value) {
        if (value != null) try { value.close(); } catch (Exception ignored) { }
    }
    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    private static TextView text(Context context, String value, String tag) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(14); view.setTag(tag);
        view.setPadding(0, 8, 0, 8); return view;
    }
    private static Button button(Context context, boolean miuix, LinearLayout parent, String label, String tag, Runnable action) {
        Button view = miuix ? MiuixFormControls.button(context) : new Button(context);
        view.setText(label); view.setTag(tag); view.setOnClickListener(ignored -> action.run());
        parent.addView(MiuixFormControls.viewFor(view), matchWrap()); return view;
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
