package com.tokai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class CaptionVisibilitySettingsTest {
    private static final String HIDE = "hide_native_captions";
    private static final String FORCE = "force_native_captions";

    @Test public void absentCaptionOptionsFollowTheHostWithoutWritingDefaults() {
        SharedPreferences preferences = preferences("defaults");
        LocalOptions options = LocalOptions.readFrom(preferences);
        assertFalse(options.enabled(HIDE));
        assertFalse(options.enabled(FORCE));
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void rawConflictsPreferHideInDetachedLocalOptions() {
        Map<String, Object> raw = new HashMap<>(Map.of(HIDE, true, FORCE, true, "keyword", "keep"));
        LocalOptions options = new LocalOptions(raw);
        assertTrue(options.enabled(HIDE));
        assertFalse("Ambiguous external settings must not enable both caption behaviors", options.enabled(FORCE));
        assertEquals(Map.of(HIDE, true, FORCE, true, "keyword", "keep"), raw);
        raw.put(HIDE, false);
        assertTrue(options.enabled(HIDE));
        assertFalse(options.enabled(FORCE));
        assertEquals("keep", options.text("keyword", ""));
    }

    @Test public void aNonBooleanHideValueDoesNotSuppressAnExplicitForceOption() {
        LocalOptions options = new LocalOptions(Map.of(HIDE, "true", FORCE, true));
        assertFalse(options.enabled(HIDE));
        assertTrue(options.enabled(FORCE));
    }

    @Test public void configurationValuesAndOptionsUseTheSameNormalizedCapture() {
        SharedPreferences preferences = preferences("snapshot");
        preferences.edit().putBoolean(HIDE, true).putBoolean(FORCE, true).putString("keyword", "keep").commit();
        AtomicInteger reads = new AtomicInteger();
        SharedPreferences captured = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getAll".equals(method.getName())) {
                        reads.incrementAndGet();
                        return preferences.getAll();
                    }
                    throw new AssertionError("Snapshot used a live getter: " + method.getName());
                });
        ConfigurationSnapshot snapshot = ConfigurationSnapshot.readFrom(captured);
        assertEquals(1, reads.get());
        assertEquals(Boolean.TRUE, snapshot.values.get(HIDE));
        assertEquals(Boolean.FALSE, snapshot.values.get(FORCE));
        assertTrue(snapshot.options.enabled(HIDE));
        assertFalse(snapshot.options.enabled(FORCE));
        assertTrue("Normalization must not rewrite the raw preference store", preferences.getBoolean(FORCE, false));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values.clear());
    }

    @Test public void enablingEitherCaptionOptionAtomicallyDisablesItsPeerAndPublishesOnce() {
        for (String selected : List.of(HIDE, FORCE)) {
            String peer = selected.equals(HIDE) ? FORCE : HIDE;
            SharedPreferences actual = preferences("write-" + selected);
            actual.edit().putBoolean(selected, false).putBoolean(peer, true).putString("keyword", "keep").commit();
            AtomicInteger applies = new AtomicInteger();
            SettingsRepository repository = new SettingsRepository(countApplies(actual, applies));
            AtomicInteger publications = new AtomicInteger();
            FrameworkConfigSync sync = new FrameworkConfigSync((connected, message, error) -> publications.incrementAndGet());
            List<Map<String, ?>> observed = new ArrayList<>();
            SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) ->
                    observed.add(new LinkedHashMap<>(preferences.getAll()));
            actual.registerOnSharedPreferenceChangeListener(listener);
            try {
                repository.setFrameworkConfigSync(sync);
                ShadowLooper.idleMainLooper();
                publications.set(0);
                observed.clear();
                repository.putBoolean(selected, true);
                ShadowLooper.idleMainLooper();
                assertEquals(1, applies.get());
                assertEquals(1, publications.get());
                assertTrue(actual.getBoolean(selected, false));
                assertFalse(actual.getBoolean(peer, true));
                assertEquals("keep", actual.getString("keyword", ""));
                assertFalse("The setting update must notify live consumers", observed.isEmpty());
                for (Map<String, ?> values : observed) {
                    assertEquals(Boolean.TRUE, values.get(selected));
                    assertEquals("A listener must never see half of the caption mode change", Boolean.FALSE, values.get(peer));
                }
                repository.putBoolean(selected, false);
                assertFalse(actual.getBoolean(selected, true));
                assertFalse("Disabling one mode must not turn on the other", actual.getBoolean(peer, true));
            } finally {
                actual.unregisterOnSharedPreferenceChangeListener(listener);
                sync.close();
            }
        }
    }

    @Test public void exportNormalizesConflictsWithoutMutatingTheRawSource() throws Exception {
        for (boolean includeSecrets : new boolean[]{false, true}) {
            SettingsRepository source = repository("export-" + includeSecrets, Map.of(HIDE, true, FORCE, true));
            Map<String, ?> raw = source.snapshot();
            JSONObject values = new JSONObject(ConfigTransfer.export(source, includeSecrets)).getJSONObject("values");
            assertTrue(values.has(FORCE));
            assertTrue(values.getJSONObject(HIDE).getBoolean("value"));
            assertFalse(values.getJSONObject(FORCE).getBoolean("value"));
            assertEquals(raw, source.snapshot());
        }
    }

    @Test public void aSingleImportedTrueOverridesTheTargetsPreviousCaptionMode() throws Exception {
        for (ConfigTransfer.Mode mode : ConfigTransfer.Mode.values()) {
            for (String selected : List.of(HIDE, FORCE)) {
                String peer = selected.equals(HIDE) ? FORCE : HIDE;
                SettingsRepository target = repository("single-" + mode + selected, Map.of(selected, false, peer, true));
                Map<String, ?> before = target.snapshot();
                ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, backup(Map.of(selected, true)), mode);
                assertEquals("Preview must not write settings", before, target.snapshot());
                assertTrue(preview.changedKeys.contains(selected));
                assertTrue(preview.changedKeys.contains(peer));
                ConfigTransfer.apply(target, preview);
                assertTrue(target.getBoolean(selected, false));
                assertFalse(target.getBoolean(peer, true));
            }
        }
    }

    @Test public void contradictoryIncomingOptionsAlwaysPreferHide() throws Exception {
        for (ConfigTransfer.Mode mode : ConfigTransfer.Mode.values()) {
            SettingsRepository target = repository("contradictory-" + mode, Map.of(HIDE, false, FORCE, true));
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, backup(Map.of(HIDE, true, FORCE, true)), mode);
            assertTrue(preview.changedKeys.contains(HIDE));
            assertTrue(preview.changedKeys.contains(FORCE));
            ConfigTransfer.apply(target, preview);
            assertEquals(Boolean.TRUE, target.snapshot().get(HIDE));
            assertEquals(Boolean.FALSE, target.snapshot().get(FORCE));
        }
    }

    @Test public void aBackupWithoutTheNewKeyMergesOrReplacesItUsingExistingTransferSemantics() throws Exception {
        for (ConfigTransfer.Mode mode : ConfigTransfer.Mode.values()) {
            SettingsRepository target = repository("legacy-" + mode, Map.of(HIDE, false, FORCE, true));
            ConfigTransfer.apply(target, ConfigTransfer.inspect(target, backup(Map.of(HIDE, false)), mode));
            assertFalse(target.getBoolean(HIDE, true));
            assertEquals(mode == ConfigTransfer.Mode.MERGE, target.getBoolean(FORCE, false));
        }
    }

    @Test public void anUnrelatedMergeRepairsAnExistingConflictInThePreview() throws Exception {
        SettingsRepository target = repository("existing-conflict", Map.of(HIDE, true, FORCE, true));
        Map<String, ?> before = target.snapshot();
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, backup(Map.of()));
        assertTrue(preview.changedKeys.contains(FORCE));
        assertEquals(before, target.snapshot());
        ConfigTransfer.apply(target, preview);
        assertTrue(target.getBoolean(HIDE, false));
        assertFalse(target.getBoolean(FORCE, true));
    }

    @Test public void rawConflictChecksDoNotIgnoreEditsThatLeaveTheEffectiveModeUnchanged() throws Exception {
        SettingsRepository target = repository("raw-cas", Map.of(HIDE, true, FORCE, true));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, backup(Map.of()));
        target.putBoolean(FORCE, false);
        assertThrows(IllegalStateException.class, () -> ConfigTransfer.apply(target, preview));
        assertTrue(target.getBoolean(HIDE, false));
        assertFalse(target.getBoolean(FORCE, true));
    }

    @Test public void compensationRestoresTheOriginalRawConflictWhileKeepingOnlyHideEffective() throws Exception {
        SettingsRepository target = repository("compensation", Map.of(HIDE, true, FORCE, true, "keyword", "original"));
        Map<String, ?> before = target.snapshot();
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, backup(Map.of(HIDE, false, FORCE, true)));
        ConfigTransfer.apply(target, preview);
        assertFalse(target.getBoolean(HIDE, true));
        assertTrue(target.getBoolean(FORCE, false));
        ConfigTransfer.rollback(target, preview);
        assertEquals(before, target.snapshot());
        LocalOptions restored = new LocalOptions(target.snapshot());
        assertTrue(restored.enabled(HIDE));
        assertFalse(restored.enabled(FORCE));
    }

    private static SharedPreferences preferences(String name) {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences result = context.getSharedPreferences("caption-visibility-" + name, Context.MODE_PRIVATE);
        assertTrue(result.edit().clear().commit());
        return result;
    }

    private static SettingsRepository repository(String name, Map<String, ?> values) {
        SettingsRepository result = new SettingsRepository(preferences(name));
        result.replaceSnapshot(Map.of(), values);
        return result;
    }

    private static String backup(Map<String, Boolean> incoming) throws Exception {
        JSONObject backup = new JSONObject(ConfigTransfer.export(new SettingsRepository(preferences("backup-template")), true));
        JSONObject values = backup.getJSONObject("values");
        for (Map.Entry<String, Boolean> entry : incoming.entrySet()) {
            values.put(entry.getKey(), new JSONObject().put("type", "boolean").put("value", entry.getValue()));
        }
        return backup.toString();
    }

    private static SharedPreferences countApplies(SharedPreferences actual, AtomicInteger applies) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(actual, args);
                    SharedPreferences.Editor editor = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                if ("apply".equals(call.getName())) applies.incrementAndGet();
                                Object result = call.invoke(editor, values);
                                return result == editor ? editorProxy : result;
                            });
                });
    }
}
