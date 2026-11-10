package com.tokai.module.ui;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.tokai.module.scan.HostAdaptation;
import com.tokai.module.scan.HostResourceProfile;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Host album selection only. No conversation, direct-send config, upload or media decoding is created. */
final class HostMediaPicker implements AutoCloseable {
    private static final String HOST_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String ACTIVITY = HOST_PACKAGE + ".im.business.mediaselectpage.choose.MediaChooseActivity";
    private static final String RESULT = HOST_PACKAGE + ".im.business.mediaselectpage.choose.model.MediaChooseResult";
    private static final String MODEL = HOST_PACKAGE + ".im.service.model.MediaModel";
    private static final String PARAMETERS = "extra_choose_parameters";
    private static final String CHOOSE_RESULT = "extra_choose_result";
    private static final int RESULT_CHOSEN = 2012;
    private static final int MAX_SELECTIONS = 4;
    private static final Map<Activity, HostMediaPicker> PENDING = new WeakHashMap<>();

    static final class Selection {
        final Uri uri;
        final boolean video;
        final String name;
        final boolean generatedName;
        final String mimeType;
        final long durationMillis;
        final long sizeBytes;
        final int width;
        final int height;

        private Selection(Uri uri, boolean video, String name, String mimeType,
                          long durationMillis, long sizeBytes, int width, int height) {
            this(uri, video, name, mimeType, durationMillis, sizeBytes, width, height, false);
        }

        private Selection(Uri uri, boolean video, String name, String mimeType,
                          long durationMillis, long sizeBytes, int width, int height, boolean generatedName) {
            this.uri = uri;
            this.video = video;
            this.name = name;
            this.generatedName = generatedName;
            this.mimeType = mimeType;
            this.durationMillis = durationMillis;
            this.sizeBytes = sizeBytes;
            this.width = width;
            this.height = height;
        }
    }

    private final Activity activity;
    private final HostAdaptation.Profile profile;
    private final boolean videoOnly;
    private final Consumer<List<Selection>> selected;
    private final ClassLoader loader;
    private Object launcher;
    private Class<?> launcherType;
    private Class<?> resultType;
    private Class<?> modelType;
    private boolean closed;
    private boolean resultReceived;
    private final TokAiLog.Operation trace = TokAiLog.begin("media_picker", "HostMediaPicker");

    private HostMediaPicker(Activity activity, boolean videoOnly, Consumer<List<Selection>> selected, HostAdaptation.Profile profile) {
        this.activity = activity;
        this.profile = profile;
        this.videoOnly = videoOnly;
        this.selected = selected;
        loader = activity.getClass().getClassLoader();
    }

