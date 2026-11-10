package com.tokai.module.i18n;

import java.util.List;
import java.util.Locale;

/** Stable UI choices, independent from host locale/region and recognition language. */
public enum ModuleLanguage {
    SYSTEM("system", ""), SIMPLIFIED("zh-Hans", "简体中文"), TRADITIONAL("zh-Hant", "繁體中文"),
    KOREAN("ko", "한국어"), JAPANESE("ja", "日本語"), ENGLISH("en", "English");

    public static final String KEY = "module_ui_language";
    public final String value, nativeName;
    ModuleLanguage(String value, String nativeName) { this.value = value; this.nativeName = nativeName; }

    public static ModuleLanguage parse(String value) {
        for (ModuleLanguage language : values()) if (language.value.equals(value)) return language;
        return SYSTEM;
    }
    public static boolean valid(String value) {
        for (ModuleLanguage language : values()) if (language.value.equals(value)) return true;
        return false;
    }
    public Locale resolve(List<Locale> systemLocales) {
        if (this != SYSTEM) return Locale.forLanguageTag(value);
        if (systemLocales != null) for (Locale locale : systemLocales) {
            if (locale == null) continue;
            switch (locale.getLanguage()) {
                case "zh":
                    String script = locale.getScript();
                    if ("Hant".equalsIgnoreCase(script)) return Locale.forLanguageTag("zh-Hant");
                    if ("Hans".equalsIgnoreCase(script)) return Locale.forLanguageTag("zh-Hans");
                    String region = locale.getCountry();
                    return Locale.forLanguageTag("TW".equals(region) || "HK".equals(region) || "MO".equals(region) ? "zh-Hant" : "zh-Hans");
                case "ko": return Locale.KOREAN;
                case "ja": return Locale.JAPANESE;
                case "en": return Locale.ENGLISH;
                default: break;
            }
        }
        return Locale.ENGLISH;
    }
}
