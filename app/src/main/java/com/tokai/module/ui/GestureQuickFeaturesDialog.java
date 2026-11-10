package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.tokai.module.config.AutomationMessageSettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.config.SpeechSettings;
import com.tokai.module.config.FishSpeechSettings;
import com.tokai.module.feature.VoiceCatalog;
import com.tokai.module.feature.VoiceNameTranslationCache;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixSettingsViews;

import java.util.Map;
import java.util.function.Consumer;

/** Compact gesture panel for frequently used comment, reply, and speech settings. */
public final class GestureQuickFeaturesDialog {
    private GestureQuickFeaturesDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable switchVoice,
                                     Consumer<String> onError) {
        com.tokai.module.i18n.ModuleLocale.ensureInitialized(activity);
        AutomationMessageSettings updates = new AutomationMessageSettings(store, null);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        MiuixSettingsViews.Toggle autoComment = new MiuixSettingsViews.Toggle(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_logs_auto_comment),
                com.tokai.module.i18n.UiText.get(R.string.editor_ui_auto_comment_hint), store.getBoolean("native_auto_comment", false), true,
                enabled -> setEnabled(updates, "comment", enabled, onError), "自动评论");
        content.addView(autoComment.getView(), row);
        MiuixSettingsViews.Toggle autoReply = new MiuixSettingsViews.Toggle(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_auto_reply),
                com.tokai.module.i18n.UiText.get(R.string.editor_ui_auto_reply_hint), store.getBoolean("native_auto_reply", false), true,
                enabled -> setEnabled(updates, "reply", enabled, onError), "自动回复");
        content.addView(autoReply.getView(), row);

        SpeechSettings speech = speechSettings(store);
        String voice = speech.fish ? fishVoiceSummary(speech.fishSettings)
                : speech.breeze ? speech.breezeSettings.voiceName.isEmpty() ? speech.breezeSettings.voiceId
                : speech.breezeSettings.voiceName + " · " + speech.breezeSettings.voiceId
                : speech.minimax ? speech.minimaxVoiceName.isEmpty() ? speech.minimaxVoiceId
                : speech.minimaxVoiceName + " · " + speech.minimaxVoiceId
                : speech.thirdParty ? speech.selectedVoiceName.isEmpty() ? speech.selectedVoice
                : speech.selectedVoiceName + " · " + speech.selectedVoice : com.tokai.module.i18n.UiText.get(R.string.editor_ui_system_speech_engine);
        if (speech.breeze || speech.fish && "single".equals(speech.fishSettings.voiceMode)) {
            String id = speech.breeze ? speech.breezeSettings.voiceId : speech.fishSettings.referenceId;
            String original = speech.breeze ? speech.breezeSettings.voiceName : speech.fishSettings.referenceName;
            if (!id.isEmpty() && !original.isEmpty()) {
                try {
                    VoiceCatalog.Entry entry = new VoiceCatalog.Entry(id, original);
                    voice = VoiceNameTranslationCache.read(store).displayName(VoiceNameTranslationCache.scope(speech), entry)
                            + " · " + id;
                } catch (IllegalArgumentException ignored) { /* Keep the existing summary for an unfinished draft. */ }
            }
        }
        MiuixSettingsViews.Option voiceOption = new MiuixSettingsViews.Option(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_voice),
                speech.fish ? "Fish Audio" : speech.breeze ? "Breeze Blue" : speech.minimax ? "MiniMax" : speech.thirdParty ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_third_party_speech) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_system_speech_engine),
                voice, true, switchVoice, "切换音色");
        content.addView(voiceOption.getView(), row);

        AlertDialog dialog = new MiuixDialogBuilder(activity, true).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_quick_features))
                .setView(content).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null).create();
        dialog.setOnDismissListener(ignored -> updates.close());
        return dialog;
    }

    private static void setEnabled(AutomationMessageSettings settings, String kind,
                                   boolean enabled, Consumer<String> onError) {
        try {
            settings.setEnabled(kind, enabled);
        } catch (RuntimeException failure) {
            if (onError != null) onError.accept(failure.getMessage() == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_settings_save_failed) : failure.getMessage());
        }
    }

    private static String fishVoiceSummary(FishSpeechSettings fish) {
        if ("multi".equals(fish.voiceMode)) return com.tokai.module.i18n.UiText.get(R.string.editor_ui_multi_voice_summary, fish.referenceIds.size());
        if ("references".equals(fish.voiceMode)) {
            try { return com.tokai.module.i18n.UiText.get(R.string.editor_ui_reference_summary, fish.referenceGroups().size()); }
            catch (RuntimeException invalid) { return com.tokai.module.i18n.UiText.get(R.string.editor_ui_references_reconfigure); }
        }
        return fish.referenceId.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_default_voice) : fish.referenceName.isEmpty()
                ? fish.referenceId : fish.referenceName + " · " + fish.referenceId;
    }

    private static SpeechSettings speechSettings(SettingsStore store) {
        try {
            Map<String, ?> snapshot = store.snapshot();
            return new SpeechSettings(new LocalOptions(snapshot));
        } catch (RuntimeException unavailable) {
            return new SpeechSettings(new LocalOptions(java.util.Collections.emptyMap()));
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
