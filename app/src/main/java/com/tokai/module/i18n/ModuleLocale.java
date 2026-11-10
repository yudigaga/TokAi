package com.tokai.module.i18n;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import android.view.LayoutInflater;
import com.tokai.module.BuildConfig;
import com.tokai.module.R;
import com.tokai.module.config.SettingsRepository;
import com.tokai.module.config.SettingsStore;
import java.util.ArrayList;
import java.util.Locale;

/** A resource context for module UI; host windows, services and private storage stay with the host. */
public final class ModuleLocale {
    // Only an Application or createPackageContext result is retained, never an Activity.
    @SuppressLint("StaticFieldLeak")
    private static Context moduleContext;
    private static SettingsStore store;
    // Derived from the application/package context above with a private Configuration.
    @SuppressLint("StaticFieldLeak")
    private static Context stringsContext;
    private static String stringsLocale = "";

    private ModuleLocale() { }

    public static synchronized void bind(Context context, SettingsStore settings) {
        Context resolved = findModuleContext(context);
        if (moduleContext != resolved) { moduleContext = resolved; stringsContext = null; }
        store = settings;
    }

    public static synchronized void ensureInitialized(Context context) {
        if (moduleContext == null) bind(context, new SettingsRepository(context));
    }

    private static Context findModuleContext(Context context) {
        Context application = context.getApplicationContext();
        if (application instanceof Application) {
            try {
                String resourcePackage = application.getResources().getResourcePackageName(R.string.module_brand);
                if (BuildConfig.APPLICATION_ID.equals(resourcePackage) || R.class.getPackage().getName().equals(resourcePackage)) return application;
            } catch (Resources.NotFoundException ignored) { }
        }
        Context owner = application == null ? context : application;
        try { return owner.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY); }
        catch (android.content.pm.PackageManager.NameNotFoundException failure) {
            throw new IllegalStateException("Module resources are unavailable", failure);
        }
    }

    public static synchronized ModuleLanguage choice() {
        if (store == null) return ModuleLanguage.SYSTEM;
        try { return ModuleLanguage.parse(store.getString(ModuleLanguage.KEY, "system")); }
        catch (ClassCastException invalid) { return ModuleLanguage.SYSTEM; }
    }

    public static Locale locale() {
        LocaleList actual = Resources.getSystem().getConfiguration().getLocales();
        ArrayList<Locale> locales = new ArrayList<>(actual.size());
        for (int index = 0; index < actual.size(); index++) locales.add(actual.get(index));
        return choice().resolve(locales);
    }

    public static void select(SettingsStore settings, String value) {
        if (!ModuleLanguage.valid(value)) throw new IllegalArgumentException("Unsupported module UI language");
        settings.putString(ModuleLanguage.KEY, value);
        synchronized (ModuleLocale.class) { store = settings; stringsContext = null; }
    }

    public static Configuration configure(Configuration original) {
        Configuration result = new Configuration(original);
        Locale locale = locale();
        result.setLocales(new LocaleList(locale));
        result.setLayoutDirection(locale);
        return result;
    }

    public static Context wrap(Context context) {
        ensureInitialized(context);
        return context instanceof LocalizedContext ? context : new LocalizedContext(context);
    }

    static synchronized String string(int id, Object... arguments) {
        if (moduleContext == null) throw new IllegalStateException("Module UI locale was not initialized");
        Locale selected = locale();
        String tag = selected.toLanguageTag();
        if (stringsContext == null || !tag.equals(stringsLocale)) {
            Configuration configuration = new Configuration(moduleContext.getResources().getConfiguration());
            configuration.setLocales(new LocaleList(selected));
            configuration.setLayoutDirection(selected);
            stringsContext = moduleContext.createConfigurationContext(configuration);
            stringsLocale = tag;
        }
        String pattern = stringsContext.getString(id);
        return arguments.length == 0 ? pattern : String.format(selected, pattern, arguments);
    }

    private static final class LocalizedContext extends ContextWrapper {
        private Context configured;
        private Configuration active;
        private LayoutInflater inflater;
        LocalizedContext(Context base) { super(base); }
        @Override public Resources getResources() {
            Configuration next = configure(getBaseContext().getResources().getConfiguration());
            if (active == null || !active.equals(next)) {
                synchronized (ModuleLocale.class) { configured = moduleContext.createConfigurationContext(next); }
                active = next;
            }
            return configured.getResources();
        }
        @Override public android.content.res.AssetManager getAssets() { return getResources().getAssets(); }
        @Override public ClassLoader getClassLoader() { return ModuleLocale.class.getClassLoader(); }
        @Override public Object getSystemService(String name) {
            if (!LAYOUT_INFLATER_SERVICE.equals(name)) return super.getSystemService(name);
            if (inflater == null) inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
            return inflater;
        }
    }
}
