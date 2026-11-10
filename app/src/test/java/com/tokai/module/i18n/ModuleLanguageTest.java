package com.tokai.module.i18n;

import java.util.List;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ModuleLanguageTest {
    @Test public void sixStableChoicesHaveExactWhitelistAndSafeUnknownDefault() {
        assertEquals(6, ModuleLanguage.values().length);
        for (ModuleLanguage language : ModuleLanguage.values()) {
            assertTrue(ModuleLanguage.valid(language.value));
            assertSame(language, ModuleLanguage.parse(language.value));
        }
        for (String invalid : new String[]{null, "", "zh_CN", "ZH-HANS", "fr", "en-US", "../ko"}) {
            assertFalse(ModuleLanguage.valid(invalid)); assertSame(ModuleLanguage.SYSTEM, ModuleLanguage.parse(invalid));
        }
    }
    @Test public void explicitSelectionWinsOverSystemAndGlobalDefault() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.KOREA);
            for (ModuleLanguage language : ModuleLanguage.values()) if (language != ModuleLanguage.SYSTEM) {
                assertEquals(Locale.forLanguageTag(language.value), language.resolve(List.of(Locale.JAPANESE)));
            }
        } finally { Locale.setDefault(previous); }
    }
    @Test public void realSystemListUsesFirstSupportedLanguageAndEnglishFallback() {
        assertEquals(Locale.KOREAN, ModuleLanguage.SYSTEM.resolve(List.of(Locale.GERMAN, Locale.KOREAN, Locale.JAPANESE)));
        assertEquals(Locale.JAPANESE, ModuleLanguage.SYSTEM.resolve(List.of(Locale.JAPANESE, Locale.ENGLISH)));
        assertEquals(Locale.ENGLISH, ModuleLanguage.SYSTEM.resolve(List.of(Locale.FRENCH, Locale.GERMAN)));
        assertEquals(Locale.ENGLISH, ModuleLanguage.SYSTEM.resolve(List.of()));
        assertEquals(Locale.ENGLISH, ModuleLanguage.SYSTEM.resolve(null));
    }
    @Test public void chineseExplicitScriptTakesPriorityOverRegion() {
        assertEquals("zh-Hans", system("zh-Hans-HK"));
        assertEquals("zh-Hant", system("zh-Hant-CN"));
    }
    @Test public void chineseRegionsDistinguishTraditionalAndSimplifiedWithoutScript() {
        for (String tag : new String[]{"zh-TW", "zh-HK", "zh-MO"}) assertEquals("zh-Hant", system(tag));
        for (String tag : new String[]{"zh-CN", "zh-SG", "zh"}) assertEquals("zh-Hans", system(tag));
    }
    @Test public void nativeNamesLetUsersRecoverFromChoosingAnotherLanguage() {
        assertEquals("简体中文", ModuleLanguage.SIMPLIFIED.nativeName);
        assertEquals("繁體中文", ModuleLanguage.TRADITIONAL.nativeName);
        assertEquals("한국어", ModuleLanguage.KOREAN.nativeName);
        assertEquals("日本語", ModuleLanguage.JAPANESE.nativeName);
        assertEquals("English", ModuleLanguage.ENGLISH.nativeName);
    }
    private static String system(String tag) { return ModuleLanguage.SYSTEM.resolve(List.of(Locale.forLanguageTag(tag))).toLanguageTag(); }
}
