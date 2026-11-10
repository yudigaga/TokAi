package com.tokai.module.config;

import android.content.Context;
import com.tokai.module.i18n.ModuleLanguage;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ModuleLanguageConfigurationTest {
    @Test public void languageHasOneUiConsumerAndSystemDefault() {
        ConfigurationCatalog.Setting entry = ConfigurationCatalog.settings().get(ModuleLanguage.KEY);
        assertNotNull(entry); assertEquals("string", entry.type); assertEquals("system", entry.defaultValue);
        assertEquals(Set.of("MODULE_UI"), entry.consumers.stream().map(Enum::name).collect(Collectors.toSet()));
        ConfigurationChanges changes = ConfigurationChanges.between(Map.of(), Map.of(ModuleLanguage.KEY, "ko"));
        for (ConfigurationCatalog.Feature feature : ConfigurationCatalog.Feature.values()) {
            assertEquals("UI language must not restart or reconfigure business features: " + feature,
                    feature.name().equals("MODULE_UI"), changes.affects(feature));
        }
    }
    @Test public void transferAcceptsOnlyTheSixStableValues() {
        for (ModuleLanguage language : ModuleLanguage.values()) ConfigKeys.validate(ModuleLanguage.KEY, language.value);
        for (Object invalid : new Object[]{"", "ja-JP", "xx", "ZH-HANS", 7, true}) {
            assertThrows(IllegalArgumentException.class, () -> ConfigKeys.validate(ModuleLanguage.KEY, invalid));
        }
    }
    @Test public void exportAndBothImportModesPreserveLanguageAndKeepOtherSettingsIndependent() throws Exception {
        SettingsRepository source = repository("source"); source.putString(ModuleLanguage.KEY, "zh-Hant");
        String exported = ConfigTransfer.export(source, false);
        JSONObject values = new JSONObject(exported).getJSONObject("values");
        assertEquals("zh-Hant", values.getJSONObject(ModuleLanguage.KEY).getString("value"));
        for (ConfigTransfer.Mode mode : ConfigTransfer.Mode.values()) {
            SettingsRepository target = repository(mode.name()); target.putString("custom_language", "ja-JP");
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, exported, mode);
            assertEquals("system", target.getString(ModuleLanguage.KEY, "system"));
            ConfigTransfer.apply(target, preview);
            assertEquals("zh-Hant", target.getString(ModuleLanguage.KEY, "system"));
            assertEquals("ja-JP", target.getString("custom_language", "ja-JP"));
        }
    }
    @Test public void invalidImportedLanguageCannotChangeTheTarget() throws Exception {
        SettingsRepository source = repository("template");
        JSONObject backup = new JSONObject(ConfigTransfer.export(source, false));
        backup.getJSONObject("values").put(ModuleLanguage.KEY, new JSONObject().put("type", "string").put("value", "xx"));
        SettingsRepository target = repository("invalid"); target.putString(ModuleLanguage.KEY, "ko");
        Map<String, ?> before = target.snapshot();
        assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, backup.toString()));
        assertEquals(before, target.snapshot());
    }
    @Test public void choosingUiLanguagePreservesHostSpoofingSpeechCaptionAndAiValuesExactly() {
        SettingsRepository target = repository("ui-only");
        target.replaceSnapshot(Map.of(), Map.of(
                "custom_language", "ja-JP", "language_spoof_enabled", true, "target_region", "JP",
                "tts_language", "zh", "reply_prompt", "用户提示词原文", "auto_message_text", "用户消息原文",
                CaptionVisibilitySettings.FORCE, true));
        Map<String, ?> before = target.snapshot();
        com.tokai.module.i18n.ModuleLocale.bind(RuntimeEnvironment.getApplication(), target);
        com.tokai.module.i18n.ModuleLocale.select(target, "ko");
        java.util.Map<String, Object> after = new java.util.LinkedHashMap<>(target.snapshot());
        assertEquals("ko", after.remove(ModuleLanguage.KEY));
        assertEquals(before, after);
    }
    private static SettingsRepository repository(String name) {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences("module-language-" + name, 0);
        assertTrue(prefs.edit().clear().commit()); return new SettingsRepository(prefs);
    }
}
