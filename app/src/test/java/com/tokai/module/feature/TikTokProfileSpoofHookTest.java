package com.tokai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.HostAdaptation;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokProfileSpoofHookTest {
    public static final class Info { String uid = "self"; public String getUid() { return uid; } }
    public static final class Common { final Info info = new Info(); public Info getUserProfileInfo() { return info; } }
    public static final class Component {
        public View LLJZIJLIL;
        public String LLJLLIL = "relation_info_follower";
        final Common common = new Common();
        public Common wq() { return common; }
    }
    @Test public void changesOnlyTheOwnedTextAndRestoresOnDisableOrAnAccountChange() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Resources base = activity.getResources();
        Context context = new ContextWrapper(activity) {
            final Resources resources = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
                @Override public String getResourcePackageName(int id) { return HostAdaptation.TARGET_PACKAGE; }
                @Override public String getResourceEntryName(int id) { return "t_v"; }
            };
            @Override public Resources getResources() { return resources; }
        };
        FrameLayout root = new FrameLayout(context);
        TextView count = new TextView(context);
        count.setId(2131388294); count.setText("123"); root.addView(count); activity.setContentView(root);
        Component owner = new Component(); owner.LLJZIJLIL = root;
        LocalOptions enabled = new LocalOptions(Map.of("spoof_profile",true,"profile_follower_count","4000000000"));
        TikTokProfileSpoofHook hook = new TikTokProfileSpoofHook(new BehaviorHookGroup("fixture", ignored -> { }),
                Component.class.getMethod("wq"), Component.class.getField("LLJZIJLIL"),
                Component.class.getField("LLJLLIL"), () -> "self", enabled);
        hook.observe(owner);
        assertEquals("4000000000",count.getText().toString());
        assertEquals("self",owner.common.info.uid);
        hook.setOptions(new LocalOptions(Map.of()));
        assertEquals("123",count.getText().toString());
        hook.setOptions(enabled);
        assertEquals("4000000000",count.getText().toString());
        owner.common.info.uid="other"; hook.observe(owner);
        assertEquals("123",count.getText().toString());
        count.setText("456"); owner.common.info.uid="self"; hook.observe(owner);
        count.setText("789"); hook.observe(owner); hook.close();
        assertEquals("789",count.getText().toString());
        activity.finish();
    }
}
