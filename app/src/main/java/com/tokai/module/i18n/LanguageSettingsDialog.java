package com.tokai.module.i18n;

import android.app.Dialog;
import android.content.Context;
import android.widget.Toast;
import com.tokai.module.R;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;

/** One local setting for this module UI entry; never writes host language or spoofing options. */
public final class LanguageSettingsDialog {
    private LanguageSettingsDialog() { }

    public static String label(ModuleLanguage language) {
        return language == ModuleLanguage.SYSTEM ? UiText.get(R.string.module_language_system) : language.nativeName;
    }

    public static Dialog show(Context context, SettingsStore settings, Runnable reopen) {
        ModuleLocale.bind(context, settings);
        ModuleLanguage[] choices = ModuleLanguage.values();
        String[] labels = new String[choices.length];
        for (int index = 0; index < choices.length; index++) labels[index] = label(choices[index]);
        return new MiuixDialogBuilder(context, settings.isInHost())
                .setTitle(UiText.get(R.string.module_language_title))
                .setSingleChoiceItems(labels, ModuleLocale.choice().ordinal(), (dialog, which) -> {
                    if (which < 0 || which >= choices.length) return;
                    try {
                        ModuleLocale.select(settings, choices[which].value);
                        dialog.dismiss();
                        if (reopen != null) reopen.run();
                        else Toast.makeText(context, UiText.get(R.string.module_language_reopen), Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException failure) {
                        Toast.makeText(context, UiText.get(R.string.module_language_save_failed), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(UiText.get(R.string.module_action_cancel), null)
                .show();
    }
}
