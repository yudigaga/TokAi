package com.tokai.module.ui;

import com.tokai.module.R;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.tokai.module.config.AutomationHistoryStore;
import com.tokai.module.config.SubmissionLedger;
import com.tokai.module.feature.NativeFollowLists;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Account-scoped human reconciliation; opening this page never sends or retries anything. */
final class SubmissionRecoveryDialog implements AutoCloseable {
    private final Context context;
    private final boolean miuix;
    private final String kind;
    private final SubmissionLedger ledger;
    private final SubmissionLedger voiceLedger;
    private final ExecutorService worker;
    private final Supplier<String> account;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView summary;
    private final Rows rows = new Rows();
    private final AlertDialog dialog;
    private AlertDialog confirmation;
    private Future<?> work;
    private int generation;
    private volatile boolean closed;
    private String loadedAccount = "";
    private Map<String, String> names = Collections.emptyMap();

    SubmissionRecoveryDialog(Context context, String kind, boolean miuix, ExecutorService worker) {
        this(context, kind, miuix, worker, NativeFollowLists::currentAccountUid);
    }

    SubmissionRecoveryDialog(Context context, String kind, boolean miuix, ExecutorService worker, Supplier<String> account) {
        if (!AutomationHistoryStore.COMMENT.equals(kind) && !AutomationHistoryStore.REPLY.equals(kind))
            throw new IllegalArgumentException("Unsupported submission kind");
        this.context = context; this.kind = kind; this.miuix = miuix; this.worker = worker; this.account = account;
        ledger = new SubmissionLedger(context.getSharedPreferences(AutomationHistoryStore.COMMENT.equals(kind)
                ? SubmissionLedger.COMMENT_FILE : SubmissionLedger.REPLY_FILE, Context.MODE_PRIVATE), SubmissionLedger.DEFAULT_CAPACITY);
        voiceLedger = AutomationHistoryStore.REPLY.equals(kind)
                ? new SubmissionLedger(context.getSharedPreferences(SubmissionLedger.VOICE_FILE, Context.MODE_PRIVATE), SubmissionLedger.DEFAULT_CAPACITY)
                : null;
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        summary = label(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reading_submission)); summary.setTag("submission.summary"); content.addView(summary);
        ListView list = new ListView(context); list.setTag("submission.records"); list.setAdapter(rows);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(330)));
        dialog = new MiuixDialogBuilder(context, miuix)
                .setTitle(AutomationHistoryStore.COMMENT.equals(kind) ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_comment_submission_status) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_private_submission_status))
                .setView(content).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_close), null).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_refresh), null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> refresh("")); refresh("");
        });
        dialog.setOnDismissListener(ignored -> close());
    }

    AlertDialog dialog() { return dialog; }

    private void refresh(String outcome) {
        if (closed) return;
        int ticket = ++generation;
        if (work != null) work.cancel(true);
        summary.setText(outcome.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_reading_submission) : outcome);
        rows.setEntries(Collections.emptyList());
        work = worker.submit(() -> {
            String uid;
            try { uid = account.get(); } catch (RuntimeException failure) { uid = ""; }
            if (uid == null || !uid.matches("[1-9][0-9]{0,19}")) {
                main.post(() -> { if (current(ticket)) summary.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_open_logged_in_host)); }); return;
            }
            String scope = SubmissionLedger.accountScope(uid);
            Map<String, String> labels = historyNames(new AutomationHistoryStore(context).read(kind), kind, uid);
            long now = System.currentTimeMillis();
            SubmissionLedger.Snapshot state = ledger.inspect(scope, labels.keySet(), now);
            SubmissionLedger.Snapshot voices = voiceLedger == null ? null
                    : voiceLedger.inspect(scope, Collections.emptySet(), now);
            List<RecoveryEntry> entries = new ArrayList<>();
            for (SubmissionLedger.Entry entry : state.entries) entries.add(new RecoveryEntry(ledger, entry, false));
            if (voices != null) for (SubmissionLedger.Entry entry : voices.entries)
                entries.add(new RecoveryEntry(voiceLedger, entry, true));
            entries.sort((a, b) -> Long.compare(b.entry.timestamp, a.entry.timestamp));
            String selectedAccount = uid;
            main.post(() -> {
                if (!current(ticket)) return;
                if (!selectedAccount.equals(currentAccount())) { summary.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_account_changed)); return; }
                loadedAccount = selectedAccount; names = labels;
                // The injected host context does not expose module string resources.
                summary.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_recovery_summary,
                        outcome.isEmpty() ? "" : outcome + "\n", selectedAccount,
                        state.total + (voices == null ? 0 : voices.total),
                        state.capacity + (voices == null ? 0 : voices.capacity),
                        state.uncertain + (voices == null ? 0 : voices.uncertain),
                        state.corrupt + (voices == null ? 0 : voices.corrupt),
                        state.unassigned + (voices == null ? 0 : voices.unassigned),
                        state.storageFailed || voices != null && voices.storageFailed
                                ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_storage_unavailable) : ""));
                rows.setEntries(entries);
            });
        });
    }

    static Map<String, String> historyNames(List<AutomationHistoryStore.Entry> history, String kind, String uid) {
        Map<String, String> labels = new HashMap<>();
        for (AutomationHistoryStore.Entry entry : history) {
            if (!kind.equals(entry.kind)) continue;
            String raw;
            if (AutomationHistoryStore.COMMENT.equals(kind)) {
                raw = "comment:" + uid + ":" + entry.targetId;
                if (entry.targetId.isEmpty() || !entry.id.startsWith(raw + ":")) continue;
            } else {
                if (entry.targetId.isEmpty() || !entry.id.startsWith(uid + ":" + entry.targetId + ":")) continue;
                raw = entry.id;
            }
            labels.put(SubmissionLedger.identity(raw), entry.targetName);
        }
        return labels;
    }

    private void choose(RecoveryEntry item) {
        SubmissionLedger.Entry entry = item.entry;
        if (closed || !entry.recoverable || !loadedAccount.equals(currentAccount())) return;
        if (confirmation != null) confirmation.dismiss();
        AlertDialog choice = new MiuixDialogBuilder(context, miuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reconcile_title))
                .setMessage(com.tokai.module.i18n.UiText.get(R.string.automation_ui_reconcile_hint))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_verified_sent), (ignored, which) -> confirm(item, true))
                .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_verified_not_sent), (ignored, which) -> confirm(item, false)).create();
        confirmation = choice; choice.show();
    }

    private void confirm(RecoveryEntry item, boolean sent) {
        if (closed || !loadedAccount.equals(currentAccount())) return;
        AlertDialog next = new MiuixDialogBuilder(context, miuix).setTitle(sent ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_confirm_mark_sent) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_confirm_release_guard))
                .setMessage(sent ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_mark_sent_hint) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_release_guard_hint))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_confirm), (ignored, which) -> reconcile(item, sent)).create();
        confirmation = next; next.show();
    }

    private void reconcile(RecoveryEntry item, boolean sent) {
        if (closed) return;
        SubmissionLedger.Entry entry = item.entry;
        TokAiLog.Operation trace = TokAiLog.begin("history", "manual_reconcile");
        trace.stage("ledger_write", "user_declared_sent=" + sent + ";entry_ref=" + TokAiLog.reference(entry.identity));
        String uid = loadedAccount;
        int ticket = ++generation;
        summary.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_saving_reconciliation));
        work = worker.submit(trace.wrap(() -> {
            if (closed || !uid.equals(currentAccount())) { trace.finish(TokAiLog.Outcome.CANCELLED, "account_or_dialog_changed", ""); return; }
            boolean saved = item.ledger.reconcile(entry, SubmissionLedger.accountScope(uid), sent, System.currentTimeMillis(),
                    () -> !closed && uid.equals(currentAccount()) && (sent || releaseLegacyGuard(uid, entry.identity)));
            trace.finish(saved ? TokAiLog.Outcome.SUCCESS : TokAiLog.Outcome.UNCONFIRMED, "ledger_reconcile_result", "saved=" + saved + ";remote_delivery=user_asserted;resend=false");
            main.post(trace.wrap(() -> { if (current(ticket)) refresh(saved ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_reconciliation_saved) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_reconciliation_failed)); }));
        }));
    }

    private String currentAccount() {
        try { String uid = account.get(); return uid == null ? "" : uid; }
        catch (RuntimeException failure) { return ""; }
    }

    private boolean releaseLegacyGuard(String uid, String identity) {
        if (!AutomationHistoryStore.COMMENT.equals(kind)) return true;
        android.content.SharedPreferences legacy = context.getSharedPreferences("tokai_comment_state", Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor change = legacy.edit();
        boolean found = false;
        for (String key : legacy.getAll().keySet()) {
            if (key.startsWith("comment:" + uid + ":") && SubmissionLedger.identity(key).equals(identity)) {
                change.remove(key); found = true;
            }
        }
        return !found || change.commit();
    }

    private boolean current(int ticket) { return !closed && ticket == generation && dialog.isShowing(); }
    private TextView label(String value) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(14);
        text.setTextColor(android.graphics.Color.rgb(31, 39, 45));
        ModuleTheme.bind(text);
        text.setPadding(dp(16), dp(10), dp(16), dp(10)); return text;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    @Override public void close() {
        closed = true; generation++; if (work != null) work.cancel(true);
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
    }
    /** Each inspected snapshot stays bound to its ledger, even when two ledgers contain the same hash. */
    private static final class RecoveryEntry {
        final SubmissionLedger ledger;
        final SubmissionLedger.Entry entry;
        final boolean manualVoice;
        RecoveryEntry(SubmissionLedger ledger, SubmissionLedger.Entry entry, boolean manualVoice) {
            this.ledger = ledger; this.entry = entry; this.manualVoice = manualVoice;
        }
    }
    private final class Rows extends BaseAdapter {
        private List<RecoveryEntry> entries = Collections.emptyList();
        void setEntries(List<RecoveryEntry> entries) { this.entries = entries; notifyDataSetChanged(); }
        @Override public int getCount() { return entries.size(); }
        @Override public RecoveryEntry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            RecoveryEntry item = getItem(position);
            SubmissionLedger.Entry entry = item.entry;
            TextView row = recycled instanceof TextView ? (TextView) recycled : label("");
            row.setText(String.format(com.tokai.module.i18n.ModuleLocale.locale(), "%1$s · %2$s\n%3$s\n%4$s",
                    item.manualVoice ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_manual_voice_record, entry.identity.substring(0, 12))
                            : names.getOrDefault(entry.identity, com.tokai.module.i18n.UiText.get(R.string.automation_ui_record_id, entry.identity.substring(0, 12))),
                    AutomationHistoryDialog.status(entry.status), DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, com.tokai.module.i18n.ModuleLocale.locale()).format(new Date(entry.timestamp)),
                    entry.recoverable ? com.tokai.module.i18n.UiText.get(R.string.automation_ui_tap_reconcile) : com.tokai.module.i18n.UiText.get(R.string.automation_ui_keep_guard)));
            row.setOnClickListener(ignored -> choose(item)); return row;
        }
    }
}
