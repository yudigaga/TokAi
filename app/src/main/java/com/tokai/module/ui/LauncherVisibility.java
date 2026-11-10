package com.tokai.module.ui;

import com.tokai.module.diagnostics.TokAiLog;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Changes only the launcher alias; the exported module settings activity remains enabled. */
public final class LauncherVisibility {
    private LauncherVisibility() { }
    private static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(), "com.tokai.module.Launcher");
    }
    public static boolean isHidden(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(alias(context))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
    public static void setHidden(Context context, boolean hidden) {
        TokAiLog.Operation trace = TokAiLog.begin("launcher", "toggle_alias");
        try {
        context.getPackageManager().setComponentEnabledSetting(alias(context), hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        try {
            boolean observed = isHidden(context);
            trace.finish(observed == hidden ? TokAiLog.Outcome.SUCCESS : TokAiLog.Outcome.UNCONFIRMED,
                    "package_manager_readback", "requested_hidden=" + hidden + ";observed_hidden=" + observed);
        } catch (RuntimeException unavailable) {
            trace.error("alias_readback", unavailable); trace.finish(TokAiLog.Outcome.UNCONFIRMED, "readback_unavailable", "set_returned=true");
        }
        } catch (RuntimeException failure) {
            trace.error("alias_state", failure); trace.finish(TokAiLog.Outcome.FAILED, "package_manager_error", ""); throw failure;
        }
    }
}
