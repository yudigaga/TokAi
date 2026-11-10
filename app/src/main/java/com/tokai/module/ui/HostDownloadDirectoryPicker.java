package com.tokai.module.ui;

import com.tokai.module.R;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Requests a persistent host-owned grant to a writable document tree. */
final class HostDownloadDirectoryPicker implements AutoCloseable {
    static final String SETTINGS_KEY = com.tokai.module.config.LocalOptions.DOWNLOAD_SAVE_TREE;
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final Map<Activity, HostDownloadDirectoryPicker> PENDING = new WeakHashMap<>();
    private final Activity activity;
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;
    private boolean resultReceived;
    private final TokAiLog.Operation trace = TokAiLog.begin("media_picker", "HostDownloadDirectoryPicker");

    private HostDownloadDirectoryPicker(Activity activity) { this.activity = activity; }

    static HostDownloadDirectoryPicker launch(Activity activity, Uri initial,
                                               Consumer<Uri> selected, Consumer<String> failed) {
        return launch(activity, initial, selected, failed,
                activity == null ? null : activity.getClass().getClassLoader());
    }

    static HostDownloadDirectoryPicker launch(Activity activity, Uri initial,
                                               Consumer<Uri> selected, Consumer<String> failed,
                                               ClassLoader loader) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostDownloadDirectoryPicker previous = PENDING.get(activity);
        if (previous != null) previous.close();
        HostDownloadDirectoryPicker picker = new HostDownloadDirectoryPicker(activity);
        try {
            Class<?> contract = HostActivityResults.type("androidx.activity.result.contract.ActivityResultContract", loader);
            Class<?> callback = HostActivityResults.type("androidx.activity.result.ActivityResultCallback", loader);
            Class<?> registryType = HostActivityResults.type("androidx.activity.result.ActivityResultRegistry", loader);
            picker.launcherType = HostActivityResults.type("androidx.activity.result.ActivityResultLauncher", loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            // The host strips OpenDocumentTree, but keeps its general Intent contract.
            Object open = HostActivityResults.type("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult", loader)
                    .getConstructor().newInstance();
            Class<?> resultType = HostActivityResults.type("androidx.activity.result.ActivityResult", loader);
            
            
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    return "TokAiDownloadDirectorySelection";
                }
                if (!HostActivityResults.callback(method) || picker.closed) return null;
                picker.resultReceived = true;
                picker.trace.stage("selection_callback", "callback_received=true");
                picker.close();
                Object value = arguments == null || arguments.length == 0 ? null : arguments[0];
                try {
                    if (!resultType.isInstance(value)
                            || !Integer.valueOf(Activity.RESULT_OK).equals(HostActivityResults.resultCode(value))) {
                        picker.trace.finish(TokAiLog.Outcome.CANCELLED, "selection_empty_or_cancelled", "uri_present=false");
                        picker.trace.wrapConsumer(selected).accept(null);
                        return null;
                    }
                    Object data = HostActivityResults.resultData(value);
                    if (!(data instanceof Intent) || ((Intent) data).getData() == null) {
                        picker.trace.finish(TokAiLog.Outcome.CANCELLED, "selection_empty_or_cancelled", "uri_present=false");
                        picker.trace.wrapConsumer(selected).accept(null);
                        return null;
                    }
                    Intent intent = (Intent) data;
                    Uri uri = intent.getData();
                    int granted = intent.getFlags();
                    if ((granted & GRANT_FLAGS) != GRANT_FLAGS
                            || (granted & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
                        throw new IllegalArgumentException("Persistent read/write permission is required");
                    }
                    requireWritableDirectory(activity, uri);
                    activity.getContentResolver().takePersistableUriPermission(uri, GRANT_FLAGS);
                    requirePersistedPermission(activity, uri);
                    picker.trace.finish(TokAiLog.Outcome.SUCCESS, "persistent_permission_verified", "read=true;write=true;persisted=true;writable_directory=true");
                    picker.trace.wrapConsumer(selected).accept(uri);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    picker.trace.error("permission_validation", failure);
                    picker.trace.finish(TokAiLog.Outcome.FAILED, "directory_permission_failed", "permission_or_directory_validation_failed");
                    if (failed != null) failed.accept(com.tokai.module.i18n.UiText.get(R.string.editor_ui_folder_invalid));
                }
                return null;
            });
            picker.launcher = HostActivityResults.register(registryType, contract, callback)
                    .invoke(registry, "tokai_download_directory_" + UUID.randomUUID(), open, result);
            PENDING.put(activity, picker);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(GRANT_FLAGS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            if (initial != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
            HostActivityResults.launch(picker.launcherType, picker.launcher, intent);
            return picker;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            picker.resultReceived = true;
            picker.trace.finish(TokAiLog.Outcome.FAILED, "picker_unavailable", "selection_not_started");
            picker.close();
            return null;
        }
    }

    private static void requirePersistedPermission(Activity activity, Uri uri) {
        for (UriPermission permission : activity.getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri()) && permission.isReadPermission()
                    && permission.isWritePermission()) return;
        }
        throw new IllegalStateException("Persistent read/write permission was not retained");
    }

    private static void requireWritableDirectory(Activity activity, Uri uri) {
        if (!"content".equalsIgnoreCase(uri.getScheme()) || !DocumentsContract.isTreeUri(uri)) {
            throw new IllegalArgumentException("Document tree is required");
        }
        Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
        String[] columns = {DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS};
        try (Cursor cursor = activity.getContentResolver().query(document, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()
                    || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))
                    || (cursor.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                throw new IllegalArgumentException("Writable document directory is required");
            }
        }
    }

    static boolean isPending(Activity activity) { return PENDING.containsKey(activity); }

    static String displayName(String configured) {
        if (configured == null || configured.isEmpty()) return "默认位置";
        try {
            Uri uri = Uri.parse(configured);
            return DocumentsContract.getTreeDocumentId(uri);
        } catch (RuntimeException ignored) { return "自定义文件夹"; }
    }

    static String localizedDisplayName(String configured) {
        if (configured == null || configured.isEmpty()) return com.tokai.module.i18n.UiText.get(R.string.editor_ui_default_location);
        try {
            Uri uri = Uri.parse(configured);
            return DocumentsContract.getTreeDocumentId(uri);
        } catch (RuntimeException ignored) { return com.tokai.module.i18n.UiText.get(R.string.editor_ui_custom_folder); }
    }

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
