package com.tokai.module.feature;

import com.tokai.module.config.ExtendedSettings;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class TikTokTopTabFilterTest {
    public static final class Item { public String title; Item(String title) { this.title = title; } }
    public static final class Appearance { public Item item; Appearance(String title) { item = new Item(title); } }
    public static final class Tab {
        public String topTabKey;
        public Appearance appearance;
        Tab(String key, String title) { topTabKey = key; appearance = title == null ? null : new Appearance(title); }
    }
    private Object filter(List<?> source, boolean enabled, String hidden) throws Exception {
        var prefs = RuntimeEnvironment.getApplication().getSharedPreferences("top-tab-test", 0);
        prefs.edit().clear().putBoolean("hide_top_tabs", enabled).putString("hidden_top_tabs", hidden).commit();
        Constructor<TopTabFilterHook> constructor = TopTabFilterHook.class.getDeclaredConstructor(
                Class.class, Field.class, Field.class, ExtendedSettings.class, Consumer.class);
        constructor.setAccessible(true);
        Field key = Tab.class.getField("topTabKey");
        TopTabFilterHook hook = constructor.newInstance(Tab.class, key, key, ExtendedSettings.readFrom(prefs),
                (Consumer<String>) ignored -> { });
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("proceed")) return source;
                    throw new AssertionError(method.getName());
                });
        Method method = TopTabFilterHook.class.getDeclaredMethod("intercept", XposedInterface.Chain.class);
        method.setAccessible(true);
        return method.invoke(hook, chain);
    }
    @Test public void matchesTheNativeKeyAndNestedConfiguredTitleWithoutReorderingModels() throws Exception {
        Tab following = new Tab("Following", "关注"), hot = new Tab("For You", "推荐"), shop = new Tab("Shop", null);
        assertEquals(Arrays.asList(following, hot), filter(Arrays.asList(following, hot, shop), true, "Shop"));
        assertEquals(Arrays.asList(hot, shop), filter(Arrays.asList(following, hot, shop), true, "关注"));
        assertEquals("推荐", TopTabFilterHook.availableTabs().get("For You"));
        assertEquals("Shop", TopTabFilterHook.availableTabs().get("Shop"));
    }
    @Test public void disabledUnknownModelsAndRemovingEveryTabPreserveTheNativeList() throws Exception {
        List<Tab> tabs = Arrays.asList(new Tab("Following", null), new Tab("For You", null));
        assertSame(tabs, filter(tabs, false, "Following"));
        assertSame(tabs, filter(tabs, true, "Following,For You"));
        List<?> unknown = Arrays.asList(tabs.get(0), new Object());
        assertSame(unknown, filter(unknown, true, "Following"));
    }
}
