package com.tokai.module.ui;

import com.tokai.module.R;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/** Imports a draft file; the main-thread callback receives null on cancellation or failure. */
public final class HostAudioPicker {
    private HostAudioPicker() { }
    private enum ImportIssue { DIRECTORY, READ, SIZE, EMPTY }
    private static final class ImportException extends IllegalStateException {
        final ImportIssue issue;
        ImportException(ImportIssue issue, String message) { super(message); this.issue = issue; }
    }


    static HostImagePicker launch(Activity activity, java.util.function.Consumer<String> onImported) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || onImported == null) return null;
        com.tokai.module.i18n.ModuleLocale.ensureInitialized(activity);
        Context context = activity.getApplicationContext();
        // The shared document picker keeps host settings alive while the system picker is open.
        return HostImagePicker.launch(activity, new String[]{"audio/*"}, source -> {
            if (source == null) {
                deliver(onImported, null);
                return;
            }
            TokAiLog.Operation selectedTrace = TokAiLog.current();
            Runnable importAction = () -> importFile(context, source, onImported);
            Thread copy = new Thread(selectedTrace == null ? importAction : selectedTrace.wrap(importAction), "TokAi-AudioImport");
            copy.setDaemon(true);
            copy.start();
        });
    }

    private static void importFile(Context context, Uri source, java.util.function.Consumer<String> onImported) {
        TokAiLog.Operation trace = TokAiLog.begin("media_picker", "audio_import");
        File target = null;
        try {
            File directory = new File(context.getFilesDir(), "tokai-voice-source");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new ImportException(ImportIssue.DIRECTORY, "无法创建语音目录");
            String mime = context.getContentResolver().getType(source);
            String extension = mime != null && mime.contains("wav") ? ".wav"
                    : mime != null && mime.contains("mpeg") ? ".mp3"
                    : mime != null && mime.contains("ogg") ? ".ogg" : ".m4a";
            target = new File(directory, UUID.randomUUID() + extension);
            try (InputStream input = context.getContentResolver().openInputStream(source);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new ImportException(ImportIssue.READ, "无法读取所选音频");
                byte[] buffer = new byte[8192];
                long count = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > 20L * 1024 * 1024) throw new ImportException(ImportIssue.SIZE, "语音文件不能超过 20 MB");
                    output.write(buffer, 0, read);
                }
                if (count == 0) throw new ImportException(ImportIssue.EMPTY, "语音文件为空");
            }
            String imported = target.getAbsolutePath();
            trace.finish(TokAiLog.Outcome.SUCCESS, "audio_file_closed_and_sized", "bytes=" + target.length() + ";audio_decoding=not_verified");
            deliver(onImported, imported);
            target = null;
        } catch (Exception failure) {
            trace.error("audio_copy", failure); trace.finish(TokAiLog.Outcome.FAILED, "audio_import_failed", "partial_file_cleanup=true");
            deliver(onImported, null);
            toast(context, audioFailure(failure));
        } finally {
            if (target != null) {
                try { java.nio.file.Files.deleteIfExists(target.toPath()); }
                catch (Exception ignored) { }
            }
        }
    }

    private static void deliver(java.util.function.Consumer<String> callback, String path) {
        if (callback != null) new Handler(Looper.getMainLooper()).post(() -> callback.accept(path));
    }

    private static String audioFailure(Exception failure) {
        if (failure instanceof ImportException) {
            switch (((ImportException) failure).issue) {
                case DIRECTORY: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_audio_directory_error);
                case READ: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_audio_selection_error);
                case SIZE: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_audio_size_error);
                case EMPTY: return com.tokai.module.i18n.UiText.get(R.string.editor_ui_audio_empty_error);
            }
        }
        return failure.getMessage() == null ? com.tokai.module.i18n.UiText.get(R.string.editor_ui_audio_read_failed) : failure.getMessage();
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
