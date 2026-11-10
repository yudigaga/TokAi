package com.tokai.module.ui;

import com.tokai.module.R;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.config.AiProviderPresets;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.feature.ChatCompletionClient;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;
import com.tokai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

/** Owns one model draft, its persistence, child dialogs, and cancellable catalog request. */
final class AiModelEditor implements AutoCloseable {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private final Context context;
    private final SettingsStore repository;
    private final boolean useMiuix;
    private final boolean activateOnSave;
    private final Runnable onDelete;
    private final Runnable onChanged;
    private final ExecutorService requests;
    private final List<Dialog> children = new ArrayList<>();
    private AlertDialog dialog;
    private ChatCompletionClient.ModelCatalogRequest modelRequest;
    private Future<?> modelFetch;
    private TokAiLog.Operation catalogTrace;
    private Future<?> avatarLoad;
    private HostImagePicker avatarPicker;
    private Bitmap avatarPreview;
    private boolean loadingAvatar;
    private boolean closed;
    private Runnable bindButtons;

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged) {
        this(context, repository, original, useMiuix, onChanged, Executors.newSingleThreadExecutor());
    }

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, ExecutorService requests) {
        this(context, repository, original, useMiuix, onChanged, requests, true);
    }

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, boolean activateOnSave) {
        this(context, repository, original, useMiuix, onChanged,
                Executors.newSingleThreadExecutor(), activateOnSave, null);
    }

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original, boolean useMiuix,
                  Runnable onChanged, boolean activateOnSave, Runnable onDelete) {
        this(context, repository, original, useMiuix, onChanged,
                Executors.newSingleThreadExecutor(), activateOnSave, onDelete);
    }

    private AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, ExecutorService requests, boolean activateOnSave) {
        this(context, repository, original, useMiuix, onChanged, requests, activateOnSave, null);
    }

    private AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, ExecutorService requests, boolean activateOnSave,
                  Runnable onDelete) {
        this.context = ModuleTheme.themedContext(context);
        this.repository = repository;
        this.useMiuix = useMiuix;
        this.activateOnSave = activateOnSave;
        this.onDelete = onDelete == null ? () -> {
            saveAiSettings(aiSettings().delete(original.id));
            onChanged.run();
        } : onDelete;
        this.onChanged = onChanged;
        this.requests = requests;
        build(original);
    }

    AlertDialog dialog() { return dialog; }

    void show(BiConsumer<Dialog, Runnable> presenter) {
        if (closed) return;
        try {
            presenter.accept(dialog, this::close);
            bindButtons.run();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelCatalog();
        if (avatarPicker != null) avatarPicker.close();
        avatarPicker = null;
        if (avatarLoad != null) avatarLoad.cancel(true);
        avatarLoad = null;
        for (Dialog child : new ArrayList<>(children)) child.dismiss();
        children.clear();
        requests.shutdownNow();
        if (dialog.isShowing()) dialog.dismiss();
        if (avatarPreview != null) avatarPreview.recycle();
        avatarPreview = null;
    }

    private void cancelCatalog() {
        if (catalogTrace != null) { catalogTrace.finish(TokAiLog.Outcome.CANCELLED, "model_catalog_cancelled", "dialog_closed_or_request_replaced"); catalogTrace = null; }
        ChatCompletionClient.ModelCatalogRequest pending = modelRequest;
        modelRequest = null;
        if (pending != null) pending.cancel();
        if (modelFetch != null) modelFetch.cancel(true);
        modelFetch = null;
    }

    private void showChild(Dialog child) {
        if (closed || !dialog.isShowing()) return;
        children.removeIf(existing -> !existing.isShowing());
        children.add(child);
        child.setOwnerActivity(dialog.getOwnerActivity());
        child.setOnDismissListener(ignored -> children.remove(child));
        child.show();
    }

    private AiModelSettings aiSettings() { return AiModelSettings.read(repository); }
    private void saveAiSettings(AiModelSettings settings) {
        TokAiLog.Operation trace = TokAiLog.begin("model_settings", "save_model_configuration");
        try {
            settings.save(repository);
            trace.finish(TokAiLog.Outcome.UNCONFIRMED, "settings_save_returned", "persistent_readback=not_performed;credentials_omitted=true");
        } catch (RuntimeException failure) {
            trace.error("save", failure); trace.finish(TokAiLog.Outcome.FAILED, "model_settings_failed", "credentials_omitted=true"); throw failure;
        }
    }

    private void build(AiModelConfig original) {
        LinearLayout form = form();
        String[] contactAvatar = {original.messageContactAvatar};
        ImageView avatar = new ImageView(context);
        avatar.setTag("ai.message_contact_avatar_preview");
        avatar.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_change_description));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable avatarShape = new android.graphics.drawable.GradientDrawable();
        avatarShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        avatarShape.setColor(Color.rgb(246, 248, 248));
        avatar.setBackground(avatarShape);
        avatar.setClipToOutline(true);
        avatar.setFocusable(true);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        avatarParams.gravity = Gravity.CENTER_HORIZONTAL;
        avatarParams.topMargin = dp(8);
        avatarParams.bottomMargin = dp(12);
        form.addView(avatar, avatarParams);
        updateAvatarPreview(avatar, contactAvatar[0]);
        Button chooseAvatar = formButton();
        chooseAvatar.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_choose_contact_avatar));
        chooseAvatar.setTag("ai.message_contact_avatar");
        Button resetAvatar = formButton();
        resetAvatar.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_restore_avatar));
        resetAvatar.setTag("ai.message_contact_avatar_reset");
        resetAvatar.setOnClickListener(ignored -> {
            if (loadingAvatar) { toast(com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_processing_wait)); return; }
            contactAvatar[0] = "";
            updateAvatarPreview(avatar, "");
        });
        chooseAvatar.setOnClickListener(ignored -> {
            if (closed || loadingAvatar || avatarPicker != null) return;
            avatarPicker = HostImagePicker.launch(ownerActivity(), uri -> {
                avatarPicker = null;
                if (closed || uri == null) return;
                loadingAvatar = true;
                chooseAvatar.setEnabled(false);
                chooseAvatar.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_processing));
                avatarLoad = requests.submit(() -> {
                    try {
                        String selected = AiContactAvatar.fromSelection(context, uri);
                        form.post(() -> {
                            if (closed) return;
                            contactAvatar[0] = selected;
                            updateAvatarPreview(avatar, selected);
                            finishAvatarLoad(chooseAvatar);
                        });
                    } catch (Exception failure) {
                        form.post(() -> {
                            if (closed) return;
                            finishAvatarLoad(chooseAvatar);
                            toast(safeError(failure));
                        });
                    } catch (OutOfMemoryError failure) {
                        form.post(() -> {
                            if (closed) return;
                            finishAvatarLoad(chooseAvatar);
                            toast(com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_too_large));
                        });
                    }
                });
            });
            if (avatarPicker == null) toast(com.tokai.module.i18n.UiText.get(R.string.editor_ui_image_picker_unavailable));
        });
        avatar.setOnClickListener(ignored -> {
            if (loadingAvatar) { toast(com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_processing_wait)); return; }
            showChild(new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_contact_avatar))
                    .setItems(new String[]{com.tokai.module.i18n.UiText.get(R.string.editor_ui_choose_image), com.tokai.module.i18n.UiText.get(R.string.editor_ui_restore_default)}, (child, which) -> {
                        if (which == 0) chooseAvatar.performClick();
                        else resetAvatar.performClick();
                    }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).create());
        });
        EditText name = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_config_name), original.name, false, "配置名称");
        EditText contactName = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_contact_name_hint), original.messageContactName, false, "联系人名字（留空使用配置名称）");
        contactName.setSingleLine(true);
        contactName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        // Created now for the draft reader; placed after all configuration fields below.
        CheckBox messageContact = !useMiuix ? new CheckBox(context)
                : MiuixFormControls.checkbox(context, com.tokai.module.i18n.UiText.get(R.string.editor_ui_add_message_contact), original.messageContactEnabled, "加入TikTok消息界面");
        messageContact.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_add_message_contact));
        messageContact.setTextColor(TEXT);
        messageContact.setChecked(original.messageContactEnabled);
        messageContact.setTag("ai.message_contact_enabled");
        Spinner provider = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        List<String> providers = new ArrayList<>();
        for (AiProviderPresets.Preset preset : AiProviderPresets.ALL) providers.add("custom".equals(preset.id) ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_provider) : preset.name);
        provider.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, providers));
        provider.setTag("ai.provider");
        int providerIndex = 0;
        for (int i = 0; i < AiProviderPresets.ALL.size(); i++) {
            if (AiProviderPresets.ALL.get(i).id.equals(original.provider)) providerIndex = i;
        }
        provider.setSelection(providerIndex);
        addField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_provider), provider);

        Spinner protocol = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        protocol.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                AiModelConfig.PROTOCOL_LABELS));
        protocol.setTag("ai.protocol");
        protocol.setSelection(protocolIndex(original.protocol));
        addField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_api_protocol), protocol);

        Spinner responseMode = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        responseMode.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.editor_ui_response_non_streaming), com.tokai.module.i18n.UiText.get(R.string.editor_ui_response_streaming)}));
        responseMode.setTag("ai.response_mode");
        responseMode.setSelection(responseModeIndex(original.responseMode));
        addField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_response_mode), responseMode);
        form.addView(text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_response_mode_hint),
                12, MUTED, false), matchWrap());

        Spinner reasoningEffort = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        reasoningEffort.setTag("ai.reasoning_effort");
        String[][] reasoningValues = {setReasoningChoices(reasoningEffort, original.protocol, original.reasoningEffort)};
        addField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_reasoning_effort), reasoningEffort);
        form.addView(text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_reasoning_hint),
                12, MUTED, false), matchWrap());

        int[] lastProtocolSelection = {protocolIndex(original.protocol)};
        protocol.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == lastProtocolSelection[0]) return;
                lastProtocolSelection[0] = position;
                String selectedEffort = reasoningValues[0][reasoningEffort.getSelectedItemPosition()];
                reasoningValues[0] = setReasoningChoices(reasoningEffort, AiModelConfig.PROTOCOLS[position], selectedEffort);
            }
        });

        EditText endpoint = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_api_base_url), original.apiEndpoint, false, "API 基础地址");
        EditText key = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_api_key), original.apiKey, true, "API 密钥");
        EditText modelName = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_model_name), original.modelName, false, "模型名称");
        EditText modelsEndpoint = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_models_url_optional), original.modelsEndpoint, false, "模型列表地址（可选）");
        Button fetchModels = formButton();
        fetchModels.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_fetch_models));
        fetchModels.setTag("ai.fetch_models");
        form.addView(MiuixFormControls.viewFor(fetchModels), matchWrap());

        CheckBox allowHttp = checkField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_allow_http), original.allowInsecureHttp, "允许不安全 HTTP");
        String[] systemPrompt = {original.systemPrompt};
        TextView memory = text(promptPreview(original.systemPrompt), 14, MUTED, false);
        memory.setTag("ai.prompt.primary");
        memory.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_primary_prompt));
        memory.setMaxLines(3);
        memory.setEllipsize(android.text.TextUtils.TruncateAt.END);
        memory.setPadding(0, dp(8), 0, dp(8));
        memory.setMinimumHeight(dp(48));
        memory.setClickable(true);
        memory.setFocusable(true);
        MiuixSettingsViews.Option[] promptRow = new MiuixSettingsViews.Option[1];
        Runnable editMemory = () -> showChild(new PromptEditorDialog(context, com.tokai.module.i18n.UiText.get(R.string.editor_ui_primary_prompt), systemPrompt[0], value -> {
            systemPrompt[0] = value;
            memory.setText(promptPreview(value));
            if (promptRow[0] != null) promptRow[0].setValue(promptPreview(value));
        }, useMiuix, "一级提示词"));
        if (useMiuix) {
            promptRow[0] = new MiuixSettingsViews.Option(context, com.tokai.module.i18n.UiText.get(R.string.editor_ui_primary_prompt), null,
                    promptPreview(original.systemPrompt), true, editMemory, "一级提示词");
            promptRow[0].getView().setTag("ai.prompt.primary");
            form.addView(promptRow[0].getView(), matchWrap());
        } else {
            memory.setOnClickListener(ignored -> editMemory.run());
            addField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_primary_prompt), memory);
        }
        EditText contextRounds = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_context_turns), Integer.toString(original.contextRounds), false, "上下文轮数（0–50）");
        EditText timeout = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_timeout_seconds), Integer.toString(original.timeoutSeconds), false, "超时秒数（5–120）");
        CheckBox temperatureEnabled = checkField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_set_temperature), original.temperatureEnabled, "设置温度");
        EditText temperature = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_temperature), Double.toString(original.temperature), false, "温度");
        bindOptionalInput(temperatureEnabled, temperature);
        CheckBox maxTokensEnabled = checkField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_limit_output_tokens), original.maxTokensEnabled, "限制最大输出 Token");
        EditText maxTokens = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_max_output_tokens), Integer.toString(original.maxTokens), false, "最大输出 Token");
        bindOptionalInput(maxTokensEnabled, maxTokens);
        CheckBox topPEnabled = checkField(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_set_top_p), original.topPEnabled, "设置 Top P");
        EditText topP = formInput(form, "Top P", Double.toString(original.topP), false, "Top P");
        bindOptionalInput(topPEnabled, topP);
        EditText headers = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_headers_json), original.customHeaders, false, "自定义请求头 JSON");
        EditText parameters = formInput(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_parameters_json), original.customParameters, false, "自定义参数 JSON");
        TextView status = text("", 12, MUTED, false);
        status.setTag("ai.status");
        status.setVisibility(View.GONE);
        form.addView(status, matchWrap());
        Callable<AiModelConfig> readDraft = () -> {
            if (loadingAvatar) throw new IllegalStateException(com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_wait_save));
            return buildAiConfig(original, name, provider, protocol, responseMode,
                    reasoningValues[0][reasoningEffort.getSelectedItemPosition()], endpoint,
                    key, modelName, modelsEndpoint, systemPrompt[0], contextRounds, timeout, allowHttp,
                    temperatureEnabled, temperature, maxTokensEnabled, maxTokens,
                    topPEnabled, topP, headers, parameters, messageContact.isChecked(),
                    contactName.getText().toString(), contactAvatar[0]);
        };

        int[] lastProviderSelection = {providerIndex};
        provider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == lastProviderSelection[0]) return;
                lastProviderSelection[0] = position;
                AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(position);
                protocol.setSelection(protocolIndex(preset.protocol));
                if (!preset.apiEndpoint.isEmpty()) endpoint.setText(preset.apiEndpoint);
                if (name.getText().toString().trim().isEmpty()
                        || name.getText().toString().equals(original.name)) name.setText(preset.name);
                if ("ollama".equals(preset.id) || "lmstudio".equals(preset.id)) allowHttp.setChecked(true);
            }
        });

        dialog = new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_model_config))
                .setView(scrolling(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null)
                .setPositiveButton(activateOnSave ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_save_enable) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), null).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_delete), null).create();
        dialog.setOnDismissListener(ignored -> close());
        bindButtons = () -> {
            boolean exists = false;
            for (AiModelConfig configured : aiSettings().configs) exists |= configured.id.equals(original.id);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(exists ? View.VISIBLE : View.GONE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(ignored -> {
                try { onDelete.run(); }
                catch (RuntimeException failure) { toast(safeError(failure)); return; }
                close();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                try {
                    AiModelConfig draft = readDraft.call();
                    draft.validate(true);
                    saveAiSettings(aiSettings().upsert(draft, activateOnSave));
                    close();
                    onChanged.run();
                } catch (Exception failure) { toast(safeError(failure)); }
            });
        };

        fetchModels.setOnClickListener(ignored -> {
            try {
                if (closed || modelRequest != null) return;
                AiModelConfig draft = readDraft.call();
                draft.validate(false);
                fetchModels.setEnabled(false);
                status.setVisibility(View.VISIBLE);
                status.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_fetching_models));
                ChatCompletionClient.ModelCatalogRequest request = new ChatCompletionClient.ModelCatalogRequest();
                modelRequest = request;
                TokAiLog.Operation trace = TokAiLog.begin("model_settings", "fetch_model_catalog");
                catalogTrace = trace;
                modelFetch = requests.submit(trace.wrap(() -> {
                    try {
                        trace.stage("catalog_request", "credentials_omitted=true");
                        List<String> fetched = request.fetch(draft);
                        status.post(trace.wrap(() -> {
                            if (closed || !dialog.isShowing() || modelRequest != request) { trace.finish(TokAiLog.Outcome.CANCELLED, "model_editor_invalidated", "catalog_result_discarded=true"); return; }
                            modelRequest = null;
                            modelFetch = null;
                            catalogTrace = null;
                            trace.finish(TokAiLog.Outcome.SUCCESS, "model_catalog_parsed", "model_count=" + fetched.size());
                            fetchModels.setEnabled(true);
                            if (fetched.isEmpty()) { status.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_models_manual)); return; }
                            String[] values = fetched.toArray(new String[0]);
                            showChild(new MiuixDialogBuilder(context, useMiuix).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_choose_model))
                                    .setItems(values, (child, selected) -> modelName.setText(values[selected]))
                                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).create());
                            status.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_models_fetched, fetched.size()));
                        }));
                    } catch (Exception failure) {
                        trace.error("catalog_request", failure);
                        trace.finish(failure instanceof java.net.SocketTimeoutException ? TokAiLog.Outcome.TIMED_OUT : TokAiLog.Outcome.FAILED, "model_catalog_failed", "response_content_omitted=true");
                        status.post(trace.wrap(() -> {
                            if (closed || !dialog.isShowing() || modelRequest != request) return;
                            modelRequest = null;
                            modelFetch = null;
                            catalogTrace = null;
                            fetchModels.setEnabled(true);
                            status.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_fetch_failed, safeError(failure)));
                        }));
                    }
                }));
            } catch (Exception failure) {
                cancelCatalog();
                fetchModels.setEnabled(true);
                toast(safeError(failure));
            }
        });

        Button test = formButton();
        test.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_test_chat));
        test.setTag("ai.test_chat");
        form.addView(MiuixFormControls.viewFor(test), matchWrap());
        test.setOnClickListener(ignored -> {
            try {
                AiModelConfig draft = readDraft.call();
                draft.validate(true);
                showChild(new AiTestChatDialog(context, draft, useMiuix));
            } catch (Exception failure) { toast(safeError(failure)); }
        });
        form.addView(MiuixFormControls.viewFor(messageContact), matchWrap());
    }

    private void finishAvatarLoad(Button chooseAvatar) {
        loadingAvatar = false;
        avatarLoad = null;
        chooseAvatar.setEnabled(true);
        chooseAvatar.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_choose_contact_avatar));
    }

    private void updateAvatarPreview(ImageView view, String value) {
        Bitmap old = avatarPreview;
        avatarPreview = AiContactAvatar.load(value);
        if (avatarPreview == null) view.setImageResource(android.R.drawable.sym_def_app_icon);
        else view.setImageBitmap(avatarPreview);
        if (old != null) old.recycle();
    }

    private Activity ownerActivity() {
        if (dialog != null && dialog.getOwnerActivity() != null) return dialog.getOwnerActivity();
        Context candidate = context;
        while (candidate instanceof ContextWrapper) {
            if (candidate instanceof Activity) return (Activity) candidate;
            Context base = ((ContextWrapper) candidate).getBaseContext();
            if (base == candidate) break;
            candidate = base;
        }
        return null;
    }

    private void addField(LinearLayout parent, String label, View input) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        parent.addView(heading, params);
        input.setContentDescription(label);
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
    }

    private CheckBox checkField(LinearLayout parent, String label, boolean checked, String stableTitle) {
        CheckBox box = !useMiuix ? new CheckBox(context)
                : MiuixFormControls.checkbox(context, label, checked, stableTitle);
        box.setText(label);
        box.setTextColor(TEXT);
        box.setChecked(checked);
        parent.addView(MiuixFormControls.viewFor(box), matchWrap());
        return box;
    }

    private static int protocolIndex(String protocol) {
        for (int i = 0; i < AiModelConfig.PROTOCOLS.length; i++) {
            if (AiModelConfig.PROTOCOLS[i].equals(protocol)) return i;
        }
        return 0;
    }

    private static int responseModeIndex(String mode) {
        for (int i = 0; i < AiModelConfig.RESPONSE_MODES.length; i++) {
            if (AiModelConfig.RESPONSE_MODES[i].equals(mode)) return i;
        }
        return 0;
    }

    private String[] setReasoningChoices(Spinner spinner, String protocol, String selected) {
        List<String> values = new ArrayList<>(java.util.Arrays.asList(AiModelConfig.reasoningEfforts(protocol)));
        List<String> labels = new ArrayList<>(values);
        labels.set(0, com.tokai.module.i18n.UiText.get(R.string.editor_ui_model_default));
        int index = values.indexOf(selected);
        if (index < 0) {
            index = values.size();
            values.add(selected);
            labels.add(com.tokai.module.i18n.UiText.get(R.string.editor_ui_protocol_inapplicable, selected));
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(index);
        return values.toArray(new String[0]);
    }

    private static void bindOptionalInput(CheckBox toggle, EditText input) {
        input.setEnabled(toggle.isChecked());
        toggle.setOnCheckedChangeListener((button, enabled) -> input.setEnabled(enabled));
    }

    private AiModelConfig buildAiConfig(AiModelConfig original, EditText name, Spinner provider, Spinner protocol,
                                       Spinner responseMode, String reasoningEffort,
                                       EditText endpoint, EditText key, EditText model,
                                       EditText modelsEndpoint, String prompt, EditText rounds,
                                       EditText timeout, CheckBox allowHttp, CheckBox temperatureEnabled,
                                       EditText temperature, CheckBox maxTokensEnabled, EditText maxTokens,
                                       CheckBox topPEnabled, EditText topP, EditText headers,
                                       EditText parameters, boolean messageContactEnabled,
                                       String messageContactName, String messageContactAvatar) throws Exception {
        AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(provider.getSelectedItemPosition());
        return AiModelConfig.fromJson(new JSONObject().put("id", original.id).put("name", name.getText().toString())
                .put("provider", preset.id).put("protocol", AiModelConfig.PROTOCOLS[protocol.getSelectedItemPosition()])
                .put("responseMode", AiModelConfig.RESPONSE_MODES[responseMode.getSelectedItemPosition()])
                .put("reasoningEffort", reasoningEffort)
                .put("apiEndpoint", endpoint.getText().toString()).put("apiKey", key.getText().toString())
                .put("modelName", model.getText().toString()).put("modelsEndpoint", modelsEndpoint.getText().toString())
                .put("systemPrompt", prompt)
                .put("contextRounds", Integer.parseInt(rounds.getText().toString().trim()))
                .put("timeoutSeconds", Integer.parseInt(timeout.getText().toString().trim()))
                .put("allowInsecureHttp", allowHttp.isChecked())
                .put("temperatureEnabled", temperatureEnabled.isChecked())
                .put("temperature", temperatureEnabled.isChecked() ? Double.parseDouble(temperature.getText().toString().trim()) : original.temperature)
                .put("maxTokensEnabled", maxTokensEnabled.isChecked())
                .put("maxTokens", maxTokensEnabled.isChecked() ? Integer.parseInt(maxTokens.getText().toString().trim()) : original.maxTokens)
                .put("topPEnabled", topPEnabled.isChecked()).put("topP", topPEnabled.isChecked() ? Double.parseDouble(topP.getText().toString().trim()) : original.topP)
                .put("customHeaders", headers.getText().toString().trim().isEmpty() ? "{}" : headers.getText().toString())
                .put("customParameters", parameters.getText().toString().trim().isEmpty() ? "{}" : parameters.getText().toString())
                .put("messageContactEnabled", messageContactEnabled).put("messageContactName", messageContactName)
                .put("messageContactAvatar", messageContactAvatar));
    }

    private EditText formInput(LinearLayout parent, String label, String value, boolean secret, String stableTitle) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = dp(10);
        parent.addView(heading, headingParams);
        EditText input = !useMiuix ? new EditText(context)
                : MiuixFormControls.input(context, label, value, secret, stableTitle);
        input.setContentDescription(label);
        input.setText(value);
        input.setTextSize(14);
        input.setMaxLines(secret ? 1 : 4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        return form;
    }

    private ScrollView scrolling(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("ai.model.scroll");
        scroll.addView(child);
        return scroll;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static String promptPreview(String value) {
        if (value.isEmpty()) return com.tokai.module.i18n.UiText.get(R.string.editor_ui_not_set);
        int points = value.codePointCount(0, value.length());
        return value.substring(0, value.offsetByCodePoints(0, Math.min(240, points)));
    }

    private static String safeError(Throwable failure) {
        if (failure instanceof AiContactAvatar.AvatarException) {
            switch (((AiContactAvatar.AvatarException) failure).issue) {
                case READ: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_read_error);
                case CONVERT: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_avatar_convert_error);
            }
        }
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_operation_failed) : message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private Button formButton() {
        return !useMiuix ? new Button(context) : MiuixFormControls.button(context);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
