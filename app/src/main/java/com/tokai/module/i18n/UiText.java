package com.tokai.module.i18n;

/** Explicit module message IDs only. User content, protocol values and logs never enter a translator. */
public final class UiText {
    private UiText() { }
    public static String get(int id, Object... arguments) { return ModuleLocale.string(id, arguments); }
    /** Only for display boundaries. The fallback is already-built original text, never a translation dictionary. */
    public static String safeGet(android.content.Context context, int id, String rawFallback, Object... arguments) {
        if (id == 0) return rawFallback;
        try {
            ModuleLocale.ensureInitialized(context);
            return get(id, arguments);
        } catch (RuntimeException | LinkageError unavailable) {
            return rawFallback;
        }
    }
}
