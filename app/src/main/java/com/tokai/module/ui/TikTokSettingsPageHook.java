package com.tokai.module.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.tokai.module.feature.HostTargets;
import com.tokai.module.scan.HostScanReport;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Wraps the verified settings fragment root before FragmentManager records its view. */
public final class TikTokSettingsPageHook implements AutoCloseable {
    private final XposedInterface.HookHandle handle;

    private TikTokSettingsPageHook(XposedInterface.HookHandle handle) { this.handle = handle; }

    public static TikTokSettingsPageHook install(XposedInterface framework, HostScanReport report,
                                                 ClassLoader loader, HostUiController controller,
                                                 Consumer<String> log) throws Exception {
        Method create = HostTargets.method(report, "host_settings_fragment_create", loader);
        Class<?> activityType = HostTargets.type(report, "host_settings_activity", loader);
        Class<?> composeType = HostTargets.type(report, "host_settings_scroll", loader);
        if (!"com.ss.android.ugc.aweme.setting.ui.rvmpcompose.SettingsComposeRvmpFragment"
                .equals(create.getDeclaringClass().getName()) || create.getReturnType() != View.class
                || !"androidx.compose.ui.platform.ComposeView".equals(composeType.getName())) {
            throw new IllegalStateException("TikTok settings fragment contract changed");
        }
        Method activity = create.getDeclaringClass().getMethod("getActivity");
        XposedInterface.HookHandle handle = framework.hook(create).setId("TokAi.Settings.ComposeRoot")
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (!composeType.isInstance(result) || !(result instanceof ViewGroup)) return result;
                    try {
                        Object owner = activity.invoke(chain.getThisObject());
                        if (!(owner instanceof Activity) || !activityType.isInstance(owner)) return result;
                        Activity settings = (Activity) owner;
                        return HostSettingsEntry.wrapCompose(settings, (ViewGroup) result,
                                () -> controller.showSettingsFromGesture(settings));
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        if (log != null) log.accept("TikTok settings entry unavailable: " + failure.getClass().getSimpleName());
                        return result;
                    }
                });
        if (handle == null) throw new IllegalStateException("No settings fragment hook handle");
        return new TikTokSettingsPageHook(handle);
    }

    @Override public void close() { handle.unhook(); }
}
