package com.tokai.module.feature;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import com.tokai.module.scan.HostAdaptation;
import com.tokai.module.scan.HostResourceProfile;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostResourceViewTest {
    @Test public void acceptsOnlyTheVerifiedRuntimeResourceAndRejectsLaterReuse() {
        Context base = RuntimeEnvironment.getApplication();
        FixtureResources resources = new FixtureResources(base.getResources());
        Context context = new ContextWrapper(base) {
            @Override public Resources getResources() { return resources; }
        };
        FrameLayout root = new FrameLayout(context);
        View target = new View(context);
        target.setId(com.tokai.module.scan.TikTokFeedResources.id(2131379772));
        root.addView(target);
        assertSame(target, HostResourceCatalog.findVerifiedView(root, 2131379772));
        resources.entries.put(com.tokai.module.scan.TikTokFeedResources.id(2131379772), "reused_for_another_action");
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379772));
        resources.entries.put(com.tokai.module.scan.TikTokFeedResources.id(2131379772), "ixi");
        resources.packageName = "com.tokai.module";
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379772));
    }

    @Test public void unknownAndMissingIdentifiersCannotSelectAView() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(context);
        View unknown = new View(context);
        unknown.setId(0x7f0a9734);
        root.addView(unknown);
        assertNull(HostResourceCatalog.findVerifiedView(root, unknown.getId()));
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379772));
        assertNull(HostResourceCatalog.findVerifiedView(null, 2131379772));
    }

    @Test public void legacyDouyinTablesCannotSelectAnyTikTokProfile() {
        FixtureResources resources = new FixtureResources(RuntimeEnvironment.getApplication().getResources(), HostAdaptation.PROFILE_406);
        assertNull(HostResourceCatalog.profile(resources));
        assertEquals(View.NO_ID, HostResourceCatalog.runtimeId(0x7f0a463c, resources));
        assertEquals(View.NO_ID, HostResourceCatalog.canonicalId(0x7f0a462d, resources));
    }

    @Test public void changedAndIncompleteSentinelsCannotChooseAProfile() {
        Resources base = RuntimeEnvironment.getApplication().getResources();
        FixtureResources changed = new FixtureResources(base);
        changed.entries.put(com.tokai.module.scan.TikTokFeedResources.id(2131374347), "foreign_action");
        assertNull(HostResourceCatalog.profile(changed));
        FixtureResources partial = new FixtureResources(base);
        partial.entries.remove(com.tokai.module.scan.TikTokFeedResources.id(2131415727));
        assertNull(HostResourceCatalog.profile(partial));
    }

    private static final class FixtureResources extends Resources {
        String packageName = HostResourceCatalog.VERIFIED_PACKAGE;
        final Map<Integer, String> entries;
        FixtureResources(Resources base) { this(base, HostAdaptation.PROFILE_TIKTOK); }
        FixtureResources(Resources base, HostAdaptation.Profile profile) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            entries = new HashMap<>(profile.getFeedResources());
        }
        @Override public String getResourcePackageName(int id) {
            if (!entries.containsKey(id)) throw new Resources.NotFoundException();
            return packageName;
        }
        @Override public String getResourceEntryName(int id) {
            String name = entries.get(id);
            if (name == null) throw new Resources.NotFoundException();
            return name;
        }
    }
}
