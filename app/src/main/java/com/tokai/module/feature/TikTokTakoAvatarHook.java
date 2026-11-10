package com.tokai.module.feature;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import com.tokai.module.config.AiModelSettings;
import com.tokai.module.scan.HostAdaptation;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import com.tokai.module.ui.AiContactAvatar;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reuses the native assistant header ImageView and restores its latest native drawable. */
public final class TikTokTakoAvatarHook implements AutoCloseable {
    private static final String[][] HEADERS = {
            {"tako_avatar_header_created", "com.ss.android.ugc.aweme.tako.botpage.toptitle.TopTitleAssem"},
            {"tako_avatar_sheet_header_created", "com.ss.android.ugc.aweme.tako.otherpage.sheet.SheetTopTitleAssem"},
            {"tako_avatar_seven_header_created", "com.ss.android.ugc.aweme.tako.otherpage.sheet.SevenSheetTopTitleAssem"}
    };
    private final Supplier<AiModelSettings> settings;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, HeaderMonitor> headers = new WeakHashMap<>();
    private volatile boolean closed;
    private String avatarData = "";
    private Bitmap avatar;

    private TikTokTakoAvatarHook(Supplier<AiModelSettings> settings, Consumer<String> log) {
        this.settings = settings;
        this.log = log == null ? ignored -> { } : log;
    }

    public static TikTokTakoAvatarHook install(XposedInterface framework, HostScanReport report,
            ClassLoader loader, Supplier<AiModelSettings> settings, Consumer<String> log) throws Exception {
        if (framework == null || report == null || loader == null || settings == null) {
            throw new IllegalArgumentException("Native framework, scan, loader and model settings are required");
        }
        TikTokTakoAvatarHook hook = new TikTokTakoAvatarHook(settings, log);
        try {
            for (String[] spec : HEADERS) {
                RuleScanResult result = report.getResults().get(spec[0]);
                if (result == null) continue;
                if (result.getStatus() != RuleScanResult.Status.UNIQUE) {
                    throw new IllegalStateException("Native Tako header is not unique: " + spec[0]);
                }
                HostCandidate candidate = result.getCandidates().get(0);
                if (!("L" + spec[1].replace('.', '/') + ";").equals(candidate.getClassDescriptor())
                        || !"onViewCreated".equals(candidate.getMethodName())
                        || !"(Landroid/view/View;)V".equals(candidate.getMethodDescriptor())) {
                    throw new IllegalStateException("Native Tako header callback changed: " + spec[0]);
                }
                Class<?> owner = Class.forName(spec[1], false, loader);
                Method created = owner.getDeclaredMethod("onViewCreated", View.class);
                Method isTako = owner.getDeclaredMethod("isTako");
                if (created.getReturnType() != void.class || Modifier.isStatic(created.getModifiers())
                        || isTako.getReturnType() != boolean.class || Modifier.isStatic(isTako.getModifiers())) {
                    throw new IllegalStateException("Native Tako header identity changed: " + spec[0]);
                }
                isTako.setAccessible(true);
                XposedInterface.HookHandle handle = framework.hook(created).setId("TokAi.Tako.Avatar." + spec[0])
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object nativeResult = chain.proceed();
                            if (!hook.closed) {
                                Object instance = chain.getThisObject();
                                Object root = chain.getArg(0);
                                if (instance != null && instance.getClass() == owner && root instanceof View) {
                                    hook.runOnMain(() -> hook.bind(instance, isTako, (View) root));
                                }
                            }
                            return nativeResult;
                        });
                if (handle == null) throw new IllegalStateException("Native Tako avatar hook handle is empty");
                hook.handles.add(handle);
            }
            if (hook.handles.isEmpty()) throw new IllegalStateException("No scanned native Tako header is available");
            hook.refreshSettings();
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void refreshSettings() {
        if (closed) return;
        runOnMain(() -> {
            if (closed) return;
            String next = "";
            try {
                AiModelSettings models = settings.get();
                if (models != null) next = models.selected().messageContactAvatar;
            } catch (RuntimeException unavailable) { /* Missing or invalid global models retain native branding. */ }
            if (!avatarData.equals(next)) {
                avatarData = next;
                avatar = AiContactAvatar.load(next);
            }
            for (HeaderMonitor header : new ArrayList<>(headers.values())) header.refresh();
        });
    }

