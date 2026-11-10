package com.tokai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import com.tokai.module.analytics.LocalAnalytics;
import com.tokai.module.analytics.LocalPortraitContext;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LegacyIdentityMigrationTest {
    @Test public void installedSettingsAndHostInitializationSurviveWithoutReplacingCurrentValues() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("douai_module_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("hide_ads", true).putString("keyword", "legacy")
                .putBoolean("douai_host_settings_initialized", true).commit();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putString("keyword", "current").commit();
        SettingsRepository repository = new SettingsRepository(context);
        assertTrue(repository.getBoolean("hide_ads", false));
        assertEquals("current", repository.getString("keyword", ""));
        SharedPreferences host = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        assertTrue(host.getBoolean("tokai_host_settings_initialized", false));
        SharedPreferences remote = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
        remote.edit().putString("keyword", "remote").commit();
        assertFalse(HostSettingsMigration.copyOnce(remote, host));
        host.edit().remove("hide_ads").commit();
        java.lang.reflect.Field migrated = LegacyIdentityMigration.class.getDeclaredField("MIGRATED");
        migrated.setAccessible(true);
        ((java.util.Set<?>) migrated.get(null)).clear();
        assertFalse(new SettingsRepository(context).getBoolean("hide_ads", false));
    }

    @Test public void historyAndDuplicatePreventionStoresRetainTheirValues() {
        Context context = RuntimeEnvironment.getApplication();
        for (String suffix : new String[]{"automation_history", "automation_limits", "comment_state",
                "message_state", "comment_submissions", "reply_submissions", "manual_voice_submissions",
                "comment_reply_ledger", "automation_ui", "adaptation_acknowledgment"}) {
            context.getSharedPreferences("douai_" + suffix, Context.MODE_PRIVATE).edit()
                    .putString("record", suffix).commit();
        }
        new SettingsRepository(context);
        for (String suffix : new String[]{"automation_history", "automation_limits", "comment_state",
                "message_state", "comment_submissions", "reply_submissions", "manual_voice_submissions",
                "comment_reply_ledger", "automation_ui", "adaptation_acknowledgment"}) {
            assertEquals(suffix, context.getSharedPreferences("tokai_" + suffix, Context.MODE_PRIVATE)
                    .getString("record", ""));
        }
    }

    @Test public void chatAndReferenceFilesMoveTogetherWithStoredReferencePaths() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        for (String name : new String[]{"douai_test_chat", "douai_message_chat", "douai_debug_logs", "douai-voice-source"}) {
            File directory = new File(context.getFilesDir(), name);
            assertTrue(directory.mkdirs());
            Files.write(new File(directory, "fixture.bin").toPath(), new byte[]{1, 2, 3});
        }
        String oldPath = new File(context.getFilesDir(), "douai-voice-source/fixture.bin").getAbsolutePath();
        context.getSharedPreferences("douai_module_settings", Context.MODE_PRIVATE).edit()
                .putString(FishSpeechSettings.REFERENCE_SAMPLES, "[[{\"path\":\"" + oldPath.replace("\\", "\\\\") + "\",\"text\":\"reference\"}]]")
                .putString("comment_voice_path", oldPath).putString("dm_voice_path", oldPath).commit();
        SettingsRepository repository = new SettingsRepository(context);
        for (String name : new String[]{"tokai_test_chat", "tokai_message_chat", "tokai_debug_logs", "tokai-voice-source"}) {
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(context.getFilesDir(), name + "/fixture.bin").toPath()));
        }
        assertTrue(repository.getString(FishSpeechSettings.REFERENCE_SAMPLES, "").contains("tokai-voice-source"));
        for (String key : new String[]{"comment_voice_path", "dm_voice_path"}) {
            String path = repository.getString(key, "");
            assertTrue(path.contains("tokai-voice-source"));
            assertTrue(new File(path).isFile());
        }
    }

    @Test public void analyticsDirectoryMigratesBeforeDirectPortraitOrDatabaseAccess() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File directory = new File(context.getNoBackupFilesDir(), "douai-analytics");
        assertTrue(directory.mkdirs());
        Files.write(new File(directory, "portrait-context.json").toPath(),
                "{\"version\":1,\"purpose\":\"old-purpose\",\"feedback\":\"\"}".getBytes(StandardCharsets.UTF_8));
        java.lang.reflect.Constructor<LocalAnalytics> constructor = LocalAnalytics.class
                .getDeclaredConstructor(File.class, java.time.Clock.class);
        constructor.setAccessible(true);
        LocalAnalytics previous = constructor.newInstance(directory, java.time.Clock.systemDefaultZone());
        previous.setEnabled(true);
        java.lang.reflect.Method record = LocalAnalytics.class.getDeclaredMethod("recordFeature", String.class, String.class);
        record.setAccessible(true);
        record.invoke(previous, "copy_text", "copy");
        assertEquals(1, previous.snapshot(1).totalFeatureUses);
        previous.close();
        assertEquals("old-purpose", LocalPortraitContext.current(context).purpose);
        LocalAnalytics analytics = LocalAnalytics.get(context);
        try {
            assertTrue(analytics.isEnabled());
            assertEquals(1, analytics.snapshot(1).totalFeatureUses);
        }
        finally { analytics.close(); }
        assertTrue(new File(context.getNoBackupFilesDir(), "tokai-analytics/portrait-context.json").isFile());
        assertFalse(directory.exists());
    }

    @Test public void failedDirectoryMigrationPreservesSourceAndCanBeRetried() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File source = new File(context.getFilesDir(), "douai_test_chat");
        assertTrue(source.mkdirs());
        Files.write(new File(source, "history.bin").toPath(), new byte[]{4, 5});
        File blocked = new File(context.getFilesDir(), "tokai_test_chat");
        Files.write(blocked.toPath(), new byte[]{9});
        assertThrows(IllegalStateException.class, () -> new SettingsRepository(context));
        assertTrue(new File(source, "history.bin").isFile());
        Files.delete(blocked.toPath());
        new SettingsRepository(context);
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(new File(blocked, "history.bin").toPath()));
    }

    @Test public void oldRedactedBackupsRestoreCurrentSecretAndExportOnlyCurrentIdentity() throws Exception {
        SettingsRepository source = repository("backup-source"), target = repository("backup-target");
        source.putString(SpeechSettings.MINIMAX_API_KEY, "source-secret");
        target.putString(SpeechSettings.MINIMAX_API_KEY, "current-secret");
        JSONObject backup = new JSONObject(ConfigTransfer.export(source, false));
        backup.getJSONObject("module").put("applicationId", "io.github.yudigaga.douai");
        String oldBackup = backup.toString().replace("__TOKAI_SECRET_OMITTED__", "__DOUAI_SECRET_OMITTED__");
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, oldBackup);
        ConfigTransfer.apply(target, preview);
        assertEquals("current-secret", target.getString(SpeechSettings.MINIMAX_API_KEY, ""));
        JSONObject exported = new JSONObject(ConfigTransfer.export(target, false));
        assertEquals("io.github.yudigaga.tokai", exported.getJSONObject("module").getString("applicationId"));
        assertFalse(exported.toString().contains("__DOUAI_SECRET_OMITTED__"));
    }

    @Test public void oldLocalBundleKindRemainsImportable() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SettingsRepository store = repository("bundle");
        JSONObject bundle = new JSONObject(ConfigBackup.export(context, store, true, true));
        bundle.put("kind", "douai-local-backup");
        try {
            assertTrue(ConfigBackup.inspect(store, bundle.toString(), ConfigTransfer.Mode.MERGE).includesAnalytics);
        } finally { LocalAnalytics.get(context).close(); }
    }

    private static SettingsRepository repository(String name) {
        return new SettingsRepository(RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE));
    }
}
