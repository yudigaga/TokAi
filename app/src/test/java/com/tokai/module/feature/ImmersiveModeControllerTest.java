package com.tokai.module.feature;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import org.robolectric.shadows.ShadowViewRootImpl;
import org.robolectric.util.ReflectionHelpers;
import java.util.Optional;

import com.tokai.module.config.ExtendedSettings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.tokai.module")
public final class ImmersiveModeControllerTest {
    @Test public void modernSystemBarsReceiveIndependentRequestsAndReleaseOnlyOwnedBars() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        int status = WindowInsets.Type.statusBars(), navigation = WindowInsets.Type.navigationBars();
        activity.getWindow().getInsetsController().show(status | navigation);
        ImmersiveModeController controller = ImmersiveModeController.install(activity, settings(true, false), ignored -> { });
        try {
            assertEquals(Optional.of(false), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(true), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
            controller.setSettings(settings(false, true));
            assertEquals(Optional.of(true), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(false), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
            controller.close();
            assertEquals(Optional.of(true), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
        } finally { controller.close(); activity.finish(); }
    }

    @Test public void modernFocusRecoveryPreservesAnUnrelatedHiddenNavigationBar() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        int status = WindowInsets.Type.statusBars(), navigation = WindowInsets.Type.navigationBars();
        activity.getWindow().getInsetsController().show(status | navigation);
        ImmersiveModeController controller = ImmersiveModeController.install(activity, settings(true, false), ignored -> { });
        try {
            activity.getWindow().getInsetsController().hide(navigation);
            activity.getWindow().getInsetsController().show(status);
            dispatchWindowFocus(activity, true);
            assertEquals(Optional.of(false), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(false), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
            controller.close();
            assertEquals(Optional.of(false), ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
        } finally { controller.close(); activity.finish(); }
    }
    @Test
    public void unrelatedBrokenComponentConfigurationDoesNotBlockSystemBarStartup() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("immersive_startup_bad_config_test", 0);
        preferences.edit().putBoolean("remove_black_edges", true)
                .putBoolean("hide_navigation_bar", true)
                .putInt("component_alpha_like", -2).commit();
        ImmersiveModeController controller = null;
        try {
            controller = ImmersiveModeController.install(activity,
                    ExtendedSettings.readSystemBars(preferences), ignored -> { });
            assertStatusBarHidden(activity);
            assertTrue((activity.getWindow().getDecorView().getSystemUiVisibility()
                    & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0);
        } finally {
            if (controller != null) controller.close();
            activity.finish();
        }
    }

    @Test
    public void successfulFullConfigurationOverridesIndependentStartupFlags() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int original = activity.getWindow().getDecorView().getSystemUiVisibility();
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("immersive_startup_override_test", 0);
        preferences.edit().putBoolean("remove_black_edges", true)
                .putString("fake_version_name", "startup-ignored")
                .putBoolean("hide_interface", true).commit();
        ExtendedSettings startup = ExtendedSettings.readSystemBars(preferences);
        assertEquals("", startup.fakeVersionName);
        assertFalse(startup.hideInterface);
        ImmersiveModeController controller = ImmersiveModeController.install(
                activity, startup, ignored -> { });
        try {
            assertStatusBarHidden(activity);
            preferences.edit().putBoolean("remove_black_edges", false).commit();
            controller.setSettings(ExtendedSettings.readFrom(preferences));
            assertEquals(original, activity.getWindow().getDecorView().getSystemUiVisibility());
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void installationAfterActivityResumeImmediatelyHidesStatusBar() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int originalVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        int originalWindowFlags = activity.getWindow().getAttributes().flags;
        ImmersiveModeController controller = ImmersiveModeController.install(
                activity, settings(true, false), ignored -> { });
        try {
            assertStatusBarHidden(activity);
        } finally {
            controller.close();
            assertEquals(originalVisibility, activity.getWindow().getDecorView().getSystemUiVisibility());
            assertEquals(originalWindowFlags, activity.getWindow().getAttributes().flags);
            activity.finish();
        }
    }

    @Test
    public void returningWindowFocusReappliesStatusBarAfterStartupReset() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int originalVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        int originalWindowFlags = activity.getWindow().getAttributes().flags;
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), settings(true, false), ignored -> { });
        controller.onActivityResumed(activity);
        try {
            activity.getWindow().getDecorView().setSystemUiVisibility(originalVisibility);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            dispatchWindowFocus(activity, true);
            assertStatusBarHidden(activity);
            assertEquals(originalVisibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION,
                    activity.getWindow().getDecorView().getSystemUiVisibility()
                            & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        } finally {
            controller.close();
            dispatchWindowFocus(activity, true);
            assertEquals(originalVisibility, activity.getWindow().getDecorView().getSystemUiVisibility());
            assertEquals(originalWindowFlags, activity.getWindow().getAttributes().flags);
            activity.finish();
        }
    }

    @Test
    public void lateInstallationReadsCurrentResumedActivityOnMain() throws Exception {
        Activity first = Robolectric.buildActivity(Activity.class).setup().get();
        Activity second = Robolectric.buildActivity(Activity.class).setup().get();
        java.util.concurrent.atomic.AtomicReference<Activity> resumed =
                new java.util.concurrent.atomic.AtomicReference<>(first);
        java.util.concurrent.atomic.AtomicReference<ImmersiveModeController> installed =
                new java.util.concurrent.atomic.AtomicReference<>();
        ExtendedSettings hidden = settings(true, false);
        Thread worker = new Thread(() -> installed.set(ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), hidden, ignored -> { }, resumed::get)));
        worker.start();
        worker.join();
        resumed.set(second);
        idleMain();
        try {
            assertStatusBarHidden(second);
            assertFalse((first.getWindow().getDecorView().getSystemUiVisibility()
                    & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0);
        } finally {
            installed.get().close();
            first.finish();
            second.finish();
        }
    }

