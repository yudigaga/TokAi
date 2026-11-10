package com.tokai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Upgrade compatibility for data written by TokAi before its internal identity was renamed. */
public final class LegacyIdentityMigration {
    static final String APPLICATION_ID = "io.github.yudigaga.douai";
    static final String BACKUP_KIND = "douai-local-backup";
    private static final String SECRET_OMITTED = "__DOUAI_SECRET_OMITTED__";
    private static final String HOST_PREFIX = "douai_host_";
    private static final String PREFERENCES_PREFIX = "douai_";
    private static final Set<String> MIGRATED = new HashSet<>();

    private LegacyIdentityMigration() { }

    public static void migrateAnalyticsIfPresent(Context context) {
        // Reading an empty analytics store must not create files or initialize settings.
        if (new File(context.getNoBackupFilesDir(), "douai-analytics").exists()) migrate(context);
    }

    public static synchronized void migrate(Context context) {
        String identity = context.getFilesDir().getAbsolutePath() + "\n" + context.getNoBackupFilesDir().getAbsolutePath();
        if (MIGRATED.contains(identity)) return;
        File lockFile = new File(context.getNoBackupFilesDir(), "tokai-identity-migration.lock");
        try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw");
             FileLock ignored = lock.getChannel().lock()) {
            migrateLocked(context);
        } catch (IOException failure) {
            throw new IllegalStateException("无法迁移本地数据，请检查存储空间后重试", failure);
        }
        MIGRATED.add(identity);
    }

    private static void migrateLocked(Context context) {
        move(new File(context.getFilesDir(), "douai_test_chat"), new File(context.getFilesDir(), "tokai_test_chat"));
        move(new File(context.getFilesDir(), "douai_message_chat"), new File(context.getFilesDir(), "tokai_message_chat"));
        move(new File(context.getFilesDir(), "douai_debug_logs"), new File(context.getFilesDir(), "tokai_debug_logs"));
        move(new File(context.getFilesDir(), "douai-voice-source"), new File(context.getFilesDir(), "tokai-voice-source"));
        File analytics = new File(context.getNoBackupFilesDir(), "tokai-analytics");
        // A database and its journal must move together; an existing current database takes precedence.
        File[] current = analytics.listFiles();
        if (!analytics.exists() || current != null && current.length == 0) {
            if (analytics.exists() && !analytics.delete()) throw failure();
            move(new File(context.getNoBackupFilesDir(), "douai-analytics"), analytics);
        }
        SharedPreferences completed = context.getSharedPreferences("tokai_identity_migration", Context.MODE_PRIVATE);
        for (String suffix : new String[]{"module_settings", "automation_history", "automation_limits",
                "comment_state", "message_state", "comment_submissions", "reply_submissions",
                "manual_voice_submissions", "comment_reply_ledger", "automation_ui", "adaptation_acknowledgment"}) {
            if (completed.getBoolean(suffix, false)) continue;
            Map<String, ?> legacy = context.getSharedPreferences(PREFERENCES_PREFIX + suffix, Context.MODE_PRIVATE).getAll();
            SharedPreferences target = context.getSharedPreferences("tokai_" + suffix, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = target.edit();
            for (Map.Entry<String, ?> entry : legacy.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(HOST_PREFIX)) key = "tokai_host_" + key.substring(HOST_PREFIX.length());
                if (!target.contains(key)) put(editor, key, entry.getValue());
            }
            if (!editor.commit()) throw failure();
            if ("module_settings".equals(suffix)) migrateReferencePaths(context, target);
            if (!completed.edit().putBoolean(suffix, true).commit()) throw failure();
        }
    }

    static boolean initialized(SharedPreferences preferences) {
        return preferences.getBoolean("tokai_host_settings_initialized", false)
                || preferences.getBoolean(HOST_PREFIX + "settings_initialized", false);
    }

    static boolean hostMetadata(String key) { return key.startsWith(HOST_PREFIX) || key.startsWith("tokai_host_"); }

    static String normalizeSecretMarker(String value) { return value.replace(SECRET_OMITTED, ConfigSecrets.OMITTED); }

    public static String normalizeLogTag(String lowerCaseValue) {
        String normalized = lowerCaseValue.replace("[douai.", "[tokai.");
        return normalized.startsWith("douai ") ? "tokai " + normalized.substring(6) : normalized;
    }

    private static void migrateReferencePaths(Context context, SharedPreferences preferences) {
        String legacy = new File(context.getFilesDir(), "douai-voice-source").getAbsolutePath() + File.separator;
        String current = new File(context.getFilesDir(), "tokai-voice-source").getAbsolutePath() + File.separator;
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (String key : new String[]{FishSpeechSettings.REFERENCE_SAMPLES, "comment_voice_path", "dm_voice_path"}) {
            String before = preferences.getString(key, "");
            // The Fish JSON escapes path separators on Windows; the other settings are plain paths.
            String after = FishSpeechSettings.REFERENCE_SAMPLES.equals(key)
                    ? before.replace(legacy.replace("\\", "\\\\"), current.replace("\\", "\\\\"))
                    : before.startsWith(legacy) ? current + before.substring(legacy.length()) : before;
            if (!before.equals(after)) { editor.putString(key, after); changed = true; }
        }
        if (changed && !editor.commit()) throw failure();
    }

    private static void move(File source, File target) {
        if (!source.exists()) return;
        if (!target.exists()) {
            if (!source.renameTo(target) && source.exists()) throw failure();
            return;
        }
        if (source.isDirectory() != target.isDirectory()) throw failure();
        if (!source.isDirectory()) return; // Existing current files win.
        File[] children = source.listFiles();
        if (children == null) throw failure();
        for (File child : children) move(child, new File(target, child.getName()));
        File[] remaining = source.listFiles();
        if (remaining != null && remaining.length == 0 && !source.delete() && source.exists()) throw failure();
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set<?>) editor.putStringSet(key, new HashSet<>((Set<String>) value));
        else throw failure();
    }

    private static IllegalStateException failure() { return new IllegalStateException("无法迁移本地数据，请检查存储空间后重试"); }
}
