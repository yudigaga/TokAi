package com.tokai.module.feature;

import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.tokai.module.scan.HostScanReport;
import com.tokai.module.diagnostics.TokAiLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Changes only the native video result; feed controls keep their original scale and positions. */
final class TikTokVideoCoverAdapter {
    static final String[] RULES = {
            "adv_video_cover_apply", "adv_video_cover_aweme", "adv_video_cover_cell",
            "adv_video_cover_current", "adv_video_cover_init", "adv_video_cover_unbind",
            "adv_video_cover_destroy", "adv_video_cover_first_frame", "adv_video_cover_result",
            "adv_video_cover_source_width", "adv_video_cover_source_height",
            "adv_video_cover_source_fake_landscape", "adv_video_cell_render_view",
            "aweme_video", "aweme_is_image", "aweme_is_live"
    };
    private final Method update, aweme, ability, current, getVideo, sourceWidth, sourceHeight, fakeLandscape;
    private final Method fakeLeft, fakeTop, fakeRight, fakeBottom;
    private final Method photo, live, videoView, resultWidth, resultHeight;
    private final Field viewport;
    private final Constructor<?> resultConstructor;
    private final BooleanSupplier enabled;
    private final Consumer<Throwable> failure;
    private final List<Binding> bindings = new ArrayList<>();
    private final ThreadLocal<Boolean> replaying = new ThreadLocal<>();
    private boolean closed;

    TikTokVideoCoverAdapter(HostScanReport report, ClassLoader loader, BooleanSupplier enabled,
                           Consumer<Throwable> failure) throws Exception {
        this.enabled = enabled;
        this.failure = failure;
        update = HostTargets.method(report, "adv_video_cover_apply", loader);
        Class<?> owner = update.getDeclaringClass();
        Class<?> result = HostTargets.type(report, "adv_video_cover_result", loader);
        requireMethod(update, owner, void.class, result, String.class);
        aweme = HostTargets.method(report, "adv_video_cover_aweme", loader);
        ability = HostTargets.method(report, "adv_video_cover_cell", loader);
        current = HostTargets.method(report, "adv_video_cover_current", loader);
        requireMethod(aweme, owner, aweme.getReturnType());
        requireMethod(ability, owner, ability.getReturnType());
        requireMethod(current, owner, result);
        for (String id : new String[]{"adv_video_cover_init", "adv_video_cover_unbind",
                "adv_video_cover_destroy", "adv_video_cover_first_frame"}) {
            Method method = HostTargets.method(report, id, loader);
            Class<?>[] params = id.endsWith("init") ? new Class<?>[]{View.class}
                    : id.endsWith("first_frame") ? new Class<?>[]{String.class} : new Class<?>[0];
            requireMethod(method, owner, void.class, params);
        }
        viewport = viewField(owner, "LLJLLIL");
        viewField(owner, "LLJZ");
        viewField(owner, "LLJLL");
        getVideo = HostTargets.method(report, "aweme_video", loader);
        photo = HostTargets.method(report, "aweme_is_image", loader);
        live = HostTargets.method(report, "aweme_is_live", loader);
        requireMethod(getVideo, aweme.getReturnType(), getVideo.getReturnType());
        requireMethod(photo, aweme.getReturnType(), photo.getReturnType());
        requireMethod(live, aweme.getReturnType(), live.getReturnType());
        if (getVideo.getReturnType().isPrimitive() || photo.getReturnType().isPrimitive()
                || live.getReturnType().isPrimitive()) throw new NoSuchMethodException("Native video model changed");
        sourceWidth = HostTargets.method(report, "adv_video_cover_source_width", loader);
        sourceHeight = HostTargets.method(report, "adv_video_cover_source_height", loader);
        requireMethod(sourceWidth, getVideo.getReturnType(), int.class);
        requireMethod(sourceHeight, getVideo.getReturnType(), int.class);
        fakeLandscape = HostTargets.method(report, "adv_video_cover_source_fake_landscape", loader);
        requireMethod(fakeLandscape, getVideo.getReturnType(), fakeLandscape.getReturnType());
        if (fakeLandscape.getReturnType().isPrimitive()) throw new NoSuchMethodException("Native visible-video bounds changed");
        fakeLeft = fakeBound(fakeLandscape.getReturnType(), "getLeft");
        fakeTop = fakeBound(fakeLandscape.getReturnType(), "getTop");
        fakeRight = fakeBound(fakeLandscape.getReturnType(), "getRight");
        fakeBottom = fakeBound(fakeLandscape.getReturnType(), "getBottom");
        videoView = ability.getReturnType().getMethod("getVideoView");
        if (videoView.getReturnType() != View.class || Modifier.isStatic(videoView.getModifiers())) {
            throw new NoSuchMethodException("Native renderer accessor changed");
        }
        resultWidth = result.getMethod("getWidth");
        resultHeight = result.getMethod("getHeight");
        requireMethod(resultWidth, result, int.class);
        requireMethod(resultHeight, result, int.class);
        requireMethod(result.getMethod("getTranslateX"), result, Float.class);
        requireMethod(result.getMethod("getTranslateY"), result, Float.class);
        Class<?> operator = result.getMethod("getResultOperator").getReturnType();
        if (operator.isPrimitive()) throw new NoSuchMethodException("Native result operator changed");
        resultConstructor = result.getConstructor(int.class, int.class, Float.class, Float.class, operator);
        resultConstructor.setAccessible(true);
    }