    @Test
    public void focusRecoveryPreservesHostNavigationAndStopsWhilePaused() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int originalVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        ImmersiveModeController controller = ImmersiveModeController.install(
                activity, settings(true, false), ignored -> { });
        try {
            int hostVisibility = originalVisibility | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LOW_PROFILE;
            activity.getWindow().getDecorView().setSystemUiVisibility(hostVisibility);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            dispatchWindowFocus(activity, true);
            assertStatusBarHidden(activity);
            assertEquals(hostVisibility, activity.getWindow().getDecorView().getSystemUiVisibility()
                    & ~1284);

            controller.onActivityPaused(activity);
            activity.getWindow().getDecorView().setSystemUiVisibility(originalVisibility);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            dispatchWindowFocus(activity, true);
            assertEquals(originalVisibility, activity.getWindow().getDecorView().getSystemUiVisibility());
            assertFalse((activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void statusAndNavigationBarsCanBeHiddenIndependentlyAndRestored() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int originalVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        int originalWindowFlags = activity.getWindow().getAttributes().flags;
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), settings(false, false), ignored -> { });
        controller.onActivityResumed(activity);

        controller.setSettings(settings(false, true));
        int navigationVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        assertTrue((navigationVisibility & 2) != 0);
        assertTrue((navigationVisibility & 4096) != 0);
        assertEquals(originalWindowFlags & WindowManager.LayoutParams.FLAG_FULLSCREEN,
                activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN);

        controller.setSettings(settings(true, false));
        int statusVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        assertTrue((statusVisibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0);
        assertFalse((statusVisibility & 2) != 0);
        assertTrue((activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);

        controller.setSettings(settings(false, false));
        assertEquals(originalVisibility, activity.getWindow().getDecorView().getSystemUiVisibility());
        assertEquals(originalWindowFlags, activity.getWindow().getAttributes().flags);
        controller.close();
        activity.finish();
    }

    @Test
    public void statusBarHidesIntoCutoutWithoutChangingNavigationAndRestoresHostState() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        int status = WindowInsets.Type.statusBars(), navigation = WindowInsets.Type.navigationBars();
        activity.getWindow().getInsetsController().show(status | navigation);
        int originalVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        int originalWindowFlags = activity.getWindow().getAttributes().flags;
        int originalCutoutMode = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
        activity.getWindow().getDecorView().setFitsSystemWindows(true);
        boolean originalFitsSystemWindows = activity.getWindow().getDecorView().getFitsSystemWindows();
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), settings(false, false), ignored -> { });
        controller.onActivityResumed(activity);
        try {
            controller.setSettings(settings(true, false));
            assertStatusBarHidden(activity);
            assertTrue((activity.getWindow().getDecorView().getSystemUiVisibility()
                    & View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) != 0);
            assertTrue((activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0);
            assertFalse(activity.getWindow().getDecorView().getFitsSystemWindows());
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            assertEquals(Optional.of(false),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));

            activity.getWindow().getDecorView().setSystemUiVisibility(originalVisibility);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            activity.getWindow().getAttributes().layoutInDisplayCutoutMode = originalCutoutMode;
            activity.getWindow().getInsetsController().show(status | navigation);
            dispatchWindowFocus(activity, true);
            assertStatusBarHidden(activity);
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            assertEquals(Optional.of(false),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));