    /** Null means unavailable; a null callback result means cancellation or an unusable result. */
    static HostMediaPicker launch(Activity activity, boolean videoOnly, Consumer<List<Selection>> selected) {
        if (activity == null || selected == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostAdaptation.Profile profile;
        try { profile = HostNativeChatViews.profileFor(activity); }
        catch (ReflectiveOperationException unavailable) {
            TokAiLog.legacy(5, "TokAi.HostAlbum", "Host album version is unavailable", unavailable);
            return null;
        }
        HostMediaPicker picker = new HostMediaPicker(activity, videoOnly, selected, profile);
        try {
            Intent intent = picker.albumIntent();
            Class<?> contract = picker.hostClass("androidx.activity.result.contract.ActivityResultContract");
            Class<?> callback = picker.hostClass("androidx.activity.result.ActivityResultCallback");
            Class<?> registryType = picker.hostClass("androidx.activity.result.ActivityResultRegistry");
            picker.launcherType = picker.hostClass("androidx.activity.result.ActivityResultLauncher");
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object start = picker.hostClass("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult")
                    .getConstructor().newInstance();
            Object resultCallback = Proxy.newProxyInstance(picker.loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                    return "TokAiHostAlbumSelection";
                }
                if (!HostActivityResults.callback(method) || picker.closed) return null;
                picker.resultReceived = true;
                picker.trace.stage("selection_callback", "callback_received=true");
                picker.close();
                List<Selection> result = null;
                boolean parseFailed = false;
                try {
                    if (args != null && args.length != 0 && args[0] != null) result = picker.readResult(args[0]);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
                    parseFailed = true; picker.trace.error("selection_parse", invalid);
                    picker.trace.finish(TokAiLog.Outcome.FAILED, "selection_protocol_error", "selection_unavailable=true");
                    TokAiLog.legacy(5, "TokAi.HostAlbum", "Host album returned an unavailable selection", invalid);
                }
                if (!parseFailed) picker.trace.finish(result == null ? TokAiLog.Outcome.CANCELLED : result.isEmpty() ? TokAiLog.Outcome.SKIPPED : TokAiLog.Outcome.SUCCESS,
                        result != null && result.isEmpty() ? "no_usable_selection" : "selection_result", "selections=" + (result == null ? 0 : result.size()) + ";file_content_verified=false");
                picker.trace.wrapConsumer(picker.selected).accept(result);
                return null;
            });
            picker.launcher = HostActivityResults.register(registryType, contract, callback)
                    .invoke(registry, "tokai_host_album_" + UUID.randomUUID(), start, resultCallback);
            HostMediaPicker previous = PENDING.put(activity, picker);
            if (previous != null) previous.close();
            HostActivityResults.launch(picker.launcherType, picker.launcher, intent);
            return picker;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            picker.resultReceived = true;
            picker.trace.finish(TokAiLog.Outcome.FAILED, "picker_unavailable", "selection_not_started");
            picker.close();
            TokAiLog.legacy(5, "TokAi.HostAlbum", "Host album is unavailable", unavailable);
            return null;
        }
    }

    static boolean isPending(Activity activity) { return PENDING.containsKey(activity); }

    private Intent albumIntent() throws ReflectiveOperationException {
        if (profile == HostAdaptation.PROFILE_TIKTOK) {
            // TikTok's creator album is coupled to publishing/session state. Use its
            // verified AndroidX result registry for a local selection-only document intent.
            return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(videoOnly ? "video/*" : "image/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        Class<?> nativeActivity = hostClass(ACTIVITY);
        if (!Activity.class.isAssignableFrom(nativeActivity)) throw new ReflectiveOperationException("Host album activity type");
        Class<?> parametersClass = hostClass("X.1UOy");
        if (!Serializable.class.isAssignableFrom(parametersClass)) throw new ReflectiveOperationException("Host album parameters type");
        Object parameters = parametersClass.getConstructor(int.class).newInstance(videoOnly ? 0x10 : 0x1);
        set(parameters, "b", boolean.class, true); // Native multiple-selection controls.
        set(parameters, "e", int.class, 3); // Ordinary album, not the emoji-upload scene.
        set(parameters, "f", int.class, MAX_SELECTIONS);
        set(parameters, "h", boolean.class, true); // Keep video selection enabled.
        set(parameters, "g", String.class, null);
        // A null direct-send config keeps the native completion path at setResult(2012).
        // In particular no SessionInfo/user/conversation model is manufactured here.
        parametersClass.getField("l").set(parameters, null);
        set(parameters, "m", boolean.class, false);
        set(parameters, "n", long.class, System.currentTimeMillis());
        resultType = hostClass(RESULT);
        resultType.getMethod("getSelectedList");
        modelType = hostClass(MODEL);
        modelType.getMethod("getFilePath");
        modelType.getMethod("LJIILJJIL");
        for (String field : new String[]{"type", "edited", "mimeType", "duration", "fileSize", "width", "height"}) {
            modelType.getField(field);
        }
        return new Intent(activity, nativeActivity)
                .putExtra(PARAMETERS, (Serializable) parameters)
                .putExtra("picure_choose_origin", 1);
    }

    private List<Selection> readResult(Object activityResult) throws ReflectiveOperationException {
        Class<?> activityResultType = hostClass("androidx.activity.result.ActivityResult");
        Object code = HostActivityResults.resultCode(activityResult);
        if (profile == HostAdaptation.PROFILE_TIKTOK) {
            if (!Integer.valueOf(Activity.RESULT_OK).equals(code)) return null;
            Intent data = HostActivityResults.resultData(activityResult);
            if (data == null) return null;
            LinkedHashSet<Uri> uris = new LinkedHashSet<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) uris.add(uri);
                if (uris.size() == MAX_SELECTIONS) break;
            }
            if (uris.isEmpty() && data.getData() != null
                    && "content".equalsIgnoreCase(data.getData().getScheme())) uris.add(data.getData());
            List<Selection> selected = new ArrayList<>();
            for (Uri uri : uris) selected.add(new Selection(uri, videoOnly, videoOnly ? "视频" : "图片",
                    videoOnly ? "video/*" : "image/*", 0, 0, 0, 0, true));
            return Collections.unmodifiableList(selected);
        }
        // Native album results use 2012/2011, rather than Activity.RESULT_OK/-1.
        if (!Integer.valueOf(RESULT_CHOSEN).equals(code)) return null;
        Intent data = HostActivityResults.resultData(activityResult);
        if (data == null) return null;
        data.setExtrasClassLoader(loader);
        Serializable result = data.getSerializableExtra(CHOOSE_RESULT);
        if (!resultType.isInstance(result)) throw new ReflectiveOperationException("Host album result type");
        Object selectedList = resultType.getMethod("getSelectedList").invoke(result);
        if (!(selectedList instanceof List<?>)) throw new ReflectiveOperationException("Host album selected list type");
        List<Selection> selections = new ArrayList<>();
        LinkedHashSet<Uri> unique = new LinkedHashSet<>();
        for (Object model : (List<?>) selectedList) {
            if (!modelType.isInstance(model)) continue;
            int type = modelType.getField("type").getInt(model);
            boolean video = type == 1;
            if (video != videoOnly || (!video && type != 0 && type != 2)) continue;
            String filePath = (String) modelType.getMethod("getFilePath").invoke(model);
            String original = (String) modelType.getMethod("LJIILJJIL").invoke(model);
            boolean edited = modelType.getField("edited").getBoolean(model);
            Uri file = localUri(filePath);
            Uri source = localUri(original);
            // Under scoped storage the host may assign filePath a future cache filename
            // while relativePath still contains the selected source content URI.
            Uri uri = edited && file != null ? file : source != null ? source : file;
            if (uri == null || !unique.add(uri)) continue;
            String name = fileName(original);
            if (TextUtils.isEmpty(name)) name = fileName(filePath);
            boolean generatedName = TextUtils.isEmpty(name);
            if (generatedName) name = video ? "视频" : "图片";
            selections.add(new Selection(uri, video, name,
                    (String) modelType.getField("mimeType").get(model),
                    modelType.getField("duration").getLong(model),
                    modelType.getField("fileSize").getLong(model),
                    modelType.getField("width").getInt(model), modelType.getField("height").getInt(model), generatedName));
            if (selections.size() == MAX_SELECTIONS) break;
        }
        return Collections.unmodifiableList(selections);
    }

    private static Uri localUri(String value) {
        if (TextUtils.isEmpty(value)) return null;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme) && !TextUtils.isEmpty(uri.getAuthority())) return uri;
        if ("file".equalsIgnoreCase(scheme) && TextUtils.isEmpty(uri.getAuthority())
                && uri.getPath() != null && uri.getPath().startsWith("/")) return fileUri(uri.getPath());
        if (scheme == null && value.startsWith("/")) return fileUri(value);
        return null;
    }

    private static Uri fileUri(String androidAbsolutePath) {
        // Resolve Android path syntax without touching storage or applying the desktop
        // JVM's Windows drive rules during the same host-side contract tests.
        return new Uri.Builder().scheme("file").authority("").path(androidAbsolutePath).build();
    }

    private static String fileName(String value) {
        Uri uri = localUri(value);
        return uri != null && "file".equalsIgnoreCase(uri.getScheme()) ? uri.getLastPathSegment() : null;
    }

    private static void set(Object target, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getField(name);
        if (field.getType() != type) throw new ReflectiveOperationException("Host album parameter " + name);
        field.set(target, value);
    }

    private Class<?> hostClass(String name) throws ClassNotFoundException {
        return name.startsWith("androidx.activity.result.") ? HostActivityResults.type(name, loader)
                : Class.forName(HostResourceProfile.className(profile, name), false, loader);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (!resultReceived) trace.finish(TokAiLog.Outcome.CANCELLED, "picker_closed", "no_selection_consumed");
        if (PENDING.get(activity) == this) PENDING.remove(activity);
        if (launcher != null) {
            try { HostActivityResults.unregister(launcherType, launcher); }
            catch (ReflectiveOperationException | RuntimeException unavailable) {
                TokAiLog.legacy(5, "TokAi.HostAlbum", "Host album callback could not be unregistered", unavailable);
            }
        }
    }
}
