package com.tokai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.config.BreezeSpeechSettings;
import com.tokai.module.config.FishSpeechSettings;
import com.tokai.module.config.MiniMaxSpeechOptions;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.config.SpeechSettings;
import com.tokai.module.config.ThirdPartyRequestTemplate;
import com.tokai.module.R;
import com.tokai.module.diagnostics.TokAiLog;
import com.tokai.module.feature.LocalSpeechSynthesis;
import com.tokai.module.feature.BreezeSpeechClient;
import com.tokai.module.feature.FishSpeechClient;
import com.tokai.module.feature.FishReferenceAudio;
import com.tokai.module.feature.MiniMaxSpeechClient;
import com.tokai.module.feature.MiniMaxSpeechModelCatalog;
import com.tokai.module.feature.SpeechHttpClient;
import com.tokai.module.feature.SpeechSynthesis;
import com.tokai.module.feature.ThirdPartyResponse;
import com.tokai.module.feature.VoiceCatalog;
import com.tokai.module.feature.VoiceCatalogLabels;
import com.tokai.module.feature.VoiceNameTranslationCache;
import com.tokai.module.feature.VoiceNameTranslator;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;
import com.tokai.module.ui.miuix.MiuixExtraControls;
import com.tokai.module.ui.miuix.MiuixSettingsViews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Provider-specific settings using the host's existing dialog ownership. */
final class SpeechSettingsDialog implements AutoCloseable {
    private static final String VOICE_SAMPLE = "tts_voice_sample";
    private static final String SYNTHESIS_SAMPLE = "tts_synthesis_sample";
    private static final String BREEZE_ADVANCED = "Breeze Blue 高级设置";
    private static final String BREEZE_AUDIO = "Breeze Blue 音频输出";
    private static final String BREEZE_VOICE = "Breeze Blue 语言与表达";
    private static final String BREEZE_VOICES = "Breeze Blue 音色列表";
    private static final String FISH_ADVANCED = "Fish Audio 高级设置";
    private static final String FISH_AUDIO = "Fish Audio 音频输出";
    private static final String FISH_GENERATION = "Fish Audio 生成参数";
    private static final String FISH_LANGUAGE = "Fish Audio 语言与发音";
    private static final String FISH_VOICES = "Fish Audio 音色列表";
    private static final String FISH_VOICE = "Fish Audio 音色设置";
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "tokai-voice-list");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadPoolExecutor NAME_WORKER = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "tokai-voice-name-translation");
                thread.setDaemon(true);
                return thread;
            });
    private final Context context;
    private final SettingsStore store;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout content;
    private final AlertDialog dialog;
    private LinearLayout advancedContent;
    private final Map<String, LinearLayout> sectionContents = new LinkedHashMap<>();
    private final Map<String, Dialog> sectionDialogs = new LinkedHashMap<>();
    private SpeechHttpClient.Request fetch;
    private Future<?> future;
    private FishCatalogSession fishCatalog;
    private BreezeCatalogSession breezeCatalog;
    private SpeechHttpClient.Request enhancement;
    private Future<?> enhancementFuture;
    private long enhancementGeneration;
    private VoiceNameTranslator.Request nameTranslation;
    private Future<?> nameTranslationFuture;
    private Object nameTranslationOwner;
    private long nameTranslationGeneration;
    private String nameTranslationModel = "";
    private int nameTranslationRequested, nameTranslationSaved;
    private AlertDialog instructionEditor;
    private final List<ReferenceEditorSession> referenceEditors = new ArrayList<>();
    private SpeechSynthesis preview;
    private TokAiLog.Operation previewTrace;
    private MediaPlayer player;
    private java.io.File previewFile;
    private long previewGeneration;
    private boolean closed;

    SpeechSettingsDialog(Context context, SettingsStore store, boolean miuix,
                         BiConsumer<Dialog, Runnable> presenter) {
        this.context = ModuleTheme.themedContext(context);
        this.store = store;
        this.miuix = miuix;
        this.presenter = presenter;
        content = form();
        render();
        dialog = builder(com.tokai.module.i18n.UiText.get(R.string.speech_settings_title)).setView(scroll(content)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_back), null).create();
    }

    Dialog dialog() { return dialog; }

    private void render() {
        content.removeAllViews();
        SpeechSettings speech = settings();
        boolean third = speech.thirdParty;
        option(com.tokai.module.i18n.UiText.get(R.string.speech_provider), speech.fish ? "Fish Audio" : speech.breeze ? "Breeze Blue" : speech.minimax ? "MiniMax" : third ? com.tokai.module.i18n.UiText.get(R.string.speech_third_party) : com.tokai.module.i18n.UiText.get(R.string.speech_system_engine), () -> show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_provider))
                .setSingleChoiceItems(new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_system_engine), com.tokai.module.i18n.UiText.get(R.string.speech_third_party), "MiniMax", "Breeze Blue", "Fish Audio"}, speech.fish ? 4 : speech.breeze ? 3 : speech.minimax ? 2 : third ? 1 : 0, (choice, which) -> {
                    if (closed) return;
                    store.putString(SpeechSettings.PROVIDER, which == 4 ? SpeechSettings.FISH : which == 3 ? SpeechSettings.BREEZE : which == 2 ? SpeechSettings.MINIMAX
                            : which == 1 ? SpeechSettings.THIRD_PARTY : SpeechSettings.LOCAL);
                    stopPreview();
                    cancelFetch();
                    discardFishCatalog();
                    discardBreezeCatalog();
                    if (speech.fish && which != 4) clearCatalogSample(true);
                    if (speech.breeze && which != 3) clearCatalogSample(false);
                    stopEnhancement();
                    stopNameTranslation( () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_source_changed));
                    instructionEditor = null;
                    for (ReferenceEditorSession session : new ArrayList<>(referenceEditors)) session.close();
                    choice.dismiss();
                    render();
                }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null), "语音来源");
        if (speech.fish) {
            FishSpeechSettings fish = speech.fishSettings;
            option(com.tokai.module.i18n.UiText.get(R.string.speech_engine), fish.engine, this::selectFishModel, "语音引擎");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_voice), voiceLabel(), () -> section(FISH_VOICE), "音色");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_api_settings), fish.apiKey.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_api_key_required) : com.tokai.module.i18n.UiText.get(R.string.speech_api_key_configured), () -> section("接口设置"), "接口设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_reading_settings), readingSummary(speech), () -> section("朗读设置"), "朗读设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_fish_advanced), com.tokai.module.i18n.UiText.get(R.string.speech_fish_advanced_summary), () -> section(FISH_ADVANCED), FISH_ADVANCED);
        } else if (speech.breeze) {
            BreezeSpeechSettings breeze = speech.breezeSettings;
            option(com.tokai.module.i18n.UiText.get(R.string.speech_speech_model), BreezeSpeechSettings.AUTO_MODEL.equals(breeze.model) ? com.tokai.module.i18n.UiText.get(R.string.speech_auto_language_model) : breeze.model, this::selectBreezeModel, "语音模型");
            String voice = voiceLabel();
            if (breezeCatalog != null && breezeCatalog.matches(speech) && breezeCatalog.loading) voice = com.tokai.module.i18n.UiText.get(R.string.speech_voice_loading, voice);
            option(com.tokai.module.i18n.UiText.get(R.string.speech_voice), voice, this::selectVoice, "音色");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_api_settings), breeze.apiKey.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_api_key_required) : com.tokai.module.i18n.UiText.get(R.string.speech_api_key_configured), () -> section("接口设置"), "接口设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_reading_settings), readingSummary(speech), () -> section("朗读设置"), "朗读设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_advanced), com.tokai.module.i18n.UiText.get(R.string.speech_breeze_advanced_summary), () -> section(BREEZE_ADVANCED), BREEZE_ADVANCED);
        } else if (speech.minimax) {
            option(com.tokai.module.i18n.UiText.get(R.string.speech_model), speech.minimaxModel, this::selectMiniMaxModel, "模型");
            if (!MiniMaxSpeechModelCatalog.builtInModels().contains(speech.minimaxModel)) {
                TextView capability = new TextView(context);
                capability.setTag("speech.model.capability");
                capability.setText(com.tokai.module.i18n.UiText.get(R.string.speech_model_capabilities_unverified));
                content.addView(capability, matchWrap());
            }
            option(com.tokai.module.i18n.UiText.get(R.string.speech_voice), voiceLabel(), this::selectVoice, "音色");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_api_settings), speech.minimaxApiKey.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_api_key_required) : com.tokai.module.i18n.UiText.get(R.string.speech_api_key_configured), () -> section("接口设置"), "接口设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_reading_settings), readingSummary(speech), () -> section("朗读设置"), "朗读设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_minimax_advanced), com.tokai.module.i18n.UiText.get(R.string.speech_minimax_advanced_summary), () -> section("MiniMax 高级设置"), "MiniMax 高级设置");
        } else if (!third) {
            option(com.tokai.module.i18n.UiText.get(R.string.speech_api_settings), com.tokai.module.i18n.UiText.get(R.string.speech_system_api_summary), () -> section("接口设置"), "接口设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_reading_settings), readingSummary(speech), () -> section("朗读设置"), "朗读设置");
        } else {
            editable(com.tokai.module.i18n.UiText.get(R.string.speech_synthesis_get), SpeechSettings.GET_TEMPLATE, SpeechSettings.DEFAULT_GET_TEMPLATE, false, "合成 GET");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_synthesis_parameters), parameterNames(settings().getTemplate), this::parameters, "合成参数");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_api_settings), com.tokai.module.i18n.UiText.get(R.string.speech_third_api_summary), () -> section("接口设置"), "接口设置");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_voice), voiceLabel(), this::selectVoice, "音色");
            option(com.tokai.module.i18n.UiText.get(R.string.speech_voice_advanced), com.tokai.module.i18n.UiText.get(R.string.speech_voice_advanced_summary), this::advanced, "音色高级设置");
        }
        option(com.tokai.module.i18n.UiText.get(R.string.speech_preview), null, this::testSpeech, "试听");
        if (advancedContent != null) renderAdvanced(advancedContent);
        for (Map.Entry<String, LinearLayout> entry : new ArrayList<>(sectionContents.entrySet())) {
            renderSection(entry.getKey(), entry.getValue());
        }
    }

    private String readingSummary(SpeechSettings speech) {
        if (speech.fish) return com.tokai.module.i18n.UiText.get(R.string.speech_reading_fish, editableValue(FishSpeechSettings.SPEED, FishSpeechSettings.defaultValue(FishSpeechSettings.SPEED)), editableValue(FishSpeechSettings.VOLUME_DB, FishSpeechSettings.defaultValue(FishSpeechSettings.VOLUME_DB)));
        if (speech.breeze) return com.tokai.module.i18n.UiText.get(R.string.speech_reading_breeze, editableValue(BreezeSpeechSettings.SPEED, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.SPEED)), editableValue(BreezeSpeechSettings.VOLUME, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.VOLUME)));
        return speech.minimax ? com.tokai.module.i18n.UiText.get(R.string.speech_reading_minimax, editableValue(SpeechSettings.MINIMAX_SPEED, "1"), editableValue(SpeechSettings.MINIMAX_VOLUME, "1"), editableValue(SpeechSettings.MINIMAX_PITCH, "0"))
                : com.tokai.module.i18n.UiText.get(R.string.speech_reading_local, editableValue(SpeechSettings.RATE, ""), editableValue(SpeechSettings.PITCH, ""));
    }

    private static String sectionLabel(String stableTitle) {
        switch (stableTitle) {
            case "Breeze Blue 语言与表达": return com.tokai.module.i18n.UiText.get(R.string.speech_breeze_expression);
            case "Breeze Blue 音色列表": return com.tokai.module.i18n.UiText.get(R.string.speech_breeze_voices);
            case "Breeze Blue 音频输出": return com.tokai.module.i18n.UiText.get(R.string.speech_breeze_audio);
            case "Breeze Blue 高级设置": return com.tokai.module.i18n.UiText.get(R.string.speech_breeze_advanced);
            case "Fish Audio 生成参数": return com.tokai.module.i18n.UiText.get(R.string.speech_fish_generation);
            case "Fish Audio 语言与发音": return com.tokai.module.i18n.UiText.get(R.string.speech_fish_language);
            case "Fish Audio 音色列表": return com.tokai.module.i18n.UiText.get(R.string.speech_fish_voices);
            case "Fish Audio 音色设置": return com.tokai.module.i18n.UiText.get(R.string.speech_fish_voice);
            case "Fish Audio 音频输出": return com.tokai.module.i18n.UiText.get(R.string.speech_fish_audio);
            case "Fish Audio 高级设置": return com.tokai.module.i18n.UiText.get(R.string.speech_fish_advanced);
            case "MiniMax 高级设置": return com.tokai.module.i18n.UiText.get(R.string.speech_minimax_advanced);
            case "接口设置": return com.tokai.module.i18n.UiText.get(R.string.speech_api_settings);
            case "朗读设置": return com.tokai.module.i18n.UiText.get(R.string.speech_reading_settings);
            case "混音与声音效果": return com.tokai.module.i18n.UiText.get(R.string.speech_mixing_effects);
            case "语言与发音": return com.tokai.module.i18n.UiText.get(R.string.speech_language_pronunciation);
            case "音频输出": return com.tokai.module.i18n.UiText.get(R.string.speech_audio_output);
            default: return stableTitle;
        }
    }

    private void section(String title) {
        if (closed) return;
        Dialog opened = sectionDialogs.get(title);
        if (opened != null && opened.isShowing()) return;
        LinearLayout form = form();
        form.setTag("speech.section." + title);
        sectionContents.put(title, form);
        renderSection(title, form);
        AlertDialog child = builder(sectionLabel(title)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_back), null).create();
        sectionDialogs.put(title, child);
        show(child, () -> {
            if (sectionContents.get(title) == form) sectionContents.remove(title);
            if (sectionDialogs.get(title) == child) sectionDialogs.remove(title);
        });
    }

    private void renderSection(String title, LinearLayout form) {
        form.removeAllViews();
        SpeechSettings speech = settings();
        if ("接口设置".equals(title)) {
            if (speech.fish) {
                editable(form, "API Key", FishSpeechSettings.API_KEY, "", true, "API Key");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_api_address), FishSpeechSettings.BASE_URL, FishSpeechSettings.DEFAULT_BASE_URL, false, "API 地址");
            } else if (speech.breeze) {
                editable(form, "API Key", BreezeSpeechSettings.API_KEY, "", true, "API Key");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_api_address), BreezeSpeechSettings.BASE_URL, BreezeSpeechSettings.DEFAULT_BASE_URL, false, "API 地址");
            } else if (speech.minimax) {
                editable(form, "API Key", SpeechSettings.MINIMAX_API_KEY, "", true, "API Key");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_api_address), SpeechSettings.MINIMAX_BASE_URL, SpeechSettings.DEFAULT_MINIMAX_BASE_URL, false, "API 地址");
            } else if (speech.thirdParty) {
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_synthesis_headers), SpeechSettings.HEADERS, "{}", false, "合成请求头");
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_synthesis_sample), synthesisSampleLabel(), this::editSynthesisSample, "合成响应样本");
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_url_field), speech.audioField.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_detect_automatically) : speech.audioField, this::chooseAudioField, "音频地址字段");
            } else {
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_engine), "tts_engine", "", false, "语音引擎");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_speech_language), "tts_language", "zh-CN", false, "语音语言");
            }
            return;
        }
        if ("朗读设置".equals(title)) {
            if (speech.fish) {
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_speed), FishSpeechSettings.SPEED, FishSpeechSettings.defaultValue(FishSpeechSettings.SPEED), false, "语速");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_volume_db), FishSpeechSettings.VOLUME_DB, FishSpeechSettings.defaultValue(FishSpeechSettings.VOLUME_DB), false, "音量（dB）");
                if (!"s1".equals(speech.fishSettings.engine)) fishBoolean(form, com.tokai.module.i18n.UiText.get(R.string.speech_normalize_loudness), FishSpeechSettings.NORMALIZE_LOUDNESS, "响度归一化");
                else hint(form, "speech.fish.loudness.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_fish_s1_loudness_hint));
                hint(form, "speech.fish.volume.hint", com.tokai.module.i18n.UiText.get(R.string.speech_fish_volume_hint));
            } else if (speech.breeze) {
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_speed), BreezeSpeechSettings.SPEED, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.SPEED), false, "语速");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_volume), BreezeSpeechSettings.VOLUME, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.VOLUME), false, "音量");
                hint(form, "speech.breeze.pitch.hint", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_pitch_hint));
            } else if (speech.minimax) {
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_speed), SpeechSettings.MINIMAX_SPEED, "1", false, "语速");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_volume), SpeechSettings.MINIMAX_VOLUME, "1", false, "音量");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_pitch), SpeechSettings.MINIMAX_PITCH, "0", false, "音调");
            } else if (!speech.thirdParty) {
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_speed), SpeechSettings.RATE, "", false, "语速");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_pitch), SpeechSettings.PITCH, "", false, "音调");
            }
            return;
        }
        if (title.startsWith("Fish Audio ")) {
            if (!speech.fish) { hint(form, "speech.fish.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_fish_inactive)); return; }
            renderFishSection(title, form, speech.fishSettings);
            return;
        }
        if (title.startsWith("Breeze Blue ")) {
            if (!speech.breeze) { hint(form, "speech.breeze.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_inactive)); return; }
            if (BREEZE_ADVANCED.equals(title)) {
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_output), speech.breezeSettings.outputFormat, () -> section(BREEZE_AUDIO), "音频输出");
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_language_expression), com.tokai.module.i18n.UiText.get(R.string.speech_expression_summary), () -> section(BREEZE_VOICE), "语言与表达");
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_catalog), com.tokai.module.i18n.UiText.get(R.string.speech_catalog_summary), () -> section(BREEZE_VOICES), "音色列表");
                hint(form, "speech.breeze.capabilities", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_capabilities_hint));
            } else if (BREEZE_AUDIO.equals(title)) {
                renderBreezeAudio(form);
            } else if (BREEZE_VOICE.equals(title)) {
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_instructions), speech.breezeSettings.instructions.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_not_set) : speech.breezeSettings.instructions, this::editBreezeInstruction, "语音指令");
                String[] languageCodes = "breeze-tts-2".equals(speech.breezeSettings.model)
                        ? new String[]{"", "zh", "en"} : BreezeSpeechSettings.LANGUAGE_CODES;
                String[] languages = languageCodes.clone();
                for (int index = 0; index < languages.length; index++) {
                    if (languages[index].isEmpty()) languages[index] = com.tokai.module.i18n.UiText.get(R.string.speech_detect_automatically);
                    else if ("zh".equals(languages[index])) languages[index] = com.tokai.module.i18n.UiText.get(R.string.speech_language_zh_code);
                    else if ("en".equals(languages[index])) languages[index] = com.tokai.module.i18n.UiText.get(R.string.speech_language_en_code);
                }
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_language_code), BreezeSpeechSettings.LANGUAGE_CODE, languageCodes, languages, "语言代码");
                if (!speech.breezeSettings.effectiveSpeakerBoost()) {
                    editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_guidance_scale), BreezeSpeechSettings.GUIDANCE_SCALE, BreezeSpeechSettings.defaultValue(BreezeSpeechSettings.GUIDANCE_SCALE), false, "指导强度");
                } else hint(form, "speech.breeze.guidance.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_guidance_inactive));
                if (speech.breezeSettings.instructions.isEmpty() || speech.breezeSettings.guidanceScale <= 1) {
                    breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_speaker_boost), BreezeSpeechSettings.SPEAKER_BOOST,
                            new String[]{"true", "false"}, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_enabled), com.tokai.module.i18n.UiText.get(R.string.speech_disabled)}, "说话人增强");
                } else hint(form, "speech.breeze.boost.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_boost_inactive));
                hint(form, "speech.breeze.language.hint", "breeze-tts-2".equals(speech.breezeSettings.model)
                        ? com.tokai.module.i18n.UiText.get(R.string.speech_breeze_base_language_hint) : com.tokai.module.i18n.UiText.get(R.string.speech_model_language_hint));
                hint(form, "speech.breeze.boost.hint", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_boost_hint));
            } else if (BREEZE_VOICES.equals(title)) {
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_scope), BreezeSpeechSettings.VOICE_TYPE,
                        new String[]{"all", "default", "personal"}, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_all_voices), com.tokai.module.i18n.UiText.get(R.string.speech_system_voices), com.tokai.module.i18n.UiText.get(R.string.speech_personal_voices)}, "音色范围");
                editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_search), BreezeSpeechSettings.VOICE_SEARCH, "", false, "音色搜索");
                String[] languages = catalogLanguageLabels(BreezeSpeechSettings.LANGUAGE_CODES);
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_language), BreezeSpeechSettings.VOICE_LANGUAGE, BreezeSpeechSettings.LANGUAGE_CODES, languages, "音色语言");
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_category), BreezeSpeechSettings.VOICE_CATEGORY, BreezeSpeechSettings.VOICE_CATEGORIES,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_CATEGORIES, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_any), com.tokai.module.i18n.UiText.get(R.string.speech_category_narration), com.tokai.module.i18n.UiText.get(R.string.speech_category_social_media), com.tokai.module.i18n.UiText.get(R.string.speech_category_advertising), com.tokai.module.i18n.UiText.get(R.string.speech_category_podcast), com.tokai.module.i18n.UiText.get(R.string.speech_category_learning), com.tokai.module.i18n.UiText.get(R.string.speech_category_assistant), com.tokai.module.i18n.UiText.get(R.string.speech_category_roleplay), com.tokai.module.i18n.UiText.get(R.string.speech_category_games), com.tokai.module.i18n.UiText.get(R.string.speech_category_animation), com.tokai.module.i18n.UiText.get(R.string.speech_category_film), com.tokai.module.i18n.UiText.get(R.string.speech_category_relaxation), com.tokai.module.i18n.UiText.get(R.string.speech_category_stylized)}), "音色分类");
                catalogMultipleChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_gender), false, BreezeSpeechSettings.VOICE_GENDER, BreezeSpeechSettings.VOICE_GENDERS,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_GENDERS, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_any), com.tokai.module.i18n.UiText.get(R.string.speech_gender_male), com.tokai.module.i18n.UiText.get(R.string.speech_gender_female), com.tokai.module.i18n.UiText.get(R.string.speech_gender_neutral)}), "音色性别");
                catalogMultipleChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_age), false, BreezeSpeechSettings.VOICE_AGE, BreezeSpeechSettings.VOICE_AGES,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_AGES, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_age_child), com.tokai.module.i18n.UiText.get(R.string.speech_age_young), com.tokai.module.i18n.UiText.get(R.string.speech_age_middle), com.tokai.module.i18n.UiText.get(R.string.speech_age_senior)}), "音色年龄");
                catalogMultipleChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_tone), false, BreezeSpeechSettings.VOICE_TONE, BreezeSpeechSettings.VOICE_TONES,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_TONES, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_any), com.tokai.module.i18n.UiText.get(R.string.speech_tone_warm), com.tokai.module.i18n.UiText.get(R.string.speech_tone_calm), com.tokai.module.i18n.UiText.get(R.string.speech_tone_bright), com.tokai.module.i18n.UiText.get(R.string.speech_tone_gentle), com.tokai.module.i18n.UiText.get(R.string.speech_tone_energetic), com.tokai.module.i18n.UiText.get(R.string.speech_tone_authoritative), com.tokai.module.i18n.UiText.get(R.string.speech_tone_sincere), com.tokai.module.i18n.UiText.get(R.string.speech_tone_tired), com.tokai.module.i18n.UiText.get(R.string.speech_tone_precise), com.tokai.module.i18n.UiText.get(R.string.speech_tone_elegant),
                                com.tokai.module.i18n.UiText.get(R.string.speech_tone_urgent), com.tokai.module.i18n.UiText.get(R.string.speech_tone_friendly), com.tokai.module.i18n.UiText.get(R.string.speech_tone_clear), com.tokai.module.i18n.UiText.get(R.string.speech_tone_steady), com.tokai.module.i18n.UiText.get(R.string.speech_tone_playful), com.tokai.module.i18n.UiText.get(R.string.speech_tone_caring), com.tokai.module.i18n.UiText.get(R.string.speech_tone_thoughtful), com.tokai.module.i18n.UiText.get(R.string.speech_tone_composed), com.tokai.module.i18n.UiText.get(R.string.speech_tone_rhythmic), com.tokai.module.i18n.UiText.get(R.string.speech_tone_passionate)}), "音色语气");
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_accent), BreezeSpeechSettings.VOICE_ACCENT, BreezeSpeechSettings.VOICE_ACCENTS,
                        namedCatalogValues(BreezeSpeechSettings.VOICE_ACCENTS, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_any), com.tokai.module.i18n.UiText.get(R.string.speech_accent_american), com.tokai.module.i18n.UiText.get(R.string.speech_accent_british), com.tokai.module.i18n.UiText.get(R.string.speech_accent_scottish), com.tokai.module.i18n.UiText.get(R.string.speech_accent_irish), com.tokai.module.i18n.UiText.get(R.string.speech_accent_australian), com.tokai.module.i18n.UiText.get(R.string.speech_accent_canadian), com.tokai.module.i18n.UiText.get(R.string.speech_accent_southern_us), com.tokai.module.i18n.UiText.get(R.string.speech_accent_new_york), com.tokai.module.i18n.UiText.get(R.string.speech_accent_indian), com.tokai.module.i18n.UiText.get(R.string.speech_accent_south_african), com.tokai.module.i18n.UiText.get(R.string.speech_accent_russian), com.tokai.module.i18n.UiText.get(R.string.speech_accent_japanese), com.tokai.module.i18n.UiText.get(R.string.speech_accent_korean), com.tokai.module.i18n.UiText.get(R.string.speech_language_chinese), com.tokai.module.i18n.UiText.get(R.string.speech_accent_northeast_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_accent_shaanxi_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_accent_shanghai_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_accent_sichuan_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_accent_henan_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_accent_beijing_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_accent_taiwan_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_language_cantonese), com.tokai.module.i18n.UiText.get(R.string.speech_accent_guangdong_mandarin), com.tokai.module.i18n.UiText.get(R.string.speech_accent_yunnan_mandarin)}), "音色口音");
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_accent_mark_scope), BreezeSpeechSettings.VOICE_ACCENT_MODE, BreezeSpeechSettings.VOICE_ACCENT_MODES,
                        new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_accent_any_mark), com.tokai.module.i18n.UiText.get(R.string.speech_accent_unmarked)}, "口音标记范围");
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_origin), BreezeSpeechSettings.VOICE_ORIGIN, BreezeSpeechSettings.VOICE_ORIGINS,
                        new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_any_origin), com.tokai.module.i18n.UiText.get(R.string.speech_origin_designed), com.tokai.module.i18n.UiText.get(R.string.speech_origin_cloned)}, "音色来源类型");
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_favorites_only), BreezeSpeechSettings.VOICE_FAVORITES_ONLY, new String[]{"false", "true"}, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_disabled), com.tokai.module.i18n.UiText.get(R.string.speech_enabled)}, "仅收藏音色");
                String[] sorts = "default".equals(speech.breezeSettings.voiceType) ? BreezeSpeechSettings.VOICE_SORTS : new String[]{"created_at_unix", "name"};
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_sort), BreezeSpeechSettings.VOICE_SORT, sorts,
                        "default".equals(speech.breezeSettings.voiceType) ? new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_created_at), com.tokai.module.i18n.UiText.get(R.string.speech_voice_name), com.tokai.module.i18n.UiText.get(R.string.speech_trending)} : new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_created_at), com.tokai.module.i18n.UiText.get(R.string.speech_voice_name)}, "音色排序");
                breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_sort_direction), BreezeSpeechSettings.VOICE_SORT_DIRECTION, BreezeSpeechSettings.VOICE_SORT_DIRECTIONS, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_ascending), com.tokai.module.i18n.UiText.get(R.string.speech_descending)}, "排序方向");
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_custom_tags), String.join("、", speech.breezeSettings.voiceTags), () -> editCatalogTags(false), "自定义标签");
                option(form, com.tokai.module.i18n.UiText.get(R.string.speech_refresh_voices), null, this::refreshVoices, "刷新音色");
                hint(form, "speech.breeze.voice.filters", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_filters_hint));
                if (breezeCatalog != null && breezeCatalog.matches(speech)) {
                    hint(form, "speech.breeze.catalog.status", breezeCatalog.status());
                }
            }
            return;
        }
        if (!speech.minimax) {
            hint(form, "speech.minimax.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_minimax_inactive));
            return;
        }
        if ("MiniMax 高级设置".equals(title)) renderMiniMaxAdvanced(form);
        else if ("音频输出".equals(title)) renderMiniMaxAudio(form);
        else if ("语言与发音".equals(title)) renderMiniMaxLanguage(form);
        else if ("混音与声音效果".equals(title)) renderMiniMaxEffects(form);
    }

    private void hint(LinearLayout form, String tag, String text) {
        TextView hint = new TextView(context);
        hint.setTag(tag);
        hint.setText(text);
        hint.setPadding(dp(4), dp(6), dp(4), dp(10));
        form.addView(hint, matchWrap());
    }

    private boolean canUseFish() { return !closed && settings().fish; }

    private void renderFishSection(String title, LinearLayout form, FishSpeechSettings fish) {
        if (FISH_ADVANCED.equals(title)) {
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_output), fish.format.toUpperCase(java.util.Locale.ROOT), () -> section(FISH_AUDIO), "音频输出");
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_generation_parameters), com.tokai.module.i18n.UiText.get(R.string.speech_generation_summary), () -> section(FISH_GENERATION), "生成参数");
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_language_pronunciation), com.tokai.module.i18n.UiText.get(R.string.speech_pronunciation_summary), () -> section(FISH_LANGUAGE), "语言与发音");
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_text_tags), com.tokai.module.i18n.UiText.get(R.string.speech_text_tags_summary), this::showFishTextTags, "文本标签说明");
            hint(form, "speech.fish.capabilities", com.tokai.module.i18n.UiText.get(R.string.speech_fish_capabilities_hint));
        } else if (FISH_AUDIO.equals(title)) {
            fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_format_api), FishSpeechSettings.FORMAT, FishSpeechSettings.FORMATS,
                    new String[]{"MP3", "WAV", "Ogg / Opus"}, "生成音频格式（API）");
            String[] sampleRates = "mp3".equals(fish.format) ? FishSpeechSettings.MP3_SAMPLE_RATES
                    : "opus".equals(fish.format) ? FishSpeechSettings.OPUS_SAMPLE_RATES : FishSpeechSettings.WAV_SAMPLE_RATES;
            String[] rates = new String[sampleRates.length + 1], labels = new String[rates.length];
            rates[0] = ""; labels[0] = com.tokai.module.i18n.UiText.get(R.string.speech_api_default);
            for (int index = 0; index < sampleRates.length; index++) {
                rates[index + 1] = sampleRates[index]; labels[index + 1] = sampleRates[index] + " Hz";
            }
            fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_sample_rate), FishSpeechSettings.SAMPLE_RATE, rates, labels, "采样率");
            if ("mp3".equals(fish.format)) {
                fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_mp3_bitrate), FishSpeechSettings.MP3_BITRATE, FishSpeechSettings.MP3_BITRATES,
                        java.util.Arrays.stream(FishSpeechSettings.MP3_BITRATES).map(value -> value + " kbps").toArray(String[]::new), "MP3 码率");
            } else if ("opus".equals(fish.format)) {
                fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_opus_bitrate), FishSpeechSettings.OPUS_BITRATE, FishSpeechSettings.OPUS_BITRATES,
                        java.util.Arrays.stream(FishSpeechSettings.OPUS_BITRATES)
                                .map(value -> "-1000".equals(value) ? com.tokai.module.i18n.UiText.get(R.string.speech_api_auto) : Integer.parseInt(value) / 1000 + " kbps").toArray(String[]::new), "Opus 码率");
            }
            hint(form, "speech.fish.audio.hint", com.tokai.module.i18n.UiText.get(R.string.speech_fish_audio_hint));
        } else if (FISH_GENERATION.equals(title)) {
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_temperature), FishSpeechSettings.TEMPERATURE, FishSpeechSettings.defaultValue(FishSpeechSettings.TEMPERATURE), false, "温度");
            editable(form, "Top P", FishSpeechSettings.TOP_P, FishSpeechSettings.defaultValue(FishSpeechSettings.TOP_P), false, "Top P");
            fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_latency_mode), FishSpeechSettings.LATENCY, FishSpeechSettings.LATENCIES,
                    new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_latency_normal), com.tokai.module.i18n.UiText.get(R.string.speech_latency_balanced), com.tokai.module.i18n.UiText.get(R.string.speech_latency_low)}, "延迟模式");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_chunk_length), FishSpeechSettings.CHUNK_LENGTH, FishSpeechSettings.defaultValue(FishSpeechSettings.CHUNK_LENGTH), false, "分段长度");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_min_chunk_length), FishSpeechSettings.MIN_CHUNK_LENGTH, FishSpeechSettings.defaultValue(FishSpeechSettings.MIN_CHUNK_LENGTH), false, "最短分段长度");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_max_tokens), FishSpeechSettings.MAX_NEW_TOKENS, FishSpeechSettings.defaultValue(FishSpeechSettings.MAX_NEW_TOKENS), false, "最大生成 Token");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_repetition_penalty), FishSpeechSettings.REPETITION_PENALTY, FishSpeechSettings.defaultValue(FishSpeechSettings.REPETITION_PENALTY), false, "重复惩罚");
            fishBoolean(form, com.tokai.module.i18n.UiText.get(R.string.speech_condition_previous_chunks), FishSpeechSettings.CONDITION_ON_PREVIOUS_CHUNKS, "关联前序分段");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_early_stop_threshold), FishSpeechSettings.EARLY_STOP_THRESHOLD, FishSpeechSettings.defaultValue(FishSpeechSettings.EARLY_STOP_THRESHOLD), false, "提前结束阈值");
            fishBoolean(form, com.tokai.module.i18n.UiText.get(R.string.speech_quality_guard), FishSpeechSettings.QUALITY_GUARD, "质量保护");
        } else if (FISH_LANGUAGE.equals(title)) {
            fishBoolean(form, com.tokai.module.i18n.UiText.get(R.string.speech_text_normalization), FishSpeechSettings.NORMALIZE, "文本归一化");
            if (fish.normalize) editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_normalization_language), FishSpeechSettings.LANGUAGE, FishSpeechSettings.defaultValue(FishSpeechSettings.LANGUAGE), false, "归一化语言");
            else hint(form, "speech.fish.normalization.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_normalization_inactive));
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_pronunciation_json), FishSpeechSettings.PRONUNCIATION_DICTIONARY,
                    FishSpeechSettings.defaultValue(FishSpeechSettings.PRONUNCIATION_DICTIONARY), false, "发音字典（JSON）");
            hint(form, "speech.fish.language.hint", com.tokai.module.i18n.UiText.get(R.string.speech_fish_language_hint));
            hint(form, "speech.fish.expression.hint", com.tokai.module.i18n.UiText.get(R.string.speech_fish_expression_hint));
        } else if (FISH_VOICES.equals(title)) {
            fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_scope), FishSpeechSettings.VOICE_SCOPE, FishSpeechSettings.VOICE_SCOPES,
                    new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_my_voices), com.tokai.module.i18n.UiText.get(R.string.speech_public_voices)}, "音色范围");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_search), FishSpeechSettings.VOICE_SEARCH, "", false, "音色搜索");
            String[] commonLanguages = {"zh", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it", "ar", "th", "vi", "id", "hi"};
            catalogMultipleChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_language), true, FishSpeechSettings.VOICE_LANGUAGE, commonLanguages, catalogLanguageLabels(commonLanguages), "音色语言");
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_custom_voice_languages), String.join("、", fish.voiceLanguages), () -> editCatalogLines(true, FishSpeechSettings.VOICE_LANGUAGE,
                    com.tokai.module.i18n.UiText.get(R.string.speech_voice_languages_per_line), com.tokai.module.i18n.UiText.get(R.string.speech_fish_voice_language_hint), "音色语言（每行一个）"), "自定义音色语言");
            catalogMultipleChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_title_language), true, FishSpeechSettings.VOICE_TITLE_LANGUAGE, commonLanguages, catalogLanguageLabels(commonLanguages), "标题语言");
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_custom_title_languages), String.join("、", fish.voiceTitleLanguages), () -> editCatalogLines(true, FishSpeechSettings.VOICE_TITLE_LANGUAGE,
                    com.tokai.module.i18n.UiText.get(R.string.speech_title_languages_per_line), com.tokai.module.i18n.UiText.get(R.string.speech_fish_title_language_hint), "标题语言（每行一个）"), "自定义标题语言");
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_custom_tags), String.join("、", fish.voiceTags), () -> editCatalogTags(true), "自定义标签");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_author_id), FishSpeechSettings.VOICE_AUTHOR_ID, "", false, "作者 ID");
            fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_licensed_only), FishSpeechSettings.VOICE_LICENSED, new String[]{"", "true"}, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_disabled), com.tokai.module.i18n.UiText.get(R.string.speech_enabled)}, "仅许可标记音色");
            fishChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_sort), FishSpeechSettings.VOICE_SORT, FishSpeechSettings.VOICE_SORTS, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_relevance), com.tokai.module.i18n.UiText.get(R.string.speech_task_count), com.tokai.module.i18n.UiText.get(R.string.speech_created_at)}, "音色排序");
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_refresh_voices), null, this::refreshVoices, "刷新音色");
            hint(form, "speech.fish.voices.hint", com.tokai.module.i18n.UiText.get(R.string.speech_fish_filters_hint));
            if (fishCatalog != null && fishCatalog.matches(settings())) {
                hint(form, "speech.fish.catalog.status", fishCatalog.status());
            }
        } else if (FISH_VOICE.equals(title)) {
            renderFishVoice(form, fish);
        }
    }

    private void fishBoolean(LinearLayout form, String title, String key, String stableTitle) {
        fishChoice(form, title, key, new String[]{"true", "false"}, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_enabled), com.tokai.module.i18n.UiText.get(R.string.speech_disabled)}, stableTitle);
    }

    private void renderFishVoice(LinearLayout form, FishSpeechSettings fish) {
        String[] modes = {com.tokai.module.i18n.UiText.get(R.string.speech_single_voice), com.tokai.module.i18n.UiText.get(R.string.speech_multi_speaker_voice), com.tokai.module.i18n.UiText.get(R.string.speech_reference_audio)};
        int active = java.util.Arrays.asList(FishSpeechSettings.VOICE_MODES).indexOf(fish.voiceMode);
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_source), active < 0 ? com.tokai.module.i18n.UiText.get(R.string.speech_select_again) : modes[active], () -> show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_voice_source))
                .setSingleChoiceItems(modes, active, (choice, index) -> {
                    if (!canUseFish()) return;
                    choice.dismiss();
                    if (index == 1) editFishSpeakers();
                    else if (index == 2) editFishReferences();
                    else {
                        store.putString(FishSpeechSettings.VOICE_MODE, "single");
                        settingChanged(FishSpeechSettings.VOICE_MODE); render();
                    }
                }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null), "音色来源");
        if ("multi".equals(fish.voiceMode)) {
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_speaker_list), com.tokai.module.i18n.UiText.get(R.string.speech_speaker_count, fish.referenceIds.size()), this::editFishSpeakers, "说话人列表");
        } else if ("references".equals(fish.voiceMode)) {
            int groups;
            try { groups = fish.referenceGroups().size(); } catch (RuntimeException invalid) { groups = 0; }
            option(form, com.tokai.module.i18n.UiText.get(R.string.speech_reference_samples), com.tokai.module.i18n.UiText.get(R.string.speech_speaker_groups, groups), this::editFishReferences, "参考音频样本");
        } else option(form, com.tokai.module.i18n.UiText.get(R.string.speech_single_voice), singleFishVoiceLabel(fish), this::selectVoice, "单音色");
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_library), "public".equals(fish.voiceScope) ? com.tokai.module.i18n.UiText.get(R.string.speech_public_voices) : com.tokai.module.i18n.UiText.get(R.string.speech_my_voices), () -> section(FISH_VOICES), "音色库");
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_text_tags), com.tokai.module.i18n.UiText.get(R.string.speech_speaker_expression_summary), this::showFishTextTags, "文本标签说明");
    }

    private String singleFishVoiceLabel(FishSpeechSettings fish) {
        return fish.referenceId.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_default_voice) : translatedVoiceLabel(fish.referenceId, fish.referenceName);
    }

    private void showFishTextTags() {
        if (!canUseFish()) return;
        String engine = settings().fishSettings.engine;
        String expression = "s1".equals(engine)
                ? com.tokai.module.i18n.UiText.get(R.string.speech_fish_s1_tags)
                : "drama-3".equals(engine)
                ? com.tokai.module.i18n.UiText.get(R.string.speech_fish_drama_tags)
                : com.tokai.module.i18n.UiText.get(R.string.speech_fish_s2_tags);
        show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_text_tags)).setMessage(com.tokai.module.i18n.UiText.get(R.string.speech_fish_tags_guide, expression))
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_got_it), null).create(), null);
    }

    private void editFishSpeakers() {
        if (!canUseFish()) return;
        List<String> ids = new ArrayList<>(settings().fishSettings.referenceIds);
        LinearLayout form = form();
        LinearLayout rows = form(); form.addView(rows, matchWrap());
        hint(form, "speech.fish.speakers.hint", com.tokai.module.i18n.UiText.get(R.string.speech_fish_speakers_hint));
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_multi_speaker_voice)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save_and_use), null).create();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            rows.removeAllViews();
            for (int index = 0; index < ids.size(); index++) {
                int position = index;
                option(rows, com.tokai.module.i18n.UiText.get(R.string.speech_speaker_number, index), cachedFishSpeakerLabel(ids.get(index)), () -> chooseFishSpeakerId(ids.get(position), value -> {
                    if (!closed && editor.isShowing() && canUseFish()) { ids.set(position, value); refresh[0].run(); }
                }), "说话人 " + index);
                android.widget.Button remove = button(rows, com.tokai.module.i18n.UiText.get(R.string.speech_remove_speaker_number, index), "speech.fish.speaker.remove." + index);
                remove.setOnClickListener(ignored -> { if (editor.isShowing() && canUseFish()) { ids.remove(position); refresh[0].run(); } });
            }
        };
        android.widget.Button add = button(form, com.tokai.module.i18n.UiText.get(R.string.speech_add_speaker), "speech.fish.speaker.add");
        add.setOnClickListener(ignored -> {
            if (!editor.isShowing() || !canUseFish()) return;
            if (ids.size() >= FishSpeechSettings.MAX_REFERENCE_GROUPS) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_speaker_limit)); return; }
            chooseFishSpeakerId("", value -> {
                if (editor.isShowing() && canUseFish()) { ids.add(value); refresh[0].run(); }
            });
        });
        refresh[0].run(); show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!editor.isShowing() || !canUseFish()) return;
            try {
                if (ids.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_speaker_required));
                String value = FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_IDS, new org.json.JSONArray(ids).toString());
                validateFishVoiceMode("multi", FishSpeechSettings.REFERENCE_IDS, value);
                store.putString(FishSpeechSettings.REFERENCE_IDS, value);
                store.putString(FishSpeechSettings.VOICE_MODE, "multi");
                settingChanged(FishSpeechSettings.VOICE_MODE); editor.dismiss(); render();
            } catch (IllegalArgumentException failure) { toast(error(failure)); }
        });
    }

    private void chooseFishSpeakerId(String current, Consumer<String> selected) {
        if (!canUseFish()) return;
        List<VoiceCatalog.Entry> cached = java.util.Collections.emptyList();
        try { cached = readCatalogSample(settings()); }
        catch (RuntimeException ignored) { }
        List<VoiceCatalog.Entry> entries = cached;
        String[] labels = new String[entries.size()];
        int checked = -1;
        for (int index = 0; index < entries.size(); index++) {
            labels[index] = catalogEntryLabel(entries.get(index));
            if (entries.get(index).id.equals(current)) checked = index;
        }
        AlertDialog.Builder picker = builder(com.tokai.module.i18n.UiText.get(R.string.speech_choose_speaker_voice)).setSingleChoiceItems(labels, checked, (choice, index) -> {
            if (!canUseFish()) return;
            selected.accept(entries.get(index).id); choice.dismiss();
        }).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_enter_manually), (choice, index) -> main.post(() -> editFishSpeakerId(current, selected)))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null);
        if (entries.isEmpty()) picker.setMessage(com.tokai.module.i18n.UiText.get(R.string.speech_speaker_no_cache));
        show(picker.create(), null);
    }

    private void editFishSpeakerId(String current, Consumer<String> selected) {
        if (!canUseFish()) return;
        LinearLayout form = form(); EditText id = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_reference_voice_id), current, false, "参考音色 ID");
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_speaker_voice_id)).setView(form).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!canUseFish() || !editor.isShowing()) return;
            try {
                String candidate = FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_ID, id.getText().toString());
                if (candidate.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_explicit_speaker_id_required));
                selected.accept(candidate); editor.dismiss();
            } catch (IllegalArgumentException failure) { id.setError(error(failure)); }
        });
    }

    private void validateFishVoiceMode(String mode, String key, String value) {
        Map<String, Object> values = speechValues(); values.put(FishSpeechSettings.VOICE_MODE, mode); values.put(key, value);
        new FishSpeechSettings(new LocalOptions(values)).validateOptions();
    }

    private void editFishReferences() {
        if (!canUseFish()) return;
        ReferenceEditorSession session = new ReferenceEditorSession();
        try {
            List<List<FishSpeechSettings.ReferenceSample>> groups = settings().fishSettings.referenceGroups();
            for (int group = 0; group < groups.size(); group++) for (FishSpeechSettings.ReferenceSample sample : groups.get(group)) {
                session.samples.add(new ReferenceDraft(group, sample.path, sample.text, sample.name));
            }
        } catch (RuntimeException ignored) { }
        LinearLayout form = form(); LinearLayout rows = form(); form.addView(rows, matchWrap());
        hint(form, "speech.fish.references.hint", com.tokai.module.i18n.UiText.get(R.string.speech_fish_references_hint));
        session.dialog = builder(com.tokai.module.i18n.UiText.get(R.string.speech_reference_samples)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save_and_use), null).create();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            rows.removeAllViews();
            for (int index = 0; index < session.samples.size(); index++) {
                ReferenceDraft sample = session.samples.get(index); int position = index;
                String caption = sample.name.isEmpty() ? new java.io.File(sample.path).getName() : sample.name;
                option(rows, com.tokai.module.i18n.UiText.get(R.string.speech_sample_speaker_number, index + 1, sample.group), caption + "\n" + sample.text,
                        () -> editFishReferenceSample(session, position, refresh[0]), "样本 " + (index + 1) + " · 说话人 " + sample.group);
                android.widget.Button remove = button(rows, com.tokai.module.i18n.UiText.get(R.string.speech_remove_sample_number, index + 1), "speech.fish.reference.remove." + index);
                remove.setOnClickListener(ignored -> { if (session.active()) { session.samples.remove(position); refresh[0].run(); } });
            }
        };
        android.widget.Button add = button(form, com.tokai.module.i18n.UiText.get(R.string.speech_add_reference), "speech.fish.reference.add");
        add.setOnClickListener(ignored -> {
            if (session.active()) editFishReferenceSample(session, -1, refresh[0]);
        });
        referenceEditors.add(session); refresh[0].run(); show(session.dialog, session::close);
        session.dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!session.active()) return;
            try {
                String value = encodeReferenceDrafts(session.samples);
                validateFishVoiceMode("references", FishSpeechSettings.REFERENCE_SAMPLES, value);
                store.putString(FishSpeechSettings.REFERENCE_SAMPLES, value);
                store.putString(FishSpeechSettings.VOICE_MODE, "references");
                settingChanged(FishSpeechSettings.VOICE_MODE); session.dialog.dismiss(); render();
            } catch (Exception failure) { toast(error(failure)); }
        });
    }

    private String encodeReferenceDrafts(List<ReferenceDraft> samples) throws Exception {
        if (samples.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_reference_required));
        int last = 0;
        long bytes = 0;
        for (ReferenceDraft sample : samples) {
            last = Math.max(last, sample.group);
            FishReferenceAudio.validateImported(context, sample.path);
            bytes += FishReferenceAudio.ownedFile(context, sample.path).length();
            if (bytes > FishReferenceAudio.MAX_REFERENCE_BYTES) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_reference_size_limit));
        }
        org.json.JSONArray groups = new org.json.JSONArray();
        for (int group = 0; group <= last; group++) {
            org.json.JSONArray entries = new org.json.JSONArray();
            for (ReferenceDraft sample : samples) if (sample.group == group) {
                org.json.JSONObject item = new org.json.JSONObject().put("path", sample.path).put("text", sample.text);
                if (!sample.name.isEmpty()) item.put("name", sample.name);
                entries.put(item);
            }
            if (entries.length() == 0) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_speaker_ids_contiguous));
            groups.put(entries);
        }
        return FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_SAMPLES, groups.toString());
    }

    private void editFishReferenceSample(ReferenceEditorSession session, int position, Runnable refresh) {
        if (!session.active()) return;
        ReferenceDraft old = position >= 0 ? session.samples.get(position) : new ReferenceDraft(0, "", "", "");
        LinearLayout form = form();
        EditText path = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_file), old.path, false, "音频文件"); path.setFocusable(false);
        EditText group = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_speaker_id), Integer.toString(old.group), false, "说话人编号");
        EditText transcript = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_sample_transcript), old.text, false, "样本原文");
        EditText name = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_sample_name_optional), old.name, false, "样本名称（可选）");
        android.widget.Button pick = button(form, com.tokai.module.i18n.UiText.get(R.string.speech_choose_audio), "speech.fish.reference.pick");
        boolean[] importing = {false}; HostImagePicker[] picker = new HostImagePicker[1];
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_reference_audio)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save_sample), null).create();
        pick.setOnClickListener(ignored -> {
            if (!session.active() || !editor.isShowing() || importing[0]) return;
            importing[0] = true; pick.setEnabled(false); editor.getButton(Dialog.BUTTON_POSITIVE).setEnabled(false);
            picker[0] = HostAudioPicker.launch(ownerActivity(context), imported -> {
                importing[0] = false;
                if (!session.active() || !editor.isShowing()) { if (imported != null) deleteDraftImport(imported); return; }
                pick.setEnabled(true); editor.getButton(Dialog.BUTTON_POSITIVE).setEnabled(true);
                if (imported != null) {
                    try {
                        FishReferenceAudio.validateImported(context, imported);
                        session.imported.add(imported); path.setText(imported);
                    } catch (Exception failure) { deleteDraftImport(imported); toast(error(failure)); }
                }
            });
            if (picker[0] != null) session.pickers.add(picker[0]);
            else {
                importing[0] = false; pick.setEnabled(true); editor.getButton(Dialog.BUTTON_POSITIVE).setEnabled(true);
                toast(com.tokai.module.i18n.UiText.get(R.string.speech_picker_unavailable));
            }
        });
        show(editor, () -> { if (picker[0] != null) { picker[0].close(); session.pickers.remove(picker[0]); } });
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!session.active() || !editor.isShowing() || importing[0]) return;
            try {
                int speaker = Integer.parseInt(group.getText().toString().trim());
                if (speaker < 0 || speaker >= FishSpeechSettings.MAX_REFERENCE_GROUPS) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_speaker_id_range));
                if (path.getText().toString().isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_choose_reference_required));
                FishReferenceAudio.validateImported(context, path.getText().toString());
                if (transcript.getText().toString().trim().isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_sample_transcript_required));
                ReferenceDraft draft = new ReferenceDraft(speaker, path.getText().toString(), transcript.getText().toString(), name.getText().toString().trim());
                List<ReferenceDraft> candidate = new ArrayList<>(session.samples);
                if (position < 0) candidate.add(draft); else candidate.set(position, draft);
                encodeReferenceDrafts(candidate);
                if (position < 0) session.samples.add(draft); else session.samples.set(position, draft);
                editor.dismiss(); refresh.run();
            } catch (Exception failure) { transcript.setError(failure instanceof NumberFormatException ? com.tokai.module.i18n.UiText.get(R.string.speech_speaker_id_invalid) : error(failure)); }
        });
    }

    private static Activity ownerActivity(Context context) {
        for (int depth = 0; depth < 16 && context != null; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext(); if (next == context) return null; context = next;
        }
        return null;
    }

    private void deleteDraftImport(String path) {
        try {
            java.io.File file = new java.io.File(path).getCanonicalFile();
            java.io.File owned = new java.io.File(context.getFilesDir(), "tokai-voice-source").getCanonicalFile();
            if (!owned.equals(file.getParentFile())) return;
            for (List<FishSpeechSettings.ReferenceSample> group : settings().fishSettings.referenceGroups()) {
                for (FishSpeechSettings.ReferenceSample sample : group) if (new java.io.File(sample.path).getCanonicalFile().equals(file)) return;
            }
            java.nio.file.Files.deleteIfExists(file.toPath());
        } catch (Exception ignored) { }
    }

    private static final class ReferenceDraft {
        final int group;
        final String path, text, name;
        ReferenceDraft(int group, String path, String text, String name) {
            this.group = group; this.path = path; this.text = text; this.name = name;
        }
    }

    private final class ReferenceEditorSession {
        final List<ReferenceDraft> samples = new ArrayList<>();
        final List<String> imported = new ArrayList<>();
        final List<HostImagePicker> pickers = new ArrayList<>();
        AlertDialog dialog;
        boolean open = true;
        boolean active() { return open && canUseFish() && dialog != null && dialog.isShowing(); }
        void close() {
            if (!open) return;
            open = false;
            for (HostImagePicker picker : pickers) picker.close();
            pickers.clear();
            for (String path : imported) deleteDraftImport(path);
            referenceEditors.remove(this);
        }
    }

    private void fishChoice(LinearLayout form, String title, String key, String[] choices, String[] labels, String stableTitle) {
        String current = store.getString(key, FishSpeechSettings.defaultValue(key));
        if (FishSpeechSettings.VOICE_LICENSED.equals(key) && "false".equals(current)) current = "";
        int selected = java.util.Arrays.asList(choices).indexOf(current);
        option(form, title, selected < 0 ? com.tokai.module.i18n.UiText.get(R.string.speech_select_again) : labels[selected], () -> {
            if (!canUseFish()) return;
            show(builder(title)
                .setSingleChoiceItems(labels, selected, (choice, index) -> {
                    if (!canUseFish()) return;
                    try {
                        String candidate = FishSpeechSettings.validateValue(key, choices[index]);
                        validateFishChange(key, candidate);
                        store.putString(key, candidate); settingChanged(key);
                        choice.dismiss(); render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null);
        }, stableTitle);
    }

    private void validateFishChange(String key, String candidate) {
        Map<String, Object> values = speechValues(); values.put(key, candidate);
        new FishSpeechSettings(new LocalOptions(values)).validateOptions();
    }

    private void selectFishModel() {
        if (!canUseFish()) return;
        String[] models = FishSpeechSettings.ENGINES;
        String[] labels = new String[models.length];
        for (int index = 0; index < models.length; index++) {
            labels[index] = "s2.1-pro".equals(models[index]) ? com.tokai.module.i18n.UiText.get(R.string.speech_model_recommended, models[index])
                    : "s1".equals(models[index]) ? com.tokai.module.i18n.UiText.get(R.string.speech_model_legacy, models[index]) : models[index];
        }
        show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_engine)).setSingleChoiceItems(labels,
                java.util.Arrays.asList(models).indexOf(settings().fishSettings.engine), (choice, index) -> {
            if (!canUseFish()) return;
            try {
                validateFishChange(FishSpeechSettings.ENGINE, models[index]);
                store.putString(FishSpeechSettings.ENGINE, models[index]);
                settingChanged(FishSpeechSettings.ENGINE); choice.dismiss(); render();
            } catch (IllegalArgumentException failure) { toast(error(failure)); }
        }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_close), null).create(), null);
    }

    private boolean validateFishCatalog(FishSpeechSettings settings) {
        try {
            if (!settings.hasCatalogFilter()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_fish_catalog_filter_required));
            if ("self".equals(settings.voiceScope) && settings.apiKey.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_fish_key_required));
            FishSpeechSettings.validateValue(FishSpeechSettings.API_KEY, settings.apiKey);
            FishSpeechSettings.validateValue(FishSpeechSettings.BASE_URL, settings.baseUrl);
            return true;
        } catch (IllegalArgumentException failure) { toast(error(failure)); return false; }
    }

    private void selectBreezeModel() {
        if (!canUseBreeze()) return;
        List<String> models = new ArrayList<>();
        String source = store.getString(BreezeSpeechSettings.MODEL_SOURCE, "built_in");
        try {
            String sample = store.getString(BreezeSpeechSettings.MODEL_SAMPLE, "[]");
            if (sample.length() > 2 * 1024 * 1024) throw new IllegalArgumentException();
            org.json.JSONArray cached = new org.json.JSONArray(sample);
            if (cached.length() > 256) throw new IllegalArgumentException();
            for (int index = 0; index < cached.length(); index++) {
                String model = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, cached.getString(index));
                if (!models.contains(model)) models.add(model);
            }
        } catch (Exception ignored) { models.clear(); }
        if (models.isEmpty()) {
            models.addAll(java.util.Arrays.asList(BreezeSpeechSettings.BUILT_IN_MODELS));
            source = "built_in";
        }
        chooseBreezeModels(models, source);
    }

    private boolean canUseBreeze() { return !closed && settings().breeze; }

    private void chooseBreezeModels(List<String> catalog, String source) {
        if (!canUseBreeze()) return;
        List<String> models = new ArrayList<>(catalog);
        if (!models.contains(BreezeSpeechSettings.AUTO_MODEL)) models.add(0, BreezeSpeechSettings.AUTO_MODEL);
        String current = settings().breezeSettings.model;
        boolean manual = !current.isEmpty() && !models.contains(current);
        if (manual) models.add(0, current);
        String[] labels = models.toArray(new String[0]);
        for (int index = 0; index < labels.length; index++) if (BreezeSpeechSettings.AUTO_MODEL.equals(labels[index])) labels[index] = com.tokai.module.i18n.UiText.get(R.string.speech_auto_language_model);
        if (manual) labels[0] = com.tokai.module.i18n.UiText.get(R.string.speech_model_current_unlisted, labels[0]);
        show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_speech_models_source, "api".equals(source) ? com.tokai.module.i18n.UiText.get(R.string.speech_api_catalog) : com.tokai.module.i18n.UiText.get(R.string.speech_built_in_docs_catalog)))
                .setSingleChoiceItems(labels, models.indexOf(current), (choice, index) -> {
                    if (!canUseBreeze()) return;
                    try { validateBreezeChange(BreezeSpeechSettings.MODEL, models.get(index)); }
                    catch (IllegalArgumentException failure) { toast(error(failure)); return; }
                    store.putString(BreezeSpeechSettings.MODEL, models.get(index));
                    settingChanged(BreezeSpeechSettings.MODEL);
                    choice.dismiss();
                    render();
                }).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_refresh), (choice, index) -> main.post(this::refreshBreezeModels))
                .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_enter_manually), (choice, index) -> main.post(this::manualBreezeModel))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_close), null).create(), null);
    }

    private void manualBreezeModel() {
        if (!canUseBreeze()) return;
        LinearLayout form = form();
        EditText value = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_speech_model), settings().breezeSettings.model, false, "语音模型");
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_speech_model)).setView(form).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!canUseBreeze()) return;
            try {
                String candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, value.getText().toString());
                validateBreezeChange(BreezeSpeechSettings.MODEL, candidate);
                store.putString(BreezeSpeechSettings.MODEL, candidate);
                settingChanged(BreezeSpeechSettings.MODEL);
                editor.dismiss();
                render();
            } catch (IllegalArgumentException failure) { value.setError(error(failure)); }
        });
    }

    private void refreshBreezeModels() {
        if (!canUseBreeze()) return;
        if (fetch != null) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_fetching)); return; }
        BreezeSpeechSettings settings = settings().breezeSettings;
        if (!validateBreezeCatalog(settings)) return;
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request;
        toast(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_models_fetching));
        try {
            future = WORKER.submit(() -> {
                try {
                    List<String> models = BreezeSpeechClient.fetchModels(request, settings);
                    if (models.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_models_empty));
                    org.json.JSONArray catalog = new org.json.JSONArray();
                    for (String model : models) catalog.put(BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, model));
                    String sample = catalog.toString();
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null; future = null;
                        if (!canUseBreeze()) return;
                        store.putString(BreezeSpeechSettings.MODEL_SAMPLE, sample);
                        store.putString(BreezeSpeechSettings.MODEL_SOURCE, "api");
                        render();
                        chooseBreezeModels(models, "api");
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null; future = null;
                        toast(com.tokai.module.i18n.UiText.get(R.string.speech_models_kept_manual, error(failure)));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null; request.close(); toast(com.tokai.module.i18n.UiText.get(R.string.speech_model_queue_full));
        }
    }

    private boolean validateBreezeCatalog(BreezeSpeechSettings settings) {
        try {
            if (settings.apiKey.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_key_required));
            BreezeSpeechSettings.validateValue(BreezeSpeechSettings.API_KEY, settings.apiKey);
            BreezeSpeechSettings.validateValue(BreezeSpeechSettings.BASE_URL, settings.baseUrl);
            return true;
        } catch (IllegalArgumentException failure) { toast(error(failure)); return false; }
    }

    private boolean validateBreezeVoiceCatalog(BreezeSpeechSettings settings) {
        if (!settings.hasCatalogFilter()) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_catalog_filter_required)); return false; }
        return validateBreezeCatalog(settings);
    }

    private static String[] catalogLanguageLabels(String[] codes) {
        Map<String, String> known = new LinkedHashMap<>();
        String[][] names = {{"zh", com.tokai.module.i18n.UiText.get(R.string.speech_language_chinese)}, {"en", com.tokai.module.i18n.UiText.get(R.string.speech_language_english)}, {"ja", com.tokai.module.i18n.UiText.get(R.string.speech_language_japanese)}, {"ko", com.tokai.module.i18n.UiText.get(R.string.speech_language_korean)}, {"fr", com.tokai.module.i18n.UiText.get(R.string.speech_language_french)},
                {"de", com.tokai.module.i18n.UiText.get(R.string.speech_language_german)}, {"es", com.tokai.module.i18n.UiText.get(R.string.speech_language_spanish)}, {"ru", com.tokai.module.i18n.UiText.get(R.string.speech_language_russian)}, {"pt", com.tokai.module.i18n.UiText.get(R.string.speech_language_portuguese)}, {"ar", com.tokai.module.i18n.UiText.get(R.string.speech_language_arabic)},
                {"it", com.tokai.module.i18n.UiText.get(R.string.speech_language_italian)}, {"th", com.tokai.module.i18n.UiText.get(R.string.speech_language_thai)}, {"vi", com.tokai.module.i18n.UiText.get(R.string.speech_language_vietnamese)}, {"id", com.tokai.module.i18n.UiText.get(R.string.speech_language_indonesian)}, {"hi", com.tokai.module.i18n.UiText.get(R.string.speech_language_hindi)},
                {"af", com.tokai.module.i18n.UiText.get(R.string.speech_language_afrikaans)}, {"bg", com.tokai.module.i18n.UiText.get(R.string.speech_language_bulgarian)}, {"bn", com.tokai.module.i18n.UiText.get(R.string.speech_language_bengali)}, {"bs", com.tokai.module.i18n.UiText.get(R.string.speech_language_bosnian)}, {"ca", com.tokai.module.i18n.UiText.get(R.string.speech_language_catalan)},
                {"cs", com.tokai.module.i18n.UiText.get(R.string.speech_language_czech)}, {"da", com.tokai.module.i18n.UiText.get(R.string.speech_language_danish)}, {"el", com.tokai.module.i18n.UiText.get(R.string.speech_language_greek)}, {"et", com.tokai.module.i18n.UiText.get(R.string.speech_language_estonian)}, {"eu", com.tokai.module.i18n.UiText.get(R.string.speech_language_basque)},
                {"fa", com.tokai.module.i18n.UiText.get(R.string.speech_language_persian)}, {"fi", com.tokai.module.i18n.UiText.get(R.string.speech_language_finnish)}, {"gl", com.tokai.module.i18n.UiText.get(R.string.speech_language_galician)}, {"he", com.tokai.module.i18n.UiText.get(R.string.speech_language_hebrew)}, {"hu", com.tokai.module.i18n.UiText.get(R.string.speech_language_hungarian)},
                {"is", com.tokai.module.i18n.UiText.get(R.string.speech_language_icelandic)}, {"kn", com.tokai.module.i18n.UiText.get(R.string.speech_language_kannada)}, {"lt", com.tokai.module.i18n.UiText.get(R.string.speech_language_lithuanian)}, {"mr", com.tokai.module.i18n.UiText.get(R.string.speech_language_marathi)}, {"ms", com.tokai.module.i18n.UiText.get(R.string.speech_language_malay)},
                {"ne", com.tokai.module.i18n.UiText.get(R.string.speech_language_nepali)}, {"nl", com.tokai.module.i18n.UiText.get(R.string.speech_language_dutch)}, {"no", com.tokai.module.i18n.UiText.get(R.string.speech_language_norwegian)}, {"pl", com.tokai.module.i18n.UiText.get(R.string.speech_language_polish)}, {"ro", com.tokai.module.i18n.UiText.get(R.string.speech_language_romanian)},
                {"sk", com.tokai.module.i18n.UiText.get(R.string.speech_language_slovak)}, {"sl", com.tokai.module.i18n.UiText.get(R.string.speech_language_slovenian)}, {"sq", com.tokai.module.i18n.UiText.get(R.string.speech_language_albanian)}, {"sv", com.tokai.module.i18n.UiText.get(R.string.speech_language_swedish)}, {"sw", com.tokai.module.i18n.UiText.get(R.string.speech_language_swahili)},
                {"ta", com.tokai.module.i18n.UiText.get(R.string.speech_language_tamil)}, {"te", com.tokai.module.i18n.UiText.get(R.string.speech_language_telugu)}, {"tl", com.tokai.module.i18n.UiText.get(R.string.speech_language_filipino)}, {"tr", com.tokai.module.i18n.UiText.get(R.string.speech_language_turkish)}, {"uk", com.tokai.module.i18n.UiText.get(R.string.speech_language_ukrainian)}, {"ur", com.tokai.module.i18n.UiText.get(R.string.speech_language_urdu)}};
        for (String[] pair : names) known.put(pair[0], pair[1]);
        String[] labels = new String[codes.length];
        for (int index = 0; index < codes.length; index++) labels[index] = codes[index].isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_any_language)
                : known.containsKey(codes[index]) ? known.get(codes[index]) + " · " + codes[index] : codes[index];
        return labels;
    }

    private void editCatalogTags(boolean fish) {
        if (fish ? !canUseFish() : !canUseBreeze()) return;
        List<String> current = fish ? settings().fishSettings.voiceTags : settings().breezeSettings.voiceTags;
        LinearLayout form = form(); EditText tags = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_tags_per_line), String.join("\n", current), false, "自定义标签（每行一个）");
        hint(form, "voice.catalog.tags.hint", com.tokai.module.i18n.UiText.get(R.string.speech_catalog_tags_hint));
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_custom_tags)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!editor.isShowing() || (fish ? !canUseFish() : !canUseBreeze())) return;
            try {
                org.json.JSONArray values = new org.json.JSONArray();
                for (String line : tags.getText().toString().split("\\R")) if (!line.trim().isEmpty()) values.put(line.trim());
                String key = fish ? FishSpeechSettings.VOICE_TAGS : BreezeSpeechSettings.VOICE_TAGS;
                String value = fish ? FishSpeechSettings.validateValue(key, values.toString()) : BreezeSpeechSettings.validateValue(key, values.toString());
                store.putString(key, value); settingChanged(key); editor.dismiss(); render();
            } catch (IllegalArgumentException failure) { tags.setError(error(failure)); }
        });
    }

    private void catalogMultipleChoice(LinearLayout form, String title, boolean fish, String key, String[] values, String[] labels, String stableTitle) {
        List<String> saved = catalogSavedValues(store.getString(key, fish ? FishSpeechSettings.defaultValue(key) : BreezeSpeechSettings.defaultValue(key)));
        List<String> options = new ArrayList<>(), text = new ArrayList<>();
        for (int index = 0; index < values.length; index++) if (!values[index].isEmpty()) { options.add(values[index]); text.add(labels[index]); }
        List<String> chosenLabels = new ArrayList<>();
        for (String value : saved) { int index = options.indexOf(value); chosenLabels.add(index < 0 ? value : text.get(index)); }
        option(form, title, chosenLabels.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_any_multiple) : String.join("、", chosenLabels), () -> {
            boolean[] checked = new boolean[options.size()];
            for (int index = 0; index < checked.length; index++) checked[index] = saved.contains(options.get(index));
            AlertDialog choice = builder(com.tokai.module.i18n.UiText.get(R.string.speech_multiple_title, title)).setMultiChoiceItems(text.toArray(new String[0]), checked,
                    (dialog, index, selected) -> checked[index] = selected).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null)
                    .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_clear_and_save), null).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create();
            show(choice, null);
            choice.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                org.json.JSONArray selected = new org.json.JSONArray();
                for (int index = 0; index < checked.length; index++) if (checked[index]) selected.put(options.get(index));
                saveCatalogValue(fish, key, selected.toString(), choice, null);
            });
            choice.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener(ignored -> saveCatalogValue(fish, key, "[]", choice, null));
        }, stableTitle);
    }

    private static List<String> catalogSavedValues(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return result;
        try {
            if (value.trim().startsWith("[")) {
                org.json.JSONArray array = new org.json.JSONArray(value);
                for (int index = 0; index < array.length(); index++) if (array.opt(index) instanceof String && !array.getString(index).isEmpty()) result.add(array.getString(index));
            } else result.add(value.trim());
        } catch (Exception ignored) { }
        return result;
    }

    private void editCatalogLines(boolean fish, String key, String title, String guidance, String stableTitle) {
        if (fish ? !canUseFish() : !canUseBreeze()) return;
        LinearLayout form = form();
        EditText value = input(form, title, String.join("\n", catalogSavedValues(store.getString(key,
                fish ? FishSpeechSettings.defaultValue(key) : BreezeSpeechSettings.defaultValue(key)))), false, stableTitle);
        hint(form, "voice.catalog.lines.hint", guidance);
        AlertDialog editor = builder(title).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            org.json.JSONArray selected = new org.json.JSONArray();
            for (String line : value.getText().toString().split("\\R")) if (!line.trim().isEmpty()) selected.put(line.trim());
            saveCatalogValue(fish, key, selected.toString(), editor, value);
        });
    }

    private void saveCatalogValue(boolean fish, String key, String candidate, AlertDialog editor, EditText input) {
        if (!editor.isShowing() || (fish ? !canUseFish() : !canUseBreeze())) return;
        try {
            String value = fish ? FishSpeechSettings.validateValue(key, candidate) : BreezeSpeechSettings.validateValue(key, candidate);
            if (fish) validateFishChange(key, value); else validateBreezeChange(key, value);
            store.putString(key, value); settingChanged(key); editor.dismiss(); render();
        } catch (IllegalArgumentException failure) { if (input != null) input.setError(error(failure)); else toast(error(failure)); }
    }

    private static String[] namedCatalogValues(String[] values, String[] names) {
        String[] labels = new String[values.length];
        for (int index = 0; index < values.length; index++) labels[index] = values[index].isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_any)
                : names[index] + " · " + values[index];
        return labels;
    }

    private void breezeChoice(LinearLayout form, String title, String key, String[] choices, String[] labels, String stableTitle) {
        String current = store.getString(key, BreezeSpeechSettings.defaultValue(key));
        int selected = java.util.Arrays.asList(choices).indexOf(current);
        option(form, title, selected < 0 ? com.tokai.module.i18n.UiText.get(R.string.speech_select_again) : labels[selected], () -> show(builder(title)
                .setSingleChoiceItems(labels, selected, (choice, index) -> {
                    if (!canUseBreeze()) return;
                    try {
                        String value = BreezeSpeechSettings.validateValue(key, choices[index]);
                        validateBreezeChange(key, value);
                        store.putString(key, value);
                        settingChanged(key);
                        choice.dismiss();
                        render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null), stableTitle);
    }

    private void validateBreezeChange(String key, String value) {
        Map<String, Object> values = speechValues();
        values.put(key, value);
        new BreezeSpeechSettings(new LocalOptions(values)).validateOptions();
    }

    private void editBreezeInstruction() {
        if (!canUseBreeze()) return;
        stopEnhancement();
        LinearLayout form = form();
        EditText value = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_instructions), settings().breezeSettings.instructions, false, "语音指令");
        hint(form, "speech.breeze.instruction.hint", com.tokai.module.i18n.UiText.get(R.string.speech_instruction_hint));
        TextView status = new TextView(context); status.setTag("speech.breeze.enhance.status"); form.addView(status, matchWrap());
        android.widget.Button enhance = button(form, com.tokai.module.i18n.UiText.get(R.string.speech_enhance_instruction), "speech.breeze.enhance");
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_instructions)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        instructionEditor = editor;
        value.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (enhancement != null) { stopEnhancement(); enhance.setEnabled(true); status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_enhancement_draft_changed)); }
            }
            public void afterTextChanged(Editable text) { }
        });
        enhance.setOnClickListener(ignored -> {
            if (!canUseBreeze() || !editor.isShowing() || instructionEditor != editor) return;
            String draft = value.getText().toString();
            if (draft.trim().isEmpty()) { value.setError(com.tokai.module.i18n.UiText.get(R.string.speech_instruction_required)); return; }
            BreezeSpeechSettings settings = settings().breezeSettings;
            if (!validateBreezeCatalog(settings)) return;
            stopEnhancement();
            long generation = enhancementGeneration;
            SpeechHttpClient.Request request = new SpeechHttpClient.Request(); enhancement = request;
            enhance.setEnabled(false); status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_enhancing));
            try {
                enhancementFuture = WORKER.submit(() -> {
                    try {
                        String optimized = BreezeSpeechClient.enhanceInstruction(request, settings, draft);
                        main.post(() -> {
                            if (enhancement != request || generation != enhancementGeneration || !canUseBreeze()
                                    || !editor.isShowing() || !value.getText().toString().equals(draft)) return;
                            enhancement = null; enhancementFuture = null;
                            value.setText(optimized); enhance.setEnabled(true); status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_enhancement_ready));
                        });
                    } catch (Exception failure) {
                        main.post(() -> {
                            if (enhancement != request || generation != enhancementGeneration || !canUseBreeze() || !editor.isShowing()) return;
                            enhancement = null; enhancementFuture = null; enhance.setEnabled(true); status.setText(error(failure));
                        });
                    } finally { request.close(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                enhancement = null; request.close(); enhance.setEnabled(true); status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_request_queue_full));
            }
        });
        show(editor, () -> { if (instructionEditor == editor) { stopEnhancement(); instructionEditor = null; } });
        editor.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!canUseBreeze() || !editor.isShowing() || instructionEditor != editor) return;
            try {
                String candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.INSTRUCTIONS, value.getText().toString());
                validateBreezeChange(BreezeSpeechSettings.INSTRUCTIONS, candidate);
                store.putString(BreezeSpeechSettings.INSTRUCTIONS, candidate);
                settingChanged(BreezeSpeechSettings.INSTRUCTIONS); editor.dismiss(); render();
            } catch (IllegalArgumentException failure) { value.setError(error(failure)); }
        });
    }

    private void stopEnhancement() {
        enhancementGeneration++;
        SpeechHttpClient.Request request = enhancement; enhancement = null;
        if (request != null) request.cancel();
        if (enhancementFuture != null) enhancementFuture.cancel(true);
        enhancementFuture = null;
    }

    private void renderBreezeAudio(LinearLayout form) {
        String[] profile = settings().breezeSettings.outputFormat.split("_", -1);
        String codec = profile[0];
        breezeOutputChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_format_api), 0, BreezeSpeechSettings.FORMATS,
                java.util.Arrays.stream(BreezeSpeechSettings.FORMATS)
                        .map(value -> "opus".equals(value) ? "Ogg / Opus" : value.toUpperCase(java.util.Locale.ROOT)).toArray(String[]::new), "生成音频格式（API）");
        String[] sampleRates = "opus".equals(codec) ? BreezeSpeechSettings.OPUS_SAMPLE_RATES : BreezeSpeechSettings.SAMPLE_RATES;
        String[] rates = new String[sampleRates.length + 1], rateLabels = new String[rates.length];
        rates[0] = ""; rateLabels[0] = com.tokai.module.i18n.UiText.get(R.string.speech_default_sample_rate);
        for (int index = 0; index < sampleRates.length; index++) {
            rates[index + 1] = sampleRates[index]; rateLabels[index + 1] = sampleRates[index] + " Hz";
        }
        breezeOutputChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_sample_rate), 1, rates, rateLabels, "采样率");
        if ("mp3".equals(codec) || "aac".equals(codec) || "opus".equals(codec)) {
            int rate = 24000;
            if (profile.length > 1) try { rate = Integer.parseInt(profile[1]); } catch (NumberFormatException ignored) { }
            String[] candidates = "mp3".equals(codec)
                    ? rate >= 32000 ? BreezeSpeechSettings.MP3_HIGH_BITRATES : BreezeSpeechSettings.MP3_LOW_BITRATES
                    : "aac".equals(codec) ? new String[]{"8", "16", "32", "64", "96", "128", "160", "192", "256", "320"}
                    : new String[]{"6", "16", "24", "32", "48", "64", "96", "128", "192", "256", "510"};
            String[] bitrates = new String[candidates.length + 1];
            String[] bitrateLabels = new String[bitrates.length];
            bitrates[0] = ""; bitrateLabels[0] = com.tokai.module.i18n.UiText.get(R.string.speech_api_default);
            for (int index = 0; index < candidates.length; index++) {
                bitrates[index + 1] = candidates[index];
                bitrateLabels[index + 1] = candidates[index] + " kbps";
            }
            breezeOutputChoice(form, "mp3".equals(codec) ? com.tokai.module.i18n.UiText.get(R.string.speech_mp3_bitrate) : "aac".equals(codec) ? com.tokai.module.i18n.UiText.get(R.string.speech_aac_bitrate) : com.tokai.module.i18n.UiText.get(R.string.speech_opus_bitrate),
                    2, bitrates, bitrateLabels, "mp3".equals(codec) ? "MP3 码率" : "aac".equals(codec) ? "AAC 码率" : "Opus 码率");
        }
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_output_format_manual), BreezeSpeechSettings.OUTPUT_FORMAT, BreezeSpeechSettings.DEFAULT_OUTPUT_FORMAT, false, "手动填写输出格式");
        breezeChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_delivery_method), BreezeSpeechSettings.DELIVERY,
                new String[]{"sync", "stream", "async"}, new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_delivery_sync), com.tokai.module.i18n.UiText.get(R.string.speech_delivery_stream), com.tokai.module.i18n.UiText.get(R.string.speech_delivery_async)}, "生成返回方式");
        hint(form, "speech.breeze.channel.hint", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_channel_hint));
        hint(form, "speech.breeze.format.hint", com.tokai.module.i18n.UiText.get(R.string.speech_breeze_format_hint));
    }

    private void breezeOutputChoice(LinearLayout form, String title, int part, String[] choices, String[] labels, String stableTitle) {
        String[] current = settings().breezeSettings.outputFormat.split("_", -1);
        String value = current.length > part ? current[part] : "";
        int selected = java.util.Arrays.asList(choices).indexOf(value);
        option(form, title, selected < 0 ? com.tokai.module.i18n.UiText.get(R.string.speech_manual_value, value) : labels[selected], () -> show(builder(title)
                .setSingleChoiceItems(labels, selected, (choice, index) -> {
                    if (!canUseBreeze()) return;
                    String[] profile = settings().breezeSettings.outputFormat.split("_", -1);
                    String codec = part == 0 ? choices[index] : profile[0];
                    String rate = part == 1 ? choices[index] : profile.length > 1 ? profile[1] : "";
                    String bitrate = part == 2 ? choices[index] : profile.length > 2 ? profile[2] : "";
                    if (rate.isEmpty() && !bitrate.isEmpty()) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_bitrate_requires_sample_rate)); return; }
                    String candidate = codec + (rate.isEmpty() ? "" : "_" + rate) + (bitrate.isEmpty() ? "" : "_" + bitrate);
                    try {
                        candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.OUTPUT_FORMAT, candidate);
                        validateBreezeChange(BreezeSpeechSettings.OUTPUT_FORMAT, candidate);
                        store.putString(BreezeSpeechSettings.OUTPUT_FORMAT, candidate);
                        settingChanged(BreezeSpeechSettings.OUTPUT_FORMAT);
                        choice.dismiss(); render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null), stableTitle);
    }

    private void selectMiniMaxModel() {
        List<String> models = new ArrayList<>();
        String source = store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, "built_in");
        try {
            String sample = store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "[]");
            if (sample.length() > 2 * 1024 * 1024) throw new IllegalArgumentException();
            org.json.JSONArray cached = new org.json.JSONArray(sample);
            if (cached.length() > 256) throw new IllegalArgumentException();
            for (int index = 0; index < cached.length(); index++) {
                String model = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_MODEL, cached.getString(index));
                if (!models.contains(model)) models.add(model);
            }
        } catch (Exception ignored) { models.clear(); }
        if (models.isEmpty()) {
            models.addAll(MiniMaxSpeechModelCatalog.builtInModels());
            source = "built_in";
        }
        chooseMiniMaxModels(models, source);
    }

    private void chooseMiniMaxModels(List<String> catalog, String source) {
        List<String> models = new ArrayList<>(catalog);
        String current = settings().minimaxModel;
        boolean manual = !current.isEmpty() && !models.contains(current);
        if (manual) models.add(0, current);
        String[] labels = models.toArray(new String[0]);
        for (int index = 0; index < labels.length; index++) if (BreezeSpeechSettings.AUTO_MODEL.equals(labels[index])) labels[index] = com.tokai.module.i18n.UiText.get(R.string.speech_auto_language_model);
        if (manual) labels[0] = com.tokai.module.i18n.UiText.get(R.string.speech_model_current_unlisted, labels[0]);
        Map<String, Object> values = speechValues();
        for (int index = 0; index < models.size(); index++) {
            String issue = MiniMaxSpeechOptions.candidateIssue(values, SpeechSettings.MINIMAX_MODEL, models.get(index));
            if (!issue.isEmpty()) labels[index] = com.tokai.module.i18n.UiText.get(R.string.speech_candidate_unavailable, labels[index], issue);
        }
        String sourceLabel = "api".equals(source) ? com.tokai.module.i18n.UiText.get(R.string.speech_api_catalog) : "document".equals(source) ? com.tokai.module.i18n.UiText.get(R.string.speech_official_docs_catalog) : com.tokai.module.i18n.UiText.get(R.string.speech_built_in_official_catalog);
        show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_models_source, sourceLabel)).setSingleChoiceItems(labels, models.indexOf(current), (choice, index) -> {
            try { validateMiniMaxChange(SpeechSettings.MINIMAX_MODEL, models.get(index)); }
            catch (IllegalArgumentException failure) { toast(error(failure)); return; }
            store.putString(SpeechSettings.MINIMAX_MODEL, models.get(index));
            settingChanged(SpeechSettings.MINIMAX_MODEL);
            choice.dismiss();
            render();
        }).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_refresh), (choice, index) -> main.post(this::chooseMiniMaxRefreshSource))
                .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_enter_manually), (choice, index) -> main.post(this::manualMiniMaxModel))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_close), null).create(), null);
    }

    private void chooseMiniMaxRefreshSource() {
        if (closed) return;
        show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_refresh_models)).setItems(new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_refresh_models_api), com.tokai.module.i18n.UiText.get(R.string.speech_refresh_models_docs)},
                (choice, index) -> refreshMiniMaxModels(index == 1)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null);
    }

    private void manualMiniMaxModel() {
        if (closed) return;
        LinearLayout form = form();
        EditText value = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_model), settings().minimaxModel, false, "模型");
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_model)).setView(form).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            try {
                String candidate = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_MODEL, value.getText().toString());
                validateMiniMaxChange(SpeechSettings.MINIMAX_MODEL, candidate);
                store.putString(SpeechSettings.MINIMAX_MODEL, candidate);
                settingChanged(SpeechSettings.MINIMAX_MODEL);
                editor.dismiss();
                render();
            } catch (IllegalArgumentException failure) { value.setError(error(failure)); }
        });
    }

    private void refreshMiniMaxModels(boolean document) {
        if (fetch != null) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_fetching)); return; }
        SpeechSettings settings = settings();
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request;
        toast(document ? com.tokai.module.i18n.UiText.get(R.string.speech_minimax_docs_fetching) : com.tokai.module.i18n.UiText.get(R.string.speech_minimax_models_fetching));
        try {
            future = WORKER.submit(() -> {
                try {
                    List<String> models = document ? MiniMaxSpeechClient.fetchDocumentModels(request)
                            : MiniMaxSpeechClient.fetchModels(request, settings);
                    if (models.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_models_empty));
                    org.json.JSONArray catalog = new org.json.JSONArray();
                    for (String model : models) catalog.put(SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_MODEL, model));
                    String sample = catalog.toString();
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, sample);
                        store.putString(SpeechSettings.MINIMAX_MODEL_SOURCE, document ? "document" : "api");
                        render();
                        chooseMiniMaxModels(models, document ? "document" : "api");
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        toast(document ? com.tokai.module.i18n.UiText.get(R.string.speech_catalog_kept_manual, error(failure)) : com.tokai.module.i18n.UiText.get(R.string.speech_catalog_kept_refresh_manual, error(failure)));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null;
            request.close();
            toast(com.tokai.module.i18n.UiText.get(R.string.speech_model_queue_full));
        }
    }

    private void advanced() {
        LinearLayout form = form();
        advancedContent = form;
        renderAdvanced(form);
        AlertDialog advanced = builder(com.tokai.module.i18n.UiText.get(R.string.speech_voice_advanced)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_back), null).create();
        show(advanced, () -> {
            if (advancedContent == form) advancedContent = null;
        });
    }

    private void renderMiniMaxAdvanced(LinearLayout form) {
        MiniMaxSpeechOptions current = settings().minimaxOptions;
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_output), com.tokai.module.i18n.UiText.get(R.string.speech_audio_profile, audioFormatLabel(current.audioFormat), current.effectiveSampleRate()), () -> section("音频输出"), "音频输出");
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_language_pronunciation), current.languageBoost.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_auto_pronunciation) : current.languageBoost, () -> section("语言与发音"), "语言与发音");
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_mixing_effects), current.hasMixedVoices() ? com.tokai.module.i18n.UiText.get(R.string.speech_mixing_enabled) : current.hasVoiceModify() ? com.tokai.module.i18n.UiText.get(R.string.speech_effects_configured) : com.tokai.module.i18n.UiText.get(R.string.speech_single_original), () -> section("混音与声音效果"), "混音与声音效果");
    }

    private static String audioFormatLabel(String format) {
        if ("pcmu_wav".equals(format)) return "G.711 μ-law / WAV";
        if ("opus".equals(format)) return "Ogg / Opus";
        if ("aac".equals(format)) return com.tokai.module.i18n.UiText.get(R.string.speech_aac_experimental);
        return format.toUpperCase(java.util.Locale.ROOT);
    }

    private void renderMiniMaxEffects(LinearLayout form) {
        MiniMaxSpeechOptions current = settings().minimaxOptions;
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_mixed_voice_weights), MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "", false, "混合音色（每行 voice_id/weight）");
        hint(form, "speech.minimax.mix.hint", current.hasMixedVoices()
                ? com.tokai.module.i18n.UiText.get(R.string.speech_mix_active_hint)
                : com.tokai.module.i18n.UiText.get(R.string.speech_mix_inactive_hint));
        boolean effectsSupported = MiniMaxSpeechOptions.supportsVoiceModify(current.audioFormat);
        if (effectsSupported) {
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_modify_pitch), MiniMaxSpeechOptions.MODIFY_PITCH, "0", false, "音高调整（-100 至 100）");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_modify_intensity), MiniMaxSpeechOptions.MODIFY_INTENSITY, "0", false, "音强调整（-100 至 100）");
            editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_modify_timbre), MiniMaxSpeechOptions.MODIFY_TIMBRE, "0", false, "音色调整（-100 至 100）");
            miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_sound_effect), MiniMaxSpeechOptions.SOUND_EFFECT, MiniMaxSpeechOptions.SOUND_EFFECTS,
                    new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_effect_none), com.tokai.module.i18n.UiText.get(R.string.speech_effect_spacious_echo), com.tokai.module.i18n.UiText.get(R.string.speech_effect_hall_echo), com.tokai.module.i18n.UiText.get(R.string.speech_effect_telephone), com.tokai.module.i18n.UiText.get(R.string.speech_effect_robot)}, "声音效果");
            hint(form, "speech.minimax.effects.hint", com.tokai.module.i18n.UiText.get(R.string.speech_effects_supported_hint));
        } else {
            hint(form, "speech.minimax.effects.hint", com.tokai.module.i18n.UiText.get(R.string.speech_effects_unsupported_hint));
        }
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_reset_voice_effects), com.tokai.module.i18n.UiText.get(R.string.speech_reset_voice_summary), this::resetMiniMaxVoiceModify, "重置音色调整");
    }

    private void renderMiniMaxLanguage(LinearLayout form) {
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_emotion), MiniMaxSpeechOptions.EMOTION, MiniMaxSpeechOptions.EMOTIONS,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_automatic), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_happy), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_sad), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_angry), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_fearful), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_disgusted), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_surprised), com.tokai.module.i18n.UiText.get(R.string.speech_tone_calm), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_fluent), com.tokai.module.i18n.UiText.get(R.string.speech_emotion_whisper)}, "情绪");
        String[] languages = MiniMaxSpeechOptions.LANGUAGES.clone();
        languages[0] = com.tokai.module.i18n.UiText.get(R.string.speech_unspecified);
        for (int index = 1; index < languages.length; index++) {
            if ("Chinese".equals(languages[index])) languages[index] = com.tokai.module.i18n.UiText.get(R.string.speech_minimax_language_chinese);
            else if ("Chinese,Yue".equals(languages[index])) languages[index] = com.tokai.module.i18n.UiText.get(R.string.speech_minimax_language_cantonese);
            else if ("auto".equals(languages[index])) languages[index] = com.tokai.module.i18n.UiText.get(R.string.speech_minimax_language_auto);
        }
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_language_boost), MiniMaxSpeechOptions.LANGUAGE_BOOST, MiniMaxSpeechOptions.LANGUAGES, languages, "语言增强");
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_text_normalization), MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, MiniMaxSpeechOptions.NORMALIZATION_MODES,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_unspecified), com.tokai.module.i18n.UiText.get(R.string.speech_normalization_basic), com.tokai.module.i18n.UiText.get(R.string.speech_normalization_quality)}, "文本归一化");
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_pronunciation_per_line), MiniMaxSpeechOptions.PRONUNCIATION_TONE, "", false, "发音字典（每行 原文/替换内容）");
        hint(form, "speech.minimax.pronunciation.hint", com.tokai.module.i18n.UiText.get(R.string.speech_minimax_pronunciation_hint));
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_latex_reading), MiniMaxSpeechOptions.LATEX_READ, MiniMaxSpeechOptions.OPTIONAL_BOOLEANS,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_unspecified), com.tokai.module.i18n.UiText.get(R.string.speech_on_chinese_only), com.tokai.module.i18n.UiText.get(R.string.speech_disabled)}, "LaTeX 朗读");
    }

    private void renderMiniMaxAudio(LinearLayout form) {
        MiniMaxSpeechOptions current = settings().minimaxOptions;
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_format_api), MiniMaxSpeechOptions.AUDIO_FORMAT, MiniMaxSpeechOptions.FORMATS,
                new String[]{"MP3", "WAV", "FLAC", "Ogg / Opus", "G.711 μ-law / WAV", com.tokai.module.i18n.UiText.get(R.string.speech_aac_experimental_rejection)}, "生成音频格式（API）");
        if ("aac".equals(current.audioFormat)) {
            hint(form, "speech.minimax.aac.hint", com.tokai.module.i18n.UiText.get(R.string.speech_aac_hint));
        }
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_transport_format), MiniMaxSpeechOptions.OUTPUT_FORMAT, MiniMaxSpeechOptions.OUTPUT_FORMATS,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_transport_url), com.tokai.module.i18n.UiText.get(R.string.speech_transport_hex)}, "返回方式（传输）");
        hint(form, "speech.minimax.transport.hint", com.tokai.module.i18n.UiText.get(R.string.speech_transport_hint));
        if (MiniMaxSpeechOptions.isMuLawFormat(current.audioFormat)) {
            hint(form, "speech.minimax.mulaw.hint", com.tokai.module.i18n.UiText.get(R.string.speech_mulaw_hint));
        } else {
            miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_sample_rate), MiniMaxSpeechOptions.SAMPLE_RATE, MiniMaxSpeechOptions.SAMPLE_RATES,
                    new String[]{"8000 Hz", "16000 Hz", "22050 Hz", "24000 Hz", "32000 Hz", "44100 Hz"}, "采样率");
        }
        if ("mp3".equals(current.audioFormat)) {
            miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_mp3_bitrate), MiniMaxSpeechOptions.BITRATE, MiniMaxSpeechOptions.BITRATES,
                    new String[]{"32 kbps", "64 kbps", "128 kbps", "256 kbps"}, "MP3 码率");
        }
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_channels), MiniMaxSpeechOptions.CHANNEL, MiniMaxSpeechOptions.CHANNELS,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_mono), com.tokai.module.i18n.UiText.get(R.string.speech_stereo)}, "声道");
        miniMaxChoice(form, com.tokai.module.i18n.UiText.get(R.string.speech_aigc_watermark), MiniMaxSpeechOptions.AIGC_WATERMARK, MiniMaxSpeechOptions.OPTIONAL_BOOLEANS,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_unspecified), com.tokai.module.i18n.UiText.get(R.string.speech_enabled), com.tokai.module.i18n.UiText.get(R.string.speech_disabled)}, "AIGC 水印");
    }

    private void resetMiniMaxVoiceModify() {
        show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_reset_voice_effects)).setMessage(com.tokai.module.i18n.UiText.get(R.string.speech_reset_voice_message))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_reset), (dialog, which) -> {
                    store.putString(MiniMaxSpeechOptions.MODIFY_PITCH, "0");
                    store.putString(MiniMaxSpeechOptions.MODIFY_INTENSITY, "0");
                    store.putString(MiniMaxSpeechOptions.MODIFY_TIMBRE, "0");
                    store.putString(MiniMaxSpeechOptions.SOUND_EFFECT, "");
                    settingChanged(MiniMaxSpeechOptions.MODIFY_PITCH);
                    render();
                }).create(), null);
    }

    private void miniMaxChoice(LinearLayout form, String title, String key, String[] choices, String[] labels, String stableTitle) {
        String value = store.getString(key, MiniMaxSpeechOptions.defaultValue(key));
        int selected = java.util.Arrays.asList(choices).indexOf(value);
        String[] candidates = labels.clone();
        Map<String, Object> values = speechValues();
        for (int index = 0; index < choices.length; index++) {
            String issue = MiniMaxSpeechOptions.candidateIssue(values, key, choices[index]);
            if (!issue.isEmpty()) candidates[index] = com.tokai.module.i18n.UiText.get(R.string.speech_candidate_unavailable, candidates[index], issue);
        }
        option(form, title, selected < 0 ? com.tokai.module.i18n.UiText.get(R.string.speech_select_again) : labels[selected], () -> show(builder(title)
                .setSingleChoiceItems(candidates, selected, (choice, index) -> {
                    try {
                        String candidate = MiniMaxSpeechOptions.validateValue(key, choices[index]);
                        validateMiniMaxChange(key, candidate);
                        store.putString(key, candidate);
                        settingChanged(key);
                        choice.dismiss();
                        render();
                    } catch (IllegalArgumentException failure) { toast(error(failure)); }
                }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null), stableTitle);
    }

    private void validateMiniMaxChange(String key, String value) {
        String issue = MiniMaxSpeechOptions.candidateIssue(speechValues(), key, value);
        if (!issue.isEmpty()) throw new IllegalArgumentException(issue);
    }

    private void renderAdvanced(LinearLayout form) {
        form.removeAllViews();
        if (!settings().thirdParty) {
            hint(form, "speech.third.inactive", com.tokai.module.i18n.UiText.get(R.string.speech_third_voice_inactive));
            return;
        }
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_get), SpeechSettings.VOICE_GET_TEMPLATE, SpeechSettings.DEFAULT_VOICE_GET_TEMPLATE, false, "音色列表 GET");
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_refresh_voices), null, this::refreshVoices, "刷新音色");
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_headers), SpeechSettings.VOICE_HEADERS, "{}", false, "音色列表请求头");
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_list_field), SpeechSettings.VOICE_LIST_PATH, "", false, "列表字段");
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_id_field), SpeechSettings.VOICE_ID_PATH, "", false, "音色编号字段");
        editable(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_name_field), SpeechSettings.VOICE_NAME_PATH, "", false, "音色名称字段");
        option(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_sample), store.getString(VOICE_SAMPLE, "").isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_not_saved) : com.tokai.module.i18n.UiText.get(R.string.speech_saved), this::editSample, "音色列表样本");
    }

    private String voiceLabel() {
        SpeechSettings settings = settings();
        if (settings.fish) {
            FishSpeechSettings fish = settings.fishSettings;
            if ("multi".equals(fish.voiceMode)) return com.tokai.module.i18n.UiText.get(R.string.speech_multi_voice_summary, fish.referenceIds.size());
            if ("references".equals(fish.voiceMode)) {
                try { return com.tokai.module.i18n.UiText.get(R.string.speech_reference_summary, fish.referenceGroups().size()); }
                catch (RuntimeException invalid) { return com.tokai.module.i18n.UiText.get(R.string.speech_references_reconfigure); }
            }
            return singleFishVoiceLabel(fish);
        }
        if (settings.breeze) return translatedVoiceLabel(settings.breezeSettings.voiceId, settings.breezeSettings.voiceName);
        if (settings.minimax) return settings.minimaxVoiceName.isEmpty() ? settings.minimaxVoiceId
                : settings.minimaxVoiceName + " · " + settings.minimaxVoiceId;
        return settings.selectedVoiceName.isEmpty() ? settings.selectedVoice : settings.selectedVoiceName + " · " + settings.selectedVoice;
    }

    private String translatedVoiceLabel(String id, String name) {
        if (name.isEmpty()) return id;
        try { return VoiceNameTranslationCache.read(store).displayName(VoiceNameTranslationCache.scope(settings()),
                new VoiceCatalog.Entry(id, name)) + " · " + id; }
        catch (IllegalArgumentException invalidDraft) { return name.isEmpty() ? id : name + " · " + id; }
    }

    private String catalogEntryLabel(VoiceCatalog.Entry entry) {
        return VoiceNameTranslationCache.read(store).displayName(VoiceNameTranslationCache.scope(settings()), entry)
                + " · " + entry.id + (entry.labels.isEmpty() ? "" : "\n" + String.join(" · ", MetadataUiText.displayVoiceLabels(entry)));
    }

    private String cachedFishSpeakerLabel(String id) {
        for (VoiceCatalog.Entry entry : readCatalogSample(settings())) if (entry.id.equals(id)) return catalogEntryLabel(entry);
        return id;
    }

    private List<VoiceCatalog.Entry> readCatalogSample(SpeechSettings speech) {
        try {
            if ((speech.fish || speech.breeze) && !VoiceCatalogLabels.queryKey(speech).equals(
                    store.getString(VoiceCatalogLabels.queryStorageKey(speech), ""))) return java.util.Collections.emptyList();
            return VoiceCatalog.parse(store.getString(voiceSampleKey(speech), ""), "", "", "");
        } catch (RuntimeException invalid) { return java.util.Collections.emptyList(); }
    }

    private void saveCatalogSample(Iterable<VoiceCatalog.Entry> entries, String queryKey) throws org.json.JSONException {
        SpeechSettings speech = settings(); org.json.JSONArray sample = new org.json.JSONArray();
        if (!queryKey.equals(VoiceCatalogLabels.queryKey(speech))) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_filters_changed));
        for (VoiceCatalog.Entry entry : entries) sample.put(VoiceCatalogLabels.cached(entry));
        store.putString(voiceSampleKey(speech), sample.toString());
        store.putString(VoiceCatalogLabels.queryStorageKey(speech), queryKey);
    }

    private void clearCatalogSample(boolean fish) {
        store.putString(fish ? FishSpeechSettings.VOICE_SAMPLE : BreezeSpeechSettings.VOICE_SAMPLE, "");
        store.putString(fish ? VoiceCatalogLabels.FISH_QUERY_KEY : VoiceCatalogLabels.BREEZE_QUERY_KEY, "");
    }

    private static String voiceSampleKey(SpeechSettings settings) {
        return settings.fish ? FishSpeechSettings.VOICE_SAMPLE : settings.breeze ? BreezeSpeechSettings.VOICE_SAMPLE
                : settings.minimax ? SpeechSettings.MINIMAX_VOICE_SAMPLE : VOICE_SAMPLE;
    }

    private static String voiceIdKey(SpeechSettings settings) {
        return settings.fish ? FishSpeechSettings.REFERENCE_ID : settings.breeze ? BreezeSpeechSettings.VOICE_ID
                : settings.minimax ? SpeechSettings.MINIMAX_VOICE_ID : SpeechSettings.SELECTED_VOICE;
    }

    private static String voiceNameKey(SpeechSettings settings) {
        return settings.fish ? FishSpeechSettings.REFERENCE_NAME : settings.breeze ? BreezeSpeechSettings.VOICE_NAME
                : settings.minimax ? SpeechSettings.MINIMAX_VOICE_NAME : SpeechSettings.SELECTED_VOICE_NAME;
    }

    private String synthesisSampleLabel() {
        String sample = store.getString(SYNTHESIS_SAMPLE, "");
        if (sample.isEmpty()) return com.tokai.module.i18n.UiText.get(R.string.speech_not_saved);
        try { return com.tokai.module.i18n.UiText.get(R.string.speech_link_field_count, audioCandidates(sample).size()); }
        catch (Exception ignored) { return com.tokai.module.i18n.UiText.get(R.string.speech_pending_parse); }
    }

    private static Map<String, String> audioCandidates(String sample) {
        Map<String, String> candidates = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, List<String>> field : ThirdPartyResponse.parse(sample).downloadFields().entrySet()) {
                List<String> urls = field.getValue();
                if (urls.size() == 1) candidates.put(field.getKey(), urls.get(0));
                else for (int index = 0; index < urls.size(); index++) {
                    candidates.put(field.getKey() + "[" + index + "]", urls.get(index));
                }
            }
        } catch (IllegalArgumentException failure) {
            // JSON scalar links are accepted by speech even when the structured parser needs an object root.
            candidates.put("", SpeechHttpClient.audioUrl(sample, ""));
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_sample_no_audio));
        return candidates;
    }

    private void editSynthesisSample() {
        LinearLayout form = form();
        EditText sample = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_json_txt_response), store.getString(SYNTHESIS_SAMPLE, ""), false, "JSON / TXT 响应");
        sample.setTag("speech.synthesis.sample");
        sample.setMaxLines(12);
        TextView preview = new TextView(context);
        preview.setTag("speech.synthesis.preview");
        android.widget.Button parse = button(form, com.tokai.module.i18n.UiText.get(R.string.speech_parse_preview), "speech.synthesis.parse");
        form.addView(preview, matchWrap());
        parse.setOnClickListener(ignored -> {
            try {
                StringBuilder fields = new StringBuilder();
                for (String path : audioCandidates(sample.getText().toString()).keySet()) {
                    if (fields.length() > 0) fields.append('\n');
                    fields.append(path.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_direct_audio_link) : path);
                }
                preview.setText(fields.toString());
                sample.setError(null);
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_synthesis_sample)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String value = sample.getText().toString().trim();
            try {
                if (!value.isEmpty()) audioCandidates(value);
                store.putString(SYNTHESIS_SAMPLE, value);
                editor.dismiss();
                render();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    private void chooseAudioField() {
        List<String> paths = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        paths.add(""); labels.add(com.tokai.module.i18n.UiText.get(R.string.speech_detect_automatically));
        try {
            for (String path : audioCandidates(store.getString(SYNTHESIS_SAMPLE, "")).keySet()) {
                if (!path.isEmpty()) { paths.add(path); labels.add(path); }
            }
        } catch (Exception ignored) { }
        String current = settings().audioField;
        if (!current.isEmpty() && !paths.contains(current)) { paths.add(current); labels.add(current); }
        show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_audio_url_field)).setSingleChoiceItems(labels.toArray(new String[0]), paths.indexOf(current), (choice, index) -> {
            store.putString(SpeechSettings.AUDIO_FIELD, paths.get(index));
            settingChanged(SpeechSettings.AUDIO_FIELD);
            choice.dismiss();
            render();
        }).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_enter_manually), (choice, index) -> main.post(() ->
                editAudioField())).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null);
    }

    private void editAudioField() {
        if (closed) return;
        LinearLayout form = form();
        EditText field = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_audio_url_field), settings().audioField, false, "音频地址字段");
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_audio_url_field)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            store.putString(SpeechSettings.AUDIO_FIELD, field.getText().toString().trim());
            settingChanged(SpeechSettings.AUDIO_FIELD);
            editor.dismiss();
            render();
        });
    }

    private void editable(String title, String key, String fallback, boolean secret, String stableTitle) {
        editable(content, title, key, fallback, secret, stableTitle);
    }

    private void editable(LinearLayout target, String title, String key, String fallback, boolean secret, String stableTitle) {
        String value = editableValue(key, fallback);
        option(target, title, secret && !value.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_configured) : value, () -> {
            if (key.startsWith("tts_breeze_") && !canUseBreeze()) return;
            if (key.startsWith("tts_fish_") && !canUseFish()) return;
            LinearLayout form = form();
            EditText input = input(form, title, editableValue(key, fallback), secret, stableTitle);
            AlertDialog editor = builder(title).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                    .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
            show(editor, null);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                if (key.startsWith("tts_breeze_") && !canUseBreeze()) return;
                if (key.startsWith("tts_fish_") && !canUseFish()) return;
                String candidate = input.getText().toString();
                if (!MiniMaxSpeechOptions.TIMBRE_WEIGHTS.equals(key) && !BreezeSpeechSettings.INSTRUCTIONS.equals(key)) candidate = candidate.trim();
                try {
                    if (SpeechSettings.RATE.equals(key) || SpeechSettings.PITCH.equals(key)) {
                        candidate = SpeechSettings.validateLocalRateOrPitch(candidate);
                    }
                    if (key.startsWith("tts_minimax_")) candidate = SpeechSettings.validateMiniMaxValue(key, candidate);
                    if (key.startsWith("tts_breeze_")) {
                        candidate = BreezeSpeechSettings.validateValue(key, candidate);
                        validateBreezeChange(key, candidate);
                    }
                    if (key.startsWith("tts_fish_")) {
                        candidate = FishSpeechSettings.validateValue(key, candidate);
                        validateFishChange(key, candidate);
                    }
                    if (MiniMaxSpeechOptions.isKey(key)) validateMiniMaxChange(key, candidate);
                    if (SpeechSettings.GET_TEMPLATE.equals(key)) ThirdPartyRequestTemplate.parse(candidate, "{text}", "{voice}");
                    if (SpeechSettings.VOICE_GET_TEMPLATE.equals(key) && !candidate.isEmpty()) ThirdPartyRequestTemplate.parse(candidate, new String[0]);
                    if (SpeechSettings.HEADERS.equals(key) || SpeechSettings.VOICE_HEADERS.equals(key)) {
                        org.json.JSONObject headers = new org.json.JSONObject(candidate.isEmpty() ? "{}" : candidate);
                        java.util.Iterator<String> names = headers.keys();
                        while (names.hasNext()) if (!(headers.get(names.next()) instanceof String)) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_headers_strings_required));
                    }
                    store.putString(key, candidate);
                    settingChanged(key);
                    editor.dismiss();
                    render();
                } catch (Exception failure) { input.setError(error(failure)); }
            });
        }, stableTitle);
    }

    private String editableValue(String key, String fallback) {
        if (SpeechSettings.RATE.equals(key)) return settings().rateText();
        if (SpeechSettings.PITCH.equals(key)) return settings().pitchText();
        return store.getString(key, fallback);
    }

    private void parameters() {
        SpeechSettings settings = settings();
        final ThirdPartyRequestTemplate template;
        try { template = ThirdPartyRequestTemplate.parse(settings.getTemplate, "{text}", "{voice}"); }
        catch (Exception failure) { toast(error(failure)); return; }
        LinearLayout form = form();
        List<ParameterRow> rows = new ArrayList<>();
        int[] nextId = {0};
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = new TextView(context);
        count.setTag("speech.parameters.count");
        toolbar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(toolbar, matchWrap());
        Runnable updateCount = () -> count.setText(rows.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_no_parameters) : com.tokai.module.i18n.UiText.get(R.string.speech_parameter_count, rows.size()));
        LinearLayout fields = form();
        form.addView(fields, matchWrap());
        Consumer<ThirdPartyRequestTemplate.Parameter> add = parameter -> {
            ParameterRow row = new ParameterRow();
            row.container = form();
            int index = nextId[0]++;
            LinearLayout heading = new LinearLayout(context);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(context);
            label.setText(com.tokai.module.i18n.UiText.get(R.string.speech_parameter));
            heading.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            icon(heading, com.tokai.module.i18n.UiText.get(R.string.speech_remove_parameter), "speech.parameter.remove." + index, android.R.drawable.ic_menu_delete, () -> {
                rows.remove(row);
                fields.removeView(row.container);
                updateCount.run();
            });
            row.container.addView(heading, matchWrap());
            row.name = input(row.container, com.tokai.module.i18n.UiText.get(R.string.speech_parameter_name), parameter.name, false, "参数名");
            row.name.setTag("speech.parameter.name." + index);
            row.name.setSingleLine(true);
            row.source = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
            row.source.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.speech_parameter_source));
            row.source.setTag("speech.parameter.source." + index);
            row.source.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                    new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_fixed_value), com.tokai.module.i18n.UiText.get(R.string.speech_synthesis_text), com.tokai.module.i18n.UiText.get(R.string.speech_selected_voice)}));
            row.container.addView(MiuixFormControls.viewFor(row.source), matchWrap());
            row.fixedValue = form();
            row.fixedValue.setTag("speech.parameter.fixed." + index);
            row.container.addView(row.fixedValue, matchWrap());
            boolean bound = "{text}".equals(parameter.value) || "{voice}".equals(parameter.value);
            row.value = input(row.fixedValue, com.tokai.module.i18n.UiText.get(R.string.speech_parameter_value), bound ? "" : parameter.value, false, "参数值");
            row.value.setTag("speech.parameter.value." + index);
            row.source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    row.fixedValue.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            row.source.setSelection("{text}".equals(parameter.value) ? 1 : "{voice}".equals(parameter.value) ? 2 : 0);
            row.fixedValue.setVisibility(bound ? View.GONE : View.VISIBLE);
            rows.add(row);
            fields.addView(row.container, matchWrap());
            updateCount.run();
        };
        for (ThirdPartyRequestTemplate.Parameter parameter : template.parameters) add.accept(parameter);
        updateCount.run();
        icon(toolbar, com.tokai.module.i18n.UiText.get(R.string.speech_add_parameter), "speech.parameter.add", android.R.drawable.ic_input_add,
                () -> add.accept(new ThirdPartyRequestTemplate.Parameter("", "")));
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_synthesis_parameters)).setView(scroll(form))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            List<ThirdPartyRequestTemplate.Parameter> parameters = new ArrayList<>();
            for (ParameterRow row : rows) {
                String name = row.name.getText().toString().trim();
                if (name.isEmpty()) { row.name.setError(com.tokai.module.i18n.UiText.get(R.string.speech_parameter_name_required)); return; }
                int source = row.source.getSelectedItemPosition();
                parameters.add(new ThirdPartyRequestTemplate.Parameter(name, source == 1 ? "{text}"
                        : source == 2 ? "{voice}" : row.value.getText().toString()));
            }
            try {
                store.putString(SpeechSettings.GET_TEMPLATE, template.withParameters(parameters));
                settingChanged(SpeechSettings.GET_TEMPLATE);
                editor.dismiss();
                render();
            } catch (Exception failure) { toast(error(failure)); }
        });
    }

    private void editSample() {
        LinearLayout form = form();
        EditText sample = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_sample), store.getString(VOICE_SAMPLE, ""), false, "音色列表样本");
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_voice_sample)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            try {
                String value = sample.getText().toString();
                VoiceCatalog.parse(value, settings());
                store.putString(VOICE_SAMPLE, value);
                cancelFetch();
                editor.dismiss();
                render();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    private void refreshVoices() {
        if (closed) return;
        SpeechSettings settings = settings();
        if (settings.fish) { refreshFishCatalog(settings.fishSettings); return; }
        if (settings.breeze) { refreshBreezeCatalog(settings.breezeSettings); return; }
        if (fetch != null) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_voices_fetching)); return; }
        if (settings.minimax) {
            try {
                if (settings.minimaxApiKey.isEmpty()) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_minimax_key_required));
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_API_KEY, settings.minimaxApiKey);
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_BASE_URL, settings.minimaxBaseUrl);
            } catch (IllegalArgumentException invalid) { toast(error(invalid)); return; }
        }
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request;
        toast(com.tokai.module.i18n.UiText.get(R.string.speech_fetching_voices));
        try {
            future = WORKER.submit(() -> {
                try {
                    String body;
                    List<VoiceCatalog.Entry> entries;
                    if (settings.minimax) {
                        entries = MiniMaxSpeechClient.fetchVoices(request, settings);
                        org.json.JSONArray catalog = new org.json.JSONArray();
                        for (VoiceCatalog.Entry entry : entries) catalog.put(new org.json.JSONObject().put("id", entry.id).put("name", entry.name));
                        body = catalog.toString();
                    } else {
                        body = request.fetchText(settings.voiceGetTemplate, settings.voiceHeaders);
                        entries = VoiceCatalog.parse(body, settings);
                    }
                    String sample = body;
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        if (settings.breeze && !canUseBreeze()) return;
                        if (settings.fish && !canUseFish()) return;
                        store.putString(voiceSampleKey(settings), sample);
                        render();
                        chooseVoices(entries);
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        toast(settings.fish ? com.tokai.module.i18n.UiText.get(R.string.speech_voices_kept_narrow, error(failure)) : settings.minimax || settings.breeze ? com.tokai.module.i18n.UiText.get(R.string.speech_voices_kept_manual, error(failure)) : error(failure));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null;
            request.close();
            toast(com.tokai.module.i18n.UiText.get(R.string.speech_voice_queue_full));
        }
    }

    private void refreshBreezeCatalog(BreezeSpeechSettings query) {
        if (!canUseBreeze() || !validateBreezeVoiceCatalog(query)) return;
        cancelFetch(); discardBreezeCatalog();
        BreezeCatalogSession session = new BreezeCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
        try {
            for (VoiceCatalog.Entry entry : readCatalogSample(settings())) {
                if (session.entries.size() >= BreezeCatalogSession.MAX_ENTRIES) break;
                session.entries.put(entry.id, entry);
            }
        } catch (RuntimeException ignored) { }
        breezeCatalog = session; loadBreezePage(session);
    }

    private void chooseBreezeVoices(List<VoiceCatalog.Entry> cached) {
        if (!canUseBreeze()) return;
        BreezeSpeechSettings query = settings().breezeSettings;
        if (breezeCatalog != null && !breezeCatalog.matches(settings())) {
            cancelFetch(); discardBreezeCatalog(); clearCatalogSample(false);
            cached = java.util.Collections.emptyList();
        }
        if (breezeCatalog == null) {
            breezeCatalog = new BreezeCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
            for (VoiceCatalog.Entry entry : cached) {
                if (breezeCatalog.entries.size() >= BreezeCatalogSession.MAX_ENTRIES) break;
                breezeCatalog.entries.put(entry.id, entry);
            }
        }
        showBreezeCatalog(breezeCatalog);
    }

    private boolean currentBreezeCatalog(BreezeCatalogSession session) {
        return canUseBreeze() && breezeCatalog == session && session.matches(settings());
    }

    private void loadBreezePage(BreezeCatalogSession session) {
        if (!currentBreezeCatalog(session) || session.loading) return;
        if (fetch != null) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_voices_fetching)); return; }
        if (session.pagesLoaded > 0 && (!session.hasMore || session.budget)) return;
        int page = session.nextPage; String token = session.nextToken;
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request; session.loading = true; render(); updateBreezeCatalogControls(session);
        toast(session.pagesLoaded == 0 ? com.tokai.module.i18n.UiText.get(R.string.speech_breeze_loading_first) : com.tokai.module.i18n.UiText.get(R.string.speech_breeze_loading_more));
        try {
            future = WORKER.submit(() -> {
                try {
                    BreezeSpeechClient.VoicePage response = BreezeSpeechClient.fetchVoicePage(request, session.query, page, token);
                    main.post(() -> {
                        if (!acceptBreezePageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        try {
                            appendBreezePage(session, response, page, token);
                            saveCatalogSample(session.entries.values(), session.queryKey);
                            render(); showBreezeCatalog(session);
                        } catch (Exception failure) { breezePageFailed(session, error(failure)); }
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!acceptBreezePageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        breezePageFailed(session, error(failure));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null; request.close(); session.loading = false;
            breezePageFailed(session, com.tokai.module.i18n.UiText.get(R.string.speech_voice_queue_full));
        }
    }

    private boolean acceptBreezePageOwner(BreezeCatalogSession session, SpeechHttpClient.Request request) {
        if (fetch != request) return false;
        if (currentBreezeCatalog(session)) return true;
        fetch = null; future = null; session.loading = false;
        if (breezeCatalog == session) { discardBreezeCatalog(); clearCatalogSample(false); }
        return false;
    }

    private void appendBreezePage(BreezeCatalogSession session, BreezeSpeechClient.VoicePage response,
                                  int requestedPage, String requestedToken) {
        if (response.page != requestedPage) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_page_mismatch));
        java.util.Set<String> added = new java.util.LinkedHashSet<>(response.sourceIds); added.removeAll(session.sourceIds);
        boolean repeatedItems = added.isEmpty() && (response.hasMore || session.pagesLoaded > 0 && response.sourceCount > 0);
        boolean repeatedCursor = response.hasMore && !response.nextPageToken.isEmpty()
                && (response.nextPageToken.equals(requestedToken) || session.tokens.contains(response.nextPageToken));
        boolean repeatedPage = response.hasMore && response.nextPageToken.isEmpty() && response.nextPage <= requestedPage;
        if (repeatedItems || repeatedCursor || repeatedPage) {
            session.hasMore = false; session.stalled = true;
            throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_paging_stalled));
        }
        if (session.pagesLoaded == 0) { session.entries.clear(); session.sourceIds.clear(); session.tokens.clear(); }
        for (VoiceCatalog.Entry entry : response.entries) {
            if (session.entries.containsKey(entry.id)) continue;
            if (session.entries.size() >= BreezeCatalogSession.MAX_ENTRIES) { session.budget = true; break; }
            session.entries.put(entry.id, entry);
        }
        session.sourceIds.addAll(response.sourceIds);
        if (!response.nextPageToken.isEmpty()) session.tokens.add(response.nextPageToken);
        session.pagesLoaded++; session.hasMore = response.hasMore;
        session.nextPage = response.nextPage; session.nextToken = response.nextPageToken;
        session.budget |= session.entries.size() >= BreezeCatalogSession.MAX_ENTRIES && session.hasMore;
        session.notice = () -> ""; session.stalled = false;
    }

    private void breezePageFailed(BreezeCatalogSession session, String failure) {
        if (!currentBreezeCatalog(session)) return;
        session.notice = () -> com.tokai.module.i18n.UiText.get(R.string.speech_catalog_failure_kept, failure);
        render();
        if (session.dialog == null || !session.dialog.isShowing()) showBreezeCatalog(session);
        else updateBreezeCatalogControls(session);
        toast(session.notice.get());
    }

    private void showBreezeCatalog(BreezeCatalogSession session) {
        if (!currentBreezeCatalog(session)) return;
        if (session.dialog != null && session.dialog.isShowing()) {
            updateCatalogChoices(session); updateBreezeCatalogControls(session); return;
        }
        session.header = new CatalogHeader(session, session.localSearch);
        AlertDialog picker = builder(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_catalog_title)).setCustomTitle(session.header.view)
                .setSingleChoiceItems(new String[0], -1, (choice, index) -> { })
                .setPositiveButton(session.moreButton(), null)
                .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_enter_manually), (choice, index) -> main.post(() -> {
                    if (currentBreezeCatalog(session)) manualVoice();
                })).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_close), null).create();
        session.dialog = picker;
        show(picker, () -> {
            if (session.dialog == picker) {
                session.dialog = null; session.header.close();
                stopNameTranslationFor(session, () ->  com.tokai.module.i18n.UiText.get(R.string.speech_translation_catalog_closed));
                if (session.loading) cancelFetch();
            }
        });
        picker.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!currentBreezeCatalog(session) || session.loading) return;
            if (session.pagesLoaded > 0 && session.hasMore && !session.budget) loadBreezePage(session);
            else refreshBreezeCatalog(settings().breezeSettings);
        });
        updateCatalogChoices(session); updateBreezeCatalogControls(session);
    }

    private void updateBreezeCatalogControls(BreezeCatalogSession session) {
        AlertDialog picker = session.dialog;
        if (picker == null || !picker.isShowing()) return;
        updateCatalogHeader(session);
        android.widget.Button more = picker.getButton(Dialog.BUTTON_POSITIVE);
        if (more != null) { more.setText(session.loading ? com.tokai.module.i18n.UiText.get(R.string.speech_loading) : session.moreButton()); more.setEnabled(!session.loading); }
    }

    private void discardBreezeCatalog() {
        BreezeCatalogSession previous = breezeCatalog; breezeCatalog = null;
        if (previous == null) return;
        stopNameTranslationFor(previous, () ->  com.tokai.module.i18n.UiText.get(R.string.speech_translation_catalog_changed));
        if (previous.header != null) previous.header.close();
        if (previous.loading) cancelFetch();
        if (previous.dialog != null) { AlertDialog picker = previous.dialog; previous.dialog = null; picker.dismiss(); }
    }

    private static final class BreezeCatalogSession {
        static final int MAX_ENTRIES = 200;
        final BreezeSpeechSettings query;
        final String queryKey;
        final Map<String, VoiceCatalog.Entry> entries = new LinkedHashMap<>();
        final java.util.Set<String> sourceIds = new java.util.LinkedHashSet<>(), tokens = new java.util.LinkedHashSet<>();
        int pagesLoaded, nextPage = 1;
        String nextToken = "", localSearch = "";
        java.util.function.Supplier<String> notice = () -> "", translationStatus = () -> "";
        CatalogHeader header;
        boolean hasMore, loading, budget, stalled;
        AlertDialog dialog;
        BreezeCatalogSession(BreezeSpeechSettings query, String queryKey) { this.query = query; this.queryKey = queryKey; }
        boolean matches(SpeechSettings current) { return queryKey.equals(VoiceCatalogLabels.queryKey(current)); }
        String moreButton() { return pagesLoaded > 0 && hasMore && !budget ? com.tokai.module.i18n.UiText.get(R.string.speech_load_more) : com.tokai.module.i18n.UiText.get(R.string.speech_refresh); }
        String status() {
            List<String> lines = new ArrayList<>();
            lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_loaded_count, entries.size()));
            if (loading) lines.add(pagesLoaded == 0 ? com.tokai.module.i18n.UiText.get(R.string.speech_loading_first_status) : com.tokai.module.i18n.UiText.get(R.string.speech_loading_next_status));
            else if (pagesLoaded == 0) lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_cached_status));
            else lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_pages_loaded, pagesLoaded));
            if (budget) lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_budget));
            else if (stalled) lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_stalled));
            else if (pagesLoaded > 0) lines.add(hasMore ? com.tokai.module.i18n.UiText.get(R.string.speech_catalog_more) : com.tokai.module.i18n.UiText.get(R.string.speech_catalog_end));
            String message = notice.get();
            return String.join(" · ", lines) + (message.isEmpty() ? "" : "\n" + message);
        }
        String title() {
            String scope = "personal".equals(query.voiceType) ? com.tokai.module.i18n.UiText.get(R.string.speech_personal_voices) : "default".equals(query.voiceType) ? com.tokai.module.i18n.UiText.get(R.string.speech_system_voices) : com.tokai.module.i18n.UiText.get(R.string.speech_all_voices);
            return com.tokai.module.i18n.UiText.get(R.string.speech_breeze_scope_title, scope, status());
        }
    }

    private void refreshFishCatalog(FishSpeechSettings query) {
        if (!canUseFish() || !validateFishCatalog(query)) return;
        cancelFetch();
        discardFishCatalog();
        FishCatalogSession session = new FishCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
        try {
            for (VoiceCatalog.Entry entry : readCatalogSample(settings())) {
                if (session.entries.size() >= FishCatalogSession.MAX_ENTRIES) break;
                session.entries.put(entry.id, entry);
            }
        } catch (RuntimeException ignored) { }
        fishCatalog = session;
        loadFishPage(session);
    }

    private void chooseFishVoices(List<VoiceCatalog.Entry> cached) {
        if (!canUseFish()) return;
        FishSpeechSettings query = settings().fishSettings;
        if (fishCatalog != null && !fishCatalog.matches(settings())) {
            cancelFetch(); discardFishCatalog();
            clearCatalogSample(true);
            cached = java.util.Collections.emptyList();
        }
        if (fishCatalog == null) {
            fishCatalog = new FishCatalogSession(query, VoiceCatalogLabels.queryKey(settings()));
            for (VoiceCatalog.Entry entry : cached) {
                if (fishCatalog.entries.size() >= FishCatalogSession.MAX_ENTRIES) break;
                fishCatalog.entries.put(entry.id, entry);
            }
        }
        showFishCatalog(fishCatalog);
    }

    private boolean currentFishCatalog(FishCatalogSession session) {
        return canUseFish() && fishCatalog == session && session.matches(settings());
    }

    private void loadFishPage(FishCatalogSession session) {
        if (!currentFishCatalog(session) || session.loading) return;
        if (fetch != null) { toast(com.tokai.module.i18n.UiText.get(R.string.speech_voices_fetching)); return; }
        if (session.page > 0 && (session.budget || !session.moreKnown || !session.hasMore)) return;
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request; session.loading = true; updateFishCatalogControls(session);
        int page = session.page + 1;
        toast(page == 1 ? com.tokai.module.i18n.UiText.get(R.string.speech_loading_first) : com.tokai.module.i18n.UiText.get(R.string.speech_loading_more));
        try {
            future = WORKER.submit(() -> {
                try {
                    FishSpeechClient.VoicePage response = FishSpeechClient.fetchVoicePage(request, session.query, page);
                    main.post(() -> {
                        if (!acceptFishPageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        try {
                            appendFishPage(session, response);
                            saveCatalogSample(session.entries.values(), session.queryKey);
                            render(); showFishCatalog(session);
                        } catch (Exception failure) { fishPageFailed(session, error(failure)); }
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!acceptFishPageOwner(session, request)) return;
                        fetch = null; future = null; session.loading = false;
                        fishPageFailed(session, error(failure));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null; request.close(); session.loading = false;
            fishPageFailed(session, com.tokai.module.i18n.UiText.get(R.string.speech_voice_queue_full));
        }
    }

    private boolean acceptFishPageOwner(FishCatalogSession session, SpeechHttpClient.Request request) {
        if (fetch != request) return false;
        if (currentFishCatalog(session)) return true;
        fetch = null; future = null; session.loading = false;
        if (fishCatalog == session) {
            discardFishCatalog(); clearCatalogSample(true);
        }
        return false;
    }

    private void appendFishPage(FishCatalogSession session, FishSpeechClient.VoicePage response) {
        if (response.page != session.page + 1) throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_page_mismatch));
        java.util.Set<String> newIds = new java.util.LinkedHashSet<>(response.sourceIds);
        newIds.removeAll(session.sourceIds);
        boolean responseMore = response.moreKnown ? response.hasMore
                : response.totalExact && session.sourceCount + response.sourceCount < response.total;
        if (session.page > 0 && response.sourceCount > 0 && newIds.isEmpty()
                || response.sourceCount == 0 && responseMore) {
            session.moreKnown = false; session.hasMore = false;
            throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.speech_fish_paging_stalled));
        }
        if (session.page == 0) { session.entries.clear(); session.sourceIds.clear(); session.sourceCount = 0; }
        for (VoiceCatalog.Entry entry : response.entries) {
            if (session.entries.containsKey(entry.id)) continue;
            if (session.entries.size() >= FishCatalogSession.MAX_ENTRIES) { session.budget = true; break; }
            session.entries.put(entry.id, entry);
        }
        session.sourceIds.addAll(response.sourceIds);
        session.sourceCount += response.sourceCount;
        session.page = response.page; session.total = response.total;
        session.windowLimited |= response.windowLimited; session.totalExact = response.totalExact;
        session.moreKnown = response.moreKnown || response.totalExact;
        session.hasMore = response.moreKnown ? response.hasMore : response.totalExact && session.sourceCount < response.total;
        session.budget |= session.entries.size() >= FishCatalogSession.MAX_ENTRIES && session.hasMore;
        session.notice = () -> "";
    }

    private void fishPageFailed(FishCatalogSession session, String failure) {
        if (!currentFishCatalog(session)) return;
        session.notice = () -> com.tokai.module.i18n.UiText.get(R.string.speech_catalog_failure_kept, failure);
        render();
        if (session.dialog == null || !session.dialog.isShowing()) showFishCatalog(session);
        else updateFishCatalogControls(session);
        toast(session.notice.get());
    }

    private void showFishCatalog(FishCatalogSession session) {
        if (!currentFishCatalog(session)) return;
        if (session.dialog != null && session.dialog.isShowing()) {
            updateCatalogChoices(session); updateFishCatalogControls(session); return;
        }
        session.header = new CatalogHeader(session, session.localSearch);
        AlertDialog picker = builder(com.tokai.module.i18n.UiText.get(R.string.speech_fish_catalog_title)).setCustomTitle(session.header.view)
                .setSingleChoiceItems(new String[0], -1, (choice, index) -> { })
                .setPositiveButton(session.moreButton(), null)
                .setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_enter_manually), (choice, index) -> main.post(() -> {
                    if (currentFishCatalog(session)) manualVoice();
                })).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_close), null).create();
        session.dialog = picker;
        show(picker, () -> {
            if (session.dialog == picker) {
                session.dialog = null; session.header.close();
                stopNameTranslationFor(session, () ->  com.tokai.module.i18n.UiText.get(R.string.speech_translation_catalog_closed));
                if (session.loading) cancelFetch();
            }
        });
        picker.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (!currentFishCatalog(session) || session.loading) return;
            if (session.page > 0 && session.moreKnown && session.hasMore && !session.budget) loadFishPage(session);
            else refreshFishCatalog(settings().fishSettings);
        });
        updateCatalogChoices(session); updateFishCatalogControls(session);
    }

    private void updateFishCatalogControls(FishCatalogSession session) {
        AlertDialog picker = session.dialog;
        if (picker == null || !picker.isShowing()) return;
        updateCatalogHeader(session);
        android.widget.Button more = picker.getButton(Dialog.BUTTON_POSITIVE);
        if (more != null) { more.setText(session.loading ? com.tokai.module.i18n.UiText.get(R.string.speech_loading) : session.moreButton()); more.setEnabled(!session.loading); }
    }

    private void discardFishCatalog() {
        FishCatalogSession previous = fishCatalog; fishCatalog = null;
        if (previous == null) return;
        stopNameTranslationFor(previous, () ->  com.tokai.module.i18n.UiText.get(R.string.speech_translation_catalog_changed));
        if (previous.header != null) previous.header.close();
        if (previous.loading) cancelFetch();
        if (previous.dialog != null) { AlertDialog picker = previous.dialog; previous.dialog = null; picker.dismiss(); }
    }

    private static final class FishCatalogSession {
        static final int MAX_ENTRIES = 200;
        final FishSpeechSettings query;
        final String queryKey;
        final Map<String, VoiceCatalog.Entry> entries = new LinkedHashMap<>();
        final java.util.Set<String> sourceIds = new java.util.LinkedHashSet<>();
        int page;
        long sourceCount, total;
        boolean hasMore, moreKnown, windowLimited, totalExact, loading, budget;
        String localSearch = "";
        java.util.function.Supplier<String> notice = () -> "", translationStatus = () -> "";
        CatalogHeader header;
        AlertDialog dialog;
        FishCatalogSession(FishSpeechSettings query, String queryKey) { this.query = query; this.queryKey = queryKey; }
        boolean matches(SpeechSettings current) { return queryKey.equals(VoiceCatalogLabels.queryKey(current)); }
        String moreButton() { return page > 0 && moreKnown && hasMore && !budget ? com.tokai.module.i18n.UiText.get(R.string.speech_load_more) : com.tokai.module.i18n.UiText.get(R.string.speech_refresh); }
        String status() {
            List<String> lines = new ArrayList<>();
            lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_loaded_count, entries.size()));
            if (page == 0) lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_cached_status));
            else {
                lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_page_number, page));
                if (windowLimited) lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_window_results));
                lines.add(totalExact ? com.tokai.module.i18n.UiText.get(R.string.speech_query_total, total) : com.tokai.module.i18n.UiText.get(R.string.speech_estimated_total, total));
                lines.add(moreKnown ? hasMore ? com.tokai.module.i18n.UiText.get(R.string.speech_catalog_more) : windowLimited ? com.tokai.module.i18n.UiText.get(R.string.speech_window_end) : com.tokai.module.i18n.UiText.get(R.string.speech_query_end)
                        : com.tokai.module.i18n.UiText.get(R.string.speech_more_unknown));
            }
            if (budget) lines.add(com.tokai.module.i18n.UiText.get(R.string.speech_catalog_budget));
            String message = notice.get();
            return String.join(" · ", lines) + (message.isEmpty() ? "" : "\n" + message);
        }
        String title() { return com.tokai.module.i18n.UiText.get(R.string.speech_fish_scope_title, "public".equals(query.voiceScope) ? com.tokai.module.i18n.UiText.get(R.string.speech_public_voices) : com.tokai.module.i18n.UiText.get(R.string.speech_my_voices), status()); }
    }

    private boolean currentCatalog(Object owner) {
        if (owner instanceof FishCatalogSession) return currentFishCatalog((FishCatalogSession) owner);
        return owner instanceof BreezeCatalogSession && currentBreezeCatalog((BreezeCatalogSession) owner);
    }

    private AlertDialog catalogDialog(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).dialog : ((BreezeCatalogSession) owner).dialog;
    }

    private CatalogHeader catalogHeader(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).header : ((BreezeCatalogSession) owner).header;
    }

    private boolean liveCatalog(Object owner) {
        if (!currentCatalog(owner)) return false;
        AlertDialog picker = catalogDialog(owner); CatalogHeader header = catalogHeader(owner);
        return picker != null && picker.isShowing() && header != null && !header.closed;
    }

    private List<VoiceCatalog.Entry> loadedCatalogEntries(Object owner) {
        return new ArrayList<>(owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).entries.values()
                : ((BreezeCatalogSession) owner).entries.values());
    }

    private String localCatalogSearch(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).localSearch : ((BreezeCatalogSession) owner).localSearch;
    }

    private void setLocalCatalogSearch(Object owner, String value) {
        if (owner instanceof FishCatalogSession) ((FishCatalogSession) owner).localSearch = value;
        else ((BreezeCatalogSession) owner).localSearch = value;
    }

    private String remoteCatalogSearch(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).query.voiceSearch : ((BreezeCatalogSession) owner).query.voiceSearch;
    }

    private void setCatalogTranslationStatus(Object owner, java.util.function.Supplier<String> value) {
        if (owner instanceof FishCatalogSession) ((FishCatalogSession) owner).translationStatus = value;
        else if (owner instanceof BreezeCatalogSession) ((BreezeCatalogSession) owner).translationStatus = value;
    }

    private String catalogTranslationStatus(Object owner) {
        return owner instanceof FishCatalogSession ? ((FishCatalogSession) owner).translationStatus.get() : ((BreezeCatalogSession) owner).translationStatus.get();
    }

    private void updateCatalogChoices(Object owner) {
        if (!liveCatalog(owner)) return;
        SpeechSettings speech = settings();
        String scope = VoiceNameTranslationCache.scope(speech);
        VoiceNameTranslationCache names = VoiceNameTranslationCache.read(store);
        String query = localCatalogSearch(owner);
        List<VoiceCatalog.Entry> visible = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean fish = owner instanceof FishCatalogSession;
        String selected = fish ? speech.fishSettings.referenceId : speech.breezeSettings.voiceId;
        int checked = -1;
        if (fish && matchesDefaultVoice(query)) {
            visible.add(null); labels.add(com.tokai.module.i18n.UiText.get(R.string.speech_default_voice)); if (selected.isEmpty()) checked = 0;
        }
        for (VoiceCatalog.Entry entry : loadedCatalogEntries(owner)) {
            if (!MetadataUiText.matchesVoice(scope, names, entry, query)) continue;
            if (entry.id.equals(selected)) checked = visible.size();
            visible.add(entry); labels.add(names.displayName(scope, entry) + " · " + entry.id
                    + (entry.labels.isEmpty() ? "" : "\n" + String.join(" · ", MetadataUiText.displayVoiceLabels(entry))));
        }
        CatalogHeader header = catalogHeader(owner);
        header.matched = visible.size() - (visible.contains(null) ? 1 : 0);
        MiuixDialogBuilder.updateSingleChoiceItems(catalogDialog(owner), labels.toArray(new CharSequence[0]), checked, (choice, index) -> {
            if (!liveCatalog(owner) || choice != catalogDialog(owner) || index < 0 || index >= visible.size()) return;
            VoiceCatalog.Entry entry = visible.get(index);
            if (fish) {
                store.putString(FishSpeechSettings.REFERENCE_ID, entry == null ? "" : entry.id);
                store.putString(FishSpeechSettings.REFERENCE_NAME, entry == null ? "" : entry.name);
                store.putString(FishSpeechSettings.VOICE_MODE, "single");
            } else {
                store.putString(BreezeSpeechSettings.VOICE_ID, entry.id);
                store.putString(BreezeSpeechSettings.VOICE_NAME, entry.name);
            }
            stopPreview(); choice.dismiss(); render();
        });
        updateCatalogHeader(owner);
    }

    private static boolean matchesDefaultVoice(String query) {
        String normalized = java.text.Normalizer.normalize(query == null ? "" : query, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).trim();
        String label = java.text.Normalizer.normalize(com.tokai.module.i18n.UiText.get(R.string.speech_default_voice), java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        for (String term : normalized.split("\\s+")) if (!label.contains(term)) return false;
        return true;
    }

    private void updateCatalogHeader(Object owner) {
        if (!liveCatalog(owner)) return;
        CatalogHeader header = catalogHeader(owner);
        String status;
        if (owner instanceof FishCatalogSession) {
            FishCatalogSession session = (FishCatalogSession) owner;
            header.title.setText("public".equals(session.query.voiceScope) ? com.tokai.module.i18n.UiText.get(R.string.speech_fish_header, com.tokai.module.i18n.UiText.get(R.string.speech_public_voices)) : com.tokai.module.i18n.UiText.get(R.string.speech_fish_header, com.tokai.module.i18n.UiText.get(R.string.speech_my_voices)));
            status = session.status();
        } else {
            BreezeCatalogSession session = (BreezeCatalogSession) owner;
            String range = "personal".equals(session.query.voiceType) ? com.tokai.module.i18n.UiText.get(R.string.speech_personal_voices) : "default".equals(session.query.voiceType) ? com.tokai.module.i18n.UiText.get(R.string.speech_system_voices) : com.tokai.module.i18n.UiText.get(R.string.speech_all_voices);
            header.title.setText(com.tokai.module.i18n.UiText.get(R.string.speech_breeze_header, range)); status = session.status();
        }
        if (!localCatalogSearch(owner).trim().isEmpty()) status = com.tokai.module.i18n.UiText.get(R.string.speech_local_matches, status, header.matched);
        SpeechSettings speech = settings();
        if (!store.getString(voiceSampleKey(speech), "").isEmpty() && !VoiceCatalogLabels.queryKey(speech)
                .equals(store.getString(VoiceCatalogLabels.queryStorageKey(speech), ""))) status = com.tokai.module.i18n.UiText.get(R.string.speech_cache_missing_filters, status);
        String remote = remoteCatalogSearch(owner);
        if (!remote.isEmpty()) status = com.tokai.module.i18n.UiText.get(R.string.speech_remote_search, status, remote);
        header.status.setText(status);
        header.clear.setText(remote.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_clear_search) : com.tokai.module.i18n.UiText.get(R.string.speech_clear_all));
        boolean running = nameTranslation != null && nameTranslationOwner == owner;
        header.translate.setText(running ? com.tokai.module.i18n.UiText.get(R.string.speech_cancel_translation) : com.tokai.module.i18n.UiText.get(R.string.speech_translate_names));
        String progress = catalogTranslationStatus(owner);
        header.translationStatus.setText(progress.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_translation_notice) : progress);
    }

    private void searchWholeCatalog(Object owner, String text) {
        if (!liveCatalog(owner)) return;
        CatalogHeader header = catalogHeader(owner);
        try {
            if (owner instanceof FishCatalogSession) {
                String query = FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_SEARCH, text);
                Map<String, Object> candidate = speechValues(); candidate.put(FishSpeechSettings.VOICE_SEARCH, query);
                if (!validateFishCatalog(new FishSpeechSettings(new LocalOptions(candidate)))) return;
                store.putString(FishSpeechSettings.VOICE_SEARCH, query); settingChanged(FishSpeechSettings.VOICE_SEARCH);
                refreshFishCatalog(settings().fishSettings);
            } else {
                String query = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_SEARCH, text);
                Map<String, Object> candidate = speechValues(); candidate.put(BreezeSpeechSettings.VOICE_SEARCH, query);
                if (!validateBreezeVoiceCatalog(new BreezeSpeechSettings(new LocalOptions(candidate)))) return;
                store.putString(BreezeSpeechSettings.VOICE_SEARCH, query); settingChanged(BreezeSpeechSettings.VOICE_SEARCH);
                refreshBreezeCatalog(settings().breezeSettings);
            }
        } catch (IllegalArgumentException failure) { header.search.setError(error(failure)); }
    }

    private void translateCatalogNames(Object owner) {
        if (!liveCatalog(owner)) return;
        if (nameTranslation != null && nameTranslationOwner == owner) {
            stopNameTranslation( () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_cancelled)); return;
        }
        stopNameTranslation( () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_another_started));
        String scope = VoiceNameTranslationCache.scope(settings());
        List<VoiceCatalog.Entry> originals = VoiceNameTranslationCache.read(store).pending(scope, loadedCatalogEntries(owner), VoiceNameTranslator.MAX_NAMES);
        if (originals.isEmpty()) { setCatalogTranslationStatus(owner, () ->  com.tokai.module.i18n.UiText.get(R.string.speech_translation_not_needed)); updateCatalogHeader(owner); return; }
        try {
            String model = store.getString(AiModelSettings.KEY, "");
            VoiceNameTranslator.Request request = VoiceNameTranslator.prepare(AiModelSettings.parse(model), originals);
            long generation = ++nameTranslationGeneration;
            nameTranslation = request; nameTranslationOwner = owner; nameTranslationModel = model;
            nameTranslationRequested = originals.size(); nameTranslationSaved = 0;
            setCatalogTranslationStatus(owner, () ->  com.tokai.module.i18n.UiText.get(R.string.speech_translating_count, nameTranslationRequested));
            updateCatalogHeader(owner);
            try {
                nameTranslationFuture = NAME_WORKER.submit(() -> {
                    try {
                        request.run(batch -> {
                            Map<String, String> copy = new LinkedHashMap<>(batch);
                            main.post(() -> {
                                if (!acceptNameTranslation(owner, request, generation, scope, model)) return;
                                VoiceNameTranslationCache.merge(store, scope, originals, copy);
                                nameTranslationSaved = originals.size() - VoiceNameTranslationCache.read(store)
                                        .pending(scope, originals, VoiceNameTranslator.MAX_NAMES).size();
                                int savedNames = nameTranslationSaved, requestedNames = nameTranslationRequested;
                                setCatalogTranslationStatus(owner, () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_progress, savedNames, requestedNames));
                                updateCatalogChoices(owner); render();
                            });
                        });
                        main.post(() -> {
                            if (!acceptNameTranslation(owner, request, generation, scope, model)) return;
                            nameTranslation = null; nameTranslationFuture = null; nameTranslationOwner = null;
                            int savedNames = nameTranslationSaved;
                            setCatalogTranslationStatus(owner, () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_complete, savedNames));
                            updateCatalogChoices(owner); updateCatalogHeader(owner);
                        });
                    } catch (Exception failure) {
                        main.post(() -> {
                            if (!acceptNameTranslation(owner, request, generation, scope, model)) return;
                            nameTranslation = null; nameTranslationFuture = null; nameTranslationOwner = null;
                            int savedNames = nameTranslationSaved;
                            setCatalogTranslationStatus(owner, () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_failure, error(failure), savedNames));
                            updateCatalogHeader(owner);
                        });
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                stopNameTranslation( () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_queue_full));
            }
        } catch (IllegalArgumentException failure) {
            setCatalogTranslationStatus(owner, () ->  error(failure)); updateCatalogHeader(owner);
        }
    }

    private boolean acceptNameTranslation(Object owner, VoiceNameTranslator.Request request, long generation, String scope, String model) {
        if (nameTranslation != request || nameTranslationOwner != owner || nameTranslationGeneration != generation) return false;
        if (liveCatalog(owner) && scope.equals(VoiceNameTranslationCache.scope(settings()))
                && model.equals(store.getString(AiModelSettings.KEY, ""))) return true;
        stopNameTranslation( () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_owner_changed)); return false;
    }

    private void stopNameTranslationFor(Object owner, java.util.function.Supplier<String> reason) {
        if (nameTranslationOwner == owner) stopNameTranslation(reason);
    }

    private void stopNameTranslation(java.util.function.Supplier<String> reason) {
        VoiceNameTranslator.Request request = nameTranslation;
        Object owner = nameTranslationOwner;
        nameTranslation = null; nameTranslationOwner = null; nameTranslationGeneration++;
        if (request != null) request.cancel();
        if (nameTranslationFuture != null) nameTranslationFuture.cancel(true);
        nameTranslationFuture = null;
        if (owner != null) { setCatalogTranslationStatus(owner, reason); updateCatalogHeader(owner); }
    }

    private final class CatalogHeader {
        final LinearLayout view = form();
        final TextView title = new TextView(context), status = new TextView(context), translationStatus = new TextView(context);
        final EditText search;
        final android.widget.Button filter, remote, clear, translate;
        final TextWatcher watcher;
        int matched;
        boolean closed;
        CatalogHeader(Object owner, String initial) {
            LinearLayout heading = new LinearLayout(context); heading.setOrientation(LinearLayout.HORIZONTAL); heading.setBaselineAligned(false); view.addView(heading, matchWrap());
            title.setTag("voice.catalog.title"); title.setTextSize(17); title.setMaxLines(2);
            heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            filter = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
            filter.setText(com.tokai.module.i18n.UiText.get(R.string.speech_filters)); filter.setTag("voice.catalog.filters"); filter.setAllCaps(false); filter.setMinWidth(0);
            heading.addView(MiuixFormControls.viewFor(filter), new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            status.setTag("voice.catalog.status"); status.setTextSize(12); status.setMaxLines(2);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END); view.addView(status, matchWrap());
            search = input(view, com.tokai.module.i18n.UiText.get(R.string.speech_search_loaded), initial, false, "搜索已加载音色");
            search.setTag("voice.catalog.search"); search.setSingleLine(true); search.setMaxLines(1);
            search.setInputType(InputType.TYPE_CLASS_TEXT);
            search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(256)});
            LinearLayout actions = new LinearLayout(context); actions.setOrientation(LinearLayout.HORIZONTAL); actions.setBaselineAligned(false); view.addView(actions, matchWrap());
            remote = catalogHeaderButton(actions, com.tokai.module.i18n.UiText.get(R.string.speech_search_all), "voice.catalog.search.all");
            remote.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.speech_search_all_description));
            clear = catalogHeaderButton(actions, com.tokai.module.i18n.UiText.get(R.string.speech_clear_search), "voice.catalog.search.clear");
            translate = catalogHeaderButton(actions, com.tokai.module.i18n.UiText.get(R.string.speech_translate_names), "voice.catalog.translate");
            translate.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.speech_translate_names_description));
            translationStatus.setTag("voice.catalog.translation.status"); translationStatus.setTextSize(12);
            translationStatus.setMaxLines(2); view.addView(translationStatus, matchWrap());
            watcher = new TextWatcher() {
                public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (closed || !liveCatalog(owner)) return;
                    setLocalCatalogSearch(owner, text.toString()); updateCatalogChoices(owner);
                }
                public void afterTextChanged(Editable text) { }
            };
            search.addTextChangedListener(watcher);
            filter.setOnClickListener(ignored -> {
                if (closed || !liveCatalog(owner)) return;
                catalogDialog(owner).dismiss();
                section(owner instanceof FishCatalogSession ? FISH_VOICES : BREEZE_VOICES);
            });
            remote.setOnClickListener(ignored -> { if (!closed) searchWholeCatalog(owner, search.getText().toString()); });
            clear.setOnClickListener(ignored -> {
                if (closed || !liveCatalog(owner)) return;
                if (remoteCatalogSearch(owner).isEmpty()) search.setText(""); else searchWholeCatalog(owner, "");
            });
            translate.setOnClickListener(ignored -> { if (!closed) translateCatalogNames(owner); });
        }
        void close() { closed = true; search.removeTextChangedListener(watcher); }
    }

    private android.widget.Button catalogHeaderButton(LinearLayout row, String text, String tag) {
        android.widget.Button button = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
        button.setText(text); button.setTag(tag); button.setAllCaps(false); button.setTextSize(11); button.setMinWidth(0);
        button.setPadding(dp(4), dp(6), dp(4), dp(6));
        row.addView(MiuixFormControls.viewFor(button), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    private void selectVoice() {
        if (closed) return;
        SpeechSettings settings = settings();
        if (settings.minimax || settings.breeze || settings.fish) {
            List<VoiceCatalog.Entry> entries = java.util.Collections.emptyList();
            try { entries = readCatalogSample(settings); }
            catch (Exception ignored) { }
            chooseVoices(entries);
            return;
        }
        try { chooseVoices(VoiceCatalog.parse(store.getString(VOICE_SAMPLE, ""), settings)); }
        catch (Exception ignored) {
            show(builder(com.tokai.module.i18n.UiText.get(R.string.speech_voice)).setItems(new String[]{com.tokai.module.i18n.UiText.get(R.string.speech_get_voice_catalog), com.tokai.module.i18n.UiText.get(R.string.speech_manual_voice_id)}, (choice, index) -> {
                if (index == 0) refreshVoices(); else manualVoice();
            }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null).create(), null);
        }
    }

    private void chooseVoices(List<VoiceCatalog.Entry> entries) {
        if (closed) return;
        SpeechSettings settings = settings();
        if (settings.fish) { chooseFishVoices(entries); return; }
        if (settings.breeze) { chooseBreezeVoices(entries); return; }
        boolean minimax = settings.minimax, breeze = settings.breeze, fish = settings.fish;
        List<VoiceCatalog.Entry> choices = new ArrayList<>(entries);
        int catalogOffset = fish ? 1 : 0;
        String[] labels = new String[choices.size() + catalogOffset];
        int selected = -1;
        String voiceKey = voiceIdKey(settings), nameKey = voiceNameKey(settings);
        String current = fish ? settings.fishSettings.referenceId : breeze ? settings.breezeSettings.voiceId
                : minimax ? settings.minimaxVoiceId : settings.selectedVoice;
        if (fish) {
            labels[0] = com.tokai.module.i18n.UiText.get(R.string.speech_default_voice);
            if (current.isEmpty()) selected = 0;
        }
        for (int i = 0; i < choices.size(); i++) {
            VoiceCatalog.Entry entry = choices.get(i);
            labels[i + catalogOffset] = entry.name + " · " + entry.id;
            if (entry.id.equals(current)) selected = i + catalogOffset;
        }
        AlertDialog.Builder picker = builder(fish ? com.tokai.module.i18n.UiText.get(R.string.speech_single_speaker_voices) : com.tokai.module.i18n.UiText.get(R.string.speech_voice)).setSingleChoiceItems(labels, selected, (choice, index) -> {
            if (breeze && !canUseBreeze()) return;
            if (fish && !canUseFish()) return;
            if (fish && index == 0) {
                store.putString(voiceKey, "");
                store.putString(nameKey, "");
            } else {
                VoiceCatalog.Entry entry = choices.get(index - catalogOffset);
                store.putString(voiceKey, entry.id);
                store.putString(nameKey, entry.name);
            }
            if (fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
            stopPreview();
            choice.dismiss();
            render();
        }).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.speech_enter_manually), (choice, index) -> main.post(this::manualVoice))
                .setNegativeButton(minimax || breeze || fish ? com.tokai.module.i18n.UiText.get(R.string.speech_close) : com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null);
        if (minimax || breeze || fish) {
            picker.setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_refresh), (choice, index) -> refreshVoices());
            if (entries.isEmpty() && !fish) picker.setMessage(com.tokai.module.i18n.UiText.get(R.string.speech_voice_no_cache));
        }
        show(picker.create(), null);
    }

    private void manualVoice() {
        if (closed) return;
        LinearLayout form = form();
        SpeechSettings settings = settings();
        boolean minimax = settings.minimax, breeze = settings.breeze, fish = settings.fish;
        EditText value = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_voice_id), fish ? settings.fishSettings.referenceId : breeze ? settings.breezeSettings.voiceId
                : minimax ? settings.minimaxVoiceId : settings.selectedVoice, false, "音色编号");
        if (fish) hint(form, "speech.fish.default.voice", com.tokai.module.i18n.UiText.get(R.string.speech_fish_default_voice_hint));
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.speech_voice_id)).setView(form).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.speech_save), null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (breeze && !canUseBreeze()) return;
            if (fish && !canUseFish()) return;
            String candidate = value.getText().toString().trim();
            if (candidate.isEmpty() && !fish) { value.setError(com.tokai.module.i18n.UiText.get(R.string.speech_voice_id_required)); return; }
            if (minimax) {
                try { candidate = SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_VOICE_ID, candidate); }
                catch (IllegalArgumentException invalid) { value.setError(error(invalid)); return; }
            }
            if (breeze) {
                try { candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_ID, candidate); }
                catch (IllegalArgumentException invalid) { value.setError(error(invalid)); return; }
            }
            if (fish) {
                try { candidate = FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_ID, candidate); }
                catch (IllegalArgumentException invalid) { value.setError(error(invalid)); return; }
            }
            store.putString(voiceIdKey(settings), candidate);
            store.putString(voiceNameKey(settings), "");
            if (fish) store.putString(FishSpeechSettings.VOICE_MODE, "single");
            stopPreview();
            editor.dismiss();
            render();
        });
    }

    private void testSpeech() {
        LinearLayout form = form();
        EditText text = input(form, com.tokai.module.i18n.UiText.get(R.string.speech_preview_text), "你好", false, "试听文本");
        TextView status = new TextView(context);
        status.setTag("speech.preview.status");
        form.addView(status, matchWrap());
        android.widget.Button start = button(form, com.tokai.module.i18n.UiText.get(R.string.speech_preview), "speech.preview.start");
        AlertDialog test = builder(com.tokai.module.i18n.UiText.get(R.string.speech_preview)).setView(form).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.speech_close), null).create();
        start.setOnClickListener(ignored -> {
            stopPreview();
            TokAiLog.Operation trace = TokAiLog.begin("speech_preview", "user_test_speech");
            previewTrace = trace;
            long generation = previewGeneration;
            start.setEnabled(false);
            status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_generating));
            trace.stage("synthesis_requested", "text_chars=" + text.length() + "; playback_started=false");
            trace.wrap(() -> preview = SpeechSynthesis.synthesize(context, text.getText().toString(), options(), (result, failure) -> {
                if (closed || generation != previewGeneration || !test.isShowing()) {
                    finishPreview(trace, TokAiLog.Outcome.CANCELLED, "preview_owner_changed", "playback_started=false");
                    delete(result); return;
                }
                preview = null;
                start.setEnabled(true);
                if (result == null) {
                    finishPreview(trace, failure != null && failure.contains("超时") ? TokAiLog.Outcome.TIMED_OUT
                            : failure != null && failure.contains("已停止") ? TokAiLog.Outcome.CANCELLED : TokAiLog.Outcome.FAILED,
                            "synthesis_not_ready", "playback_started=false; diagnostic_ref=" + TokAiLog.reference(failure));
                    status.setText(failure); return;
                }
                previewFile = result.file;
                trace.stage("audio_ready", "duration_ms=" + result.durationMillis + "; playback_started=false");
                try {
                    MediaPlayer preparing = new MediaPlayer();
                    player = preparing;
                    preparing.setDataSource(result.file.getAbsolutePath());
                    preparing.setOnPreparedListener(ready -> {
                        if (closed || generation != previewGeneration || player != ready || !test.isShowing()) {
                            finishPreview(trace, TokAiLog.Outcome.CANCELLED, "late_player_prepared", "playback_started=false"); return;
                        }
                        try { ready.start(); }
                        catch (RuntimeException error) {
                            trace.error("player_start", error);
                            finishPreview(trace, TokAiLog.Outcome.FAILED, "player_start_failed", "playback_completed=false");
                            throw error;
                        }
                        trace.stage("playing", "player_start_returned=true; playback_completed=false");
                        status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_playing));
                    });
                    preparing.setOnCompletionListener(completed -> {
                        if (generation != previewGeneration || player != completed) {
                            finishPreview(trace, TokAiLog.Outcome.CANCELLED, "late_player_completion", "owner_matches=false"); return;
                        }
                        finishPreview(trace, TokAiLog.Outcome.SUCCESS, "player_completion_observed", "evidence=owned_media_player_completion_callback");
                        stopPreview();
                        status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_preview_complete));
                    });
                    preparing.setOnErrorListener((failed, what, extra) -> {
                        if (generation == previewGeneration && player == failed) {
                            finishPreview(trace, TokAiLog.Outcome.FAILED, "player_error", "what=" + what + "; extra=" + extra + "; playback_completed=false");
                            stopPreview();
                            status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_playback_failed));
                        } else trace.stage("late_player_error", "ignored=true; what=" + what + "; extra=" + extra);
                        return true;
                    });
                    status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_preparing_playback));
                    trace.stage("player_preparing", "prepare_async_requested=true; playback_started=false");
                    preparing.prepareAsync();
                } catch (Exception error) {
                    trace.error("player_prepare", error);
                    finishPreview(trace, TokAiLog.Outcome.FAILED, "player_prepare_failed", "playback_completed=false");
                    stopPreview(); status.setText(com.tokai.module.i18n.UiText.get(R.string.speech_playback_failed));
                }
            })).run();
        });
        show(test, this::stopPreview);
    }

    private void stopPreview() {
        TokAiLog.Operation trace = previewTrace;
        previewTrace = null;
        if (trace != null) trace.finish(TokAiLog.Outcome.CANCELLED, "preview_stopped_or_replaced", "playback_completion_unobserved=true");
        previewGeneration++;
        if (preview != null) { preview.close(); preview = null; }
        if (player != null) { player.release(); player = null; }
        if (previewFile != null) { previewFile.delete(); previewFile = null; }
    }

    private void finishPreview(TokAiLog.Operation trace, TokAiLog.Outcome outcome, String reason, String evidence) {
        if (previewTrace == trace) previewTrace = null;
        trace.finish(outcome, reason, evidence);
    }

    private void settingChanged(String key) {
        stopPreview();
        stopNameTranslation( () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_settings_changed));
        if (key.startsWith("tts_breeze_")) stopEnhancement();
        if (key.startsWith("tts_voice_") || key.startsWith("tts_minimax_") || key.startsWith("tts_breeze_") || key.startsWith("tts_fish_")) cancelFetch();
        if (FishSpeechSettings.API_KEY.equals(key) || FishSpeechSettings.BASE_URL.equals(key)) {
            discardFishCatalog();
            clearCatalogSample(true);
            store.putString(FishSpeechSettings.MODEL_SAMPLE, "");
            store.putString(FishSpeechSettings.MODEL_SOURCE, "");
        } else if (isCatalogSetting(true, key)) {
            discardFishCatalog();
            clearCatalogSample(true);
        }
        if (BreezeSpeechSettings.API_KEY.equals(key) || BreezeSpeechSettings.BASE_URL.equals(key)) {
            discardBreezeCatalog();
            clearCatalogSample(false);
            store.putString(BreezeSpeechSettings.MODEL_SAMPLE, "");
            store.putString(BreezeSpeechSettings.MODEL_SOURCE, "");
        } else if (isCatalogSetting(false, key)) {
            discardBreezeCatalog();
            clearCatalogSample(false);
        }
        if (SpeechSettings.MINIMAX_API_KEY.equals(key) || SpeechSettings.MINIMAX_BASE_URL.equals(key)) {
            store.putString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "");
            if ("api".equals(store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, ""))) {
                store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "");
                store.putString(SpeechSettings.MINIMAX_MODEL_SOURCE, "");
            }
        }
    }

    private static boolean isCatalogSetting(boolean fish, String key) {
        return fish ? key.startsWith("tts_fish_voice_") && !FishSpeechSettings.VOICE_MODE.equals(key) && !FishSpeechSettings.VOICE_SAMPLE.equals(key)
                : key.startsWith("tts_breeze_voice_") && !BreezeSpeechSettings.VOICE_ID.equals(key)
                && !BreezeSpeechSettings.VOICE_NAME.equals(key) && !BreezeSpeechSettings.VOICE_SAMPLE.equals(key);
    }

    private void cancelFetch() {
        SpeechHttpClient.Request request = fetch;
        fetch = null;
        if (request != null) request.cancel();
        if (future != null) future.cancel(true);
        future = null;
        if (fishCatalog != null && fishCatalog.loading) {
            fishCatalog.loading = false; updateFishCatalogControls(fishCatalog);
        }
        if (breezeCatalog != null && breezeCatalog.loading) {
            breezeCatalog.loading = false; updateBreezeCatalogControls(breezeCatalog);
        }
    }

    private LocalOptions options() {
        return new LocalOptions(speechValues());
    }

    private Map<String, Object> speechValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : new String[]{SpeechSettings.PROVIDER, SpeechSettings.GET_TEMPLATE, SpeechSettings.HEADERS,
                SpeechSettings.AUDIO_FIELD, SpeechSettings.VOICE_GET_TEMPLATE, SpeechSettings.VOICE_HEADERS,
                SpeechSettings.VOICE_LIST_PATH, SpeechSettings.VOICE_ID_PATH, SpeechSettings.VOICE_NAME_PATH,
                SpeechSettings.SELECTED_VOICE, SpeechSettings.SELECTED_VOICE_NAME, "tts_engine", "tts_language", SpeechSettings.RATE, SpeechSettings.PITCH,
                SpeechSettings.MINIMAX_API_KEY, SpeechSettings.MINIMAX_BASE_URL, SpeechSettings.MINIMAX_MODEL,
                SpeechSettings.MINIMAX_VOICE_ID, SpeechSettings.MINIMAX_VOICE_NAME, SpeechSettings.MINIMAX_SPEED,
                SpeechSettings.MINIMAX_VOLUME, SpeechSettings.MINIMAX_PITCH}) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        for (String key : MiniMaxSpeechOptions.KEYS) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        for (String key : BreezeSpeechSettings.KEYS) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        for (String key : FishSpeechSettings.KEYS) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        return values;
    }

    private SpeechSettings settings() { return new SpeechSettings(options()); }

    private static String parameterNames(String template) {
        try {
            List<String> names = new ArrayList<>();
            for (ThirdPartyRequestTemplate.Parameter parameter : ThirdPartyRequestTemplate.parse(template, "{text}", "{voice}").parameters) names.add(parameter.name);
            return names.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.speech_no_parameters) : String.join("、", names);
        } catch (Exception ignored) { return com.tokai.module.i18n.UiText.get(R.string.speech_not_set); }
    }

    private void option(String title, String value, Runnable action, String stableTitle) {
        option(content, title, value, action, stableTitle);
    }

    private void option(LinearLayout target, String title, String value, Runnable action, String stableTitle) {
        if (miuix) {
            target.addView(new MiuixSettingsViews.Option(context, title, "", value, true, action, stableTitle).getView(), matchWrap());
        } else {
            LinearLayout row = form();
            row.setTag("settings.option." + stableTitle);
            TextView label = new TextView(context);
            label.setText(title);
            label.setTextSize(15);
            row.addView(label, matchWrap());
            if (value != null && !value.isEmpty()) {
                TextView detail = new TextView(context);
                detail.setTag("settings.value." + stableTitle);
                detail.setText(value);
                detail.setMaxLines(3);
                row.addView(detail, matchWrap());
            }
            row.setOnClickListener(ignored -> action.run());
            target.addView(row, matchWrap());
        }
    }

    private EditText input(LinearLayout form, String label, String initial, boolean secret, String stableTitle) {
        EditText input = miuix ? MiuixFormControls.input(context, label, initial, secret, stableTitle) : new EditText(context);
        input.setContentDescription(label);
        input.setTag("settings.input." + stableTitle);
        input.setText(initial);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setMaxLines(6);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2 * 1024 * 1024)});
        form.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private android.widget.Button button(LinearLayout form, String text, String tag) {
        android.widget.Button button = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
        button.setText(text);
        button.setTag(tag);
        form.addView(MiuixFormControls.viewFor(button), matchWrap());
        return button;
    }

    private void icon(LinearLayout row, String label, String tag, int resource, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(resource);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setTag(tag);
        button.setOnClickListener(ignored -> action.run());
        row.addView(miuix ? MiuixExtraControls.iconAction(context, button, resource) : button,
                new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private MiuixDialogBuilder builder(String title) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuix);
        builder.setTitle(title);
        return builder;
    }
    private void show(Dialog dialog, Runnable dismiss) { presenter.accept(dialog, dismiss); }
    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(12, 8, 12, 8);
        return form;
    }
    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static String error(Throwable failure) { return failure.getMessage() == null ? com.tokai.module.i18n.UiText.get(R.string.speech_request_parse_failed) : failure.getMessage(); }
    private void toast(String value) { android.widget.Toast.makeText(context, value, android.widget.Toast.LENGTH_SHORT).show(); }
    private static void delete(LocalSpeechSynthesis.Result result) { if (result != null) result.file.delete(); }
    @Override public void close() {
        closed = true;
        stopNameTranslation( () -> com.tokai.module.i18n.UiText.get(R.string.speech_translation_settings_closed));
        cancelFetch();
        discardFishCatalog();
        discardBreezeCatalog();
        stopPreview();
        stopEnhancement();
        instructionEditor = null;
        for (ReferenceEditorSession session : new ArrayList<>(referenceEditors)) session.close();
        for (Dialog child : new ArrayList<>(sectionDialogs.values())) child.dismiss();
        sectionDialogs.clear();
        sectionContents.clear();
        advancedContent = null;
    }
    private static final class ParameterRow {
        LinearLayout container;
        LinearLayout fixedValue;
        EditText name;
        EditText value;
        Spinner source;
    }
}
