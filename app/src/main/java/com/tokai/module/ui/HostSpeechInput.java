package com.tokai.module.ui;

import com.tokai.module.R;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

/** Uses an installed system recognizer to fill a local draft; never records or sends IM audio. */
final class HostSpeechInput implements AutoCloseable {
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;

    static HostSpeechInput launch(Activity activity, Consumer<String> selected) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        com.tokai.module.i18n.ModuleLocale.ensureInitialized(activity);
        HostSpeechInput input = new HostSpeechInput();
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> contract = HostActivityResults.type("androidx.activity.result.contract.ActivityResultContract", loader);
            Class<?> callback = HostActivityResults.type("androidx.activity.result.ActivityResultCallback", loader);
            Class<?> registryType = HostActivityResults.type("androidx.activity.result.ActivityResultRegistry", loader);
            input.launcherType = HostActivityResults.type("androidx.activity.result.ActivityResultLauncher", loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object open = HostActivityResults.type("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult", loader)
                    .getConstructor().newInstance();
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                    return "TokAiSpeechInput";
                }
                if (!HostActivityResults.callback(method) || input.closed) return null;
                input.close();
                String text = null;
                if (args != null && args.length > 0 && args[0] != null) {
                    Object raw = args[0];
                    Class<?> resultType = HostActivityResults.type("androidx.activity.result.ActivityResult", loader);
                    if (Integer.valueOf(Activity.RESULT_OK).equals(HostActivityResults.resultCode(raw))) {
                        Intent data = HostActivityResults.resultData(raw);
                        ArrayList<String> words = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (words != null && !words.isEmpty()) text = words.get(0);
                    }
                }
                selected.accept(text);
                return null;
            });
            input.launcher = HostActivityResults.register(registryType, contract, callback)
                    .invoke(registry, "tokai_speech_" + UUID.randomUUID(), open, result);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, com.tokai.module.i18n.UiText.get(R.string.editor_ui_speech_input));
            HostActivityResults.launch(input.launcherType, input.launcher, intent);
            return input;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            input.close();
            return null;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (launcher != null) {
            try { HostActivityResults.unregister(launcherType, launcher); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
}