    /** Called only for a real host aq argument; internal replays never replace the saved result. */
    Object prepare(Object owner, Object nativeResult) throws ReflectiveOperationException {
        if (closed || Boolean.TRUE.equals(replaying.get()) || nativeResult == null) return nativeResult;
        Binding binding = remember(owner, nativeResult);
        if (binding == null) return nativeResult;
        Object cover = enabled.getAsBoolean() ? cover(binding, nativeResult) : null;
        binding.applied = cover != null;
        return cover == null ? nativeResult : cover;
    }

    /** Initialization and the first frame also cover a surface that was created or reparented late. */
    void ready(Object owner) throws ReflectiveOperationException {
        if (closed || Boolean.TRUE.equals(replaying.get())) return;
        Binding binding = find(owner);
        if (binding == null || !sameBinding(binding)) {
            Object result = current.invoke(owner);
            binding = result == null ? null : remember(owner, result);
        }
        if (binding != null) binding.schedule();
    }

    void reflowAll() throws ReflectiveOperationException {
        if (closed) return;
        for (Binding binding : new ArrayList<>(bindings)) reflow(binding);
        bindings.removeIf(binding -> binding.owner.get() == null || binding.viewport.get() == null);
    }

    /** Restore before the host clears its native result in unBind. Never touch a surface owned by another cell. */
    void forget(Object owner) throws ReflectiveOperationException {
        Binding binding = find(owner);
        if (binding == null) return;
        bindings.remove(binding);
        binding.removeListeners();
        restore(binding);
    }

    void close() throws ReflectiveOperationException {
        if (closed) return;
        closed = true;
        ReflectiveOperationException first = null;
        for (Binding binding : new ArrayList<>(bindings)) {
            binding.removeListeners();
            try { restore(binding); }
            catch (ReflectiveOperationException problem) { if (first == null) first = problem; }
        }
        bindings.clear();
        if (first != null) throw first;
    }

    private Binding remember(Object owner, Object result) throws ReflectiveOperationException {
        Object model = aweme.invoke(owner);
        View area = (View) viewport.get(owner);
        Binding previous = find(owner);
        if (previous != null && (previous.aweme.get() != model || previous.viewport.get() != area)) {
            bindings.remove(previous);
            previous.removeListeners();
            previous = null;
        }
        if (model == null || area == null || photo.invoke(model) != null || live.invoke(model) != null) return null;
        if (previous == null) {
            previous = new Binding(owner, model, area);
            bindings.add(previous);
            area.addOnLayoutChangeListener(previous);
            area.addOnAttachStateChangeListener(previous);
        }
        previous.nativeResult = new WeakReference<>(result);
        return previous;
    }

