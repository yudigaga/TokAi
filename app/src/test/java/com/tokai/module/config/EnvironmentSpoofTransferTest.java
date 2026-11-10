package com.tokai.module.config;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class EnvironmentSpoofTransferTest {
    @Test public void everyEnvironmentKeyHasATypeDefaultAndAnIsolatedConsumer() {
        for (String key : EnvironmentSpoofSettings.BOOLEAN_KEYS) {
            assertEquals("boolean", ConfigKeys.TYPES.get(key));
            assertTrue(ConfigurationCatalog.settings().get(key).defaultValue instanceof Boolean);
        }
        for (String key : EnvironmentSpoofSettings.STRING_KEYS) {
            assertEquals("string", ConfigKeys.TYPES.get(key));
            assertNull(EnvironmentSpoofSettings.validate(key, (String) ConfigurationCatalog.settings().get(key).defaultValue));
        }
        for (String key : EnvironmentSpoofSettings.BOOLEAN_KEYS) assertOnlyEnvironment(key);
        for (String key : EnvironmentSpoofSettings.STRING_KEYS) assertOnlyEnvironment(key);
    }

    @Test public void configurationRoundTripsAllSwitchesAndDormantCustomValuesWithoutSecrets() throws Exception {
        for (ConfigTransfer.Mode mode : ConfigTransfer.Mode.values()) {
            MemoryStore source = populated(), target = new MemoryStore();
            source.values.put("reply_prompt", "keep AI behavior");
            String exported = ConfigTransfer.export(source, false);
            JSONObject values = new JSONObject(exported).getJSONObject("values");
            for (String key : EnvironmentSpoofSettings.BOOLEAN_KEYS) assertTrue(values.has(key));
            for (String key : EnvironmentSpoofSettings.STRING_KEYS) assertTrue(values.has(key));
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, exported, mode);
            assertTrue(target.values.isEmpty());
            assertEquals(0, target.commits);
            ConfigTransfer.apply(target, preview);
            assertEquals(source.values, target.values);
            assertEquals(1, target.commits);
        }
    }

    @Test public void invalidEnvironmentImportNeverChangesAnyStoredValue() throws Exception {
        Map<String, String> invalid = Map.of("target_region", "USA", "sim_operator_code", "abc",
                "custom_language", "not a language", "custom_timezone", "Asia/NoSuchZone",
                "custom_latitude", "NaN", "custom_longitude", "181");
        for (ConfigTransfer.Mode mode : ConfigTransfer.Mode.values()) for (Map.Entry<String, String> sample : invalid.entrySet()) {
            MemoryStore target = populated();
            Map<String, Object> before = new HashMap<>(target.values);
            JSONObject backup = new JSONObject(ConfigTransfer.export(populated(), true));
            backup.getJSONObject("values").getJSONObject(sample.getKey()).put("value", sample.getValue());
            assertThrows(sample.getKey(), BackupValidationException.class,
                    () -> ConfigTransfer.inspect(target, backup.toString(), mode));
            assertEquals(before, target.values);
            assertEquals(0, target.commits);
        }
    }

    @Test public void replacingWithOlderBackupRemovesEnvironmentOptionsAndRestoresOffDefaults() {
        MemoryStore target = populated(), old = new MemoryStore();
        old.values.put("keyword", "older backup");
        target.values.put("runtime_unrelated", "retained");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(old, false), ConfigTransfer.Mode.REPLACE));
        EnvironmentSpoofSettings reset = EnvironmentSpoofSettings.read(new LocalOptions(target.values));
        assertFalse(reset.anyEnabled());
        assertEquals("JP", reset.regionCode);
        assertEquals("retained", target.values.get("runtime_unrelated"));
        assertEquals("older backup", target.values.get("keyword"));
    }

    @Test public void invalidDormantCustomValuesAlsoFailExportRatherThanLosingThemOnImport() {
        MemoryStore source = populated();
        source.values.put("language_follow_region", true);
        source.values.put("custom_language", "bad language");
        assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.export(source, false));
        assertEquals("bad language", source.values.get("custom_language"));
    }

    private static void assertOnlyEnvironment(String key) {
        assertEquals(EnumSet.of(ConfigurationCatalog.Feature.ENVIRONMENT), ConfigurationCatalog.settings().get(key).consumers);
        ConfigurationChanges change = ConfigurationChanges.between(Collections.emptyMap(), Map.of(key, "changed"));
        ConfigurationChanges removed = ConfigurationChanges.between(Map.of(key, "previous"), Collections.emptyMap());
        for (ConfigurationCatalog.Feature feature : ConfigurationCatalog.Feature.values()) {
            assertEquals(key + ":" + feature, feature == ConfigurationCatalog.Feature.ENVIRONMENT, change.affects(feature));
            assertEquals(key + ":remove:" + feature, feature == ConfigurationCatalog.Feature.ENVIRONMENT, removed.affects(feature));
        }
    }

    private static MemoryStore populated() {
        MemoryStore store = new MemoryStore();
        for (String key : EnvironmentSpoofSettings.BOOLEAN_KEYS) store.values.put(key, true);
        store.values.put("target_region", "US");
        store.values.put("sim_operator_code", "310260");
        store.values.put("sim_operator_name", "Example carrier");
        store.values.put("custom_language", "en-GB");
        store.values.put("custom_timezone", "Europe/London");
        store.values.put("custom_latitude", "-33.8688");
        store.values.put("custom_longitude", "151.2093");
        return store;
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        int commits;
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings settings) { throw new AssertionError(); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public Map<String, ?> snapshot() { return new HashMap<>(values); }
        public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            assertEquals(expected, values);
            values.clear(); values.putAll(replacement); commits++;
        }
    }
}
