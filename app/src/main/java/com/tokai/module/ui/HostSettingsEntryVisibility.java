package com.tokai.module.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.TextView;

import com.tokai.module.diagnostics.TokAiLog;

import java.util.function.BiConsumer;

/** Reads attached entry geometry without changing the host hierarchy or reading host text. */
final class HostSettingsEntryVisibility {
    private HostSettingsEntryVisibility() { }

    static void observe(Activity activity, TextView entry) {
        observe(activity, entry, (reason, evidence) -> TokAiLog.event(
                "settings_entry", "visibility_readback", reason, evidence));
    }

    static void observe(Activity activity, TextView entry, BiConsumer<String, String> logger) {
        Observer observer = new Observer(activity.getWindow().getDecorView(), entry, logger);
        entry.addOnAttachStateChangeListener(observer);
        if (entry.isAttachedToWindow()) observer.onViewAttachedToWindow(entry);
    }

    private static final class Observer implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnPreDrawListener {
        private final View decor;
        private final TextView entry;
        private final BiConsumer<String, String> logger;
        private ViewTreeObserver tree;
        private String lastState;

        Observer(View decor, TextView entry, BiConsumer<String, String> logger) {
            this.decor = decor;
            this.entry = entry;
            this.logger = logger;
        }

        @Override public void onViewAttachedToWindow(View view) {
            removePreDrawListener();
            lastState = null;
            tree = entry.getViewTreeObserver();
            tree.addOnPreDrawListener(this);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            removePreDrawListener();
            lastState = null;
        }

        private void removePreDrawListener() {
            if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
            tree = null;
        }

        @Override public boolean onPreDraw() {
            // Constructing a fragment root or adding a child alone is not visible evidence.
            if (!entry.isAttachedToWindow() || !decor.isAttachedToWindow()) return true;
            boolean rootAttached = entry.getRootView() == decor;
            boolean shown = entry.isShown();
            boolean nonzero = entry.getWidth() > 0 && entry.getHeight() > 0;
            Rect visible = new Rect();
            boolean hasBounds = entry.getGlobalVisibleRect(visible) && !visible.isEmpty();
            boolean fullyVisible = hasBounds && visible.width() >= entry.getWidth()
                    && visible.height() >= entry.getHeight();
            Rect safe = rootAttached ? safeViewport(decor, decor.getRootWindowInsets()) : null;
            Boolean fullyInSafeViewport = safe == null ? null
                    : nonzero && fullyVisible && safe.contains(visible);
            boolean transparentText = Color.alpha(entry.getCurrentTextColor()) == 0;
            boolean zeroAlpha = hasZeroAlpha(entry);
            String reason = !rootAttached ? "different_window_root"
                    : !shown ? "not_shown"
                    : !nonzero ? "zero_size"
                    : zeroAlpha || transparentText ? "transparent"
                    : !hasBounds ? "no_visible_bounds"
                    : !fullyVisible ? "clipped"
                    : fullyInSafeViewport == null ? "safe_viewport_unknown"
                    : !fullyInSafeViewport ? "outside_safe_viewport" : "visible_geometry";
            // Position changes within the same visibility state must not log every frame.
            String state = reason + ";" + rootAttached + ";" + shown + ";" + nonzero
                    + ";" + hasBounds + ";" + fullyVisible + ";" + fullyInSafeViewport
                    + ";" + transparentText + ";" + zeroAlpha;
            if (!state.equals(lastState)) {
                lastState = state;
                String evidence = "rootAttached=" + rootAttached + ";shown=" + shown
                        + ";size=" + entry.getWidth() + "x" + entry.getHeight()
                        + ";visible=" + (hasBounds ? bounds(visible) : "none")
                        + ";fullyInSafeViewport=" + (fullyInSafeViewport == null
                                ? "unknown" : fullyInSafeViewport);
                if (transparentText) evidence += ";textTransparent=true";
                if (zeroAlpha) evidence += ";alphaZero=true";
                try { logger.accept(reason, evidence); }
                catch (RuntimeException ignored) { /* Diagnostics cannot break a host draw. */ }
            }
            return true;
        }
    }

    /** Insets and getGlobalVisibleRect both describe the decor root's coordinate space. */
    static Rect safeViewport(View decor, WindowInsets insets) {
        if (insets == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) return null;
        int left, top, right, bottom;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets edges = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = edges.left; top = edges.top; right = edges.right; bottom = edges.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
        }
        return new Rect(left, top, decor.getWidth() - right, decor.getHeight() - bottom);
    }

    private static boolean hasZeroAlpha(View entry) {
        View current = entry;
        while (true) {
            if (current.getAlpha() <= 0f) return true;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return false;
            current = (View) parent;
        }
    }

    private static String bounds(Rect bounds) {
        return bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
    }
}
