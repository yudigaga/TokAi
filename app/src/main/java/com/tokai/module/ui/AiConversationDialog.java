package com.tokai.module.ui;

import com.tokai.module.R;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.os.Bundle;
import android.widget.Toast;

import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.config.SettingsStore;

/** Opens a module-owned conversation for a saved model on the host message page. */
public final class AiConversationDialog {
    private AiConversationDialog() { }

    public static Dialog show(Activity activity, SettingsStore store, AiModelConfig requested) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        AiModelConfig config = null;
        for (AiModelConfig saved : AiModelSettings.read(store).configs) {
            if (saved.id.equals(requested.id) && saved.messageContactEnabled) { config = saved; break; }
        }
        if (config == null) return null;
        AiModelConfig snapshot = config;
        HostNativeChatViews nativeViews;
        try { nativeViews = HostNativeChatViews.create(activity); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            TokAiLog.legacy(5, "TokAi.AiContact", "Native chat views unavailable", failure);
            Toast.makeText(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_native_chat_unsupported), Toast.LENGTH_LONG).show();
            return null;
        }
        AiTestChatDialog dialog = null;
        try {
            dialog = new AiTestChatDialog(activity, snapshot, true, true, nativeViews);
            dialog.setOwnerActivity(activity);
            AiTestChatDialog opened = dialog;
            dialog.setContactEditAction(() -> {
                opened.dismiss();
                edit(activity, store, snapshot);
            });
            dialog.show();
            return dialog;
        } catch (RuntimeException | LinkageError failure) {
            if (dialog != null) dialog.dismiss();
            TokAiLog.legacy(5, "TokAi.AiContact", "Native chat could not open", failure);
            Toast.makeText(activity, com.tokai.module.i18n.UiText.get(R.string.editor_ui_native_chat_reopen), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    static AiModelEditor contactEditor(Activity activity, SettingsStore store, AiModelConfig config) {
        return new AiModelEditor(activity, store, config, true, () -> { }, false, () -> {
            AiModelSettings current = AiModelSettings.read(store);
            for (AiModelConfig candidate : current.configs) {
                if (candidate.id.equals(config.id) && !candidate.toJson().toString().equals(config.toJson().toString())) {
                    throw new IllegalStateException(com.tokai.module.i18n.UiText.get(R.string.editor_ui_contact_changed));
                }
            }
            current.delete(config.id).save(store);
        });
    }

    private static void edit(Activity activity, SettingsStore store, AiModelConfig config) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        AiModelEditor editor = contactEditor(activity, store, config);
        Application application = activity.getApplication();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity owner) {
                if (owner == activity && !HostImagePicker.isPending(owner)
                        && !HostMediaPicker.isPending(owner)) editor.close();
            }
            @Override public void onActivityDestroyed(Activity owner) { if (owner == activity) editor.close(); }
            @Override public void onActivityCreated(Activity owner, Bundle state) { }
            @Override public void onActivityStarted(Activity owner) { }
            @Override public void onActivityResumed(Activity owner) { }
            @Override public void onActivityStopped(Activity owner) { }
            @Override public void onActivitySaveInstanceState(Activity owner, Bundle state) { }
        };
        editor.show((child, close) -> {
            child.setOwnerActivity(activity);
            child.setOnDismissListener(ignored -> {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                close.run();
            });
            application.registerActivityLifecycleCallbacks(lifecycle);
            try {
                child.show();
                HostDialogBounds.install(activity, child, false);
            } catch (RuntimeException failure) {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                throw failure;
            }
        });
    }
}