    private Binding find(Object owner) {
        for (Binding binding : bindings) if (binding.owner.get() == owner) return binding;
        return null;
    }

    private boolean sameBinding(Binding binding) throws ReflectiveOperationException {
        Object owner = binding.owner.get();
        return owner != null && binding.aweme.get() != null && aweme.invoke(owner) == binding.aweme.get()
                && viewport.get(owner) == binding.viewport.get();
    }

    private View ownedVideo(Binding binding) throws ReflectiveOperationException {
        if (!sameBinding(binding)) return null;
        Object cell = ability.invoke(binding.owner.get());
        View video = cell == null ? null : (View) videoView.invoke(cell);
        View area = binding.viewport.get();
        // TikTok can share a surface. Its previous cell must not resize it after reparenting.
        for (ViewParent parent = video == null ? null : video.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == area) return video;
        }
        return null;
    }

    private Object cover(Binding binding, Object nativeResult) throws ReflectiveOperationException {
        View area = binding.viewport.get();
        View video = ownedVideo(binding);
        if (area == null || video == null || !area.isAttachedToWindow()
                || area.getWidth() <= 0 || area.getHeight() <= 0
                || (int) resultWidth.invoke(nativeResult) <= 0 || (int) resultHeight.invoke(nativeResult) <= 0) return null;
        Object source = getVideo.invoke(binding.aweme.get());
        if (source == null) return null;
        int width = (int) sourceWidth.invoke(source), height = (int) sourceHeight.invoke(source);
        if (width <= 0 || height <= 0) return null;
        Object visibleBounds = fakeLandscape.invoke(source);
        double left = 0d, top = 0d, right = 1d, bottom = 1d;
        double baseWidth = width, baseHeight = height;
        if (visibleBounds != null) {
            left = ((Float) fakeLeft.invoke(visibleBounds)).doubleValue();
            top = ((Float) fakeTop.invoke(visibleBounds)).doubleValue();
            right = ((Float) fakeRight.invoke(visibleBounds)).doubleValue();
            bottom = ((Float) fakeBottom.invoke(visibleBounds)).doubleValue();
            if (!validBounds(left, top, right, bottom)) return null;
            // Fake-landscape bounds describe the visible crop within the host-sized result.
            baseWidth = (int) resultWidth.invoke(nativeResult);
            baseHeight = (int) resultHeight.invoke(nativeResult);
        }
        double visibleWidth = baseWidth * (right - left);
        double visibleHeight = baseHeight * (bottom - top);
        if (visibleWidth <= 0d || visibleHeight <= 0d) return null;
        double scale = Math.max((double) area.getWidth() / visibleWidth, (double) area.getHeight() / visibleHeight);
        double scaledWidth = Math.ceil(baseWidth * scale), scaledHeight = Math.ceil(baseHeight * scale);
        if (scaledWidth > Integer.MAX_VALUE || scaledHeight > Integer.MAX_VALUE) return null;
        // The renderer is centered. Offset that center by the crop rectangle's center so the
        // visible bounds, rather than any encoded border, cover the viewport symmetrically.
        float translateX = (float) ((1d - left - right) * scaledWidth / 2d);
        float translateY = (float) ((1d - top - bottom) * scaledHeight / 2d);
        TokAiLog.event("hide_video_black_edges", "video_adaptation", "cover_computed",
                "viewport=" + area.getWidth() + "x" + area.getHeight()
                        + ";source=" + width + "x" + height
                        + ";native=" + resultWidth.invoke(nativeResult) + "x"
                        + resultHeight.invoke(nativeResult)
                        + ";output=" + (int) scaledWidth + "x" + (int) scaledHeight
                        + ";translate=" + translateX + "," + translateY
                        + ";visible=" + left + "," + top + "," + right + "," + bottom
                         + ";viewport_screen=" + screenBounds(area)
                         + ";renderer_screen=" + screenBounds(video)
                        + ";result_scope=video_adaption_args");
        return resultConstructor.newInstance((int) scaledWidth, (int) scaledHeight,
                translateX, translateY, null);
    }

    private void reflow(Binding binding) throws ReflectiveOperationException {
        if (!sameBinding(binding)) {
            bindings.remove(binding);
            binding.removeListeners();
            return;
        }
        if (!enabled.getAsBoolean()) { restore(binding); return; }
        Object result = nativeResult(binding);
        if (result == null) return;
        Object replacement = cover(binding, result);
        if (replacement == null) return;
        binding.applied = true;
        invoke(binding, replacement);
    }

    private Object nativeResult(Binding binding) throws ReflectiveOperationException {
        Object result = binding.nativeResult.get();
        return result == null ? current.invoke(binding.owner.get()) : result;
    }

    private void restore(Binding binding) throws ReflectiveOperationException {
        if (!binding.applied) return;
        binding.applied = false;
        if (ownedVideo(binding) == null) return;
        Object original = nativeResult(binding);
        if (original != null) invoke(binding, original);
    }

    private void invoke(Binding binding, Object result) throws ReflectiveOperationException {
        Boolean previous = replaying.get();
        replaying.set(true);
        try { update.invoke(binding.owner.get(), result, "TokAi video viewport changed"); }
        finally { if (previous == null) replaying.remove(); else replaying.set(previous); }
    }

    private static void requireMethod(Method method, Class<?> owner, Class<?> result, Class<?>... params)
            throws NoSuchMethodException {
        if (method.getDeclaringClass() != owner || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != result || !java.util.Arrays.equals(method.getParameterTypes(), params)) {
            throw new NoSuchMethodException("Native video adaptation signature changed: " + method.getName());
        }
        method.setAccessible(true);
    }

    private static Field viewField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = HostTargets.field(owner, name);
        if (Modifier.isStatic(field.getModifiers()) || !FrameLayout.class.isAssignableFrom(field.getType())) {
            throw new NoSuchFieldException("Native video viewport changed: " + name);
        }
        return field;
    }

    private static Method fakeBound(Class<?> owner, String name) throws ReflectiveOperationException {
        Method method = owner.getMethod(name);
        requireMethod(method, owner, float.class);
        return method;
    }

    private static boolean validBounds(double left, double top, double right, double bottom) {
        return Double.isFinite(left) && Double.isFinite(top) && Double.isFinite(right) && Double.isFinite(bottom)
                && left >= 0d && top >= 0d && right <= 1d && bottom <= 1d
                && right > left && bottom > top;
    }

    private static String screenBounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[0] + "," + location[1] + "," + view.getWidth() + "," + view.getHeight();
    }

    private final class Binding implements View.OnLayoutChangeListener, View.OnAttachStateChangeListener, Runnable {
        final WeakReference<Object> owner, aweme;
        final WeakReference<View> viewport;
        WeakReference<Object> nativeResult = new WeakReference<>(null);
        boolean applied, scheduled;

        Binding(Object owner, Object aweme, View viewport) {
            this.owner = new WeakReference<>(owner);
            this.aweme = new WeakReference<>(aweme);
            this.viewport = new WeakReference<>(viewport);
        }
        void schedule() {
            View area = viewport.get();
            if (closed || scheduled || area == null || !area.isAttachedToWindow()) return;
            scheduled = true;
            area.post(this);
        }
        void removeListeners() {
            View area = viewport.get();
            if (area != null) {
                area.removeCallbacks(this);
                area.removeOnLayoutChangeListener(this);
                area.removeOnAttachStateChangeListener(this);
            }
            scheduled = false;
        }
        @Override public void run() {
            scheduled = false;
            if (closed || !bindings.contains(this)) return;
            try { reflow(this); }
            catch (ReflectiveOperationException | RuntimeException problem) { failure.accept(problem); }
        }
        @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                             int oldL, int oldT, int oldR, int oldB) {
            if (r - l != oldR - oldL || b - t != oldB - oldT) schedule();
        }
        @Override public void onViewAttachedToWindow(View view) { schedule(); }
        @Override public void onViewDetachedFromWindow(View view) {
            view.removeCallbacks(this);
            scheduled = false;
        }
    }
}
