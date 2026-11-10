package com.tokai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import com.tokai.module.config.FeatureSettings;
import com.tokai.module.config.RegionPreset;
import com.tokai.module.config.SettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class EnvironmentSpoofSettingsDialogTest {
    private Activity activity;
    private MemoryStore store;
    private EnvironmentSpoofSettingsDialog page;
    private View root;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        store.values.put("reply_prompt", "keep reply");
        store.values.put("tts_provider", "fish");
        page = new EnvironmentSpoofSettingsDialog(activity, store, false, (child, dismiss) -> {
            child.setOwnerActivity(activity);
            if (dismiss != null) child.setOnDismissListener(ignored -> dismiss.run());
            child.show();
        });
        page.dialog().setOwnerActivity(activity);
        page.dialog().show();
        root = page.dialog().getWindow().getDecorView();
    }

    @After public void tearDown() {
        page.close();
        activity.finish();
    }

    @Test public void defaultsRemainOffAndOpeningThePageNeverWritesConfiguration() {
        for (String title : new String[]{"SIM 地区伪装", "语言伪装", "时区伪装", "GPS 定位伪装"}) assertFalse(toggle(title).isChecked());
        for (String title : new String[]{"语言跟随地区", "时区跟随地区", "定位跟随地区"}) assertTrue(toggle(title).isChecked());
        assertFalse(option("自定义语言").isEnabled());
        assertFalse(option("自定义时区").isEnabled());
        assertFalse(option("自定义纬度").isEnabled());
        assertTrue(preview().contains("地区：JP"));
        assertTrue(preview().contains("ja-JP"));
        TextView restart = root.findViewWithTag("environment.restart");
        assertTrue(restart.getText().toString().contains("重新启动 TikTok"));
        assertEquals(Map.of("reply_prompt", "keep reply", "tts_provider", "fish"), store.values);
    }

    @Test public void switchesAreIndependentAndFollowChoicesRetainDormantCustomValues() {
        toggle("SIM 地区伪装").setChecked(true);
        toggle("语言伪装").setChecked(true);
        toggle("语言跟随地区").setChecked(false);
        save("自定义语言", "custom_language", "en-GB");
        assertTrue(preview().contains("语言：开启 · en-GB"));
        toggle("语言跟随地区").setChecked(true);
        assertEquals("en-GB", store.values.get("custom_language"));
        assertTrue(preview().contains("语言：开启 · ja-JP"));
        assertFalse(toggle("时区伪装").isChecked());
        assertFalse(toggle("GPS 定位伪装").isChecked());
        assertEquals("keep reply", store.values.get("reply_prompt"));
        assertEquals("fish", store.values.get("tts_provider"));
    }

    @Test public void regionSearchSelectsAPresetAndUpdatesAllFollowingPreviews() {
        option("目标地区").performClick();
        Dialog picker = ShadowDialog.getLatestDialog();
        EditText search = picker.getWindow().getDecorView().findViewWithTag("environment.region.search");
        search.setText("United States");
        RegionPreset us = RegionPreset.find("US");
        View choice = picker.getWindow().getDecorView().findViewWithTag("settings.option." + us.displayName + " · " + us.englishName);
        assertNotNull(choice);
        choice.performClick();
        assertFalse(picker.isShowing());
        assertEquals("US", store.values.get("target_region"));
        assertTrue(preview().contains("地区：US"));
        assertTrue(preview().contains(us.operatorCode));
        assertTrue(preview().contains(us.languageTag));
        assertTrue(preview().contains(us.timeZoneId));
        assertTrue(preview().contains(Double.toString(us.latitude)));
    }

    @Test public void customRegionEditorRejectsInvalidValuesAndNormalizesTheSavedCode() {
        option("目标地区").performClick();
        Dialog picker = ShadowDialog.getLatestDialog();
        picker.getWindow().getDecorView().findViewWithTag("settings.option.自定义国家/地区码").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = editor.getWindow().getDecorView().findViewWithTag("environment.input.target_region");
        input.setText("USA");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(editor.isShowing());
        assertNotNull(input.getError());
        assertFalse(store.values.containsKey("target_region"));
        input.setText(" nz ");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("NZ", store.values.get("target_region"));
        assertFalse(editor.isShowing());
        assertFalse(picker.isShowing());
    }

    @Test public void selectingAPresetReplacesAllThreeCustomCarrierFieldsInOneTransaction() {
        option("目标地区").performClick();
        Dialog picker = ShadowDialog.getLatestDialog();
        picker.getWindow().getDecorView().findViewWithTag("settings.option.自定义国家/地区码").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        setField(editor, "target_region", "US");
        setField(editor, "sim_operator_code", "310260");
        setField(editor, "sim_operator_name", "Custom US carrier");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1, store.transactions);
        assertEquals("US", store.values.get("target_region"));
        assertTrue(preview().contains("Custom US carrier"));
        store.values.put("custom_language", "en-GB");
        store.values.put("language_follow_region", false);
        chooseRegion("JP");
        RegionPreset jp = RegionPreset.find("JP");
        assertEquals("JP", store.values.get("target_region"));
        assertEquals(jp.operatorCode, store.values.get("sim_operator_code"));
        assertEquals(jp.operatorName, store.values.get("sim_operator_name"));
        assertEquals(2, store.transactions);
        assertEquals("en-GB", store.values.get("custom_language"));
        assertEquals(false, store.values.get("language_follow_region"));
        assertEquals("keep reply", store.values.get("reply_prompt"));
        assertEquals("fish", store.values.get("tts_provider"));
    }

    @Test public void customRegionEmptyFieldsUseTokisOriginalGroupFallbacks() {
        option("目标地区").performClick();
        Dialog picker = ShadowDialog.getLatestDialog();
        picker.getWindow().getDecorView().findViewWithTag("settings.option.自定义国家/地区码").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        for (String key : new String[]{"target_region", "sim_operator_code", "sim_operator_name"}) setField(editor, key, "");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.isShowing());
        assertEquals("JP", store.values.get("target_region"));
        assertEquals("00000", store.values.get("sim_operator_code"));
        assertEquals("JP Carrier", store.values.get("sim_operator_name"));
        assertEquals(1, store.transactions);
    }

    @Test public void failedPresetTransactionKeepsCountryCarrierAndUnrelatedSettingsTogether() {
        Map<String, Object> original = new HashMap<>(store.values);
        store.rejectTransaction = true;
        option("目标地区").performClick();
        Dialog picker = ShadowDialog.getLatestDialog();
        EditText search = picker.getWindow().getDecorView().findViewWithTag("environment.region.search");
        search.setText("United States");
        RegionPreset us = RegionPreset.find("US");
        picker.getWindow().getDecorView().findViewWithTag("settings.option." + us.displayName + " · " + us.englishName).performClick();
        assertTrue(picker.isShowing());
        assertNotNull(search.getError());
        assertEquals(original, store.values);
        assertEquals(0, store.transactions);
    }

    @Test public void quickLanguageAndTimeZonePresetsFillTheEditorWithoutSavingUntilConfirmed() {
        toggle("语言跟随地区").setChecked(false);
        option("自定义语言").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        editor.getWindow().getDecorView().findViewWithTag("environment.presets.language").performClick();
        AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(8, choices.getListView().getCount());
        choices.getListView().performItemClick(choices.getListView().getChildAt(0), 3, 3);
        EditText language = editor.getWindow().getDecorView().findViewWithTag("environment.input.custom_language");
        assertEquals("en-US", language.getText().toString());
        assertFalse(store.values.containsKey("custom_language"));
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("en-US", store.values.get("custom_language"));

        toggle("时区跟随地区").setChecked(false);
        option("自定义时区").performClick();
        editor = (AlertDialog) ShadowDialog.getLatestDialog();
        editor.getWindow().getDecorView().findViewWithTag("environment.presets.timezone").performClick();
        choices = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(8, choices.getListView().getCount());
        choices.getListView().performItemClick(choices.getListView().getChildAt(0), 4, 4);
        EditText timeZone = editor.getWindow().getDecorView().findViewWithTag("environment.input.custom_timezone");
        assertEquals("Europe/London", timeZone.getText().toString());
        assertFalse(store.values.containsKey("custom_timezone"));
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("Europe/London", store.values.get("custom_timezone"));
        assertFalse(store.values.containsKey("target_region"));
        assertFalse(toggle("语言伪装").isChecked());
        assertFalse(toggle("时区伪装").isChecked());
    }

    @Test public void coordinatePresetChangesBothFieldsAndSavesThemAtomicallyOnlyAfterConfirmation() {
        toggle("定位跟随地区").setChecked(false);
        option("自定义纬度").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        editor.getWindow().getDecorView().findViewWithTag("environment.presets.gps").performClick();
        AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(8, choices.getListView().getCount());
        choices.getListView().performItemClick(choices.getListView().getChildAt(0), 4, 4);
        EditText latitude = editor.getWindow().getDecorView().findViewWithTag("environment.input.custom_latitude");
        EditText longitude = editor.getWindow().getDecorView().findViewWithTag("environment.input.custom_longitude");
        assertEquals("40.7128", latitude.getText().toString());
        assertEquals("-74.0060", longitude.getText().toString());
        assertFalse(store.values.containsKey("custom_latitude"));
        assertFalse(store.values.containsKey("custom_longitude"));
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(1, store.transactions);
        assertEquals("40.7128", store.values.get("custom_latitude"));
        assertEquals("-74.0060", store.values.get("custom_longitude"));
        assertFalse(store.values.containsKey("target_region"));
        assertFalse(toggle("GPS 定位伪装").isChecked());
    }

    @Test public void operatorOverridesAndSignedCoordinatesSaveWithoutTouchingOtherSettings() {
        save("运营商代码", "sim_operator_code", "310260");
        save("运营商名称", "sim_operator_name", "Example carrier");
        assertTrue(preview().contains("310260 · Example carrier"));
        save("运营商代码", "sim_operator_code", "");
        assertTrue(preview().contains(RegionPreset.find("JP").operatorCode));
        toggle("定位跟随地区").setChecked(false);
        save("自定义纬度", "custom_latitude", "-33.8688");
        save("自定义经度", "custom_longitude", "151.2093");
        assertTrue(preview().contains("-33.8688, 151.2093"));
        option("自定义经度").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = editor.getWindow().getDecorView().findViewWithTag("environment.input.custom_longitude");
        input.setText("181");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(editor.isShowing());
        assertNotNull(input.getError());
        assertEquals("151.2093", store.values.get("custom_longitude"));
        assertEquals("keep reply", store.values.get("reply_prompt"));
    }

    @Test public void closingPageDismissesEditorsAndStaleControlsCannotSave() {
        option("运营商名称").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = editor.getWindow().getDecorView().findViewWithTag("environment.input.sim_operator_name");
        input.setText("stale");
        Switch sim = toggle("SIM 地区伪装");
        page.close();
        assertFalse(editor.isShowing());
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        sim.setChecked(true);
        assertFalse(store.values.containsKey("sim_operator_name"));
        assertFalse(store.values.containsKey("sim_spoof_enabled"));
    }

    @Test public void hostHomeAndFeatureSearchOpenTheOwnedEnvironmentPageWithoutSaving() {
        page.close();
        Map<String, Object> original = new HashMap<>(store.values);
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        try {
            View home = screen.createView();
            activity.setContentView(home);
            home.findViewWithTag("settings.category.ENVIRONMENT").performClick();
            Dialog environment = ShadowDialog.getLatestDialog();
            assertNotNull(environment.getWindow().getDecorView().findViewWithTag("environment.preview"));
            screen.dismissChildPages();
            assertFalse(environment.isShowing());
            EditText search = home.findViewWithTag("settings.search");
            search.setText("GPS");
            home.findViewWithTag("settings.search.result.environment").performClick();
            Dialog searched = ShadowDialog.getLatestDialog();
            assertNotNull(searched.getWindow().getDecorView().findViewWithTag("environment.preview"));
            assertEquals(original, store.values);
            screen.dismissChildPages();
            assertFalse(searched.isShowing());
        } finally { screen.close(); }
    }

    private void save(String title, String key, String value) {
        option(title).performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = editor.getWindow().getDecorView().findViewWithTag("environment.input." + key);
        assertNotNull(input);
        input.setText(value);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.isShowing());
    }

    private void chooseRegion(String code) {
        option("目标地区").performClick();
        Dialog picker = ShadowDialog.getLatestDialog();
        RegionPreset region = RegionPreset.find(code);
        EditText search = picker.getWindow().getDecorView().findViewWithTag("environment.region.search");
        search.setText(region.englishName);
        picker.getWindow().getDecorView().findViewWithTag("settings.option." + region.displayName + " · " + region.englishName).performClick();
        assertFalse(picker.isShowing());
    }

    private static void setField(Dialog editor, String key, String value) {
        EditText input = editor.getWindow().getDecorView().findViewWithTag("environment.input." + key);
        input.setText(value);
    }

    private Switch toggle(String title) { return root.findViewWithTag("settings.toggle." + title); }
    private View option(String title) { return root.findViewWithTag("settings.option." + title); }
    private String preview() { return ((TextView) root.findViewWithTag("environment.preview")).getText().toString(); }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        int transactions;
        boolean rejectTransaction;
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings settings) { throw new AssertionError("Environment edits must not rewrite automation"); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public Map<String, ?> snapshot() { return new HashMap<>(values); }
        public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            assertEquals(expected, values);
            if (rejectTransaction) throw new IllegalStateException("Simulated storage failure");
            values.clear();
            values.putAll(replacement);
            transactions++;
        }
        public boolean isInHost() { return true; }
    }
}
