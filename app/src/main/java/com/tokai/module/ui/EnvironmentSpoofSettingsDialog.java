package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.tokai.module.config.EnvironmentSpoofSettings;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.config.RegionPreset;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;
import com.tokai.module.ui.miuix.MiuixFormControls;
import com.tokai.module.ui.miuix.MiuixSettingsViews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Independent, host-local controls for the four environment options recovered from Toki. */
final class EnvironmentSpoofSettingsDialog implements AutoCloseable {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final String[][] LANGUAGE_PRESETS = {{"ja-JP", "日本語"}, {"zh-TW", "繁體中文 (台灣)"},
            {"zh-HK", "繁體中文 (香港)"}, {"en-US", "English (US)"}, {"ko-KR", "한국어"},
            {"th-TH", "ไทย"}, {"vi-VN", "Tiếng Việt"}, {"id-ID", "Bahasa Indonesia"}};
    private static final String[] TIME_ZONE_PRESETS = {"Asia/Tokyo", "Asia/Taipei", "Asia/Hong_Kong",
            "America/New_York", "Europe/London", "Asia/Seoul", "Asia/Bangkok", "Asia/Singapore"};
    private static final String[][] GPS_PRESETS = {{"东京", "35.6762", "139.6503"}, {"台北", "25.0330", "121.5654"},
            {"香港", "22.3193", "114.1694"}, {"首尔", "37.5665", "126.9780"},
            {"纽约", "40.7128", "-74.0060"}, {"伦敦", "51.5074", "-0.1278"},
            {"曼谷", "13.7563", "100.5018"}, {"新加坡", "1.3521", "103.8198"}};
    private final Context context;
    private final SettingsStore store;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final LinearLayout content;
    private final AlertDialog dialog;
    private final List<Dialog> children = new ArrayList<>();
    private boolean closed;

