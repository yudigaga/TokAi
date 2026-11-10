package com.tokai.module.ui;

import com.tokai.module.R;
import com.tokai.module.i18n.UiText;

import com.tokai.module.diagnostics.TokAiLog;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.Gravity;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Injects one entry into the uniquely identified host settings scroll content. */
final class HostSettingsEntry {
    private static final String TAG = "TokAi.HostSettingsEntry";

    private HostSettingsEntry() { }

    static View attach(Activity activity, Class<?> scrollClass, Runnable openSettings) {
        View content = activity.findViewById(android.R.id.content);
        List<ViewGroup> scrolls = new ArrayList<>();
        findScrolls(content, scrollClass, scrolls);
        if (scrolls.size() != 1) { TokAiLog.event("settings_entry", "target_lookup", "ambiguous_or_missing", "candidate_count=" + scrolls.size()); return null; }
        ViewGroup scroll = scrolls.get(0);
        if ("androidx.compose.ui.platform.ComposeView".equals(scrollClass.getName())) {
            return attachCompose(activity, scroll, openSettings);
        }
        if (scroll.getChildCount() != 1 || !(scroll.getChildAt(0) instanceof LinearLayout)) return null;
        LinearLayout container = (LinearLayout) scroll.getChildAt(0);
        if (container.getOrientation() != LinearLayout.VERTICAL) return null;
        for (int index = 0; index < container.getChildCount(); index++) {
            View existing = container.getChildAt(index);
            if (TAG.equals(existing.getTag())) return existing;
        }

        TextView entry = entry(activity, openSettings);
        container.addView(entry, Math.min(1, container.getChildCount()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        TokAiLog.event("settings_entry", "view_attached", "parent_readback", "parent_matches=" + (entry.getParent() == container));
        return entry;
    }

    /** Only observes roots wrapped before FragmentManager takes ownership. */
    static View attachCompose(Activity activity, ViewGroup compose, Runnable openSettings) {
        if (!(compose.getParent() instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) compose.getParent();
        if ((TAG + ".container").equals(parent.getTag())) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (TAG.equals(child.getTag())) return child;
            }
            return null;
        }
        return null;
    }

    /** Called at onCreateView return, before the fragment stores or attaches its root. */
    static View wrapCompose(Activity activity, ViewGroup compose, Runnable openSettings) {
        if (compose.getParent() != null) throw new IllegalStateException("Settings root already attached");
        LinearLayout column = new LinearLayout(activity);
        column.setTag(TAG + ".container");
        column.setOrientation(LinearLayout.VERTICAL);
        // The host's original fragment root fits system windows. Its replacement
        // must retain that contract so the entry clears forced edge-to-edge bars.
        column.setFitsSystemWindows(compose.getFitsSystemWindows());
        if (compose.getFitsSystemWindows()) {
            column.setOnApplyWindowInsetsListener(HostSettingsEntry::applySystemInsets);
        }
        TextView entry = entry(activity, openSettings);
        column.addView(entry, new LinearLayout.LayoutParams(-1, -2));
        column.addView(compose, new LinearLayout.LayoutParams(-1, 0, 1));
        TokAiLog.event("settings_entry", "root_prepared", "compose_container",
                "composition_preserved=true;entry_count=1;window_attachment=unobserved");
        return column;
    }

    private static WindowInsets applySystemInsets(View root, WindowInsets insets) {
        int left, top, right, bottom;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
        }
        root.setPadding(left, top, right, bottom);
        // Child coordinates now start inside the safe area. Forward the remaining
        // insets so the original Compose root does not add the same bars twice.
        if (Build.VERSION.SDK_INT >= 29) return insets.inset(left, top, right, bottom);
        return insets.consumeSystemWindowInsets().consumeDisplayCutout();
    }

    private static TextView entry(Activity activity, Runnable openSettings) {
        com.tokai.module.i18n.ModuleLocale.ensureInitialized(activity);
        TextView entry = new TextView(activity);
        entry.setTag(TAG);
        entry.setText(UiText.get(R.string.common_ui_m191));
        entry.setTextSize(16);
        entry.setTextColor(ModuleTheme.color(activity, 0xFF1F272D));
        entry.setBackgroundColor(ModuleTheme.color(activity, 0xFFFFFFFF));
        ModuleTheme.bind(entry);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        entry.setMinHeight(dp(activity, 54));
        entry.setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_preferences,
                0, 0, 0);
        entry.setCompoundDrawablePadding(dp(activity, 12));
        entry.setOnClickListener(ignored -> {
            TokAiLog.Operation trace = TokAiLog.begin("settings_entry", "entry_click");
            try { openSettings.run(); trace.finish(TokAiLog.Outcome.UNCONFIRMED, "open_requested", "settings_render_result=unobserved"); }
            catch (RuntimeException failure) { trace.error("open_settings", failure); trace.finish(TokAiLog.Outcome.FAILED, "open_failed", ""); throw failure; }
        });
        entry.setFocusable(true);
        HostSettingsEntryVisibility.observe(activity, entry);
        return entry;
    }

    private static void findScrolls(View view, Class<?> scrollClass, List<ViewGroup> matches) {
        if (view == null) return;
        if (scrollClass.isInstance(view) && view instanceof ViewGroup) {
            matches.add((ViewGroup) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                findScrolls(group.getChildAt(index), scrollClass, matches);
            }
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