    private void bind(Object owner, Method isTako, View root) {
        if (closed) return;
        for (HeaderMonitor previous : new ArrayList<>(headers.values())) {
            if (previous.owner.get() == owner && previous.root.get() != root) previous.close();
        }
        HeaderMonitor header = headers.get(root);
        if (header == null || header.owner.get() != owner) {
            if (header != null) header.close();
            header = new HeaderMonitor(owner, isTako, root);
            headers.put(root, header);
            root.addOnAttachStateChangeListener(header);
        }
        header.refresh();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); }
            catch (Throwable unavailable) { safeLog("Native Tako avatar unhook failed"); }
        }
        handles.clear();
        runOnMain(() -> {
            for (HeaderMonitor header : new ArrayList<>(headers.values())) header.close();
            headers.clear();
            avatar = null;
            avatarData = "";
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }

    private final class HeaderMonitor implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final WeakReference<Object> owner;
        final WeakReference<View> root;
        final Method isTako;
        ViewTreeObserver observer;
        Original original;
        boolean stopped;
        HeaderMonitor(Object owner, Method isTako, View root) {
            this.owner = new WeakReference<>(owner);
            this.root = new WeakReference<>(root);
            this.isTako = isTako;
        }
        void refresh() {
            if (closed || stopped) return;
            View view = root.get();
            Object instance = owner.get();
            if (view == null || instance == null) { close(); return; }
            if (!view.isAttachedToWindow()) { suspend(); return; }
            ViewTreeObserver next = view.getViewTreeObserver();
            if (observer != next && next.isAlive()) {
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                observer = next;
                observer.addOnPreDrawListener(this);
            }
            try {
                ImageView target = Boolean.TRUE.equals(isTako.invoke(instance)) ? avatarTarget(view) : null;
                if (target == null || avatar == null) { restore(); return; }
                if (original == null || original.view.get() != target) {
                    restore();
                    original = new Original(target);
                }
                original.apply(avatar);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                restore();
            }
        }
        private void restore() {
            if (original != null) original.restore();
            original = null;
        }
        private void suspend() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            restore();
        }
        void close() {
            if (stopped) return;
            stopped = true;
            suspend();
            View view = root.get();
            if (view != null) {
                view.removeOnAttachStateChangeListener(this);
                if (headers.get(view) == this) headers.remove(view);
            }
        }
        @Override public boolean onPreDraw() { refresh(); return true; }
        @Override public void onViewAttachedToWindow(View view) { refresh(); }
        @Override public void onViewDetachedFromWindow(View view) { suspend(); }
    }

    private static final class Original {
        final WeakReference<ImageView> view;
        Drawable nativeDrawable;
        Bitmap ownedBitmap;
        Original(ImageView view) { this.view = new WeakReference<>(view); nativeDrawable = view.getDrawable(); }
        boolean owns(Drawable drawable) {
            return ownedBitmap != null && drawable instanceof BitmapDrawable
                    && ((BitmapDrawable) drawable).getBitmap() == ownedBitmap;
        }
        void apply(Bitmap bitmap) {
            ImageView image = view.get();
            if (image == null) return;
            Drawable current = image.getDrawable();
            if (!owns(current)) nativeDrawable = current;
            if (!(current instanceof BitmapDrawable) || ((BitmapDrawable) current).getBitmap() != bitmap) {
                image.setImageBitmap(bitmap);
            }
            ownedBitmap = bitmap;
        }
        void restore() {
            ImageView image = view.get();
            if (image != null && owns(image.getDrawable())) image.setImageDrawable(nativeDrawable);
            ownedBitmap = null;
        }
    }

    private static ImageView avatarTarget(View root) {
        if (!(root instanceof ViewGroup) || !hasClass(root, "androidx.constraintlayout.widget.ConstraintLayout")
                || !resource(root, 2131395452, "yib")) return null;
        View brand = child((ViewGroup) root, 2131381509, "obg");
        if (!(brand instanceof ViewGroup) || !hasClass(brand, "androidx.appcompat.widget.LinearLayoutCompat")) return null;
        View image = child((ViewGroup) brand, 2131395850, "yt3");
        View title = child((ViewGroup) brand, 2131395849, "yt2");
        return image instanceof ImageView && hasClass(image, "com.bytedance.lighten.loader.SmartImageView")
                && title instanceof TextView ? (ImageView) image : null;
    }

    private static View child(ViewGroup parent, int id, String name) {
        if (parent.getChildCount() > 32) return null;
        View found = null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View next = parent.getChildAt(i);
            if (next.getId() != id) continue;
            if (found != null || !resource(next, id, name)) return null;
            found = next;
        }
        return found;
    }

    private static boolean resource(View view, int id, String name) {
        if (view.getId() != id) return false;
        try {
            return HostAdaptation.TARGET_PACKAGE.equals(view.getResources().getResourcePackageName(id))
                    && name.equals(view.getResources().getResourceEntryName(id));
        } catch (android.content.res.Resources.NotFoundException unavailable) { return false; }
    }

    private static boolean hasClass(View view, String name) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }
}
