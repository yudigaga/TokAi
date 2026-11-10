package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.config.SettingsStore;
import com.tokai.module.ui.miuix.MiuixDialogBuilder;

import java.util.List;

/** Model profile switcher surfaced by the feed gesture menu. */
public final class GestureQuickModelDialog {
    private GestureQuickModelDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable openSettings) {
        com.tokai.module.i18n.ModuleLocale.ensureInitialized(activity);
        AiModelSettings settings;
        try {
            settings = AiModelSettings.read(store);
        } catch (RuntimeException invalid) {
            return new MiuixDialogBuilder(activity, true).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_model))
                    .setMessage(com.tokai.module.i18n.UiText.get(R.string.editor_ui_model_config_unreadable))
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null).create();
        }
        List<AiModelConfig> configs = settings.configs;
        if (configs.isEmpty()) {
            return new MiuixDialogBuilder(activity, true).setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_model))
                    .setMessage(com.tokai.module.i18n.UiText.get(R.string.editor_ui_no_model_configs))
                    .setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null)
                    .setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_open_module_settings), (dialog, which) -> openSettings.run())
                    .create();
        }

        String[] labels = new String[configs.size()];
        int selected = -1;
        for (int index = 0; index < configs.size(); index++) {
            AiModelConfig config = configs.get(index);
            labels[index] = config.name + " · " + config.modelName;
            if (config.id.equals(settings.activeId)) selected = index;
        }
        MiuixDialogBuilder builder = new MiuixDialogBuilder(activity, true);
        builder.setTitle(com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_model));
        builder.setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    try {
                        activate(store, configs.get(index).id);
                        Toast.makeText(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_switched_to, configs.get(index).name), Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException failure) {
                        Toast.makeText(activity, failure.getMessage() == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_switch_model_failed) : failure.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                    dialog.dismiss();
                });
        builder.setNegativeButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_close), null);
        builder.setPositiveButton(com.tokai.module.i18n.UiText.get(R.string.editor_ui_module_settings), (dialog, which) -> openSettings.run());
        return builder.create();
    }

    static void activate(SettingsStore store, String modelId) {
        AiModelSettings.read(store).activate(modelId).save(store);
    }
}