            controller.setSettings(settings(false, false));
            assertEquals(originalVisibility, activity.getWindow().getDecorView().getSystemUiVisibility());
            assertEquals(originalWindowFlags, activity.getWindow().getAttributes().flags);
            assertEquals(originalCutoutMode,
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            assertEquals(originalFitsSystemWindows,
                    activity.getWindow().getDecorView().getFitsSystemWindows());
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void statusAndNavigationBarsRemainIndependent() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        int status = WindowInsets.Type.statusBars(), navigation = WindowInsets.Type.navigationBars();
        activity.getWindow().getInsetsController().show(status | navigation);
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), settings(false, false), ignored -> { });
        controller.onActivityResumed(activity);
        try {
            controller.setSettings(settings(true, false));
            assertStatusBarHidden(activity);
            assertEquals(Optional.of(false),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));

            controller.setSettings(settings(false, true));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(false),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));

            controller.setSettings(settings(true, true));
            assertEquals(Optional.of(false),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(false),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));

            controller.setSettings(settings(false, false));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void hiddenVideoEdgesDoNotChangeSystemBars() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        int status = WindowInsets.Type.statusBars(), navigation = WindowInsets.Type.navigationBars();
        activity.getWindow().getInsetsController().show(status | navigation);
        int originalVisibility = activity.getWindow().getDecorView().getSystemUiVisibility();
        int originalFlags = activity.getWindow().getAttributes().flags;
        int originalCutoutMode = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("immersive_controller_test", 0);
        preferences.edit().putBoolean("remove_black_edges", false)
                .putBoolean("hide_navigation_bar", false)
                .putBoolean("hide_video_black_edges", true).commit();
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(),
                ExtendedSettings.readSystemBars(preferences), ignored -> { });
        controller.onActivityResumed(activity);
        try {
            controller.setSettings(ExtendedSettings.readSystemBars(preferences));
            assertEquals(originalVisibility,
                    activity.getWindow().getDecorView().getSystemUiVisibility());
            assertEquals(originalFlags, activity.getWindow().getAttributes().flags);
            assertEquals(originalCutoutMode,
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isStatusBarVisible"));
            assertEquals(Optional.of(true),
                    ReflectionHelpers.getStaticField(ShadowViewRootImpl.class, "isNavigationBarVisible"));
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void backgroundConfigurationAppliesOnMainAndLateUpdatesAfterCloseAreIgnored() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        int original = activity.getWindow().getDecorView().getSystemUiVisibility();
        ImmersiveModeController controller = ImmersiveModeController.install(
                RuntimeEnvironment.getApplication(), settings(false, false), ignored -> { });
        controller.onActivityResumed(activity);
        ExtendedSettings hidden = settings(false, true);
        Thread worker = new Thread(() -> controller.setSettings(hidden));
        worker.start();
        worker.join();
        Object looperShadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        looperShadow.getClass().getMethod("idle").invoke(looperShadow);
        assertTrue((activity.getWindow().getDecorView().getSystemUiVisibility() & 2) != 0);
        controller.close();
        controller.setSettings(hidden);
        looperShadow.getClass().getMethod("idle").invoke(looperShadow);
        assertEquals(original, activity.getWindow().getDecorView().getSystemUiVisibility());
        activity.finish();
    }

    private static ExtendedSettings settings(boolean hideStatusBar, boolean hideNavigationBar) {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("immersive_controller_test", 0);
        preferences.edit().putBoolean("remove_black_edges", hideStatusBar)
                .putBoolean("hide_video_black_edges", false)
                .putBoolean("hide_navigation_bar", hideNavigationBar).commit();
        return ExtendedSettings.readSystemBars(preferences);
    }

    private static void assertStatusBarHidden(Activity activity) {
        assertTrue((activity.getWindow().getDecorView().getSystemUiVisibility()
                & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0);
        assertTrue((activity.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);
    }

    private static void dispatchWindowFocus(Activity activity, boolean focused) throws Exception {
        java.lang.reflect.Method dispatch = android.view.ViewTreeObserver.class
                .getDeclaredMethod("dispatchOnWindowFocusChange", boolean.class);
        dispatch.setAccessible(true);
        dispatch.invoke(activity.getWindow().getDecorView().getViewTreeObserver(), focused);
    }

    private static void idleMain() throws Exception {
        Object looperShadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        looperShadow.getClass().getMethod("idle").invoke(looperShadow);
    }
}
