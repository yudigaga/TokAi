package com.tokai.module.feature;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostAdaptation;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Own-profile text views only; shared User/RelationData and network requests remain untouched. */
final class TikTokProfileSpoofHook implements AutoCloseable {
    private final BehaviorHookGroup registration;
    private final Method common;
    private final Field root, component;
    private final Supplier<String> currentUid;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Monitor> monitors = new WeakHashMap<>();
    private volatile LocalOptions options;
    private boolean closed;

    TikTokProfileSpoofHook(BehaviorHookGroup registration, Method common, Field root, Field component,
                           Supplier<String> currentUid, LocalOptions options) {
        this.registration = registration;
        this.common = common;
        this.root = root;
        this.component = component;
        this.currentUid = currentUid;
        this.options = options;
    }

    static TikTokProfileSpoofHook install(XposedInterface framework, HostScanReport report, ClassLoader loader,
                                          LocalOptions options, Consumer<String> log) throws Exception {
        Method render = HostTargets.method(report, "tiktok_profile_relation_render", loader);
        Method common = HostTargets.method(report, "tiktok_profile_common", loader);
        if (!common.getDeclaringClass().isAssignableFrom(render.getDeclaringClass())
                || render.getParameterCount() != 0 || render.getReturnType() != void.class)
            throw new IllegalStateException("TikTok profile render relationship changed");
        Field root = HostTargets.field(common.getDeclaringClass(), "LLJZIJLIL");
        Field component = HostTargets.field(common.getDeclaringClass(), "LLJLLIL");
        if (!View.class.isAssignableFrom(root.getType()) || component.getType() != String.class)
            throw new IllegalStateException("TikTok profile view relationship changed");
        BehaviorHookGroup group = new BehaviorHookGroup("spoof_profile", log == null ? ignored -> { } : log);
        TikTokProfileSpoofHook hook = new TikTokProfileSpoofHook(group, common, root, component,
                () -> {
                    Object user;
                    try { user = HostAccountAccess.currentUser(loader); }
                    catch (ReflectiveOperationException | RuntimeException failure) { return null; }
                    Object uid = HostTargets.invoke(user, "getUid");
                    return uid instanceof String ? (String) uid : null;
                }, options);
        try {
            group.add(framework, render, chain -> {
                Object value = chain.proceed();
                hook.observe(chain.getThisObject());
                return value;
            });
            return hook;
        } catch (Throwable failure) {
            hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    boolean isActive() { return !closed && registration.isActive(); }
    void setOptions(LocalOptions value) {
        options = value;
        onMain(() -> { for (Monitor monitor : new ArrayList<>(monitors.values())) monitor.refresh(); });
    }
    void observe(Object owner) {
        onMain(() -> {
            if (closed || owner == null) return;
            Monitor monitor = monitors.get(owner);
            if (monitor == null) { monitor = new Monitor(owner); monitors.put(owner, monitor); }
            monitor.refresh();
        });
    }
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
    @Override public void close() {
        closed = true;
        registration.close();
        onMain(() -> { for (Monitor monitor : new ArrayList<>(monitors.values())) monitor.restore(); monitors.clear(); });
    }

    private final class Monitor implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<Object> owner;
        private WeakReference<TextView> number = new WeakReference<>(null);
        private CharSequence original, applied;
        private ViewTreeObserver observer;
        Monitor(Object value) { owner = new WeakReference<>(value); }
        @Override public boolean onPreDraw() { refresh(); return true; }
        // These identifiers belong to the verified host table, outside the module's R class.
        @android.annotation.SuppressLint("ResourceType")
        void refresh() {
            try {
                Object target = owner.get();
                if (closed || target == null || options == null || !options.enabled("spoof_profile")) { restore(); return; }
                Object info = HostTargets.invoke(common.invoke(target), "getUserProfileInfo");
                Object uid = HostTargets.invoke(info, "getUid");
                String own = currentUid.get();
                if (own == null || own.isEmpty() || !own.equals(uid)) { restore(); return; }
                String id = (String) component.get(target);
                Object model = HostTargets.read(HostTargets.read(target, "LLL"), "LLJJI");
                Object nativeId = HostTargets.read(model, "componentId");
                if (nativeId instanceof String && !((String) nativeId).isEmpty()) id = (String) nativeId;
                String key = "relation_info_like".equals(id) ? "profile_like_count"
                        : "relation_info_following".equals(id) ? "profile_following_count"
                        : "relation_info_follower".equals(id) ? "profile_follower_count" : null;
                View view = (View) root.get(target);
                if (key == null || view == null || !view.isAttachedToWindow()) { restore(); return; }
                if (!HostAdaptation.TARGET_PACKAGE.equals(view.getResources().getResourcePackageName(2131388294))
                        || !"t_v".equals(view.getResources().getResourceEntryName(2131388294))) { restore(); return; }
                View candidate = view.findViewById(2131388294);
                if (!(candidate instanceof TextView)) { restore(); return; }
                TextView count = number.get();
                if (count != candidate) { restore(); count = (TextView) candidate; number = new WeakReference<>(count); original = count.getText(); }
                if (applied != null && !android.text.TextUtils.equals(count.getText(), applied)) original = count.getText();
                applied = ProfileSpoofHook.displayCount(options, key);
                if (!android.text.TextUtils.equals(count.getText(), applied)) count.setText(applied);
                ViewTreeObserver next = view.getViewTreeObserver();
                if (next != observer && next.isAlive()) {
                    if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                    observer = next;
                    observer.addOnPreDrawListener(this);
                }
            } catch (ReflectiveOperationException | RuntimeException failure) { restore(); }
        }
        void restore() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            TextView count = number.get();
            if (count != null && android.text.TextUtils.equals(count.getText(), applied)) count.setText(original);
            number.clear(); original = null; applied = null;
        }
    }
}
