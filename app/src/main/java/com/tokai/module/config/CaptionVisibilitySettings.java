package com.tokai.module.config;

import java.util.Map;

/** Mutually exclusive native-caption choices; ambiguous external snapshots preserve hiding. */
public final class CaptionVisibilitySettings {
    public static final String HIDE = "hide_native_captions";
    public static final String FORCE = "force_native_captions";

    private CaptionVisibilitySettings() { }

    static void normalize(Map<String, Object> values) {
        if (Boolean.TRUE.equals(values.get(HIDE)) && Boolean.TRUE.equals(values.get(FORCE))) {
            values.put(FORCE, false);
        }
    }
}
