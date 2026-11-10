package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.tokai.module.config.SettingsStore;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.ThirdPartyDownloadSettings;
import com.tokai.module.config.ThirdPartyRequestTemplate;
import com.tokai.module.diagnostics.TokAiLog;
import com.tokai.module.feature.ThirdPartyGetClient;
import com.tokai.module.feature.ThirdPartyResponse;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixExtraControls;
import com.tokai.module.ui.miuix.MiuixFormControls;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/** Dialogs for one third-party GET configuration and its response fields. */
final class ThirdPartyDownloadEditor {
    private static final int MAX_SAMPLE_CHARS = 2 * 1024 * 1024;
    private final Context context;
    private final SettingsStore repository;
    private final boolean useMiuix;
    private final BiConsumer<Dialog, Runnable> presenter;

    ThirdPartyDownloadEditor(Context context, SettingsStore repository, boolean useMiuix,
                             BiConsumer<Dialog, Runnable> presenter) {
        this.context = ModuleTheme.themedContext(context);
        this.repository = repository;
        this.useMiuix = useMiuix;
        this.presenter = presenter;
    }

    String valuePreview(String key, String fallback, boolean secret) {
        String value = ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key)
                ? requestSettings().getTemplate : repository.getString(key, fallback);
        return value.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_not_set) : secret ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_configured) : value;
    }

    String signatureMode() { return requestSettings().signatureMode; }

    String signaturePreview() {
        return ThirdPartyDownloadSettings.SIGNATURE_MD5.equals(signatureMode()) ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_signature_md5) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_signature);
    }

    void chooseSignature(Runnable changed) {
        ThirdPartyDownloadSettings config = requestSettings();
        String[] modes = {ThirdPartyDownloadSettings.SIGNATURE_NONE, ThirdPartyDownloadSettings.SIGNATURE_MD5};
        show(builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_signature_mode)).setSingleChoiceItems(new String[]{com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_signature), com.tokai.module.i18n.UiText.get(R.string.editor_ui_signature_md5)},
                ThirdPartyDownloadSettings.SIGNATURE_MD5.equals(config.signatureMode) ? 1 : 0, (dialog, which) -> {
                    // Keep the resolved legacy link binding when an explicit signature mode is first saved.
                    repository.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, config.getTemplate);
                    repository.putString(ThirdPartyDownloadSettings.SIGNATURE_MODE, modes[which]);
                    dialog.dismiss();
                    changed.run();
                }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).create(), null);
    }

    String parametersPreview() {
        try {
            List<String> names = new ArrayList<>();
            for (ThirdPartyRequestTemplate.Parameter parameter : requestTemplate().parameters) {
                names.add(parameter.name);
            }
            return names.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_request_parameters) : String.join("、", names);
        } catch (Exception failure) { return com.tokai.module.i18n.UiText.get(R.string.editor_ui_needs_setup); }
    }

    String samplePreview() {
        try {
            ThirdPartyResponse response = response();
            return com.tokai.module.i18n.UiText.get(R.string.editor_ui_sample_field_count, response.values().size(), response.downloadFields().size());
        } catch (Exception failure) {
            return com.tokai.module.i18n.UiText.get(R.string.editor_ui_pending_parse);
        }
    }

    String fieldsPreview(boolean naming) {
        List<String> fields = selected(naming);
        return fields.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_not_selected) : String.join(naming ? " → " : "、", fields);
    }

    void editValue(String title, String key, String fallback, boolean secret, Runnable changed) { editValue(title, key, fallback, secret, changed, title); }

    void editValue(String title, String key, String fallback, boolean secret, Runnable changed, String stableTitle) {
        LinearLayout form = form();
        EditText input = input(form, title, ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key)
                ? requestSettings().getTemplate : repository.getString(key, fallback), secret,
                ThirdPartyDownloadSettings.HEADERS.equals(key) ? 32768 : 8192, stableTitle);
        AlertDialog dialog = builder(title).setView(scroll(form))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), null).create();
        show(dialog, null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String value = input.getText().toString();
            if (!secret) value = value.trim();
            try {
                if (ThirdPartyDownloadSettings.HEADERS.equals(key)) {
                    JSONObject headers = new JSONObject(value.isEmpty() ? "{}" : value);
                    java.util.Iterator<String> names = headers.keys();
                    while (names.hasNext()) {
                        if (!(headers.get(names.next()) instanceof String)) {
                            throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.editor_ui_headers_strings_required));
                        }
                    }
                    value = headers.toString();
                }
                if (ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key)) {
                    ThirdPartyRequestTemplate.parse(value);
                    saveTemplate(value);
                } else {
                    if (ThirdPartyDownloadSettings.SIGNATURE_HEADER.equals(key)
                            && !value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                        throw new IllegalArgumentException(com.tokai.module.i18n.UiText.get(R.string.editor_ui_header_name_invalid));
                    }
                    repository.putString(key, value);
                }
                changed.run();
                dialog.dismiss();
            } catch (Exception failure) { input.setError(error(failure)); }
        });
    }

    void editParameters(Runnable changed) {
        final ThirdPartyRequestTemplate template;
        try { template = requestTemplate(); }
        catch (Exception failure) { toast(error(failure)); return; }
        LinearLayout form = form();
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = text("", 14);
        count.setTag("third_party.parameters.count");
        toolbar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(toolbar, matchWrap());
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        form.addView(rows, matchWrap());
        List<ParameterRow> draft = new ArrayList<>();
        int[] nextId = {0};
        Runnable updateCount = () -> count.setText(draft.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_request_parameters) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_request_parameter_count, draft.size()));
        for (ThirdPartyRequestTemplate.Parameter parameter : template.parameters) {
            addParameterRow(rows, draft, nextId[0]++, parameter, updateCount);
        }
        updateCount.run();
        icon(toolbar, com.tokai.module.i18n.UiText.get(R.string.editor_ui_add_parameter), "third_party.parameter.add", android.R.drawable.ic_input_add, () -> {
            addParameterRow(rows, draft, nextId[0]++, new ThirdPartyRequestTemplate.Parameter("", ""), updateCount);
            updateCount.run();
        });
        AlertDialog dialog = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_request_parameters)).setView(scroll(form))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), null).create();
        show(dialog, null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            List<ThirdPartyRequestTemplate.Parameter> parameters = new ArrayList<>();
            for (ParameterRow row : draft) {
                String name = row.name.getText().toString().trim();
                if (name.isEmpty()) { row.name.setError(com.tokai.module.i18n.UiText.get(R.string.editor_ui_parameter_name_required)); return; }
                row.name.setError(null);
                parameters.add(new ThirdPartyRequestTemplate.Parameter(name,
                        row.source.getSelectedItemPosition() == 1 ? "{url}" : row.value.getText().toString()));
            }
            try {
                saveTemplate(template.withParameters(parameters));
                changed.run();
                dialog.dismiss();
            } catch (Exception failure) { toast(error(failure)); }
        });
    }

    private void addParameterRow(LinearLayout rows, List<ParameterRow> draft, int id,
                                 ThirdPartyRequestTemplate.Parameter parameter, Runnable updateCount) {
        ParameterRow row = new ParameterRow();
        row.container = new LinearLayout(context);
        row.container.setOrientation(LinearLayout.VERTICAL);
        row.container.setPadding(0, dp(8), 0, dp(12));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_parameter), 14);
        heading.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        icon(heading, com.tokai.module.i18n.UiText.get(R.string.editor_ui_remove_parameter), "third_party.parameter.remove." + id, android.R.drawable.ic_menu_delete, () -> {
            draft.remove(row);
            rows.removeView(row.container);
            updateCount.run();
        });
        row.container.addView(heading, matchWrap());
        row.name = input(row.container, com.tokai.module.i18n.UiText.get(R.string.editor_ui_parameter_name), parameter.name, false, 1024, "参数名");
        row.name.setTag("third_party.parameter.name." + id);
        row.name.setMaxLines(1);
        row.name.setSingleLine(true);
        if (!useMiuix) row.container.addView(text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_parameter_source), 14), matchWrap());
        row.source = useMiuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        row.source.setContentDescription(com.tokai.module.i18n.UiText.get(R.string.editor_ui_parameter_source));
        row.source.setTag("third_party.parameter.source." + id);
        row.source.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                new String[]{com.tokai.module.i18n.UiText.get(R.string.editor_ui_fixed_value), com.tokai.module.i18n.UiText.get(R.string.editor_ui_post_link)}));
        row.container.addView(MiuixFormControls.viewFor(row.source), matchWrap());
        row.fixedValue = new LinearLayout(context);
        row.fixedValue.setOrientation(LinearLayout.VERTICAL);
        row.container.addView(row.fixedValue, matchWrap());
        row.value = input(row.fixedValue, com.tokai.module.i18n.UiText.get(R.string.editor_ui_parameter_value), "{url}".equals(parameter.value) ? "" : parameter.value, false, 32768, "参数值");
        row.value.setTag("third_party.parameter.value." + id);
        row.source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long itemId) {
                row.fixedValue.setVisibility(position == 1 ? View.GONE : View.VISIBLE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        boolean workUrl = "{url}".equals(parameter.value);
        row.source.setSelection(workUrl ? 1 : 0);
        row.fixedValue.setVisibility(workUrl ? View.GONE : View.VISIBLE);
        draft.add(row);
        rows.addView(row.container, matchWrap());
    }

    private static final class ParameterRow {
        LinearLayout container;
        LinearLayout fixedValue;
        EditText name;
        Spinner source;
        EditText value;
    }

    void editSample(Runnable changed) {
        LinearLayout form = form();
        EditText sample = input(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_json_txt_response), sample(), false, MAX_SAMPLE_CHARS, "JSON / TXT 响应");
        sample.setTag("third_party.sample");
        sample.setMaxLines(12);
        Button parse = button(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_parse_preview), "third_party.parse");
        TextView preview = output(form, "third_party.preview");
        parse.setOnClickListener(ignored -> {
            try {
                preview.setText(preview(ThirdPartyResponse.parse(sample.getText().toString())));
                sample.setError(null);
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
        AlertDialog dialog = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_response_sample)).setView(scroll(form))
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), null).create();
        show(dialog, null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String value = sample.getText().toString().trim();
            try {
                ThirdPartyResponse.parse(value);
                repository.putString(ThirdPartyDownloadSettings.SAMPLE, value);
                changed.run();
                dialog.dismiss();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    void chooseDownloadFields(Runnable changed) {
        final ThirdPartyResponse response;
        try { response = response(); }
        catch (Exception failure) { toast(error(failure)); return; }
        LinkedHashSet<String> candidates = new LinkedHashSet<>(response.downloadFields().keySet());
        for (Map.Entry<String, String> value : response.values().entrySet()) {
            if ("[]".equals(value.getValue())) candidates.add(value.getKey());
        }
        List<String> selected = selected(false);
        candidates.addAll(selected);
        LinearLayout form = form();
        Map<String, CheckBox> choices = new LinkedHashMap<>();
        for (String path : candidates) {
            List<String> urls = response.downloadFields().get(path);
            String label = path + " · " + (urls == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_links) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_link_count, urls.size()));
            CheckBox choice = useMiuix ? MiuixFormControls.checkbox(context, label, selected.contains(path), path)
                    : new CheckBox(context);
            choice.setText(label);
            choice.setChecked(selected.contains(path));
            choice.setTag("third_party.field." + path);
            choices.put(path, choice);
            form.addView(MiuixFormControls.viewFor(choice), matchWrap());
        }
        EditText extra = input(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_extra_field_paths), "", false, 8192, "其他字段路径，每行一个");
        extra.setTag("third_party.extra_fields");
        show(builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_download_fields)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), (dialog, which) -> {
                    LinkedHashSet<String> next = new LinkedHashSet<>();
                    for (Map.Entry<String, CheckBox> choice : choices.entrySet()) {
                        if (choice.getValue().isChecked()) next.add(choice.getKey());
                    }
                    for (String path : extra.getText().toString().split("\\r?\\n")) {
                        if (!path.trim().isEmpty()) next.add(path.trim());
                    }
                    repository.putString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS,
                            ThirdPartyDownloadSettings.encodeFields(new ArrayList<>(next)));
                    changed.run();
                }).create(), null);
    }

    void chooseNamingFields(Runnable changed) {
        final ThirdPartyResponse response;
        try { response = response(); }
        catch (Exception failure) { toast(error(failure)); return; }
        List<String> draft = new ArrayList<>(selected(true));
        LinearLayout form = form();
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setTag("third_party.naming_rows");
        form.addView(rows, matchWrap());
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            for (int index = 0; index < draft.size(); index++) {
                final int position = index;
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(48));
                TextView name = text((index + 1) + ". " + draft.get(index), 14);
                name.setTag("third_party.naming.path." + index);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                icon(row, com.tokai.module.i18n.UiText.get(R.string.editor_ui_move_up), "third_party.naming.up." + index, android.R.drawable.arrow_up_float,
                        () -> { if (position > 0) { java.util.Collections.swap(draft, position, position - 1); render[0].run(); } });
                icon(row, com.tokai.module.i18n.UiText.get(R.string.editor_ui_move_down), "third_party.naming.down." + index, android.R.drawable.arrow_down_float,
                        () -> { if (position + 1 < draft.size()) { java.util.Collections.swap(draft, position, position + 1); render[0].run(); } });
                icon(row, com.tokai.module.i18n.UiText.get(R.string.editor_ui_remove_field), "third_party.naming.remove." + index, android.R.drawable.ic_menu_delete,
                        () -> { draft.remove(position); render[0].run(); });
                rows.addView(row, matchWrap());
            }
        };
        render[0].run();
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_naming_fields)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null)
                .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), (dialog, which) -> {
                    repository.putString(ThirdPartyDownloadSettings.NAMING_FIELDS,
                            ThirdPartyDownloadSettings.encodeFields(draft));
                    changed.run();
                }).create();
        button(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_add_field), "third_party.naming.add").setOnClickListener(ignored -> {
            List<String> candidates = new ArrayList<>(response.values().keySet());
            candidates.removeIf(path -> "[]".equals(response.values().get(path)));
            candidates.removeAll(draft);
            show(builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_add_naming_field)).setSingleChoiceItems(candidates.toArray(new String[0]), -1,
                    (dialog, which) -> {
                        draft.add(candidates.get(which));
                        render[0].run();
                        dialog.dismiss();
                    }).setNeutralButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_enter_path), (dialog, which) ->
                            addNamingPath(draft, render[0], editor)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).create(), null);
        });
        show(editor, null);
    }

    private void addNamingPath(List<String> draft, Runnable render, Dialog parent) {
        // Present after the chooser's dismissal so the new dialog stays in its parent's branch.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!parent.isShowing()) return;
            LinearLayout form = form();
            EditText path = input(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_field_path), "", false, 1024, "字段路径");
            AlertDialog dialog = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_add_naming_field)).setView(form).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null)
                    .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_add), null).create();
            show(dialog, null);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                String value = path.getText().toString().trim();
                if (value.isEmpty()) { path.setError(com.tokai.module.i18n.UiText.get(R.string.editor_ui_field_path_required)); return; }
                if (!draft.contains(value)) draft.add(value);
                render.run();
                dialog.dismiss();
            });
        });
    }

    void testGet(Runnable changed) {
        LinearLayout form = form();
        EditText share = input(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_share_text_hint), "", false, 32768, "作品链接或分享文本");
        share.setTag("third_party.share");
        Button fetch = button(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_test_get), "third_party.fetch");
        TextView status = output(form, "third_party.status");
        TextView preview = output(form, "third_party.preview");
        Button saveSample = button(form, com.tokai.module.i18n.UiText.get(R.string.editor_ui_save_response_sample), "third_party.save_sample");
        saveSample.setEnabled(false);
        String[] result = new String[1];
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ThirdPartyGetClient.Request[] pending = new ThirdPartyGetClient.Request[1];
        TokAiLog.Operation[] activeTrace = new TokAiLog.Operation[1], sampleOwner = new TokAiLog.Operation[1];
        Future<?>[] future = new Future<?>[1];
        boolean[] closed = {false};
        Handler handler = new Handler(Looper.getMainLooper());
        AlertDialog dialog = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_test_get)).setView(scroll(form)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null).create();
        Runnable cancel = () -> {
            closed[0] = true;
            TokAiLog.Operation trace = activeTrace[0];
            activeTrace[0] = null;
            if (trace != null) trace.finish(TokAiLog.Outcome.CANCELLED, "test_dialog_closed", "response_preview_not_completed=true");
            if (pending[0] != null) pending[0].cancel();
            pending[0] = null;
            if (future[0] != null) future[0].cancel(true);
            future[0] = null;
            executor.shutdownNow();
        };
        fetch.setOnClickListener(ignored -> {
            if (closed[0] || pending[0] != null) return;
            String input = share.getText().toString();
            ThirdPartyDownloadSettings config = requestSettings();
            TokAiLog.Operation trace = TokAiLog.begin("third_party_get_test", "user_test_get");
            trace.stage("validate_input", "template_ref=" + TokAiLog.reference(config.getTemplate)
                    + "; share_chars=" + input.length() + "; network_started=false");
            try {
                if (config.getTemplate.contains("{url}")) ThirdPartyGetClient.extractShareUrl(input);
            }
            catch (Exception failure) {
                trace.error("validate_input", failure);
                trace.finish(TokAiLog.Outcome.SKIPPED, "invalid_share_link", "network_started=false");
                share.setError(error(failure)); return;
            }
            share.setError(null);
            ThirdPartyGetClient.Request request = new ThirdPartyGetClient.Request();
            pending[0] = request;
            activeTrace[0] = trace;
            sampleOwner[0] = null;
            result[0] = null;
            fetch.setEnabled(false);
            saveSample.setEnabled(false);
            status.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_requesting));
            preview.setText("");
            trace.stage("request_queued", "business_result=pending");
            future[0] = executor.submit(trace.wrap(() -> {
                try {
                    trace.stage("request_started", "business_result=pending");
                    String body = request.fetch(config, input);
                    trace.stage("response_received", "chars=" + body.length() + "; parsed=false; sample_ref=" + TokAiLog.reference(body));
                    ThirdPartyResponse parsed = ThirdPartyResponse.parse(body);
                    trace.stage("response_parsed", "value_fields=" + parsed.values().size() + "; download_fields=" + parsed.downloadFields().size());
                    String display = preview(parsed);
                    handler.post(trace.wrap(() -> {
                        if (closed[0] || !dialog.isShowing() || pending[0] != request) {
                            trace.finish(TokAiLog.Outcome.CANCELLED, "late_response_owner_changed", "preview_displayed=false"); return;
                        }
                        pending[0] = null;
                        activeTrace[0] = null;
                        sampleOwner[0] = trace;
                        future[0] = null;
                        result[0] = body;
                        preview.setText(display);
                        status.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_parse_success));
                        fetch.setEnabled(true);
                        saveSample.setEnabled(true);
                        trace.finish(TokAiLog.Outcome.SUCCESS, "parsed_preview_displayed", "value_fields=" + parsed.values().size()
                                + "; download_fields=" + parsed.downloadFields().size() + "; preview_chars=" + display.length()
                                + "; sample_saved=false; media_downloaded=false");
                    }));
                } catch (Exception failure) {
                    trace.error("request_or_parse", failure);
                    handler.post(trace.wrap(() -> {
                        if (closed[0] || !dialog.isShowing() || pending[0] != request) {
                            trace.finish(TokAiLog.Outcome.CANCELLED, "late_failure_owner_changed", "preview_displayed=false"); return;
                        }
                        pending[0] = null;
                        activeTrace[0] = null;
                        future[0] = null;
                        status.setText(error(failure));
                        fetch.setEnabled(true);
                        String message = failure.getMessage();
                        trace.finish(failure instanceof java.util.concurrent.CancellationException ? TokAiLog.Outcome.CANCELLED
                                        : failure instanceof java.net.SocketTimeoutException || message != null && (message.contains("timed out") || message.contains("超时"))
                                        ? TokAiLog.Outcome.TIMED_OUT : TokAiLog.Outcome.FAILED,
                                "request_or_response_parse_failed", "preview_displayed=false; sample_saved=false");
                    }));
                }
            }));
        });
        saveSample.setOnClickListener(ignored -> {
            if (result[0] == null || closed[0]) return;
            Runnable save = () -> {
                TokAiLog.Operation trace = TokAiLog.begin("third_party_response_sample", "save_test_response");
                try {
                    repository.putString(ThirdPartyDownloadSettings.SAMPLE, result[0]);
                    trace.stage("settings_save_returned", "sample_ref=" + TokAiLog.reference(result[0]) + "; disk_commit=unconfirmed");
                    changed.run();
                    status.setText(com.tokai.module.i18n.UiText.get(R.string.editor_ui_sample_saved));
                    trace.finish(TokAiLog.Outcome.UNCONFIRMED, "sample_settings_write_returned", "readback_not_performed=true; disk_commit=unconfirmed");
                } catch (RuntimeException failure) {
                    trace.error("sample_save_or_refresh", failure);
                    trace.finish(TokAiLog.Outcome.FAILED, "sample_save_or_refresh_failed", "disk_commit=unconfirmed");
                    throw failure;
                }
            };
            if (sampleOwner[0] == null) save.run(); else sampleOwner[0].wrap(save).run();
        });
        try { show(dialog, cancel); }
        catch (RuntimeException failure) { cancel.run(); throw failure; }
    }

    private String sample() {
        return repository.getString(ThirdPartyDownloadSettings.SAMPLE, ThirdPartyDownloadSettings.DEFAULT_SAMPLE);
    }

    private ThirdPartyRequestTemplate requestTemplate() {
        return ThirdPartyRequestTemplate.parse(requestSettings().getTemplate);
    }

    private void saveTemplate(String value) {
        ThirdPartyRequestTemplate.parse(value);
        String mode = signatureMode();
        repository.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, value);
        repository.putString(ThirdPartyDownloadSettings.SIGNATURE_MODE, mode);
    }

    private ThirdPartyDownloadSettings requestSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : new String[]{ThirdPartyDownloadSettings.GET_TEMPLATE,
                ThirdPartyDownloadSettings.APP_ID, ThirdPartyDownloadSettings.APP_SECRET,
                ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_HEADER,
                ThirdPartyDownloadSettings.HEADERS}) {
            String value = repository.getString(key, null);
            if (value != null) values.put(key, value);
        }
        return new ThirdPartyDownloadSettings(new LocalOptions(values));
    }

    private ThirdPartyResponse response() throws Exception { return ThirdPartyResponse.parse(sample()); }

    private List<String> selected(boolean naming) {
        return ThirdPartyDownloadSettings.fields(repository.getString(
                naming ? ThirdPartyDownloadSettings.NAMING_FIELDS : ThirdPartyDownloadSettings.DOWNLOAD_FIELDS,
                naming ? ThirdPartyDownloadSettings.DEFAULT_NAMING_FIELDS : ThirdPartyDownloadSettings.DEFAULT_DOWNLOAD_FIELDS));
    }

    private static String preview(ThirdPartyResponse response) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<String>> field : response.downloadFields().entrySet()) {
            text.append(com.tokai.module.i18n.UiText.get(R.string.editor_ui_link_field_line, field.getKey(), field.getValue().size()));
        }
        for (Map.Entry<String, String> field : response.values().entrySet()) {
            text.append(field.getKey()).append(": ").append(compact(field.getValue(), 160)).append('\n');
        }
        return text.length() > 65536 ? text.substring(0, 65536) : text.toString().trim();
    }

    private static String compact(String value, int maximum) {
        return value == null ? "null" : value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }

    private MiuixDialogBuilder builder(String title) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, useMiuix);
        builder.setTitle(title);
        return builder;
    }

    private void show(Dialog dialog, Runnable dismiss) { presenter.accept(dialog, dismiss); }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(4), dp(16), dp(8));
        return form;
    }

    private EditText input(LinearLayout form, String label, String value, boolean secret, int maximum, String stableTitle) {
        if (!useMiuix) form.addView(text(label, 14), matchWrap());
        EditText input = useMiuix ? MiuixFormControls.input(context, label, value, secret, stableTitle) : new EditText(context);
        input.setContentDescription(label);
        input.setTag("settings.input." + stableTitle);
        input.setText(value);
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setMaxLines(secret ? 1 : 6);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maximum)});
        form.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private Button button(LinearLayout form, String label, String tag) {
        Button button = useMiuix ? MiuixFormControls.button(context) : new Button(context);
        button.setText(label);
        button.setTag(tag);
        form.addView(MiuixFormControls.viewFor(button), matchWrap());
        return button;
    }

    private TextView output(LinearLayout form, String tag) {
        TextView output = text("", 13);
        output.setTextIsSelectable(true);
        output.setTag(tag);
        output.setPadding(0, dp(8), 0, dp(8));
        form.addView(output, matchWrap());
        return output;
    }

    private void icon(LinearLayout row, String label, String tag, int resource, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(resource);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setTag(tag);
        button.setOnClickListener(ignored -> action.run());
        row.addView(useMiuix ? MiuixExtraControls.iconAction(context, button, resource) : button,
                new LinearLayout.LayoutParams(dp(40), dp(48)));
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(31, 39, 45));
        return text;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static String error(Throwable failure) {
        String message = failure.getMessage();
        return compact(message == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_parse_failed) : message, 220);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
