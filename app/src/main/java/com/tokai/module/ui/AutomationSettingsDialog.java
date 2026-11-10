package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.tokai.module.config.FeatureSettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;
import com.tokai.module.ui.miuix.MiuixSettingsViews;
import java.util.function.Consumer;

/** Owns interaction automation controls and preserves other settings on each edit. */
final class AutomationSettingsDialog implements AutoCloseable {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int LINE = Color.rgb(226, 231, 232);
    private final Context context;
    private final SettingsStore repository;
    private final boolean useMiuix;
    private final Runnable onChanged;
    private final AlertDialog dialog;
    private final Toggle[] controls = new Toggle[3];
    private boolean changing;
    private boolean closed;

    AutomationSettingsDialog(Context context, SettingsStore repository, boolean useMiuix) {
        this(context, repository, useMiuix, () -> { });
    }

    AutomationSettingsDialog(Context baseContext, SettingsStore repository, boolean useMiuix, Runnable onChanged) {
        Context context = ModuleTheme.themedContext(baseContext);
        this.context = context;
        this.repository = repository;
        this.useMiuix = useMiuix;
        this.onChanged = onChanged;
        FeatureSettings settings = repository.load();
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        controls[0] = addToggle(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_automation_master), com.tokai.module.i18n.UiText.get(R.string.automation_ui_automation_master_hint),
                settings.automationEnabled, true, value -> change(Field.MASTER, value), "自动化总开关");
        controls[1] = addToggle(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_auto_like), "",
                settings.autoLikeEnabled, settings.automationEnabled, value -> change(Field.LIKE, value), "自动点赞");
        controls[2] = addToggle(panel, com.tokai.module.i18n.UiText.get(R.string.automation_ui_auto_follow), "",
                settings.autoFollowEnabled, settings.automationEnabled, value -> change(Field.FOLLOW, value), "自动关注");
        Button stop = useMiuix ? MiuixFormControls.button(context) : new Button(context);
        stop.setText(com.tokai.module.i18n.UiText.get(R.string.automation_ui_stop_now));
        stop.setTag("automation.stop");
        stop.setOnClickListener(ignored -> {
            if (closed) return;
            repository.putBoolean("native_auto_reply", false);
            repository.putBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false);
            repository.putBoolean("native_auto_comment", false);
            repository.putBoolean("streak_enabled", false);
            change(Field.STOP, false);
        });
        panel.addView(MiuixFormControls.viewFor(stop), matchWrap());
        TextView stopDetail = text(com.tokai.module.i18n.UiText.get(R.string.automation_ui_stop_now_hint), 12, MUTED);
        panel.addView(stopDetail, matchWrap());
        ScrollView scroll = new ScrollView(context);
        scroll.addView(panel);
        dialog = new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.automation_ui_interaction_automation))
                .setView(scroll).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.automation_ui_back), null).create();
        dialog.setOnDismissListener(ignored -> close());
    }

    AlertDialog dialog() { return dialog; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void change(Field field, boolean value) {
        if (changing || closed) return;
        FeatureSettings current = repository.load();
        boolean stop = field == Field.STOP;
        FeatureSettings next = new FeatureSettings(current.downloadEnabled, current.removeWatermark,
                current.feedFilterEnabled, current.hideAds, current.hideLives, current.hideImagePosts,
                current.hideRecommendationCards, current.hideRecommendationMerchants,
                current.keywordFilterEnabled, current.keyword, current.minimumDurationEnabled,
                current.minimumDurationSeconds, current.minimumLikesEnabled, current.minimumLikeCount,
                stop ? false : field == Field.MASTER ? value : current.automationEnabled,
                stop ? false : field == Field.LIKE ? value : current.autoLikeEnabled,
                stop ? false : field == Field.FOLLOW ? value : current.autoFollowEnabled,
                stop ? false : current.autoCommentEnabled, stop ? false : current.autoMessageEnabled);
        repository.save(next);
        onChanged.run();
        changing = true;
        try {
            controls[0].checked.accept(next.automationEnabled);
            controls[1].checked.accept(next.autoLikeEnabled);
            controls[2].checked.accept(next.autoFollowEnabled);
            controls[1].enabled.accept(next.automationEnabled);
            controls[2].enabled.accept(next.automationEnabled);
        } finally { changing = false; }
    }

    private Toggle addToggle(LinearLayout panel, String title, String summary, boolean checked,
                             boolean enabled, Consumer<Boolean> listener, String stableTitle) {
        if (useMiuix) {
            MiuixSettingsViews.Toggle toggle = new MiuixSettingsViews.Toggle(
                    context, title, summary, checked, enabled, listener, stableTitle);
            panel.addView(toggle.getView(), matchWrap());
            return new Toggle(toggle::setChecked, toggle::setEnabled);
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, TEXT), matchWrap());
        TextView description = text(summary, 12, MUTED);
        description.setVisibility(summary == null || summary.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(3);
        labels.addView(description, descriptionParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(context);
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        toggle.setTag("settings.toggle." + stableTitle);
        toggle.setContentDescription(title);
        toggle.setOnCheckedChangeListener((button, value) -> listener.accept(value));
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(row, matchWrap());
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        panel.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return new Toggle(toggle::setChecked, toggle::setEnabled);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private enum Field { MASTER, LIKE, FOLLOW, STOP }
    private static final class Toggle {
        final Consumer<Boolean> checked;
        final Consumer<Boolean> enabled;
        Toggle(Consumer<Boolean> checked, Consumer<Boolean> enabled) {
            this.checked = checked;
            this.enabled = enabled;
        }
    }
}
