package com.tokai.module.i18n;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import com.tokai.module.R;
import com.tokai.module.config.SettingsRepository;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en")
public final class ModuleLocaleTest {
    Context context;
    SettingsRepository store;
    @Before public void bind() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().clear().commit();
        store = new SettingsRepository(context); ModuleLocale.bind(context, store);
    }
    @Test public void defaultFollowsRealSystemResourcesInsteadOfSpoofableJavaDefault() {
        RuntimeEnvironment.setQualifiers("ja");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.KOREAN);
            assertEquals("ja", ModuleLocale.locale().getLanguage());
            assertEquals("モジュールの状態", UiText.get(R.string.module_status));
        } finally { Locale.setDefault(previous); }
    }
    @Test public void allExplicitLanguagesRenderResourcesAndKeepHostConfigurationAndDefaultUnchanged() {
        Configuration host = new Configuration(context.getResources().getConfiguration());
        Locale original = Locale.getDefault();
        String[] expected = {"模块状态", "模組狀態", "모듈 상태", "モジュールの状態", "Module status"};
        ModuleLanguage[] choices = {ModuleLanguage.SIMPLIFIED, ModuleLanguage.TRADITIONAL,
                ModuleLanguage.KOREAN, ModuleLanguage.JAPANESE, ModuleLanguage.ENGLISH};
        for (int index = 0; index < choices.length; index++) {
            ModuleLocale.select(store, choices[index].value);
            assertEquals(choices[index], ModuleLocale.choice());
            assertEquals(expected[index], UiText.get(R.string.module_status));
            assertEquals(host, context.getResources().getConfiguration()); assertEquals(original, Locale.getDefault());
        }
    }
    @Test public void selectionPersistsAndCanReturnToSystemAndRejectsUnknownWithoutChangingStorage() {
        ModuleLocale.select(store, "ko");
        assertEquals("ko", new SettingsRepository(context).getString(ModuleLanguage.KEY, "system"));
        assertThrows(IllegalArgumentException.class, () -> ModuleLocale.select(store, "xx"));
        assertEquals("ko", store.getString(ModuleLanguage.KEY, "system"));
        ModuleLocale.select(store, "system"); assertEquals("en", ModuleLocale.locale().getLanguage());
    }
    @Test public void corruptPreferenceSafelyUsesSystem() {
        context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit().putInt(ModuleLanguage.KEY, 7).commit();
        assertEquals(ModuleLanguage.SYSTEM, ModuleLocale.choice()); assertEquals("en", ModuleLocale.locale().getLanguage());
    }
    @Test public void localizedWrapperPreservesServiceAndStorageOwnerAndDoesNotMutateInputConfiguration() {
        ModuleLocale.select(store, "zh-Hant");
        Context wrapped = ModuleLocale.wrap(context);
        assertEquals(context.getPackageName(), wrapped.getPackageName());
        assertSame(context.getApplicationContext(), wrapped.getApplicationContext());
        assertEquals("zh-Hant", wrapped.getResources().getConfiguration().getLocales().get(0).toLanguageTag());
        Configuration input = new Configuration(context.getResources().getConfiguration());
        Configuration before = new Configuration(input); input.fontScale = 1.4f;
        Configuration configured = ModuleLocale.configure(input);
        assertEquals(1.4f, configured.fontScale, 0); assertEquals(before.getLocales(), input.getLocales());
        assertEquals("zh-Hant", configured.getLocales().get(0).toLanguageTag());
    }
    @Test public void stringsNeverReadModuleIdsFromHostResources() {
        Context module = context;
        Resources hostResources = new Resources(module.getAssets(), module.getResources().getDisplayMetrics(),
                new Configuration(module.getResources().getConfiguration())) {
            @Override public String getResourcePackageName(int id) { return "com.zhiliaoapp.musically"; }
            @Override public String getString(int id) { return "HOST STRING"; }
        };
        Context host = new ContextWrapper(module) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageName() { return "com.zhiliaoapp.musically"; }
            @Override public Resources getResources() { return hostResources; }
            @Override public Context createPackageContext(String name, int flags) {
                assertEquals("io.github.yudigaga.tokai", name); return module;
            }
        };
        ModuleLocale.bind(host, store); ModuleLocale.select(store, "ko");
        assertEquals("모듈 상태", UiText.get(R.string.module_status));
        assertEquals("HOST STRING", host.getString(R.string.module_status));
        assertEquals("모듈 상태", ModuleLocale.wrap(host).getString(R.string.module_status));
    }
    @Test public void positionalFormattingUsesSelectedModuleLanguage() {
        ModuleLocale.select(store, "ja");
        assertEquals("モジュールバージョン 0.1.18", UiText.get(R.string.module_version, "0.1.18"));
        assertEquals("キャッシュ：3件 · 一致：2件", UiText.get(R.string.voice_quick_catalog_status, 3, 2, ""));
    }
}
