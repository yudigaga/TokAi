package com.tokai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import com.tokai.module.config.AutomationHistoryStore;
import com.tokai.module.config.SubmissionLedger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SubmissionRecoveryDialogTest {
    private Activity activity;
    private ExecutorService worker;
    private SubmissionRecoveryDialog page;
    private SharedPreferences preferences;
    private SubmissionLedger ledger;
    private final AtomicReference<String> account = new AtomicReference<>("1");
    private final String id = SubmissionLedger.identity("comment:1:video");

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        worker = Executors.newSingleThreadExecutor();
        preferences = activity.getSharedPreferences(SubmissionLedger.COMMENT_FILE, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        activity.getSharedPreferences(SubmissionLedger.REPLY_FILE, Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences(SubmissionLedger.VOICE_FILE, Context.MODE_PRIVATE).edit().clear().commit();
        ledger = new SubmissionLedger(preferences, SubmissionLedger.DEFAULT_CAPACITY);
        new AutomationHistoryStore(activity).clear(AutomationHistoryStore.COMMENT);
        new AutomationHistoryStore(activity).clear(AutomationHistoryStore.REPLY);
    }
    @After public void tearDown() {
        if (page != null) { page.close(); page.dialog().dismiss(); }
        worker.shutdownNow(); activity.finish();
    }

    @Test public void openingOnlyReadsAndRequiresTwoExplicitConfirmationsToRelease() throws Exception {
        previousReservation();
        SharedPreferences legacy = activity.getSharedPreferences("tokai_comment_state", 0);
        legacy.edit().clear().putBoolean("comment:1:video", true).putBoolean("comment:2:video", true).commit();
        open();
        assertTrue(ledger.contains(id));
        ListView list = page.dialog().getWindow().getDecorView().findViewWithTag("submission.records");
        assertEquals(1, list.getAdapter().getCount());
        list.getAdapter().getView(0, null, list).performClick();
        AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
        choice.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(ledger.contains(id));
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain();
        assertFalse(ledger.contains(id));
        assertFalse(legacy.contains("comment:1:video"));
        assertTrue(legacy.contains("comment:2:video"));
        assertTrue(text(page.dialog().getWindow().getDecorView()).contains("未补发"));
    }

    @Test public void accountChangeAfterPreviewCannotModifyTheOldAccount() throws Exception {
        previousReservation(); open();
        ListView list = page.dialog().getWindow().getDecorView().findViewWithTag("submission.records");
        list.getAdapter().getView(0, null, list).performClick();
        AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
        account.set("2"); choice.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); drain();
        assertTrue(ledger.contains(id));
    }

    @Test public void activeProcessReservationHasNoRecoveryAction() throws Exception {
        ledger.reserve(id, "one", System.currentTimeMillis() - 500_000, SubmissionLedger.accountScope("1"));
        open();
        ListView list = page.dialog().getWindow().getDecorView().findViewWithTag("submission.records");
        View row = list.getAdapter().getView(0, null, list);
        assertTrue(text(row).contains("保留防重")); row.performClick();
        assertSame(page.dialog(), ShadowDialog.getLatestDialog()); assertTrue(ledger.contains(id));
    }

    @Test public void replyPageReconcilesOnlySelectedVoiceLedgerWithoutCreatingAnotherSubmission() throws Exception {
        SharedPreferences replies = activity.getSharedPreferences(SubmissionLedger.REPLY_FILE, Context.MODE_PRIVATE);
        SharedPreferences voices = activity.getSharedPreferences(SubmissionLedger.VOICE_FILE, Context.MODE_PRIVATE);
        // Identical hashes deliberately prove that identity alone cannot select the owning ledger.
        previousReservation(replies, id, "reply");
        previousReservation(voices, id, "voice");
        Map<String, ?> replyBefore = replies.getAll(), voiceBefore = voices.getAll();
        open(AutomationHistoryStore.REPLY);
        assertEquals(replyBefore, replies.getAll()); assertEquals(voiceBefore, voices.getAll());
        assertTrue(text(page.dialog().getWindow().getDecorView()).contains("全部账号已用 2/2000，待核对 2"));
        manualVoiceRow().performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(replyBefore, replies.getAll()); assertEquals(voiceBefore, voices.getAll());
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain();
        assertEquals(replyBefore, replies.getAll());
        assertTrue(voices.getAll().isEmpty());
        assertTrue(text(page.dialog().getWindow().getDecorView()).contains("未补发"));
        ListView list = page.dialog().getWindow().getDecorView().findViewWithTag("submission.records");
        assertEquals(1, list.getAdapter().getCount());
    }

    @Test public void voiceRecoveryCannotReleaseAfterAccountSwitchOrNewTerminalSnapshot() throws Exception {
        SharedPreferences voices = activity.getSharedPreferences(SubmissionLedger.VOICE_FILE, Context.MODE_PRIVATE);
        previousReservation(voices, id, "voice");
        Map<String, ?> before = voices.getAll();
        open(AutomationHistoryStore.REPLY);
        manualVoiceRow().performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        account.set("2");
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain();
        assertEquals(before, voices.getAll());

        account.set("1"); page.dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); drain();
        manualVoiceRow().performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        // A late host success replaces the exact snapshot being confirmed. It must retain its guard.
        SubmissionLedger voice = new SubmissionLedger(voices, SubmissionLedger.DEFAULT_CAPACITY);
        assertTrue(voice.complete(id, "voice", "sent", System.currentTimeMillis()));
        Map<String, ?> terminal = voices.getAll();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain();
        assertEquals(terminal, voices.getAll());
        assertEquals("sent", new JSONObject(voices.getString("send." + id, "")).getString("status"));
        assertTrue(activity.getSharedPreferences(SubmissionLedger.REPLY_FILE, Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test public void legacyAttributionDoesNotAcceptOtherAccountsOrCommentReplies() {
        AutomationHistoryStore.Entry own = history("comment:1:video:uuid", "comment", "video");
        AutomationHistoryStore.Entry other = history("comment:2:video:uuid", "comment", "video");
        AutomationHistoryStore.Entry unrelated = history("comment:1:video:uuid", "comment_reply", "video");
        Map<String, String> matches = SubmissionRecoveryDialog.historyNames(Arrays.asList(own, other, unrelated), "comment", "1");
        assertEquals(Collections.singleton(id), matches.keySet());
        assertTrue(SubmissionRecoveryDialog.historyNames(Collections.singletonList(own), "comment", "2").isEmpty());
    }

    @Test public void aPageCreatedInDarkModeCanReturnToLightWithoutKeepingWhiteText() throws Exception {
        org.robolectric.RuntimeEnvironment.setQualifiers("+night");
        open();
        TextView summary = page.dialog().getWindow().getDecorView().findViewWithTag("submission.summary");
        ModuleTheme.bind(summary);
        assertEquals(0xFFF2F2F2, summary.getCurrentTextColor());
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight");
        ModuleTheme.bind(summary);
        assertEquals(android.graphics.Color.rgb(31, 39, 45), summary.getCurrentTextColor());
    }

    private AutomationHistoryStore.Entry history(String identity, String kind, String target) {
        return new AutomationHistoryStore.Entry(identity, kind, target, "作者", "", "", "", "unknown", 1, 1);
    }
    private void previousReservation() throws Exception {
        previousReservation(preferences, id, "one");
    }
    private void previousReservation(SharedPreferences store, String identity, String attempt) throws Exception {
        SubmissionLedger owner = new SubmissionLedger(store, SubmissionLedger.DEFAULT_CAPACITY);
        long previous = System.currentTimeMillis() - 500_000;
        assertEquals(SubmissionLedger.ReserveResult.RESERVED,
                owner.reserve(identity, attempt, previous, SubmissionLedger.accountScope("1")));
        assertTrue(owner.complete(identity, attempt, "unknown", previous));
        JSONObject value = new JSONObject(store.getString("send." + identity, ""));
        assertTrue(store.edit().putString("send." + identity, value.put("session", "previous-process").toString()).commit());
    }
    private View manualVoiceRow() {
        ListView list = page.dialog().getWindow().getDecorView().findViewWithTag("submission.records");
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            View row = list.getAdapter().getView(i, null, list);
            if (text(row).contains("手动语音")) return row;
        }
        throw new AssertionError("Missing manual voice record");
    }
    private void open() throws Exception {
        open(AutomationHistoryStore.COMMENT);
    }
    private void open(String kind) throws Exception {
        page = new SubmissionRecoveryDialog(activity, kind, false, worker, account::get);
        page.dialog().show(); drain();
    }
    private void drain() throws Exception {
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS); ShadowLooper.idleMainLooper();
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS); ShadowLooper.idleMainLooper();
    }
    private String text(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView) out.append(((TextView) view).getText());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            out.append(text(((ViewGroup) view).getChildAt(i)));
        return out.toString();
    }
}
