package com.tokai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tokai.module.R;
import com.tokai.module.config.HostSettingsStore;
import com.tokai.module.config.BreezeSpeechSettings;
import com.tokai.module.config.FishSpeechSettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.config.SpeechSettings;
import com.tokai.module.feature.VoiceCatalog;
import com.tokai.module.feature.VoiceCatalogLabels;
import com.tokai.module.feature.VoiceNameTranslationCache;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Cached provider voice picker for the feed quick-action sheet. */
public final class GestureQuickVoiceDialog {
    private static final String THIRD_PARTY_SAMPLE = "tts_voice_sample";

    private GestureQuickVoiceDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable openSettings,
                                     Consumer<AlertDialog> show) {
        com.tokai.module.i18n.ModuleLocale.ensureInitialized(activity);
        SpeechSettings speech;
        String provider;
        List<VoiceCatalog.Entry> entries = Collections.emptyList();
        try {
            Map<String, ?> values = store.snapshot();
            LocalOptions snapshot = new LocalOptions(values);
            speech = new SpeechSettings(snapshot);
            provider = snapshot.text(SpeechSettings.PROVIDER, SpeechSettings.LOCAL);
            if (speech.minimax) {
                entries = VoiceCatalog.parse(store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""), "", "", "");
            } else if (speech.breeze) {
                String sample = snapshot.text(BreezeSpeechSettings.VOICE_SAMPLE, "");
                if (!sample.trim().isEmpty() && VoiceCatalogLabels.queryKey(speech).equals(
                        snapshot.text(VoiceCatalogLabels.BREEZE_QUERY_KEY, ""))) {
                    entries = VoiceCatalog.parse(sample, "", "", "");
                }
            } else if (speech.fish) {
                String sample = snapshot.text(FishSpeechSettings.VOICE_SAMPLE, "");
                if (!sample.trim().isEmpty() && VoiceCatalogLabels.queryKey(speech).equals(
                        snapshot.text(VoiceCatalogLabels.FISH_QUERY_KEY, ""))) {
                    entries = VoiceCatalog.parse(sample, "", "", "");
                }
            } else if (speech.thirdParty) {
                entries = VoiceCatalog.parse(store.getString(THIRD_PARTY_SAMPLE, ""), speech);
            }
        } catch (RuntimeException unavailable) {
            return new MiuixDialogBuilder(activity, true).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_voice))
                    .setMessage(com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_cached_voices))
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null)
                    .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_open_module_settings), (dialog, which) -> openSettings.run())
                    .create();
        }
        if (speech.local) {
            return createLocalVoicePicker(activity, store, provider, openSettings, show);
        }
        String selectedId = speech.fish ? speech.fishSettings.referenceId : speech.breeze ? speech.breezeSettings.voiceId
                : speech.minimax ? speech.minimaxVoiceId : speech.selectedVoice;
        String key = speech.fish ? FishSpeechSettings.VOICE_ID : speech.breeze ? BreezeSpeechSettings.VOICE_ID
                : speech.minimax ? SpeechSettings.MINIMAX_VOICE_ID : SpeechSettings.SELECTED_VOICE;
        String nameKey = speech.fish ? FishSpeechSettings.VOICE_NAME : speech.breeze ? BreezeSpeechSettings.VOICE_NAME
                : speech.minimax ? SpeechSettings.MINIMAX_VOICE_NAME : SpeechSettings.SELECTED_VOICE_NAME;
        List<VoiceCatalog.Entry> availableEntries = entries;
        if (entries.isEmpty() && !speech.fish) {
            return new MiuixDialogBuilder(activity, true).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_voice))
                    .setMessage(com.tokai.module.i18n.UiText.get(R.string.editor_ui_fetch_voices_first))
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null)
                    .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_enter_manually), (dialog, which) -> {
                        dialog.dismiss();
                        presentManualVoice(activity, store, provider, key, nameKey, selectedId, show);
                    })
                    .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_open_module_settings), (dialog, which) -> openSettings.run())
                    .create();
        }

        if (speech.fish || speech.breeze) {
            if (entries.size() > 200) entries = new ArrayList<>(entries.subList(0, 200));
            return new CachedProviderPicker(activity, store, speech, provider, entries,
                    key, nameKey, openSettings, show).create();
        }

        int offset = speech.fish ? 1 : 0;
        String[] labels = new String[availableEntries.size() + offset];
        int selected = speech.fish && selectedId.isEmpty() ? 0 : -1;
        if (speech.fish) labels[0] = com.tokai.module.i18n.UiText.get(R.string.editor_ui_default_voice);
        for (int index = 0; index < availableEntries.size(); index++) {
            VoiceCatalog.Entry entry = availableEntries.get(index);
            labels[index + offset] = entry.name + " · " + entry.id;
            if (entry.id.equals(selectedId)) selected = index + offset;
        }
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle(speech.fish ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_single_voice) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_voice));
        builder.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    if (!sameProvider(store, provider)) { dialog.dismiss(); return; }
                    try {
                        VoiceCatalog.Entry entry = speech.fish && index == 0 ? null : availableEntries.get(index - offset);
                        String id = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(key, entry.id)
                                : speech.breeze ? BreezeSpeechSettings.validateValue(key, entry.id) : entry.id;
                        String name = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(nameKey, entry.name)
                                : speech.breeze ? BreezeSpeechSettings.validateValue(nameKey, entry.name) : entry.name;
                        store.putString(key, id);
                        store.putString(nameKey, name);
                        if (speech.fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
                        Toast.makeText(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_switched_to, (entry == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_default_voice) : name)), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } catch (IllegalArgumentException invalid) {
                        Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
        builder.setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null);
        builder.setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_enter_manually), (dialog, which) -> {
                    dialog.dismiss();
                    presentManualVoice(activity, store, provider, key, nameKey, selectedId, show);
                });
        builder.setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_settings_title), (dialog, which) -> openSettings.run());
        return builder.create();
    }

    /** A cached-only search; the full settings screen owns directory and translation requests. */
    private static final class CachedProviderPicker {
        private final Activity activity;
        private final SettingsStore store;
        private final SpeechSettings speech;
        private final String provider, scope, queryKey, key, nameKey;
        private final List<VoiceCatalog.Entry> entries;
        private final List<VoiceCatalog.Entry> visible = new ArrayList<>();
        private final VoiceNameTranslationCache names;
        private final Runnable openSettings;
        private final Consumer<AlertDialog> show;
        private final EditText search;
        private final TextView status;
        private AlertDialog picker;

        CachedProviderPicker(Activity activity, SettingsStore store, SpeechSettings speech, String provider,
                             List<VoiceCatalog.Entry> entries, String key, String nameKey,
                             Runnable openSettings, Consumer<AlertDialog> show) {
            this.activity = activity; this.store = store; this.speech = speech; this.provider = provider;
            this.entries = entries; this.key = key; this.nameKey = nameKey;
            this.openSettings = openSettings; this.show = show;
            scope = VoiceNameTranslationCache.scope(speech);
            queryKey = VoiceCatalogLabels.queryKey(speech);
            names = VoiceNameTranslationCache.read(store);
            search = new EditText(ModuleTheme.themedContext(activity));
            search.setTag("speech.quick.voice.search");
            search.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_search_voice));
            search.setHint(com.tokai.module.i18n.UiText.get(R.string.editor_ui_voice_search_hint));
            search.setSingleLine(true);
            search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
            status = new TextView(ModuleTheme.themedContext(activity));
            status.setTag("speech.quick.voice.search.status");
        }

        AlertDialog create() {
            LinearLayout header = new LinearLayout(ModuleTheme.themedContext(activity));
            header.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            header.setPadding(padding, padding, padding, 0);
            TextView title = new TextView(header.getContext());
            title.setText(speech.fish ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_single_voice) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_voice)); title.setTextSize(18);
            header.addView(title);
            LinearLayout row = new LinearLayout(header.getContext());
            row.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button clear = new Button(header.getContext()); clear.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_clear));
            clear.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_clear_voice_search)); clear.setTag("speech.quick.voice.search.clear");
            clear.setOnClickListener(ignored -> search.setText("")); row.addView(clear);
            header.addView(row); header.addView(status);
            TextView hint = new TextView(header.getContext());
            hint.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_voice_cache_hint));
            header.addView(hint);
            MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
            builder.setCustomTitle(header);
            builder.setSingleChoiceItems(new String[0], -1, (dialog, index) -> choose(index));
            builder.setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null);
            builder.setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_enter_manually), (dialog, which) -> {
                if (current()) presentManualVoice(activity, store, provider, key, nameKey,
                        store.getString(key, ""), show);
            });
            builder.setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_settings_title), (dialog, which) -> openSettings.run());
            picker = builder.create();
            picker.setOnShowListener(ignored -> update());
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) { update(); }
                @Override public void afterTextChanged(Editable value) { }
            });
            return picker;
        }

        private boolean current() {
            if (!sameProvider(store, provider)) return false;
            try {
                SpeechSettings current = new SpeechSettings(new LocalOptions(store.snapshot()));
                return scope.equals(VoiceNameTranslationCache.scope(current))
                        && queryKey.equals(VoiceCatalogLabels.queryKey(current));
            }
            catch (RuntimeException invalid) { return false; }
        }

        private void update() {
            if (picker == null || !picker.isShowing()) return;
            if (!current()) { picker.dismiss(); return; }
            String query = search.getText().toString().trim();
            visible.clear();
            if (speech.fish && (query.isEmpty() || com.tokai.module.i18n.UiText.get(R.string.editor_ui_default_voice).toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)))) visible.add(null);
            int matched = 0;
            for (VoiceCatalog.Entry entry : entries) if (MetadataUiText.matchesVoice(scope, names, entry, query)) {
                visible.add(entry); matched++;
            }
            String[] labels = new String[visible.size()]; int selected = -1;
            String selectedId = store.getString(key, "");
            for (int index = 0; index < visible.size(); index++) {
                VoiceCatalog.Entry entry = visible.get(index);
                labels[index] = entry == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_default_voice) : names.displayName(scope, entry) + " · " + entry.id
                        + (entry.labels.isEmpty() ? "" : "\n" + String.join(" · ", MetadataUiText.displayVoiceLabels(entry)));
                if (entry == null ? selectedId.isEmpty() : entry.id.equals(selectedId)) selected = index;
            }
            String emptyStatus = visible.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.voice_quick_catalog_empty) : "";
            status.setText(com.tokai.module.i18n.UiText.get(R.string.voice_quick_catalog_status, entries.size(), matched, emptyStatus));
            MiuixDialogBuilder.updateSingleChoiceItems(picker, labels, selected, (dialog, index) -> choose(index));
        }

        private void choose(int index) {
            if (!current()) { picker.dismiss(); return; }
            if (index < 0 || index >= visible.size()) return;
            VoiceCatalog.Entry entry = visible.get(index);
            try {
                String id = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(key, entry.id)
                        : BreezeSpeechSettings.validateValue(key, entry.id);
                String original = entry == null ? "" : speech.fish ? FishSpeechSettings.validateValue(nameKey, entry.name)
                        : BreezeSpeechSettings.validateValue(nameKey, entry.name);
                store.putString(key, id); store.putString(nameKey, original);
                if (speech.fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
                Toast.makeText(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_switched_to, (entry == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_default_voice) : names.displayName(scope, entry))),
                        Toast.LENGTH_SHORT).show();
                picker.dismiss();
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static AlertDialog createLocalVoicePicker(Activity activity, SettingsStore store,
                                                      String provider,
                                                      Runnable openSettings,
                                                      Consumer<AlertDialog> show) {
        MiuixDialogBuilder loadingBuilder = new MiuixDialogBuilder(activity, true);
        loadingBuilder.setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_voice));
        loadingBuilder.setMessage(com.tokai.module.i18n.UiText.get(R.string.editor_ui_reading_offline_voices));
        loadingBuilder.setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null);
        AlertDialog loading = loadingBuilder.create();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean resolved = new AtomicBoolean();
        TextToSpeech[] engine = new TextToSpeech[1];
        Runnable timeout = () -> {
            if (!resolved.compareAndSet(false, true) || !loading.isShowing()) return;
            shutdown(engine);
            if (!sameProvider(store, provider)) { loading.dismiss(); return; }
            show.accept(localVoiceMessage(activity, openSettings,
                    com.tokai.module.i18n.UiText.get(R.string.editor_ui_tts_read_timeout)));
        };
        loading.setOnDismissListener(ignored -> {
            main.removeCallbacks(timeout);
            if (resolved.compareAndSet(false, true)) shutdown(engine);
        });
        String configuredEngine = store.getString("tts_engine", "").trim();
        try {
            engine[0] = new TextToSpeech(activity, status -> main.post(() -> {
                if (!resolved.compareAndSet(false, true) || !loading.isShowing()) {
                    shutdown(engine);
                    return;
                }
                main.removeCallbacks(timeout);
                if (!sameProvider(store, provider)) {
                    shutdown(engine);
                    loading.dismiss();
                    return;
                }
                TextToSpeech current = engine[0];
                if (status != TextToSpeech.SUCCESS || current == null) {
                    shutdown(engine);
                    show.accept(localVoiceMessage(activity, openSettings,
                            com.tokai.module.i18n.UiText.get(R.string.editor_ui_tts_read_failed)));
                    return;
                }
                List<Voice> voices = new ArrayList<>();
                try {
                    if (current.getVoices() != null) {
                        for (Voice voice : current.getVoices()) {
                            if (voice != null && voice.getLocale() != null
                                    && !voice.isNetworkConnectionRequired()) voices.add(voice);
                        }
                    }
                } catch (RuntimeException unavailable) { }
                shutdown(engine);
                voices.sort(Comparator.comparing((Voice voice) -> voice.getLocale().toLanguageTag())
                        .thenComparing(Voice::getName, String.CASE_INSENSITIVE_ORDER));
                if (voices.isEmpty()) {
                    show.accept(localVoiceMessage(activity, openSettings,
                            com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_offline_voices)));
                    return;
                }

                String savedVoice = store.getString(SpeechSettings.LOCAL_VOICE_NAME, "").trim();
                Locale savedLanguage = Locale.forLanguageTag(store.getString("tts_language", "zh-CN"));
                String[] labels = new String[voices.size()];
                int selected = -1;
                for (int index = 0; index < voices.size(); index++) {
                    Voice voice = voices.get(index);
                    Locale locale = voice.getLocale();
                    labels[index] = locale.getDisplayName(com.tokai.module.i18n.ModuleLocale.locale()) + " · " + voice.getName();
                    if (voice.getName().equals(savedVoice)
                            && locale.toLanguageTag().equals(savedLanguage.toLanguageTag())) selected = index;
                    else if (selected < 0 && savedVoice.isEmpty() && locale.equals(savedLanguage)) selected = index;
                }
                MiuixDialogBuilder picker = new MiuixDialogBuilder(activity, true);
                picker.setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_choose_offline_voice));
                picker.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    if (!sameProvider(store, provider)) { dialog.dismiss(); return; }
                    Voice voice = voices.get(index);
                    store.putString(SpeechSettings.LOCAL_VOICE_NAME, voice.getName());
                    store.putString("tts_language", voice.getLocale().toLanguageTag());
                    Toast.makeText(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_switched_to, voice.getLocale().getDisplayName(com.tokai.module.i18n.ModuleLocale.locale())),
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                });
                picker.setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null);
                picker.setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_speech_settings), (dialog, which) -> openSettings.run());
                show.accept(picker.create());
            }), configuredEngine.isEmpty() ? null : configuredEngine);
            main.postDelayed(timeout, 10_000L);
        } catch (RuntimeException | LinkageError failure) {
            resolved.set(true);
            shutdown(engine);
            show.accept(localVoiceMessage(activity, openSettings,
                    com.tokai.module.i18n.UiText.get(R.string.editor_ui_tts_init_failed)));
        }
        return loading;
    }

    private static AlertDialog localVoiceMessage(Activity activity, Runnable openSettings, String message) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_voice));
        builder.setMessage(message);
        builder.setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null);
        builder.setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_open_speech_settings), (dialog, which) -> openSettings.run());
        return builder.create();
    }

    private static void shutdown(TextToSpeech[] engine) {
        TextToSpeech current = engine[0];
        engine[0] = null;
        if (current == null) return;
        try { current.shutdown(); } catch (RuntimeException ignored) { }
    }

    private static void presentManualVoice(Activity activity, SettingsStore store, String provider,
                                           String key, String nameKey, String current,
                                           Consumer<AlertDialog> show) {
        if (!sameProvider(store, provider)) return;
        AlertDialog prompt = MiuixDialogBuilder.prompt(activity, true, com.tokai.module.i18n.UiText.get(R.string.editor_ui_voice_id), current, value -> {
            if (!sameProvider(store, provider)) return;
            String candidate = value == null ? "" : value.trim();
            try {
                if (SpeechSettings.FISH.equals(provider)) {
                    candidate = FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_ID, candidate);
                } else if (SpeechSettings.BREEZE.equals(provider)) {
                    candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_ID, candidate);
                    if (candidate.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.editor_ui_breeze_voice_invalid));
                } else if (SpeechSettings.MINIMAX.equals(provider)) {
                    candidate = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_VOICE_ID, candidate);
                } else if (candidate.isEmpty() || candidate.length() > 256
                        || candidate.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.editor_ui_voice_id_invalid));
                }
                store.putString(key, candidate);
                store.putString(nameKey, "");
            if (SpeechSettings.FISH.equals(provider)) store.putString(FishSpeechSettings.VOICE_MODE, "single");
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        show.accept(prompt);
    }

    private static boolean sameProvider(SettingsStore store, String provider) {
        return provider.equals(store.getString(SpeechSettings.PROVIDER, SpeechSettings.LOCAL));
    }
}
