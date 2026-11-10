package com.tokai.module.ui;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.net.Uri;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Host AndroidX document selection, without replacing the host's result callbacks. */
final class HostImagePicker implements AutoCloseable {
    private static final Map<Activity, HostImagePicker> PENDING = new WeakHashMap<>();
    private final Activity activity;
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;
    private boolean resultReceived;
    private final TokAiLog.Operation trace = TokAiLog.begin("media_picker", "HostImagePicker");

    private HostImagePicker(Activity activity) { this.activity = activity; }

    static HostImagePicker launch(Activity activity, Consumer<Uri> selected) {
        return launch(activity, new String[]{"image/*"}, selected);
    }

    static HostImagePicker launch(Activity activity, String[] mimeTypes, Consumer<Uri> selected) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        if (mimeTypes == null || mimeTypes.length == 0) return null;
        HostImagePicker picker = new HostImagePicker(activity);
        try {
            ClassLoader loader = activity.getClass().getClassLoader();
            Class<?> contract = HostActivityResults.type("androidx.activity.result.contract.ActivityResultContract", loader);
            Class<?> callback = HostActivityResults.type("androidx.activity.result.ActivityResultCallback", loader);
            Class<?> registryType = HostActivityResults.type("androidx.activity.result.ActivityResultRegistry", loader);
            picker.launcherType = HostActivityResults.type("androidx.activity.result.ActivityResultLauncher", loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            boolean obfuscated = "X.013R".equals(registryType.getName());
            Object open = HostActivityResults.type(obfuscated
                    ? "androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult"
                    : "androidx.activity.result.contract.ActivityResultContracts$OpenDocument", loader)
                    .getConstructor().newInstance();
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    return "TokAiImageSelection";
                }
                if (!HostActivityResults.callback(method) || picker.closed) return null;
                picker.resultReceived = true;
                picker.trace.stage("selection_callback", "callback_received=true");
                picker.close();
                Object value = arguments == null || arguments.length == 0 ? null : arguments[0];
                if (obfuscated && value != null) {
                    Class<?> resultType = HostActivityResults.type("androidx.activity.result.ActivityResult", loader);
                    android.content.Intent data = HostActivityResults.resultData(value);
                    value = Integer.valueOf(Activity.RESULT_OK).equals(HostActivityResults.resultCode(value))
                            && data != null ? data.getData() : null;
                }
                picker.trace.finish(value instanceof Uri ? TokAiLog.Outcome.SUCCESS : TokAiLog.Outcome.CANCELLED,
                        value instanceof Uri ? "selection_returned" : "user_cancelled", "uri_present=" + (value instanceof Uri) + ";file_content_verified=false");
                picker.trace.wrapConsumer(selected).accept(value instanceof Uri ? (Uri) value : null);
                return null;
            });
            picker.launcher = HostActivityResults.register(registryType, contract, callback)
                    .invoke(registry, "tokai_image_" + UUID.randomUUID(), open, result);
            PENDING.put(activity, picker);
            Object input = obfuscated ? new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType(mimeTypes.length == 1 ? mimeTypes[0] : "*/*")
                    .putExtra(android.content.Intent.EXTRA_MIME_TYPES, mimeTypes.clone())
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) : mimeTypes.clone();
            HostActivityResults.launch(picker.launcherType, picker.launcher, input);
            return picker;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            picker.resultReceived = true;
            picker.trace.finish(TokAiLog.Outcome.FAILED, "picker_unavailable", "selection_not_started");
            picker.close();
            return null;
        }
    }

    static boolean isPending(Activity activity) { return PENDING.containsKey(activity); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (!resultReceived) trace.finish(TokAiLog.Outcome.CANCELLED, "picker_closed", "no_selection_consumed");
        if (PENDING.get(activity) == this) PENDING.remove(activity);
        if (launcher != null) {
            try { HostActivityResults.unregister(launcherType, launcher); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
}
