package com.tokai.module.ui;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Host-owned SAF document selection using the general contract retained by the host. */
final class HostConfigFilePicker implements AutoCloseable {
    private static final Map<Activity, HostConfigFilePicker> PENDING = new WeakHashMap<>();
    private final Activity activity;
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;
    private boolean resultReceived;
    private final TokAiLog.Operation trace = TokAiLog.begin("media_picker", "HostConfigFilePicker");

    private HostConfigFilePicker(Activity activity) { this.activity = activity; }

    static HostConfigFilePicker launch(Activity activity, boolean export, String name,
                                       Consumer<Uri> selected) {
        return launch(activity, export, name, selected,
                activity == null ? null : activity.getClass().getClassLoader());
    }

    static HostConfigFilePicker launch(Activity activity, boolean export, String name,
                                       Consumer<Uri> selected, ClassLoader loader) {
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(export ? "application/json" : "*/*")
                .addFlags(export ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (export) intent.putExtra(Intent.EXTRA_TITLE, name);
        else intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        return launch(activity, intent, selected, loader);
    }

    static HostConfigFilePicker launch(Activity activity, Intent intent, Consumer<Uri> selected) {
        return launch(activity, intent, selected, activity == null ? null : activity.getClass().getClassLoader());
    }

    private static HostConfigFilePicker launch(Activity activity, Intent intent,
                                               Consumer<Uri> selected, ClassLoader loader) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostConfigFilePicker previous = PENDING.get(activity);
        if (previous != null) previous.close();
        HostConfigFilePicker picker = new HostConfigFilePicker(activity);
        try {
            Class<?> contract = HostActivityResults.type("androidx.activity.result.contract.ActivityResultContract", loader);
            Class<?> callback = HostActivityResults.type("androidx.activity.result.ActivityResultCallback", loader);
            Class<?> registryType = HostActivityResults.type("androidx.activity.result.ActivityResultRegistry", loader);
            picker.launcherType = HostActivityResults.type("androidx.activity.result.ActivityResultLauncher", loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object start = HostActivityResults.type("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult", loader)
                    .getConstructor().newInstance();
            Class<?> resultType = HostActivityResults.type("androidx.activity.result.ActivityResult", loader);
            
            
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                    return "TokAiConfigSelection";
                }
                if (!HostActivityResults.callback(method) || picker.closed) return null;
                picker.resultReceived = true;
                picker.trace.stage("selection_callback", "callback_received=true");
                picker.close();
                Uri uri = null;
                boolean parseFailed = false;
                try {
                    Object value = args == null || args.length == 0 ? null : args[0];
                    if (resultType.isInstance(value) && Integer.valueOf(Activity.RESULT_OK).equals(HostActivityResults.resultCode(value))) {
                        Object data = HostActivityResults.resultData(value);
                        if (data instanceof Intent) uri = ((Intent) data).getData();
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    parseFailed = true; picker.trace.error("selection_parse", failure);
                    picker.trace.finish(TokAiLog.Outcome.FAILED, "selection_protocol_error", "uri_unavailable=true");
                }
                // No persistent grant is needed: the operation consumes this document immediately.
                if (!parseFailed) picker.trace.finish(uri != null && "content".equalsIgnoreCase(uri.getScheme()) ? TokAiLog.Outcome.SUCCESS : TokAiLog.Outcome.CANCELLED,
                        "selection_result", "content_uri=" + (uri != null && "content".equalsIgnoreCase(uri.getScheme())) + ";document_io_verified=false");
                picker.trace.wrapConsumer(selected).accept(uri != null && "content".equalsIgnoreCase(uri.getScheme()) ? uri : null);
                return null;
            });
            picker.launcher = HostActivityResults.register(registryType, contract, callback)
                    .invoke(registry, "tokai_config_" + UUID.randomUUID(), start, result);
            PENDING.put(activity, picker);
            HostActivityResults.launch(picker.launcherType, picker.launcher, intent);
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
