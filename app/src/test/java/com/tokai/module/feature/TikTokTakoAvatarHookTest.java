package com.tokai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.bytedance.lighten.loader.SmartImageView;
import com.tokai.module.config.AiModelConfig;
import com.tokai.module.config.AiModelSettings;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import com.ss.android.ugc.aweme.tako.botpage.toptitle.TopTitleAssem;
import io.github.libxposed.api.XposedInterface;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class TikTokTakoAvatarHookTest {
    @Test public void globalAvatarReplacesOnlyNativeAssistantHeaderAndRestoresOnClose() throws Throwable {
        try (Scene scene = new Scene()) {
            scene.fire();
            assertEquals(1, scene.owner.nativeCalls);
            assertSame(scene.header, scene.owner.root);
            assertTrue(scene.avatar.getDrawable() instanceof BitmapDrawable);
            assertEquals(Color.GREEN, ((BitmapDrawable) scene.avatar.getDrawable()).getBitmap().getPixel(0, 0));
            assertSame(scene.userOriginal, scene.user.getDrawable());
            assertEquals(2, scene.brand.getChildCount());
            assertEquals(2, scene.header.getChildCount());
            scene.hook.close();
            assertSame(scene.original, scene.avatar.getDrawable());
        }
    }

    @Test public void lateNativeDrawablesAndGlobalAvatarChangesRestoreTheLatestNativeImage() throws Throwable {
        try (Scene scene = new Scene()) {
            scene.fire();
            Drawable late = new ColorDrawable(Color.MAGENTA);
            scene.avatar.setImageDrawable(late);
            scene.draw();
            assertEquals(Color.GREEN, imageColor(scene.avatar));
            scene.models.set(settings(""));
            scene.hook.refreshSettings();
            assertSame(late, scene.avatar.getDrawable());
            scene.models.set(settings(avatar(Color.RED)));
            scene.hook.refreshSettings();
            assertEquals(Color.RED, imageColor(scene.avatar));
            scene.models.set(null);
            scene.hook.refreshSettings();
            assertSame(late, scene.avatar.getDrawable());
            assertSame(scene.userOriginal, scene.user.getDrawable());
        }
    }

    @Test public void assistantIdentityChangesNeverOverrideThirdPartyOrUserAvatars() throws Throwable {
        try (Scene scene = new Scene()) {
            scene.owner.tako = false;
            scene.fire();
            assertSame(scene.original, scene.avatar.getDrawable());
            scene.owner.tako = true;
            scene.draw();
            assertEquals(Color.GREEN, imageColor(scene.avatar));
            scene.owner.tako = false;
            scene.draw();
            assertSame(scene.original, scene.avatar.getDrawable());
            assertSame(scene.userOriginal, scene.user.getDrawable());
        }
    }

    @Test public void nativeHeaderRebuildRestoresTheOldImageAndBindsOnlyTheNewOwnedImage() throws Throwable {
        try (Scene scene = new Scene()) {
            scene.fire();
            scene.brand.removeView(scene.avatar);
            SmartImageView replacement = new SmartImageView(scene.context);
            replacement.setId(2131395850);
            Drawable nativeImage = new ColorDrawable(Color.CYAN);
            replacement.setImageDrawable(nativeImage);
            scene.brand.addView(replacement, 0);
            scene.draw();
            assertSame(scene.original, scene.avatar.getDrawable());
            assertEquals(Color.GREEN, imageColor(replacement));
            scene.hook.close();
            assertSame(nativeImage, replacement.getDrawable());
        }
    }

    @Test public void detachedHeadersRestoreAndClosedCallbacksOnlyRunNativeCode() throws Throwable {
        try (Scene scene = new Scene()) {
            scene.fire();
            scene.activity.setContentView(new android.widget.FrameLayout(scene.activity));
            assertSame(scene.original, scene.avatar.getDrawable());
            scene.hook.refreshSettings();
            assertSame(scene.original, scene.avatar.getDrawable());
            scene.hook.close();
            scene.fire();
            assertEquals(2, scene.owner.nativeCalls);
            assertSame(scene.original, scene.avatar.getDrawable());
        }
    }

    @Test public void reusedNativeHeaderReappliesAvatarAfterDetachAndReattachWithoutAnotherCallback() throws Throwable {
        try (Scene scene = new Scene()) {
            scene.fire();
            scene.activity.setContentView(new android.widget.FrameLayout(scene.activity));
            assertSame(scene.original, scene.avatar.getDrawable());
            scene.activity.setContentView(scene.header);
            scene.draw();
            assertEquals(1, scene.owner.nativeCalls);
            assertEquals(Color.GREEN, imageColor(scene.avatar));
        }
    }

    @Test public void ambiguousOrUnownedHeaderResourcesFailClosedWithoutAddingUi() throws Throwable {
        try (Scene scene = new Scene()) {
            scene.header.setId(View.NO_ID);
            scene.fire();
            assertSame(scene.original, scene.avatar.getDrawable());
            scene.header.setId(2131395452);
            scene.draw();
            assertEquals(Color.GREEN, imageColor(scene.avatar));
            LinearLayoutCompat duplicate = new LinearLayoutCompat(scene.context);
            duplicate.setId(2131381509);
            scene.header.addView(duplicate);
            scene.draw();
            assertSame(scene.original, scene.avatar.getDrawable());
            assertEquals(3, scene.header.getChildCount());
        }
    }

    @Test public void aLaterNativeLinkageFailureUnhooksAlreadyInstalledCallbacks() throws Exception {
        HostCandidate primary = new HostCandidate("split_df_a_dex.apk", "classes16.dex",
                "Lcom/ss/android/ugc/aweme/tako/botpage/toptitle/TopTitleAssem;", "onViewCreated", "(Landroid/view/View;)V");
        HostCandidate sheet = new HostCandidate("split_df_a_dex.apk", "classes16.dex",
                "Lcom/ss/android/ugc/aweme/tako/otherpage/sheet/SheetTopTitleAssem;", "onViewCreated", "(Landroid/view/View;)V");
        HostScanReport report = new HostScanReport("tako-avatar-linkage-test", Arrays.asList(
                new RuleScanResult("tako_avatar_header_created", "method_scan", Collections.singletonList(primary)),
                new RuleScanResult("tako_avatar_sheet_header_created", "method_scan", Collections.singletonList(sheet))), false);
        AtomicInteger unhooked = new AtomicInteger();
        ClassLoader broken = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.ss.android.ugc.aweme.tako.otherpage.sheet.SheetTopTitleAssem")) {
                    throw new NoClassDefFoundError("injected native header linkage failure");
                }
                return super.loadClass(name, resolve);
            }
        };
        try {
            TikTokTakoAvatarHook.install(framework(new AtomicReference<>(), unhooked), report,
                    broken, () -> null, null);
            fail("Expected the original native linkage error");
        } catch (NoClassDefFoundError expected) {
            assertEquals("injected native header linkage failure", expected.getMessage());
        }
        assertEquals(1, unhooked.get());
    }

    private static int imageColor(SmartImageView image) {
        assertTrue(image.getDrawable() instanceof BitmapDrawable);
        return ((BitmapDrawable) image.getDrawable()).getBitmap().getPixel(0, 0);
    }

    private static AiModelSettings settings(String avatar) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "global").put("name", "global")
                .put("apiEndpoint", "https://example.com/v1/chat/completions").put("modelName", "test")
                .put("messageContactAvatar", avatar));
        return new AiModelSettings(Collections.singletonList(config), "global");
    }

    private static String avatar(int color) {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        bitmap.recycle();
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static final class Scene implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final Context context = nativeContext(activity);
        final ConstraintLayout header = new ConstraintLayout(context);
        final LinearLayoutCompat brand = new LinearLayoutCompat(context);
        final SmartImageView avatar = new SmartImageView(context);
        final SmartImageView user = new SmartImageView(context);
        final Drawable original = new ColorDrawable(Color.BLUE);
        final Drawable userOriginal = new ColorDrawable(Color.YELLOW);
        final TopTitleAssem owner = new TopTitleAssem();
        final AtomicReference<AiModelSettings> models = new AtomicReference<>(settings(avatar(Color.GREEN)));
        final AtomicReference<XposedInterface.Hooker> interceptor = new AtomicReference<>();
        final TikTokTakoAvatarHook hook;
        final Method method = TopTitleAssem.class.getDeclaredMethod("onViewCreated", View.class);
        Scene() throws Exception {
            header.setId(2131395452); // yib
            brand.setId(2131381509); // obg
            avatar.setId(2131395850); // yt3, assistant brand
            avatar.setImageDrawable(original);
            TextView title = new TextView(context);
            title.setId(2131395849); // yt2
            brand.addView(avatar);
            brand.addView(title);
            user.setImageDrawable(userOriginal);
            header.addView(brand);
            header.addView(user);
            activity.setContentView(header);
            HostCandidate candidate = new HostCandidate("split_df_a_dex.apk", "classes16.dex",
                    "Lcom/ss/android/ugc/aweme/tako/botpage/toptitle/TopTitleAssem;",
                    "onViewCreated", "(Landroid/view/View;)V");
            HostScanReport report = new HostScanReport("tako-avatar-test", Collections.singletonList(
                    new RuleScanResult("tako_avatar_header_created", "method_scan", Collections.singletonList(candidate))), false);
            hook = TikTokTakoAvatarHook.install(framework(interceptor), report, getClass().getClassLoader(), models::get, null);
        }
        void fire() throws Throwable {
            assertNotNull(interceptor.get());
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, called, args) -> {
                        switch (called.getName()) {
                            case "proceed": method.invoke(owner, header); return null;
                            case "getThisObject": return owner;
                            case "getArg": return header;
                            default: throw new UnsupportedOperationException(called.getName());
                        }
                    });
            interceptor.get().intercept(chain);
        }
        void draw() { header.getViewTreeObserver().dispatchOnPreDraw(); }
        @Override public void close() { hook.close(); activity.finish(); }
    }

    private static XposedInterface framework(AtomicReference<XposedInterface.Hooker> target) {
        return framework(target, new AtomicInteger());
    }

    private static XposedInterface framework(AtomicReference<XposedInterface.Hooker> target, AtomicInteger unhooked) {
        XposedInterface.HookHandle handle = (XposedInterface.HookHandle) Proxy.newProxyInstance(
                XposedInterface.HookHandle.class.getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                (proxy, method, args) -> { if ("unhook".equals(method.getName())) unhooked.incrementAndGet(); return null; });
        AtomicReference<XposedInterface.HookBuilder> builder = new AtomicReference<>();
        builder.set((XposedInterface.HookBuilder) Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                new Class<?>[]{XposedInterface.HookBuilder.class}, (proxy, method, args) -> {
                    if ("intercept".equals(method.getName())) { target.set((XposedInterface.Hooker) args[0]); return handle; }
                    return builder.get();
                }));
        return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> "hook".equals(method.getName()) ? builder.get() : null);
    }

    private static Context nativeContext(Context activity) {
        Resources resources = new Resources(activity.getAssets(), activity.getResources().getDisplayMetrics(),
                activity.getResources().getConfiguration()) {
            @Override public String getResourcePackageName(int id) {
                return owned(id) ? "com.zhiliaoapp.musically" : super.getResourcePackageName(id);
            }
            @Override public String getResourceEntryName(int id) {
                switch (id) {
                    case 2131395452: return "yib";
                    case 2131381509: return "obg";
                    case 2131395850: return "yt3";
                    case 2131395849: return "yt2";
                    default: return super.getResourceEntryName(id);
                }
            }
            private boolean owned(int id) {
                return id == 2131395452 || id == 2131381509 || id == 2131395850 || id == 2131395849;
            }
        };
        return new ContextWrapper(activity) { @Override public Resources getResources() { return resources; } };
    }
}
