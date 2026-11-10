package com.tokai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.compose.ui.platform.ComposeView;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.tokai.module.scan.HostApkTestProfile;
import com.tokai.module.scan.HostRule;
import com.tokai.module.scan.HostRuleSet;
import com.tokai.module.scan.HostScanCache;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.HostScanner;
import com.tokai.module.scan.RuleScanResult;
import com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity;
import com.ss.android.ugc.aweme.setting.ui.rvmpcompose.SettingsComposeRvmpFragment;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.*;

/** Exercises installation, callback and entry click together, using the packaged settings rules. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.tokai.module")
public final class TikTokSettingsPageHookTest {
    private static final String FRAGMENT = "Lcom/ss/android/ugc/aweme/setting/ui/rvmpcompose/SettingsComposeRvmpFragment;";
    private static final String ACTIVITY = "Lcom/ss/android/ugc/aweme/setting/ui/SettingContainerActivity;";
    private static final String COMPOSE = "Landroidx/compose/ui/platform/ComposeView;";
    private static final String ENTRY = "TokAi.HostSettingsEntry";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void freshScanInstallsOnceWrapsFragmentRootAndEntryOpensMiuixSettings() throws Throwable {
        verifyEntry(false);
    }

    @Test public void cachedScanInstallsTheSameFragmentEntry() throws Throwable {
        verifyEntry(true);
    }

    private void verifyEntry(boolean cached) throws Throwable {
        HostScanReport report = settingsReport(cached);
        SettingContainerActivity activity = Robolectric.buildActivity(SettingContainerActivity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        Framework framework = new Framework();
        try {
            controller.setSettingsPageTargets(SettingContainerActivity.class, ComposeView.class);
            controller.installSettingsPageHook(framework.api, report, getClass().getClassLoader());
            controller.installSettingsPageHook(framework.api, report, getClass().getClassLoader());
            assertEquals("Installation must be idempotent", 1, framework.registrations);
            assertEquals("TokAi.Settings.ComposeRoot", framework.id);
            assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, framework.mode);
            assertEquals(SettingsComposeRvmpFragment.class.getDeclaredMethod("onCreateView",
                    LayoutInflater.class, ViewGroup.class, Bundle.class), framework.target);
            SettingsComposeRvmpFragment fragment = new SettingsComposeRvmpFragment(activity);
            ComposeView nativeRoot = (ComposeView) fragment.onCreateView(LayoutInflater.from(activity), null, null);
            View wrapped = (View) framework.invoke(fragment, nativeRoot, null);
            assertTrue(wrapped instanceof LinearLayout);
            LinearLayout column = (LinearLayout) wrapped;
            assertSame(nativeRoot, column.getChildAt(1));
            assertSame(activity, ViewTreeLifecycleOwner.get(nativeRoot));
            assertSame(activity, ViewTreeViewModelStoreOwner.get(nativeRoot));
            assertSame(activity, ViewTreeSavedStateRegistryOwner.get(nativeRoot));
            assertEquals(1f, ((LinearLayout.LayoutParams) nativeRoot.getLayoutParams()).weight, 0f);
            assertEquals(ENTRY, column.getChildAt(0).getTag());
            assertEquals("TokAi 模块设置", ((TextView) column.getChildAt(0)).getText().toString());
            activity.setContentView(wrapped);
            controller.onActivityResumed(activity);
            idleMain();
            assertSame(column.getChildAt(0), HostSettingsEntry.attach(activity, ComposeView.class, () -> fail("Duplicate entry")));
            assertEquals(2, column.getChildCount());
            column.getChildAt(0).performClick();
            idleMain();
            Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            assertSame(activity, dialog.getOwnerActivity());
            assertNotNull("Entry must open the established Miuix settings", dialog.getWindow()
                    .getDecorView().findViewWithTag("tokai.miuix.home"));
            controller.close();
            assertEquals(1, framework.unhooks);
            assertFalse(dialog.isShowing());
        } finally {
            controller.close();
            activity.finish();
            idleMain();
        }
    }

    @Test public void unrelatedOwnerAndNonComposeResultPassThrough() throws Throwable {
        Activity unrelated = Robolectric.buildActivity(Activity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        Framework framework = new Framework();
        try (TikTokSettingsPageHook hook = TikTokSettingsPageHook.install(framework.api,
                settingsReport(false), getClass().getClassLoader(), controller, ignored -> { })) {
            ComposeView root = new ComposeView(unrelated);
            assertSame(root, framework.invoke(new SettingsComposeRvmpFragment(unrelated), root, null));
            View ordinary = new View(unrelated);
            assertSame(ordinary, framework.invoke(new SettingsComposeRvmpFragment(unrelated), ordinary, null));
            assertNull(root.getParent());
        } finally { controller.close(); unrelated.finish(); }
    }

    @Test public void alreadyAttachedRootIsPreservedAndFailureIsReported() throws Throwable {
        SettingContainerActivity activity = Robolectric.buildActivity(SettingContainerActivity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        Framework framework = new Framework();
        List<String> logs = new ArrayList<>();
        try (TikTokSettingsPageHook hook = TikTokSettingsPageHook.install(framework.api,
                settingsReport(false), getClass().getClassLoader(), controller, logs::add)) {
            ComposeView root = new ComposeView(activity);
            activity.setContentView(root);
            Object parent = root.getParent();
            assertSame(root, framework.invoke(new SettingsComposeRvmpFragment(activity), root, null));
            assertSame(parent, root.getParent());
            assertEquals(Collections.singletonList("TikTok settings entry unavailable: IllegalStateException"), logs);
        } finally { controller.close(); activity.finish(); }
    }

    @Test public void originalHostExceptionPropagatesWithoutChangingTheRoot() throws Throwable {
        SettingContainerActivity activity = Robolectric.buildActivity(SettingContainerActivity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        Framework framework = new Framework();
        try (TikTokSettingsPageHook hook = TikTokSettingsPageHook.install(framework.api,
                settingsReport(false), getClass().getClassLoader(), controller, ignored -> fail("Host exception was swallowed"))) {
            IllegalArgumentException original = new IllegalArgumentException("fixture host failure");
            assertSame(original, assertThrows(IllegalArgumentException.class,
                    () -> framework.invoke(new SettingsComposeRvmpFragment(activity), null, original)));
        } finally { controller.close(); activity.finish(); }
    }

    @Test public void rejectedHookRegistrationFailsInstallation() throws Exception {
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        Framework framework = new Framework();
        framework.failure = new IllegalStateException("fixture registration rejected");
        try {
            assertSame(framework.failure, assertThrows(IllegalStateException.class, () ->
                    TikTokSettingsPageHook.install(framework.api, settingsReport(false),
                            getClass().getClassLoader(), controller, ignored -> { })));
            assertEquals(0, framework.unhooks);
        } finally { controller.close(); }
    }

    /** A real scanner report, then a real cache round trip, rather than manually marked UNIQUE targets. */
    private HostScanReport settingsReport(boolean cached) throws Exception {
        List<HostRule> rules = new ArrayList<>();
        try (FileInputStream input = new FileInputStream(HostApkTestProfile.assetFile("tiktok-host-rules.tsv"))) {
            for (HostRule rule : HostRuleSet.parse(input).getRules()) {
                if (rule.getId().startsWith("host_settings_")) rules.add(rule);
            }
        }
        assertEquals(3, rules.size());
        List<ImmutableMethodParameter> parameters = Arrays.asList(
                parameter("Landroid/view/LayoutInflater;"), parameter("Landroid/view/ViewGroup;"),
                parameter("Landroid/os/Bundle;"));
        ImmutableMethod create = new ImmutableMethod(FRAGMENT, "onCreateView", parameters,
                "Landroid/view/View;", AccessFlags.PUBLIC.getValue() | AccessFlags.FINAL.getValue(),
                Collections.emptySet(), Collections.emptySet(), null);
        List<ImmutableClassDef> classes = Arrays.asList(classDef(ACTIVITY, Collections.emptyList()),
                classDef(COMPOSE, Collections.emptyList()), classDef(FRAGMENT, Collections.singletonList(create)));
        MemoryDataStore data = new MemoryDataStore();
        DexPool.writeTo(data, new ImmutableDexFile(Opcodes.getDefault(), classes));
        File apk = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(data.getData());
            zip.closeEntry();
        } finally { data.close(); }
        HostScanCache cache = new HostScanCache(new File(temporary.newFolder(), "settings.cache"));
        HostScanner scanner = new HostScanner();
        HostRuleSet settingsRules = HostRuleSet.of(rules);
        HostScanReport report = scanner.scanCached(Collections.singletonList(apk), settingsRules, cache);
        if (cached) report = scanner.scanCached(Collections.singletonList(apk), settingsRules, cache);
        assertEquals(cached, report.isCacheHit());
        for (RuleScanResult result : report.getRuleResults()) assertEquals(result.getRuleId(),
                RuleScanResult.Status.UNIQUE, result.getStatus());
        return report;
    }

    private static ImmutableMethodParameter parameter(String type) {
        return new ImmutableMethodParameter(type, Collections.emptySet(), null);
    }

    private static ImmutableClassDef classDef(String type, List<ImmutableMethod> methods) {
        return new ImmutableClassDef(type, AccessFlags.PUBLIC.getValue(), "Ljava/lang/Object;",
                Collections.emptyList(), null, Collections.emptySet(), Collections.emptyList(), methods);
    }

    private static void idleMain() throws Exception {
        Object shadow = Class.forName("org.robolectric.Shadows").getMethod("shadowOf", Looper.class)
                .invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idle").invoke(shadow);
    }

    private static final class Framework {
        Method target;
        XposedInterface.Hooker callback;
        XposedInterface.ExceptionMode mode;
        String id;
        int registrations;
        int unhooks;
        RuntimeException failure;
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (!"hook".equals(method.getName())) return null;
                    target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if ("setId".equals(call.getName())) id = (String) values[0];
                                if ("setExceptionMode".equals(call.getName())) mode = (XposedInterface.ExceptionMode) values[0];
                                if (!"intercept".equals(call.getName())) return builder;
                                if (failure != null) throw failure;
                                callback = (XposedInterface.Hooker) values[0];
                                registrations++;
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, operation, parameters) -> {
                                            if ("unhook".equals(operation.getName())) unhooks++;
                                            return null;
                                        });
                            });
                });

        Object invoke(Object owner, Object result, Throwable hostFailure) throws Throwable {
            assertNotNull("Production installation must register its interceptor", callback);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                        if ("getThisObject".equals(method.getName())) return owner;
                        if ("getExecutable".equals(method.getName())) return target;
                        if ("getArgs".equals(method.getName())) return Arrays.asList(null, null, null);
                        if ("proceed".equals(method.getName())) {
                            if (hostFailure != null) throw hostFailure;
                            return result;
                        }
                        return null;
                    });
            return callback.intercept(chain);
        }
    }
}
