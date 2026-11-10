package com.tokai.module.config;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Copies old module values once into the target app's independent settings store. */
public final class HostSettingsMigration {
    private static final String INITIALIZED = "tokai_host_settings_initialized";

    private HostSettingsMigration() { }

    public static boolean copyOnce(SharedPreferences legacy, SharedPreferences host) {
        if (LegacyIdentityMigration.initialized(host)) return false;
        SharedPreferences.Editor editor = host.edit();
        for (Map.Entry<String, ?> entry : legacy.getAll().entrySet()) {
            String key = entry.getKey();
            if (LegacyIdentityMigration.hostMetadata(key)) continue;
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set<?>) {
                Set<String> strings = new HashSet<>();
                for (Object item : (Set<?>) value) if (item instanceof String) strings.add((String) item);
                editor.putStringSet(key, strings);
            }
        }
        editor.putBoolean(INITIALIZED, true);
        if (!editor.commit()) throw new IllegalStateException("Could not commit the initial host settings copy");
        return true;
    }
}
