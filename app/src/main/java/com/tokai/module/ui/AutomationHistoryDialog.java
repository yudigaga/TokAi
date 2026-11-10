package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tokai.module.config.AutomationHistoryStore;
import com.tokai.module.config.AutomationHistoryStore.Entry;
import com.tokai.module.config.AutomationDiagnostics;
import com.tokai.module.ui.miuix.HostComposeContext;
import com.tokai.module.ui.miuix.MiuixAutomationHistory;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Read-only records independent of the model's in-memory conversation context. */
final class AutomationHistoryDialog {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "TokAi-history");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Context context;
    private final AutomationHistoryStore store;
    private final String kind;
    private final boolean miuix;
    private final Rows adapter;
    private final MiuixAutomationHistory composeRows;
    private final AlertDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private AlertDialog detail;
    private SubmissionRecoveryDialog recovery;
    private Future<?> work;
    private int generation;

    AutomationHistoryDialog(Context context, String kind) {
        this(context, kind, false);
    }

    AutomationHistoryDialog(Context context, String kind, boolean miuix) {
        this(context, kind, miuix, IO);
    }

    AutomationHistoryDialog(Context baseContext, String kind, boolean miuix, ExecutorService io) {
        Context context = ModuleTheme.themedContext(baseContext);
        this.context = context;
        this.kind = kind;
        if (io == null) throw new IllegalArgumentException("History executor is required");
        worker = io;
        Activity owner = ownerActivity(context);
        Context styledContext = context;
        boolean styled = false;
        if (miuix && owner != null && !owner.isFinishing() && !owner.isDestroyed()) {
            try {
                styledContext = HostComposeContext.create(owner);
                styled = true;
            } catch (RuntimeException unavailable) {
                styledContext = context;
            }
        }
        this.miuix = styled;
        store = new AutomationHistoryStore(context);
        View history;
        if (this.miuix) {
            adapter = null;
            composeRows = new MiuixAutomationHistory(styledContext, this::showDetail,
                    AutomationHistoryDialog::rowTitle);
            history = composeRows.getView();
        } else {
            composeRows = null;
            adapter = new Rows();
            ListView rows = new ListView(context);
            rows.setTag("automation.history");
            rows.setDividerHeight(dp(1));
            rows.setAdapter(adapter);
            history = rows;
        }
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, this.miuix);
        builder
                .setTitle(AutomationHistoryStore.COMMENT_REPLY.equals(kind) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_reply_history)
                        : AutomationHistoryStore.REPLY.equals(kind) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_reply_history) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_history))
                .setView(history).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_close), null).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_clear_history), null);
        if (!AutomationHistoryStore.COMMENT_REPLY.equals(kind)) builder.setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_submission_status), null);
        dialog = builder.create();
        if (owner != null) dialog.setOwnerActivity(owner);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> clear());
            if (!AutomationHistoryStore.COMMENT_REPLY.equals(kind)) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (recovery != null) { recovery.close(); recovery.dialog().dismiss(); }
                    recovery = new SubmissionRecoveryDialog(context, kind, this.miuix, worker);
                    recovery.dialog().show();
                });
            }
            refresh();
        });
        dialog.setOnDismissListener(ignored -> {
            invalidateWork();
            closeDetails();
        });
    }

    AlertDialog dialog() { return dialog; }

    private void refresh() {
        int ticket = nextGeneration();
        setState(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reading));
        work = worker.submit(() -> {
            List<Entry> result = read();
            main.post(() -> {
                if (!isCurrent(ticket)) return;
                work = null;
                setEntries(result);
            });
        });
    }

    private void clear() {
        int ticket = nextGeneration();
        setState(com.tokai.module.i18n.UiText.get(R.string.automation_ui_clearing));
        worker.execute(() -> {
            try { store.clear(kind); }
            catch (RuntimeException ignored) { }
            List<Entry> result = read();
            main.post(() -> {
                if (!isCurrent(ticket)) return;
                work = null;
                setEntries(result);
            });
        });
    }

    private List<Entry> read() {
        try { return store.read(kind); }
        catch (RuntimeException failure) { return Collections.emptyList(); }
    }

    private void setState(String value) {
        if (composeRows != null) composeRows.setState(value);
        else adapter.setState(value);
    }

    private void setEntries(List<Entry> value) {
        if (composeRows != null) composeRows.setEntries(value);
        else adapter.setEntries(value);
    }

    private int nextGeneration() {
        if (work != null) work.cancel(true);
        work = null;
        return ++generation;
    }

    private boolean isCurrent(int ticket) {
        return ticket == generation && dialog.isShowing();
    }

    private void invalidateWork() {
        generation++;
        if (work != null) work.cancel(true);
        work = null;
    }

    private void showDetail(Entry entry) {
        if (detail != null) detail.dismiss();
        TextView text = label(com.tokai.module.i18n.UiText.get(R.string.automation_ui_history_header, entry.targetName, entry.targetId, status(entry.status), entry.modelName, DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, com.tokai.module.i18n.ModuleLocale.locale()).format(new Date(entry.createdAt)))
                + (AutomationHistoryStore.COMMENT_REPLY.equals(entry.kind) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_history_comment_source) : "")
                + (isComment(entry.kind) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_history_delivery, deliveryMode(entry), imageRequest(entry)) : "")
                + "\n" + AutomationDiagnostics.displayDetails(entry)
                + com.tokai.module.i18n.UiText.get(R.string.automation_ui_history_content, entry.input, entry.output));
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(text);
        AlertDialog next = new MiuixDialogBuilder(context, miuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_history_details)).setView(scroll)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_close), null).create();
        if (dialog.getOwnerActivity() != null) next.setOwnerActivity(dialog.getOwnerActivity());
        next.setOnDismissListener(ignored -> { if (detail == next) detail = null; });
        detail = next;
        next.show();
    }

    /** Also use as the parent's dismiss callback when its owner replaces the listener. */
    void closeDetails() {
        invalidateWork();
        if (recovery != null) { recovery.close(); recovery.dialog().dismiss(); recovery = null; }
        AlertDialog child = detail;
        detail = null;
        if (child != null) child.dismiss();
    }

    static String status(String status) {
        switch (status) {
            case "sent": return com.tokai.module.i18n.UiText.get(R.string.automation_ui_sent);
            case "submitted": return com.tokai.module.i18n.UiText.get(R.string.automation_ui_submitted);
            case "failed": return com.tokai.module.i18n.UiText.get(R.string.automation_ui_failed);
            case "cancelled": return com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancelled);
            case "sending": return com.tokai.module.i18n.UiText.get(R.string.automation_ui_unconfirmed);
            case "unknown": return com.tokai.module.i18n.UiText.get(R.string.automation_ui_unconfirmed);
            default: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_submitted);
        }
    }

    private static String rowTitle(Entry entry) {
        String value = entry.targetName + " · " + status(entry.status)
                + (isComment(entry.kind) ? " · " + deliveryMode(entry) : "");
        return AutomationHistoryStore.COMMENT_REPLY.equals(entry.kind) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_reply_row, value) : value;
    }

    private static boolean isComment(String kind) {
        return AutomationHistoryStore.COMMENT.equals(kind) || AutomationHistoryStore.COMMENT_REPLY.equals(kind);
    }

    private static String deliveryMode(Entry entry) {
        switch (entry.deliveryMode) {
            case AutomationHistoryStore.DELIVERY_TEXT: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_text_delivery);
            case AutomationHistoryStore.DELIVERY_VOICE: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_voice_delivery);
            default: return com.tokai.module.i18n.UiText.get(R.string.automation_ui_not_recorded);
        }
    }

    private static String imageRequest(Entry entry) {
        if (entry.requestImageCount < 0) return com.tokai.module.i18n.UiText.get(R.string.automation_ui_images_not_recorded);
        if (!entry.modelRequestSent) return com.tokai.module.i18n.UiText.get(R.string.automation_ui_images_unconfirmed);
        return entry.requestImageCount == 0 ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_images_none)
                : com.tokai.module.i18n.UiText.get(R.string.automation_ui_images_attached, entry.requestImageCount);
    }

    private TextView label(String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(31, 39, 45));
        return view;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static Activity ownerActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) return null;
            current = next;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private final class Rows extends BaseAdapter {
        private List<Entry> entries = Collections.emptyList();
        private String state;

        void setState(String value) {
            entries = Collections.emptyList();
            state = value;
            notifyDataSetChanged();
        }

        void setEntries(List<Entry> value) {
            entries = value == null ? Collections.emptyList() : new ArrayList<>(value);
            state = null;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return entries.isEmpty() ? 1 : entries.size(); }
        @Override public Entry getItem(int position) { return entries.isEmpty() ? null : entries.get(position); }
        @Override public long getItemId(int position) { return getItem(position) == null ? -1 : position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Entry entry = getItem(position);
            if (entry == null) {
                TextView empty = recycled instanceof TextView ? (TextView) recycled : label("");
                empty.setTag("automation.history.empty");
                empty.setText(state == null ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_no_records) : state);
                empty.setOnClickListener(null);
                empty.setPadding(dp(16), dp(20), dp(16), dp(20));
                return empty;
            }
            String title = rowTitle(entry);
            String date = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, com.tokai.module.i18n.ModuleLocale.locale()).format(new Date(entry.createdAt));
            TextView row = recycled instanceof TextView ? (TextView) recycled : label("");
            row.setTag("automation.history." + entry.id);
            row.setText(title + "\n" + date + "\n" + entry.output);
            row.setMaxLines(5);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setOnClickListener(ignored -> showDetail(entry));
            return row;
        }
    }
}