    EnvironmentSpoofSettingsDialog(Context baseContext, SettingsStore store, boolean miuix,
                                    BiConsumer<Dialog, Runnable> presenter) {
        context = ModuleTheme.themedContext(baseContext);
        this.store = store;
        this.miuix = miuix;
        this.presenter = presenter;
        content = form();
        render();
        dialog = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_environment_spoof)).setView(scroll(content)).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_back), null).create();
    }

    AlertDialog dialog() { return dialog; }

    private EnvironmentSpoofSettings settings() {
        return EnvironmentSpoofSettings.read(new LocalOptions(store.snapshot()));
    }

    private void render() {
        content.removeAllViews();
        EnvironmentSpoofSettings current = settings();
        TextView restart = text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_restart_spoof_hint), 13, MUTED);
        restart.setTag("environment.restart");
        content.addView(restart, matchWrap());
        option(content, com.tokai.module.i18n.UiText.get(R.string.editor_ui_target_region), com.tokai.module.i18n.UiText.get(R.string.editor_ui_region_hint), regionLabel(current.regionCode),
                true, this::chooseRegion, "目标地区");
        section(com.tokai.module.i18n.UiText.get(R.string.editor_ui_sim_carrier));
        toggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_sim_spoof), com.tokai.module.i18n.UiText.get(R.string.editor_ui_sim_spoof_hint), "sim_spoof_enabled", current.simEnabled, "SIM 地区伪装");
        editable(com.tokai.module.i18n.UiText.get(R.string.editor_ui_carrier_code), com.tokai.module.i18n.UiText.get(R.string.editor_ui_carrier_code_hint), "sim_operator_code", "", false, true, "运营商代码");
        editable(com.tokai.module.i18n.UiText.get(R.string.editor_ui_carrier_name), com.tokai.module.i18n.UiText.get(R.string.editor_ui_region_default_hint), "sim_operator_name", "", false, true, "运营商名称");
        section(com.tokai.module.i18n.UiText.get(R.string.editor_ui_language));
        toggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_language_spoof), "", "language_spoof_enabled", current.languageEnabled, "语言伪装");
        toggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_language_follow_region), "", "language_follow_region", current.languageFollowRegion, "语言跟随地区");
        editable(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_language), com.tokai.module.i18n.UiText.get(R.string.editor_ui_language_tag_hint), "custom_language", "ja-JP", false, !current.languageFollowRegion, "自定义语言");
        section(com.tokai.module.i18n.UiText.get(R.string.editor_ui_time_zone));
        toggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_time_zone_spoof), "", "timezone_spoof_enabled", current.timeZoneEnabled, "时区伪装");
        toggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_time_zone_follow_region), "", "timezone_follow_region", current.timeZoneFollowRegion, "时区跟随地区");
        editable(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_time_zone), com.tokai.module.i18n.UiText.get(R.string.editor_ui_time_zone_hint), "custom_timezone", "Asia/Tokyo", false, !current.timeZoneFollowRegion, "自定义时区");
        section(com.tokai.module.i18n.UiText.get(R.string.editor_ui_gps));
        toggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_gps_spoof), "", "gps_spoof_enabled", current.gpsEnabled, "GPS 定位伪装");
        toggle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_gps_follow_region), com.tokai.module.i18n.UiText.get(R.string.editor_ui_gps_follow_region_hint), "gps_follow_region", current.gpsFollowRegion, "定位跟随地区");
        editable(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_latitude), com.tokai.module.i18n.UiText.get(R.string.editor_ui_latitude_hint), "custom_latitude", "35.6762", true, !current.gpsFollowRegion, "自定义纬度");
        editable(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_longitude), com.tokai.module.i18n.UiText.get(R.string.editor_ui_longitude_hint), "custom_longitude", "139.6503", true, !current.gpsFollowRegion, "自定义经度");
        section(com.tokai.module.i18n.UiText.get(R.string.editor_ui_resolved_settings));
        TextView preview = text(preview(current), 13, MUTED);
        preview.setTag("environment.preview");
        preview.setTextIsSelectable(true);
        content.addView(preview, matchWrap());
        ModuleTheme.bind(content);
    }

    private static String preview(EnvironmentSpoofSettings settings) {
        return com.tokai.module.i18n.UiText.get(R.string.editor_ui_spoof_preview, settings.regionCode, state(settings.simEnabled), settings.operatorCode, settings.operatorName, state(settings.languageEnabled), settings.languageTag, state(settings.timeZoneEnabled), settings.timeZoneId, state(settings.gpsEnabled), settings.latitude, settings.longitude);
    }

    private static String state(boolean enabled) { return enabled ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_enabled) : com.tokai.module.i18n.UiText.get(R.string.editor_ui_disabled); }

    private static String regionLabel(String code) {
        RegionPreset preset = RegionPreset.find(code);
        return preset == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_region, code) : countryName(preset.code) + " · " + preset.englishName + " · " + preset.code;
    }

    private static String countryName(String code) { return new java.util.Locale("", code).getDisplayCountry(com.tokai.module.i18n.ModuleLocale.locale()); }
    private static List<RegionPreset> searchRegions(String query) {
        String term = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<RegionPreset> result = new ArrayList<>();
        for (RegionPreset item : RegionPreset.all()) if (term.isEmpty() || countryName(item.code).toLowerCase(java.util.Locale.ROOT).contains(term) || item.chineseName.toLowerCase(java.util.Locale.ROOT).contains(term) || item.englishName.toLowerCase(java.util.Locale.ROOT).contains(term) || item.code.toLowerCase(java.util.Locale.ROOT).contains(term)) result.add(item);
        return result;
    }
    private static String gpsPresetLabel(int index) {
        switch (index) {
            case 0: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_tokyo);
            case 1: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_taipei);
            case 2: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_hong_kong);
            case 3: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_seoul);
            case 4: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_new_york);
            case 5: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_london);
            case 6: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_bangkok);
            case 7: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_city_singapore);
            default: throw new AssertionError(index);
        }
    }

    private static String validationError(String key, String value) {
        EnvironmentSpoofSettings.ValidationIssue issue = EnvironmentSpoofSettings.validateIssue(key, value);
        if (issue == null) return null;
        switch (issue) {
            case NULL: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_null);
            case LENGTH: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_length);
            case COUNTRY: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_country);
            case CARRIER: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_carrier);
            case LANGUAGE: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_language);
            case TIMEZONE: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_timezone);
            case LATITUDE: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_latitude);
            case LONGITUDE: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_validation_longitude);
            default: throw new AssertionError(issue);
        }
    }

    private void chooseRegion() {
        if (closed) return;
        LinearLayout panel = form();
        EditText search = input(com.tokai.module.i18n.UiText.get(R.string.editor_ui_search_region), "", false, "搜索国家/地区");
        search.setTag("environment.region.search");
        search.setHint(com.tokai.module.i18n.UiText.get(R.string.editor_ui_search_region_hint));
        panel.addView(MiuixFormControls.viewFor(search), matchWrap());
        AlertDialog[] pickerOwner = new AlertDialog[1];
        option(panel, com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_region_code), com.tokai.module.i18n.UiText.get(R.string.editor_ui_country_code_hint), settings().regionCode, true,
                () -> editCustomRegion(() -> pickerOwner[0].dismiss()), "自定义国家/地区码");
        LinearLayout results = form();
        results.setPadding(0, 0, 0, 0);
        ScrollView choices = scroll(results);
        panel.addView(choices, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        AlertDialog picker = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_target_region)).setView(panel).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_back), null).create();
        pickerOwner[0] = picker;
        Runnable update = () -> {
            results.removeAllViews();
            for (RegionPreset region : searchRegions(search.getText().toString())) {
                option(results, countryName(region.code) + " · " + region.englishName, "", region.code, true, () -> {
                    if (closed) return;
                    try {
                        saveRegion(region.code, region.operatorCode, region.operatorName);
                        render();
                        picker.dismiss();
                    } catch (RuntimeException failure) { search.setError(com.tokai.module.i18n.UiText.get(R.string.editor_ui_region_save_failed)); }
                }, region.displayName + " · " + region.englishName);
            }
            if (results.getChildCount() == 0) results.addView(text(com.tokai.module.i18n.UiText.get(R.string.editor_ui_region_not_found), 13, MUTED), matchWrap());
            ModuleTheme.bind(results);
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { update.run(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        update.run();
        show(picker);
    }

    private void saveRegion(String region, String operatorCode, String operatorName) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("target_region", region.toUpperCase(java.util.Locale.ROOT));
        changes.put("sim_operator_code", operatorCode);
        changes.put("sim_operator_name", operatorName);
        replaceValues(changes);
    }

    private void replaceValues(Map<String, Object> changes) {
        Map<String, ?> before = store.snapshot();
        Map<String, Object> replacement = new LinkedHashMap<>(before);
        replacement.putAll(changes);
        store.replaceSnapshot(before, replacement);
    }

    private void editCustomRegion(Runnable afterSave) {
        if (closed) return;
        EnvironmentSpoofSettings current = settings();
        String[] keys = {"target_region", "sim_operator_code", "sim_operator_name"};
        String[] labels = {com.tokai.module.i18n.UiText.get(R.string.editor_ui_country_code), com.tokai.module.i18n.UiText.get(R.string.editor_ui_carrier_code), com.tokai.module.i18n.UiText.get(R.string.editor_ui_carrier_name)};
        String[] defaults = {current.regionCode, current.operatorCode, current.operatorName};
        EditText[] fields = new EditText[keys.length];
        LinearLayout panel = form();
        for (int index = 0; index < keys.length; index++) {
            panel.addView(text(labels[index], 13, MUTED), matchWrap());
            fields[index] = input(labels[index], store.getString(keys[index], defaults[index]), false, keys[index]);
            fields[index].setTag("environment.input." + keys[index]);
            panel.addView(MiuixFormControls.viewFor(fields[index]), matchWrap());
        }
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_region_code)).setView(panel)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), null).create();
        show(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (closed) return;
            String[] values = new String[keys.length];
            for (int index = 0; index < keys.length; index++) {
                values[index] = fields[index].getText().toString().trim();
            }
            if (values[0].isEmpty()) values[0] = "JP";
            values[0] = values[0].toUpperCase(java.util.Locale.ROOT);
            if (values[1].isEmpty()) values[1] = "00000";
            if (values[2].isEmpty()) values[2] = values[0] + " Carrier";
            for (int index = 0; index < keys.length; index++) {
                String error = validationError(keys[index], values[index]);
                if (error != null) { fields[index].setError(error); return; }
            }
            try {
                saveRegion(values[0], values[1], values[2]);
                render();
                editor.dismiss();
                afterSave.run();
            } catch (RuntimeException failure) { fields[0].setError(com.tokai.module.i18n.UiText.get(R.string.editor_ui_region_save_failed)); }
        });
    }

    private void editable(String title, String detail, String key, String fallback, boolean numeric, boolean enabled, String stableTitle) {
        String stored = store.getString(key, fallback);
        option(content, title, detail, stored.isEmpty() ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_use_region_preset) : stored, enabled,
                () -> {
                    if ("custom_latitude".equals(key) || "custom_longitude".equals(key)) editCoordinates();
                    else edit(title, key, fallback, numeric, stableTitle);
                }, stableTitle);
    }

    private void edit(String title, String key, String fallback, boolean numeric, String stableTitle) {
        if (closed) return;
        EditText value = input(title, store.getString(key, fallback), numeric, stableTitle);
        value.setTag("environment.input." + key);
        LinearLayout panel = form();
        panel.addView(MiuixFormControls.viewFor(value), matchWrap());
        if ("custom_language".equals(key)) {
            String[] labels = new String[LANGUAGE_PRESETS.length];
            for (int index = 0; index < labels.length; index++) labels[index] = java.util.Locale.forLanguageTag(LANGUAGE_PRESETS[index][0]).getDisplayName(com.tokai.module.i18n.ModuleLocale.locale()) + " · " + LANGUAGE_PRESETS[index][0];
            presetButton(panel, com.tokai.module.i18n.UiText.get(R.string.editor_ui_language_presets), "language", () -> presets(com.tokai.module.i18n.UiText.get(R.string.editor_ui_language_presets), labels,
                    index -> value.setText(LANGUAGE_PRESETS[index][0])));
        } else if ("custom_timezone".equals(key)) {
            presetButton(panel, com.tokai.module.i18n.UiText.get(R.string.editor_ui_time_zone_presets), "timezone", () -> presets(com.tokai.module.i18n.UiText.get(R.string.editor_ui_time_zone_presets), TIME_ZONE_PRESETS,
                    index -> value.setText(TIME_ZONE_PRESETS[index])));
        }
        AlertDialog editor = builder(title).setView(panel).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), null).create();
        show(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (closed) return;
            String candidate = value.getText().toString().trim();
            String error = validationError(key, candidate);
            if (error != null) { value.setError(error); return; }
            store.putString(key, candidate);
            render();
            editor.dismiss();
        });
    }

    private void editCoordinates() {
        if (closed) return;
        String[] keys = {"custom_latitude", "custom_longitude"};
        String[] labels = {com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_latitude), com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_longitude)};
        String[] defaults = {"35.6762", "139.6503"};
        EditText[] fields = new EditText[2];
        LinearLayout panel = form();
        for (int index = 0; index < keys.length; index++) {
            panel.addView(text(labels[index], 13, MUTED), matchWrap());
            fields[index] = input(labels[index], store.getString(keys[index], defaults[index]), true, keys[index]);
            fields[index].setTag("environment.input." + keys[index]);
            panel.addView(MiuixFormControls.viewFor(fields[index]), matchWrap());
        }
        String[] presetLabels = new String[GPS_PRESETS.length];
        for (int index = 0; index < presetLabels.length; index++) presetLabels[index] = gpsPresetLabel(index)
                + " · " + GPS_PRESETS[index][1] + ", " + GPS_PRESETS[index][2];
        presetButton(panel, com.tokai.module.i18n.UiText.get(R.string.editor_ui_coordinate_presets), "gps", () -> presets(com.tokai.module.i18n.UiText.get(R.string.editor_ui_coordinate_presets), presetLabels, index -> {
            fields[0].setText(GPS_PRESETS[index][1]);
            fields[1].setText(GPS_PRESETS[index][2]);
        }));
        AlertDialog editor = builder(com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_coordinates)).setView(panel)
                .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_save), null).create();
        show(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            if (closed) return;
            Map<String, Object> changes = new LinkedHashMap<>();
            for (int index = 0; index < keys.length; index++) {
                String candidate = fields[index].getText().toString().trim();
                String error = validationError(keys[index], candidate);
                if (error != null) { fields[index].setError(error); return; }
                changes.put(keys[index], candidate);
            }
            try {
                replaceValues(changes);
                render();
                editor.dismiss();
            } catch (RuntimeException failure) { fields[0].setError(com.tokai.module.i18n.UiText.get(R.string.editor_ui_coordinates_save_failed)); }
        });
    }

    private void presetButton(LinearLayout panel, String label, String id, Runnable choose) {
        android.widget.Button button = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
        button.setTag("environment.presets." + id);
        button.setText(label);
        button.setOnClickListener(ignored -> { if (!closed) choose.run(); });
        panel.addView(MiuixFormControls.viewFor(button), matchWrap());
    }

    private void presets(String title, String[] labels, Consumer<Integer> choose) {
        if (closed) return;
        AlertDialog choices = builder(title).setSingleChoiceItems(labels, -1, (selected, index) -> {
            if (closed) return;
            choose.accept(index);
            selected.dismiss();
        }).setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_cancel), null).create();
        show(choices);
    }

    private void toggle(String title, String summary, String key, boolean initial, String stableTitle) {
        java.util.function.Consumer<Boolean> change = value -> {
            if (closed) return;
            store.putBoolean(key, value);
            render();
        };
        if (miuix) {
            MiuixSettingsViews.Toggle toggle = new MiuixSettingsViews.Toggle(context, title, summary, initial, true, change, stableTitle);
            content.addView(toggle.getView(), matchWrap());
            return;
        }
        LinearLayout row = new LinearLayout(context);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout labels = form();
        labels.setPadding(0, 0, dp(8), 0);
        labels.addView(text(title, 15, TEXT), matchWrap());
        if (!summary.isEmpty()) labels.addView(text(summary, 12, MUTED), matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(context);
        toggle.setTag("settings.toggle." + stableTitle);
        toggle.setContentDescription(title);
        toggle.setChecked(initial);
        toggle.setOnCheckedChangeListener((button, checked) -> change.accept(checked));
        row.addView(toggle);
        content.addView(row, matchWrap());
    }

    private void option(LinearLayout parent, String title, String summary, String value, boolean enabled, Runnable action, String stableTitle) {
        if (miuix) {
            MiuixSettingsViews.Option option = new MiuixSettingsViews.Option(context, title, summary, value, enabled,
                    () -> { if (!closed && enabled) action.run(); }, stableTitle);
            option.setEnabled(enabled);
            parent.addView(option.getView(), matchWrap());
            return;
        }
        LinearLayout row = form();
        row.setPadding(0, dp(12), 0, dp(12));
        row.setTag("settings.option." + stableTitle);
        row.setContentDescription(title);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.5f);
        row.addView(text(title, 15, TEXT), matchWrap());
        if (!summary.isEmpty()) row.addView(text(summary, 12, MUTED), matchWrap());
        TextView preview = text(value, 13, MUTED);
        preview.setTag("settings.value." + stableTitle);
        row.addView(preview, matchWrap());
        row.setOnClickListener(ignored -> { if (!closed && enabled) action.run(); });
        parent.addView(row, matchWrap());
    }

    private EditText input(String label, String value, boolean numeric, String stableTitle) {
        EditText field = miuix ? MiuixFormControls.input(context, label, value, false, stableTitle) : new EditText(context);
        field.setText(value);
        field.setSingleLine(true);
        field.setContentDescription(label);
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                : InputType.TYPE_CLASS_TEXT);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(120)});
        return field;
    }

    private void section(String title) {
        TextView heading = text(title, 14, TEXT);
        heading.setPadding(0, dp(18), 0, dp(5));
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(heading, matchWrap());
    }

    private void show(Dialog child) {
        if (closed) return;
        children.add(child);
        presenter.accept(child, () -> children.remove(child));
    }

    private MiuixDialogBuilder builder(String title) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuix);
        builder.setTitle(title);
        return builder;
    }

    private LinearLayout form() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(8));
        return panel;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(child);
        return scroll;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Dialog child : new ArrayList<>(children)) child.dismiss();
        children.clear();
        if (dialog.isShowing()) dialog.dismiss();
    }
}
