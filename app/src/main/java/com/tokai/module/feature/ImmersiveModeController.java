package com.tokai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.tokai.module.config.ExtendedSettings;
import com.tokai.module.diagnostics.TokAiLog;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reversible status-bar hiding for the target app's resumed Activities. */
public final class ImmersiveModeController implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private static final int HIDE_STATUS_FLAGS = 1284;
    private static final int HIDE_NAVIGATION_FLAGS = 2 | 512 | 4096;
    private static final int FULLSCREEN_FLAG = 1024;
    private static final int LAYOUT_IN_SCREEN_FLAG =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    private static final int MANAGED_WINDOW_FLAGS = FULLSCREEN_FLAG | LAYOUT_IN_SCREEN_FLAG;

    private final Application application;
    private final Consumer<String> log;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Activity, WindowState> windows = new WeakHashMap<>();
    private volatile boolean hideStatusBar;
    private volatile boolean hideNavigationBar;
    private volatile boolean closed;

    private ImmersiveModeController(Application application, ExtendedSettings settings,
                                    Consumer<String> log) {
        this.application = application;
        this.hideStatusBar = settings != null && settings.removeBlackEdges;
        this.hideNavigationBar = settings != null && settings.hideNavigationBar;
        this.log = log;
    }

    public static ImmersiveModeController install(Context context, ExtendedSettings settings,
                                                  Consumer<String> log) {
        return install(context, settings, log, () -> context instanceof Activity ? (Activity) context : null);
    }

    public static ImmersiveModeController install(Context context, ExtendedSettings settings,
                                                  Consumer<String> log, Supplier<Activity> resumedActivity) {
        if (context == null) throw new IllegalArgumentException("Target context is required");
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) throw new IllegalStateException("Target Application unavailable");
        Application application = (Application) appContext;
        ImmersiveModeController controller = new ImmersiveModeController(application, settings,
                log == null ? ignored -> { } : log);
        application.registerActivityLifecycleCallbacks(controller);
        controller.runOnMain(() -> {
            if (!controller.closed && resumedActivity != null) {
                controller.onActivityResumed(resumedActivity.get());
            }
        });
        return controller;
    }

    public void setSettings(ExtendedSettings settings) {
        if (closed) return;
        hideStatusBar = settings != null && settings.removeBlackEdges;
        hideNavigationBar = settings != null && settings.hideNavigationBar;
        runOnMain(this::refreshWindows);
    }

    private boolean hidesStatusBar() {
        return hideStatusBar;
    }

    private boolean changesWindow() {
        return hidesStatusBar() || hideNavigationBar;
    }

    private void refreshWindows() {
        if (closed) return;
        synchronized (windows) {
            for (Map.Entry<Activity, WindowState> entry : windows.entrySet()) {
                Activity activity = entry.getKey();
                if (activity == null) continue;
                if (changesWindow()) apply(activity, entry.getValue());
                else restore(activity, entry.getValue());
            }
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (closed || activity == null || !application.getPackageName().equals(activity.getPackageName())) return;
        runOnMain(() -> attachResumedWindow(activity));
    }

    private void attachResumedWindow(Activity activity) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        synchronized (windows) {
            WindowState state = windows.get(activity);
            if (state == null) {
                state = capture(activity);
                if (state == null) return;
                windows.put(activity, state);
            }
            attachFocusObserver(activity, state);
            if (changesWindow()) apply(activity, state);
        }
    }

    private void attachFocusObserver(Activity activity, WindowState state) {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (!observer.isAlive() || state.focusObserver.get() == observer) return;
        removeFocusObserver(state);
        WeakReference<Activity> owner = new WeakReference<>(activity);
        state.focusListener = focused -> {
            Activity resumed = owner.get();
            if (!focused || closed || resumed == null || resumed.isFinishing() || resumed.isDestroyed()
                    || state.focusObserver.get() == null
                    || !changesWindow()) return;
            // The host can clear its startup flags after onResume, before the window gains focus.
            reapplyRequestedBars(resumed, state);
        };
        state.focusObserver = new WeakReference<>(observer);
        observer.addOnWindowFocusChangeListener(state.focusListener);
    }

    private static void removeFocusObserver(WindowState state) {
        ViewTreeObserver observer = state.focusObserver.get();
        if (observer != null && observer.isAlive() && state.focusListener != null) {
            observer.removeOnWindowFocusChangeListener(state.focusListener);
        }
        state.focusObserver.clear();
        state.focusListener = null;
    }

    private void reapplyRequestedBars(Activity activity, WindowState state) {
        try {
            Window window = activity.getWindow();
            if (window == null || window.getDecorView() == null) return;
            View decor = window.getDecorView();
            int requested = (hidesStatusBar() ? HIDE_STATUS_FLAGS : 0)
                    | (hideNavigationBar ? HIDE_NAVIGATION_FLAGS : 0);
            int visibility = decor.getSystemUiVisibility();
            if ((visibility & requested) != requested) decor.setSystemUiVisibility(visibility | requested);
            int requestedFlags = hidesStatusBar()
                    ? FULLSCREEN_FLAG | LAYOUT_IN_SCREEN_FLAG : 0;
            int currentFlags = window.getAttributes().flags;
            if ((currentFlags & requestedFlags) != requestedFlags) {
                window.setFlags(currentFlags | requestedFlags, MANAGED_WINDOW_FLAGS);
            }
            applyDecorInsets(decor, !hidesStatusBar() && state.decorFitsSystemWindows);
            applyDisplayCutout(window, state, hidesStatusBar());
            if (Build.VERSION.SDK_INT >= 30) updateModernBars(window, state,
                    (hidesStatusBar() ? WindowInsets.Type.statusBars() : 0)
                            | (hideNavigationBar ? WindowInsets.Type.navigationBars() : 0));
        } catch (RuntimeException failure) {
            safeLog("Could not apply system bar visibility: " + failure.getMessage());
        }
    }

    private static WindowState capture(Activity activity) {
        Window window = activity.getWindow();
        if (window == null || window.getDecorView() == null) return null;
        WindowState state = new WindowState(window.getDecorView().getSystemUiVisibility(),
                window.getAttributes().flags, window.getAttributes().layoutInDisplayCutoutMode,
                window.getDecorView().getFitsSystemWindows());
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = window.getDecorView().getRootWindowInsets();
            if (insets != null) {
                if (insets.isVisible(WindowInsets.Type.statusBars())) state.originalVisibleBars |= WindowInsets.Type.statusBars();
                if (insets.isVisible(WindowInsets.Type.navigationBars())) state.originalVisibleBars |= WindowInsets.Type.navigationBars();
            } else {
                if ((state.windowFlags & FULLSCREEN_FLAG) == 0) state.originalVisibleBars |= WindowInsets.Type.statusBars();
                if ((state.systemUiVisibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) state.originalVisibleBars |= WindowInsets.Type.navigationBars();
            }
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) state.originalBarBehavior = controller.getSystemBarsBehavior();
        }
        return state;
    }

    private void apply(Activity activity, WindowState state) {
        try {
            Window window = activity.getWindow();
            if (window == null || window.getDecorView() == null) return;
            int visibility = state.systemUiVisibility;
            if (hidesStatusBar()) visibility |= HIDE_STATUS_FLAGS;
            if (hideNavigationBar) visibility |= HIDE_NAVIGATION_FLAGS;
            int windowFlags = state.windowFlags;
            if (hidesStatusBar()) windowFlags |= FULLSCREEN_FLAG | LAYOUT_IN_SCREEN_FLAG;
            window.getDecorView().setSystemUiVisibility(visibility);
            window.setFlags(windowFlags, MANAGED_WINDOW_FLAGS);
            applyDecorInsets(window.getDecorView(),
                    !hidesStatusBar() && state.decorFitsSystemWindows);
            applyDisplayCutout(window, state, hidesStatusBar());
            if (Build.VERSION.SDK_INT >= 30) updateModernBars(window, state,
                    (hidesStatusBar() ? WindowInsets.Type.statusBars() : 0)
                            | (hideNavigationBar ? WindowInsets.Type.navigationBars() : 0));
            TokAiLog.event("immersive", "window_state", "flags_readback",
                    "hide_status=" + hidesStatusBar()
                            + ";hide_navigation=" + hideNavigationBar
                            + ";requested_ui_flags=" + visibility + ";actual_ui_flags=" + window.getDecorView().getSystemUiVisibility()
                            + ";fullscreen=" + ((window.getAttributes().flags & FULLSCREEN_FLAG) != 0)
                            + ";layout_in_screen="
                            + ((window.getAttributes().flags & LAYOUT_IN_SCREEN_FLAG) != 0)
                            + ";decor_fits_system_windows="
                            + window.getDecorView().getFitsSystemWindows()
                            + ";cutout_mode=" + window.getAttributes().layoutInDisplayCutoutMode
                            + ";result_scope=window_flags;rom_rendering=unobserved");
        } catch (RuntimeException failure) {
            safeLog("Could not apply system bar visibility: " + failure.getMessage());
        }
    }

    private void restore(Activity activity, WindowState state) {
        try {
            Window window = activity.getWindow();
            if (window == null || window.getDecorView() == null) return;
            window.getDecorView().setSystemUiVisibility(state.systemUiVisibility);
            window.setFlags(state.windowFlags, MANAGED_WINDOW_FLAGS);
            applyDecorInsets(window.getDecorView(), state.decorFitsSystemWindows);
            applyDisplayCutout(window, state, false);
            if (Build.VERSION.SDK_INT >= 30) updateModernBars(window, state, 0);
        } catch (RuntimeException failure) {
            safeLog("Could not restore system bar visibility: " + failure.getMessage());
        }
    }

    private static void applyDisplayCutout(Window window, WindowState state, boolean enabled) {
        int requested = enabled
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                : state.cutoutMode;
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.layoutInDisplayCutoutMode != requested) {
            attributes.layoutInDisplayCutoutMode = requested;
            window.setAttributes(attributes);
            window.getDecorView().requestLayout();
            window.getDecorView().requestApplyInsets();
        }
    }

    private static void applyDecorInsets(View decor, boolean fitsSystemWindows) {
        if (decor.getFitsSystemWindows() == fitsSystemWindows) return;
        decor.setFitsSystemWindows(fitsSystemWindows);
        decor.requestApplyInsets();
    }

    // Round-trip the platform getter value, including API 30's legacy behavior.
    @android.annotation.SuppressLint("WrongConstant")
    private static void updateModernBars(Window window, WindowState state, int requested) {
        if (Build.VERSION.SDK_INT < 30) return;
        WindowInsetsController controller = window.getInsetsController();
        if (controller == null) return; // Focus recovery retries when the window becomes attached.
        int released = state.modernRequestedBars & ~requested;
        int show = released & state.originalVisibleBars;
        int hide = requested | (released & ~state.originalVisibleBars);
        if (show != 0) controller.show(show);
        if (hide != 0) controller.hide(hide);
        if (state.originalBarBehavior >= 0 && (requested != 0 || state.modernRequestedBars != 0)) {
            controller.setSystemBarsBehavior(requested == 0 ? state.originalBarBehavior
                    : WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        state.modernRequestedBars = requested;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        hideStatusBar = false;
        hideNavigationBar = false;
        application.unregisterActivityLifecycleCallbacks(this);
        runOnMain(() -> {
            synchronized (windows) {
                for (Map.Entry<Activity, WindowState> entry : windows.entrySet()) {
                    removeFocusObserver(entry.getValue());
                    if (entry.getKey() != null) restore(entry.getKey(), entry.getValue());
                }
                windows.clear();
            }
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not affect Activity rendering.
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) {
        synchronized (windows) {
            WindowState state = windows.get(activity);
            if (state != null) removeFocusObserver(state);
        }
    }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        synchronized (windows) {
            WindowState state = windows.remove(activity);
            if (state != null) removeFocusObserver(state);
        }
    }

    private static final class WindowState {
        final int systemUiVisibility;
        final int windowFlags;
        final int cutoutMode;
        final boolean decorFitsSystemWindows;
        int originalVisibleBars;
        int originalBarBehavior = -1;
        int modernRequestedBars;
        WeakReference<ViewTreeObserver> focusObserver = new WeakReference<>(null);
        ViewTreeObserver.OnWindowFocusChangeListener focusListener;

        WindowState(int systemUiVisibility, int windowFlags, int cutoutMode,
                    boolean decorFitsSystemWindows) {
            this.systemUiVisibility = systemUiVisibility;
            this.windowFlags = windowFlags;
            this.cutoutMode = cutoutMode;
            this.decorFitsSystemWindows = decorFitsSystemWindows;
        }
    }
}
