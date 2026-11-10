package com.tokai.module.config;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import io.github.libxposed.service.XposedService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.Set;

/** Publishes local controls to the target process through libxposed remote preferences. */
public final class FrameworkConfigSync {
    public static final String PREFERENCES_GROUP = "douyin_configuration";

    public enum Stage { CONNECTED, DISCONNECTED, UNAVAILABLE, WAITING_CONNECTION, OPERATION_FAILED }
    public enum ErrorReason { NONE, NULL_SERVICE, CONNECTION_FAILED, REMOTE_WRITE_FAILED, RAW }
    public static final class Presentation {
        public final Stage stage;
        public final ErrorReason errorReason;
        public final String errorDetail;
        private Presentation(Stage stage, String error) {
            this.stage = stage;
            if (error == null || error.isEmpty()) { errorReason = ErrorReason.NONE; errorDetail = ""; }
            else if (error.equals("LSPosed 返回了空服务")) { errorReason = ErrorReason.NULL_SERVICE; errorDetail = ""; }
            else if (error.equals("无法连接 LSPosed 服务") || error.startsWith("无法连接 LSPosed 服务：")) {
                errorReason = ErrorReason.CONNECTION_FAILED;
                errorDetail = error.equals("无法连接 LSPosed 服务") ? "" : error.substring("无法连接 LSPosed 服务：".length());
            } else if (error.equals("远程配置写入失败") || error.startsWith("远程配置写入失败：")) {
                errorReason = ErrorReason.REMOTE_WRITE_FAILED;
                errorDetail = error.equals("远程配置写入失败") ? "" : error.substring("远程配置写入失败：".length());
            } else { errorReason = ErrorReason.RAW; errorDetail = error; }
        }
    }
    private volatile Presentation currentPresentation = new Presentation(Stage.DISCONNECTED, null);
    private final ThreadLocal<Presentation> dispatchPresentation = new ThreadLocal<>();
    public Presentation presentation() {
        Presentation dispatch = dispatchPresentation.get();
        return dispatch == null ? currentPresentation : dispatch;
    }

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private XposedService service;
    private volatile SettingsRepository settingsRepository;
    private String lastError;
    private long serviceGeneration;

    public FrameworkConfigSync(Listener listener) {
        this.listener = listener;
    }

    public synchronized void onServiceBound(XposedService service) {
        if (service == null) {
            onServiceError("LSPosed 返回了空服务");
            return;
        }
        this.service = service;
        serviceGeneration++;
        lastError = null;
        notifyStatus(Stage.CONNECTED, true, "LSPosed 服务已连接。", null);
        if (settingsRepository != null) {
            publish(settingsRepository.load());
        }
    }

    public synchronized void onServiceUnbound() {
        service = null;
        serviceGeneration++;
        notifyStatus(Stage.DISCONNECTED, false, "LSPosed 服务未连接。", null);
    }

    public synchronized void onServiceError(Throwable error) {
        service = null;
        String message = error == null || error.getMessage() == null
                ? "无法连接 LSPosed 服务"
                : "无法连接 LSPosed 服务：" + error.getMessage();
        onServiceError(message);
    }

    public synchronized void onServiceError(String message) {
        service = null;
        serviceGeneration++;
        lastError = message == null ? "无法连接 LSPosed 服务" : message;
        notifyStatus(Stage.UNAVAILABLE, false, "LSPosed 服务不可用。", lastError);
    }

    public synchronized boolean publish(FeatureSettings settings) {
        if (service == null) {
            notifyStatus(Stage.WAITING_CONNECTION, false, "本地设置已保存；等待 LSPosed 服务连接。", lastError);
            return false;
        }
        XposedService boundService = service;
        long generation = serviceGeneration;
        syncExecutor.execute(() -> {
            String error = null;
            try {
                SettingsRepository repository = settingsRepository;
                FeatureSettings latestSettings;
                Map<String, ?> localValues;
                if (repository == null) {
                    latestSettings = settings;
                    localValues = java.util.Collections.emptyMap();
                } else synchronized (repository) {
                    // Bulk import/rollback holds this same lock; never mix two different snapshots.
                    latestSettings = repository.load();
                    localValues = repository.snapshot();
                }
                SharedPreferences remote = boundService.getRemotePreferences(PREFERENCES_GROUP);
                SharedPreferences.Editor editor = remote.edit();
                writeSettings(editor, latestSettings, localValues);
                if (!editor.commit()) {
                    throw new IllegalStateException("远程配置写入失败");
                }
            } catch (RuntimeException exception) {
                error = exception.getMessage() == null
                        ? "远程配置写入失败"
                        : "远程配置写入失败：" + exception.getMessage();
            }

            final String result = error;
            synchronized (FrameworkConfigSync.this) {
                if (generation != serviceGeneration || boundService != service) return;
                lastError = result;
            }
            mainHandler.post(() -> {
                synchronized (FrameworkConfigSync.this) {
                    if (generation != serviceGeneration || boundService != service) return;
                }
                if (result == null) {
                    notifyStatus(Stage.CONNECTED, true, "LSPosed 服务已连接。", null);
                } else {
                    notifyStatus(Stage.OPERATION_FAILED, false, "LSPosed 服务操作失败。", result);
                }
            });
        });
        return true;
    }

    public synchronized void close() {
        service = null;
        serviceGeneration++;
        syncExecutor.shutdownNow();
    }

    public synchronized boolean isConnected() {
        return service != null;
    }

    synchronized void setSettingsRepository(SettingsRepository repository) {
        settingsRepository = repository;
    }

    private void writeSettings(SharedPreferences.Editor editor, FeatureSettings settings,
                               Map<String, ?> localValues) {
        editor.clear(); // This group is a full snapshot: removed import keys must disappear remotely too.
        settings.writeTo(editor);
        for (Map.Entry<String, ?> entry : localValues.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set<?>) {
                @SuppressWarnings("unchecked")
                Set<String> strings = (Set<String>) value;
                editor.putStringSet(entry.getKey(), strings);
            }
        }
        editor.putInt("config_schema_version", 1);
    }

    private void notifyStatus(Stage stage, boolean connected, String message, String error) {
        Presentation presentation = new Presentation(stage, error);
        currentPresentation = presentation;
        if (listener != null) {
            mainHandler.post(() -> {
                dispatchPresentation.set(presentation);
                try { listener.onStatusChanged(connected, message, error); }
                finally { dispatchPresentation.remove(); }
            });
        }
    }

    public interface Listener {
        void onStatusChanged(boolean connected, String message, String error);
    }
}
