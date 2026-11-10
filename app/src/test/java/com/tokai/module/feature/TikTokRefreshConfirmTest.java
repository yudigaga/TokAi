package com.tokai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import com.tokai.module.config.ExtendedSettings;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.Collections;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokRefreshConfirmTest {
    public static final class Listener { public Activity LL; int calls; public void click(View view) { calls++; } }
    public static final class Manager { String tab = "HOME"; public String current() { return tab; } }
    private static Manager manager;
    public static Manager manager(Activity activity) { return manager; }
    private RefreshConfirmHook hook(Activity activity, Listener listener) throws Exception {
        var preferences = activity.getSharedPreferences("refresh-test",0);
        preferences.edit().putBoolean("confirm_refresh",true).commit();
        Constructor<RefreshConfirmHook> constructor = RefreshConfirmHook.class.getDeclaredConstructor(
                Method.class, ClassLoader.class, ExtendedSettings.class, Consumer.class);
        constructor.setAccessible(true);
        RefreshConfirmHook hook = constructor.newInstance(Listener.class.getMethod("click",View.class),
                getClass().getClassLoader(),ExtendedSettings.readFrom(preferences),(Consumer<String>) ignored -> { });
        for (String key : new String[]{"tikTok","tabManagerFactory","currentTab"}) {
            Field field = RefreshConfirmHook.class.getDeclaredField(key); field.setAccessible(true);
            field.set(hook,key.equals("tikTok") ? true : key.equals("tabManagerFactory")
                    ? getClass().getMethod("manager",Activity.class) : Manager.class.getMethod("current"));
        }
        return hook;
    }
    private void invoke(RefreshConfirmHook hook, Listener listener, View view) throws Exception {
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class},(proxy,method,args) -> switch(method.getName()) {
                    case "getThisObject" -> listener;
                    case "getArgs" -> Collections.singletonList(view);
                    case "proceed" -> { listener.click(view); yield null; }
                    default -> throw new AssertionError(method.getName());
                });
        Method method = RefreshConfirmHook.class.getDeclaredMethod("intercept",XposedInterface.Chain.class);
        method.setAccessible(true); method.invoke(hook,chain);
    }
    @Test public void repeatedHomeClickWaitsForConfirmationAndCancelDoesNotDispatch() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        Listener listener=new Listener();listener.LL=activity;manager=new Manager();
        RefreshConfirmHook hook=hook(activity,listener);
        invoke(hook,listener,null);assertEquals(0,listener.calls);
        AlertDialog first=ShadowAlertDialog.getLatestAlertDialog();assertTrue(first.isShowing());
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(0,listener.calls);
        invoke(hook,listener,null);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(1,listener.calls);hook.close();activity.finish();
    }
    @Test public void mockClicksAndSwitchingToHomeFromAnotherTabKeepTheirNativePath() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        Listener listener=new Listener();listener.LL=activity;manager=new Manager();
        RefreshConfirmHook hook=hook(activity,listener);View mock=new View(activity);mock.setTag("is_mocked_click");
        invoke(hook,listener,mock);assertEquals(1,listener.calls);
        manager.tab="USER";invoke(hook,listener,null);assertEquals(2,listener.calls);
        hook.close();activity.finish();
    }
}
