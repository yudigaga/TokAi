package com.tokai.module.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import com.tokai.module.i18n.ModuleLocale;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "en", application = HostUiControllerResourcesTest.ResourcesUnavailableApplication.class)
public final class HostUiControllerResourcesTest {
    @Before public void clearPreviouslyBoundModuleContext() throws Exception {
        // Another locale test may have populated the process-wide cache before this fixture.
        Field context = ModuleLocale.class.getDeclaredField("moduleContext");
        context.setAccessible(true);
        context.set(null, null);
    }

    @Test public void unavailableModuleResourcesKeepHostLifecycleAndQueuedUiSafeAndRetryOnResume() {
        ResourcesUnavailableApplication application = (ResourcesUnavailableApplication) RuntimeEnvironment.getApplication();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        List<String> logs = new ArrayList<>();
        HostUiController controller = new HostUiController(application, logs::add);
        try {
            controller.onActivityResumed(activity);
            assertSame("Resource failure must not lose the resumed host", activity, controller.getResumedActivity());
            assertTrue("Fixture must exercise package resource failure", application.packageContextAttempts > 0);
            assertFalse("Resource failure should be logged", logs.isEmpty());
            assertNull(ShadowDialog.getLatestDialog());

            controller.beginScan();
            controller.showSettingsFromGesture(activity);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertSame(activity, controller.getResumedActivity());
            assertNull("Unavailable resources must not open module dialogs", ShadowDialog.getLatestDialog());

            int attempts = application.packageContextAttempts;
            controller.onActivityPaused(activity);
            controller.onActivityResumed(activity);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertSame(activity, controller.getResumedActivity());
            assertTrue("Next resume should retry resource initialization", application.packageContextAttempts > attempts);
            assertNull(ShadowDialog.getLatestDialog());
        } finally {
            controller.close();
            activity.finish();
        }
    }

    public static final class ResourcesUnavailableApplication extends Application {
        private Resources hostResources;
        int packageContextAttempts;

        @Override public Resources getResources() {
            if (hostResources == null) {
                Resources actual = super.getResources();
                hostResources = new Resources(actual.getAssets(), actual.getDisplayMetrics(),
                        new Configuration(actual.getConfiguration())) {
                    @Override public String getResourcePackageName(int id) {
                        return "com.zhiliaoapp.musically";
                    }
                };
            }
            return hostResources;
        }

        @Override public Context createPackageContext(String packageName, int flags)
                throws PackageManager.NameNotFoundException {
            packageContextAttempts++;
            throw new PackageManager.NameNotFoundException(packageName);
        }
    }
}
