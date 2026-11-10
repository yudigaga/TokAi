package com.tokai.module.feature;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.tokai.module.config.ExtendedSettings;
import com.tokai.module.config.ComponentHideSettings;
import com.tokai.module.config.CustomViewLocator;
import com.tokai.module.config.ComponentHideSettings.Component;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.tokai.module")
public final class FeedUiHideHookTest {
    // The real host pager owns its cells. Keep the equivalent test owner alive while the
    // module deliberately holds only weak references, including during forced collection.
    private final List<FeedHolderFixture> pagerHolders = new java.util.ArrayList<>();

    @After public void releasePagerFixturesAfterAssertions() {
        java.lang.ref.Reference.reachabilityFence(pagerHolders);
        pagerHolders.clear();
    }

    public static final class FeedHolderFixture {
        public FrameLayout w;
        public FrameLayout f;
        public String aid = "current";
        public AwemeFixture getAweme() { return new AwemeFixture(aid); }

        public void onVideoPageSelected() { }
        public void onImagePageSelected() { }
        public void onVideoResume() { }
        public void onVideoPause() { }
        public void onImageResume() { }
        public void onImagePause() { }
    }

    public static final class AwemeFixture {
        private final String aid;
        AwemeFixture(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }

    public static final class PlayerFixture {
        public void onPause(String aid) { }
        public void onPlaying(String aid) { }
    }

    @Test
    public void matchesThe40_6FeedActionAndAuthorViewIds() {
        int[] resourceIds = {
                2131374327, 2131374339, 2131374340, 2131374360,
                2131371285, 2131371299, 2131371376, 2131371605,
                2131401927, 2131405175, 2131405391,
                2131415727, 2131415055, 2131415033, 2131379772, 2131379761,
                2131370692, 2131386238, 2131380062, 2131380063,
                2131398470, 2131401805, 2131410333
        };

        for (int resourceId : resourceIds) {
            assertTrue("Expected feed control resource ID " + resourceId,
                    FeedUiHideHook.isHiddenResourceId(resourceId));
        }
    }

    @Test
    public void leavesUnrelatedFeedResourcesVisible() {
        assertFalse(FeedUiHideHook.isHiddenResourceId(0x7f0a9734));
        assertFalse(FeedUiHideHook.isHiddenResourceId(0));
    }

    @Test
    public void matchesAccessibleFeedActionsWithoutDependingOnResourceNames() {
        assertTrue(FeedUiHideHook.matchesDescription("点赞 2.4 万"));
        assertTrue(FeedUiHideHook.matchesDescription("评论, 149"));
        assertTrue(FeedUiHideHook.matchesDescription("收藏"));
        assertTrue(FeedUiHideHook.matchesDescription("分享"));
        assertFalse(FeedUiHideHook.matchesDescription("Demon 发布了新作品"));
        assertFalse(FeedUiHideHook.matchesDescription("视频播放"));
    }

    @Test
    public void hidesVideoAndImageActionsAgainAfterHostRebindAndRestoresOnClose() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView digg = new TextView(activity);
        digg.setId(2131374327);
        digg.setVisibility(View.VISIBLE);
        root.addView(digg, new FrameLayout.LayoutParams(120, 48));
        activity.setContentView(root);

        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), enabledSettings(), ignored -> { });

        intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, digg.getVisibility());

        digg.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, digg.getVisibility());
        assertTrue(hook.getPreDrawRechecks() > 0);
        assertEquals(1, hook.getHostStateResets());

        digg.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, digg.getVisibility());
        assertEquals(2, hook.getHostStateResets());

        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, digg.getVisibility());
        activity.finish();
    }

    @Test
    public void appliesDyydsStyleRightActionOpacityAndRestoresAtFullOpacity() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView digg = new TextView(activity);
        digg.setId(2131374327);
        root.addView(digg, new FrameLayout.LayoutParams(120, 48));
        activity.setContentView(root);

        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), settings(false, 35), ignored -> { });
        intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();

        assertEquals(View.VISIBLE, digg.getVisibility());
        assertEquals(0.35f, digg.getAlpha(), 0.001f);

        hook.setSettings(settings(false, 0));
        idleMainLooper();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, digg.getVisibility());
        assertEquals(0.0f, digg.getAlpha(), 0.001f);

        hook.setSettings(settings(false, 100));
        idleMainLooper();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(1.0f, digg.getAlpha(), 0.001f);
        hook.close();
        activity.finish();
    }

    @Test
    public void hidesAnonymousWidgetChildrenAndAuthorButKeepsMediaVisible() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View media = new View(activity);
        root.addView(media);
        FrameLayout widgets = new FrameLayout(activity);
        widgets.setId(2131362267);
        widgets.setAlpha(0.8f);
        widgets.setClickable(true);
        widgets.setFocusable(false);
        root.addView(widgets);
        widgets.addView(new View(activity));
        widgets.addView(new TextView(activity));
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        widgets.addView(right);
        right.addView(new View(activity));
        TextView author = new TextView(activity);
        author.setId(2131398470);
        author.setAlpha(0.7f);
        author.setClickable(false);
        author.setFocusable(true);
        root.addView(author);
        activity.setContentView(root);
        FeedHolderFixture holder = holder(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());

        intercept(hooks.get("TokAi.FeedUi.ImageResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, widgets.getVisibility());
        assertFalse(widgets.isClickable());
        assertFalse(widgets.isFocusable());
        assertEquals(View.INVISIBLE, author.getVisibility());
        assertFalse(author.isFocusable());
        assertEquals(View.VISIBLE, media.getVisibility());
        assertEquals(View.VISIBLE, root.getVisibility());

        hook.setSettings(settings(false, 35));
        idleMainLooper();
        assertEquals(View.VISIBLE, widgets.getVisibility());
        assertEquals(0.8f, widgets.getAlpha(), 0.001f);
        assertEquals(0.35f, right.getAlpha(), 0.001f);
        assertEquals(View.VISIBLE, author.getVisibility());
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertTrue(author.isFocusable());

        hook.setSettings(settings(false, 100));
        idleMainLooper();
        assertEquals(View.VISIBLE, widgets.getVisibility());
        assertEquals(0.8f, widgets.getAlpha(), 0.001f);
        assertEquals(1f, right.getAlpha(), 0.001f);
        assertTrue(widgets.isClickable());
        assertFalse(widgets.isFocusable());
        assertEquals(View.VISIBLE, author.getVisibility());
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertFalse(author.isClickable());
        assertTrue(author.isFocusable());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void appliesOpacityOnceToRightRegionWithoutChangingAuthorOrChildAlpha() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        right.setAlpha(0.6f);
        root.addView(right);
        TextView star = new TextView(activity);
        star.setId(2131374340);
        star.setAlpha(0.8f);
        right.addView(star);
        right.addView(new View(activity));
        TextView author = new TextView(activity);
        author.setId(2131398470);
        author.setAlpha(0.7f);
        root.addView(author);
        root.setClickable(true);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 35));
        FeedHolderFixture holder = holder(root);

        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(0.21f, right.getAlpha(), 0.001f);
        assertEquals(0.8f, star.getAlpha(), 0.001f);
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertEquals(1.0f, root.getAlpha(), 0.001f);
        assertEquals(1, hook.getHiddenViews());

        right.setAlpha(1f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(0.21f, right.getAlpha(), 0.001f);
        assertEquals(0.8f, star.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(0.6f, right.getAlpha(), 0.001f);
        assertTrue(root.isClickable());
        activity.finish();
    }

    @Test
    public void preDrawReappliesCachedTargetsWithoutSearchingTheViewTreeAgain() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        root.addView(right);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 35));
        FeedHolderFixture holder = holder(root);

        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        int searchesAfterBind = hook.getViewSearches();
        assertEquals(1, searchesAfterBind);

        for (int frame = 0; frame < 12; frame++) {
            right.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.35f, right.getAlpha(), 0.001f);
        }

        assertEquals(searchesAfterBind, hook.getViewSearches());
        assertTrue(hook.getPreDrawRechecks() >= 12);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void aCustomHideRetainsPriorityOverTheSameCachedComponentOnStableFrames() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View feedback = new View(activity);
        feedback.setId(2131371909);
        root.addView(feedback);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(root.getRootView(), root, feedback);
        assertNotNull(locator);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "128")
                .putString(ComponentHideSettings.CUSTOM_VIEWS,
                        CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 12; frame++) {
                feedback.setVisibility(View.VISIBLE);
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("Custom hide must win even when the window observer runs before the card", View.GONE,
                        feedback.getVisibility());
            }
            preferences.edit().remove(ComponentHideSettings.CUSTOM_VIEWS).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, feedback.getVisibility());
            assertEquals(128 / 255f, feedback.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void largeStableTreeKeepsStructureWorkBoundedAcross300Frames() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        root.addView(right);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 35));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            int searches = hook.getViewSearches();
            long before = hook.getStructureNodeChecks();
            for (int frame = 0; frame < 300; frame++) {
                right.setAlpha(1f);
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("Cached styles must still be maintained every frame", 0.35f, right.getAlpha(), 0.001f);
            }
            long checks = hook.getStructureNodeChecks() - before;
            System.out.println("Feed stable 300 frames: structureNodeChecks=" + checks
                    + ", discoveries=" + (hook.getViewSearches() - searches));
            assertEquals(searches, hook.getViewSearches());
            assertTrue("Stable large trees retain the 16-frame fallback with far fewer checks; actual=" + checks,
                    checks > 0 && checks <= 300L * 70);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void unchangedGlobalLayoutsDoNotRediscoverTargets() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View target = new View(activity);
        target.setId(2131374327);
        root.addView(target);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            int searches = hook.getViewSearches();
            for (int frame = 0; frame < 30; frame++) {
                root.getViewTreeObserver().dispatchOnGlobalLayout();
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(View.INVISIBLE, target.getVisibility());
            }
            System.out.println("Feed unchanged 30 layouts: discoveries=" + (hook.getViewSearches() - searches));
            assertEquals("Layout notifications alone must not rediscover an unchanged tree", searches, hook.getViewSearches());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void largeWindowCustomTargetKeepsChecksAndLayoutRediscoveryBounded() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        page.addView(card);
        for (int index = 0; index < 1024; index++) page.addView(new View(activity));
        TextView custom = new TextView(activity);
        custom.setId(android.R.id.text1);
        page.addView(custom);
        activity.setContentView(page);
        CustomViewLocator locator = CustomViewLocator.capture(page.getRootView(), card, custom);
        assertNotNull(locator);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(card));
            idleMainLooper();
            int searches = hook.getViewSearches();
            long before = hook.getStructureNodeChecks();
            for (int frame = 0; frame < 300; frame++) {
                custom.setVisibility(View.VISIBLE);
                page.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(View.GONE, custom.getVisibility());
            }
            long checks = hook.getStructureNodeChecks() - before;
            int stableDiscoveries = hook.getViewSearches() - searches;
            for (int frame = 0; frame < 30; frame++) {
                page.getViewTreeObserver().dispatchOnGlobalLayout();
                page.getViewTreeObserver().dispatchOnPreDraw();
            }
            int layoutDiscoveries = hook.getViewSearches() - searches - stableDiscoveries;
            System.out.println("Feed window 300 frames: structureNodeChecks=" + checks
                    + ", discoveries=" + stableDiscoveries + "; unchanged 30 layouts: discoveries=" + layoutDiscoveries);
            assertEquals(0, stableDiscoveries);
            assertEquals("Unchanged window layouts must not restart custom locator discovery", 0, layoutDiscoveries);
            assertTrue("Stable card and window checks must stay below their old per-frame budgets; actual=" + checks,
                    checks > 0 && checks <= 300L * 80);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void largeTreeFindsLateInsertionAndReusedTextWithin16FramesWithoutLayout() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        FrameLayout tail = new FrameLayout(activity);
        TextView label = new TextView(activity);
        tail.addView(label);
        root.addView(tail);
        int[] hostHierarchyEvents = {0};
        tail.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) { hostHierarchyEvents[0]++; }
            @Override public void onChildViewRemoved(View parent, View child) { hostHierarchyEvents[0]++; }
        });
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 20; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            View inserted = new View(activity);
            inserted.setId(2131374327);
            tail.addView(inserted);
            for (int frame = 0; frame < 16; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Insertion at the end of a large tree must be detected without a layout event", View.INVISIBLE, inserted.getVisibility());
            assertEquals("The host's hierarchy observer must remain installed", 1, hostHierarchyEvents[0]);
            label.setText("分享");
            for (int frame = 0; frame < 16; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.INVISIBLE, label.getVisibility());
            label.setText("正常文案");
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Previously matched target identity changes must restore on the next frame", View.VISIBLE, label.getVisibility());
            hook.close();
            idleMainLooper();
            assertEquals(View.VISIBLE, inserted.getVisibility());
            long checks = hook.getStructureNodeChecks();
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(checks, hook.getStructureNodeChecks());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void quietLargeTreeFinishesCheckingLateInsertionAfterOneLayoutAndDraw() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        FrameLayout tail = new FrameLayout(activity);
        root.addView(tail);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        java.util.Set<View> requestedFrames = new java.util.LinkedHashSet<>();
        hook.requestStructureFrame = view -> requestedFrames.add(view.getRootView());
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            requestedFrames.clear();
            View inserted = new View(activity);
            inserted.setId(2131374327);
            tail.addView(inserted);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            long checks = hook.getStructureNodeChecks();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(hook.getStructureNodeChecks() - checks <= 256);

            // The host is now idle. Only redraws explicitly requested by the hook may run.
            int extraFrames = 0;
            while (!requestedFrames.isEmpty()) {
                assertTrue("A layout sweep must stop after a bounded number of frames", extraFrames++ < 16);
                java.util.List<View> frames = new java.util.ArrayList<>(requestedFrames);
                requestedFrames.clear();
                for (View frame : frames) {
                    checks = hook.getStructureNodeChecks();
                    frame.getViewTreeObserver().dispatchOnPreDraw();
                    assertTrue("Each requested frame retains the structure budget",
                            hook.getStructureNodeChecks() - checks <= 256);
                }
            }
            assertEquals("An idle host must not leave a late feed control visible", View.INVISIBLE, inserted.getVisibility());
            assertTrue("The tail was outside the first frame's budget", extraFrames > 0);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void quietLargeWindowFindsLateCustomTargetAfterItsInitialRetriesAreExhausted() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        page.addView(card);
        for (int index = 0; index < 1024; index++) page.addView(new View(activity));
        FrameLayout tail = new FrameLayout(activity);
        TextView custom = new TextView(activity);
        custom.setId(android.R.id.text1);
        tail.addView(custom);
        page.addView(tail);
        activity.setContentView(page);
        CustomViewLocator locator = CustomViewLocator.capture(page.getRootView(), card, custom);
        assertNotNull(locator);
        tail.removeView(custom);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        java.util.Set<View> requestedFrames = new java.util.LinkedHashSet<>();
        hook.requestStructureFrame = view -> requestedFrames.add(view.getRootView());
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(card));
            idleMainLooper();
            for (int frame = 0; frame < 8; frame++) page.getViewTreeObserver().dispatchOnPreDraw();
            requestedFrames.clear();
            tail.addView(custom);
            page.getViewTreeObserver().dispatchOnGlobalLayout();
            page.getViewTreeObserver().dispatchOnPreDraw();
            drainRequestedStructureFrames(hook, requestedFrames, 512);
            assertEquals("An idle window must rediscover a custom view inserted after retries ran out",
                    View.GONE, custom.getVisibility());

            int searches = hook.getViewSearches();
            page.getViewTreeObserver().dispatchOnGlobalLayout();
            page.getViewTreeObserver().dispatchOnPreDraw();
            drainRequestedStructureFrames(hook, requestedFrames, 512);
            assertEquals("Finishing a stable layout sweep must not rediscover targets", searches, hook.getViewSearches());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void closingDuringALayoutSweepDoesNotContinueCheckingOrRequestMoreFrames() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        java.util.Set<View> requestedFrames = new java.util.LinkedHashSet<>();
        hook.requestStructureFrame = view -> requestedFrames.add(view.getRootView());
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            requestedFrames.clear();
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse("A large stable layout needs a bounded follow-up sweep", requestedFrames.isEmpty());
            hook.close();
            long checks = hook.getStructureNodeChecks();
            drainRequestedStructureFrames(hook, requestedFrames, 0);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(checks, hook.getStructureNodeChecks());
            assertTrue(requestedFrames.isEmpty());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    private static void drainRequestedStructureFrames(FeedUiHideHook hook, java.util.Set<View> requestedFrames,
                                                       int perFrameBudget) {
        int extraFrames = 0;
        while (!requestedFrames.isEmpty()) {
            assertTrue("A layout sweep must stop after a bounded number of frames", extraFrames++ < 16);
            java.util.List<View> frames = new java.util.ArrayList<>(requestedFrames);
            requestedFrames.clear();
            for (View frame : frames) {
                long checks = hook.getStructureNodeChecks();
                frame.getViewTreeObserver().dispatchOnPreDraw();
                assertTrue("Requested frames must retain the per-monitor structure budget",
                        hook.getStructureNodeChecks() - checks <= perFrameBudget);
            }
        }
    }

    @Test
    public void layoutInvalidatesCachedTargetsForLateInsertedControls() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout nested = new FrameLayout(activity);
        root.addView(nested);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        int initialSearches = hook.getViewSearches();

        View lateControl = new View(activity);
        lateControl.setId(2131374327);
        nested.addView(lateControl);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, lateControl.getVisibility());
        assertEquals(initialSearches + 1, hook.getViewSearches());
        for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(initialSearches + 1, hook.getViewSearches());

        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, lateControl.getVisibility());
        int searchesAfterClose = hook.getViewSearches();
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(searchesAfterClose, hook.getViewSearches());
        activity.finish();
    }

    @Test
    public void delayedProgressEscapesPreviouslyHiddenContainerWithoutAnotherLayoutEvent() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout bottomTools = new FrameLayout(activity);
        bottomTools.setId(2131367357);
        root.addView(bottomTools);
        View tool = new View(activity);
        bottomTools.addView(tool);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        for (Component component : Component.values()) editor.putString(component.key, "-2");
        editor.putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertEquals(View.GONE, bottomTools.getVisibility());
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();

            View progress = new View(activity);
            progress.setId(2131378139);
            progress.setAlpha(0.7f);
            bottomTools.addView(progress);
            root.getViewTreeObserver().dispatchOnPreDraw();

            assertTrue("A kept progress bar must not inherit a cached container hide", progress.isShown());
            assertEquals(0.7f, progress.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedProgressPreservesHostRevealOfAnInitiallyGoneContainer() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        tools.setVisibility(View.GONE);
        tools.setAlpha(0f);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            View progress = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
            tools.addView(progress);
            tools.setVisibility(View.VISIBLE);
            tools.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue("Restoring a shared container must preserve the host's later reveal", progress.isShown());
            assertEquals("The host's fade-in must not be replaced by the initial zero alpha", 1f,
                    tools.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertTrue(progress.isShown());
            assertEquals(View.VISIBLE, tool.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void switchingGoneToOpacityKeepsHostFadeInBeforeTheNextDraw() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        like.setAlpha(0f);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            like.setVisibility(View.VISIBLE);
            like.setAlpha(1f);
            preferences.edit().putString(Component.LIKE.key, "128").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(128 / 255f, like.getAlpha(), 0.001f);
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(1f, like.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void alternateHostProgressViewsKeepTheirBranchAndHostVisibility() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        View line = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
        View alternate = new com.ss.android.ugc.aweme.shortvideo.ui.NewVideoPlayerProgressbar(activity);
        alternate.setVisibility(View.GONE);
        tools.addView(line);
        tools.addView(alternate);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        for (Component component : Component.values()) editor.putString(component.key, "-2");
        editor.putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertTrue("The line progress variant must escape bottom-tools hiding", line.isShown());
            assertEquals(View.GONE, alternate.getVisibility());
            assertEquals(View.GONE, tool.getVisibility());
            alternate.setVisibility(View.VISIBLE);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue("Host reveal of a kept alternate progress must remain visible", alternate.isShown());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressValueAppliesToProSeekbarOutsideSelectedCardAndRestoresOnPause() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        page.addView(card);
        View progress = new com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView(activity);
        progress.setAlpha(1f);
        page.addView(progress);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(card));
            idleMainLooper();
            assertEquals(124 / 255f, progress.getAlpha(), 0.001f);
            for (int frame = 0; frame < 12; frame++) page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(124 / 255f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals(1f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(false);
            idleMainLooper();
            assertEquals(124 / 255f, progress.getAlpha(), 0.001f);
            preferences.edit().putString(Component.PROGRESS.key, "-2").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.GONE, progress.getVisibility());
            preferences.edit().putString(Component.PROGRESS.key, "-1").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.INVISIBLE, progress.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, progress.getVisibility());
            assertEquals(1f, progress.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressAt124FollowsHostFadeInAndRestoresTheLatestHostAlpha() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View progress = new com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar(activity);
        progress.setVisibility(View.GONE);
        progress.setAlpha(0f);
        root.addView(progress);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        for (Component component : Component.values()) editor.putString(component.key, "-2");
        editor.putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            progress.setVisibility(View.VISIBLE);
            progress.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(progress.isShown());
            assertEquals("Progress opacity must use the host's later fade-in", 124 / 255f,
                    progress.getAlpha(), 0.001f);
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Reapplying must not compound opacity each frame", 124 / 255f,
                    progress.getAlpha(), 0.001f);
            progress.setAlpha(0.5f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.5f * 124 / 255f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals("Pausing must restore the latest host alpha", 0.5f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(false);
            idleMainLooper();
            assertEquals(0.5f * 124 / 255f, progress.getAlpha(), 0.001f);
            progress.setVisibility(View.GONE);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("An opacity setting must not force a host-hidden progress visible",
                    View.GONE, progress.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.GONE, progress.getVisibility());
            assertEquals(0.5f, progress.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressValuesKeepOpacityInvisibilityAndLayoutRemovalDistinct() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        android.widget.LinearLayout column = new android.widget.LinearLayout(activity);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(column);
        View progress = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
        column.addView(progress, new android.widget.LinearLayout.LayoutParams(100, 12));
        column.addView(new View(activity), new android.widget.LinearLayout.LayoutParams(100, 8));
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            int[] values = {-2, -1, 0, 64, 124, 255};
            int[] visibility = {View.GONE, View.INVISIBLE, View.VISIBLE, View.VISIBLE, View.VISIBLE, View.VISIBLE};
            int[] heights = {8, 20, 20, 20, 20, 20};
            float[] alphas = {1f, 1f, 0f, 64 / 255f, 124 / 255f, 1f};
            for (int index = 0; index < values.length; index++) {
                preferences.edit().putString(Component.PROGRESS.key, Integer.toString(values[index])).commit();
                hook.setSettings(ExtendedSettings.readFrom(preferences));
                idleMainLooper();
                column.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST));
                assertEquals("visibility for " + values[index], visibility[index], progress.getVisibility());
                assertEquals("layout height for " + values[index], heights[index], column.getMeasuredHeight());
                assertEquals("alpha for " + values[index], alphas[index], progress.getAlpha(), 0.001f);
            }
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressMountAt124FollowsHostAlphaAndKeepsHiddenToolsSeparate() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        FrameLayout mount = new FrameLayout(activity);
        mount.setId(2131378139);
        mount.setAlpha(0f);
        tools.addView(mount);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            mount.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(124 / 255f, mount.getAlpha(), 0.001f);
            assertTrue(mount.isShown());
            assertEquals(View.GONE, tool.getVisibility());
            preferences.edit().putString(Component.PROGRESS.key, "255").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(1f, mount.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressDiagnosticsCaptureEnabledAndDisabledStatesAndStopOnClose() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        root.addView(new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity));
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        java.util.ArrayList<String> logs = new java.util.ArrayList<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), ExtendedSettings.readFrom(preferences), logs::add);
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            idleForMillis(3100L);
            assertEquals(3, logs.stream().filter(value -> value.contains(" reason=selection ")).count());
            assertTrue(logs.stream().anyMatch(value -> value.contains("LineProgressBar")));
            assertTrue(logs.stream().anyMatch(value -> value.contains("progress=124")));
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertTrue(logs.stream().anyMatch(value -> value.contains("reason=settings")
                    && value.contains("enabled=false")));
            hook.close();
            idleMainLooper();
            int afterClose = logs.size();
            idleForMillis(10000L);
            assertEquals("Close must cancel delayed diagnostic samples", afterClose, logs.size());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressDiagnosticSelectionBudgetReservesSamplesForSettingsChanges() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        java.util.ArrayList<String> logs = new java.util.ArrayList<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), ExtendedSettings.readFrom(preferences), logs::add);
        FeedHolderFixture holder = holder(root);
        try {
            for (int card = 0; card < 8; card++) {
                holder.aid = "card" + card;
                intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
                idleMainLooper();
                idleForMillis(3100L);
            }
            assertEquals(18, logs.stream().filter(value -> value.contains(" reason=selection ")).count());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            idleForMillis(3100L);
            assertEquals(3, logs.stream().filter(value -> value.contains(" reason=settings ")).count());
            assertFalse(logs.stream().anyMatch(value -> value.contains("aid=") || value.contains("card7")));
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void chapterProgressUsesItsIndependentSettingInsideSharedTools() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        View chapter = new com.ss.android.ugc.aweme.feed.widget.ChapterOuterSeekBar(activity);
        chapter.setAlpha(0.75f);
        tools.addView(chapter);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertTrue("Retained chapter progress must escape shared container hiding", chapter.isShown());
            assertEquals(0.75f, chapter.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
            preferences.edit().putString(Component.PROGRESS.key, "-2").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertFalse(chapter.isShown());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertTrue(chapter.isShown());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedFeedbackInsertedIntoUntargetedBranchIsHiddenBeforeDrawing() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout nested = new FrameLayout(activity);
        root.addView(nested);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            View feedback = new View(activity);
            feedback.setId(2131371909);
            nested.addView(feedback);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Late feedback must join the active hide targets", View.GONE, feedback.getVisibility());
            int discovered = hook.getViewSearches();
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Stable frames must still reuse cached targets", discovered, hook.getViewSearches());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedRecommendationTagUsesFeedbackSettingWithoutMatchingCaptionText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        android.widget.TextView caption = new android.widget.TextView(activity);
        caption.setText("这段视频获得了100人推荐");
        root.addView(caption);
        View progress = new View(activity);
        progress.setId(2131378139);
        root.addView(progress);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            com.ss.android.ugc.aweme.base.ui.TagLayout tag = new com.ss.android.ugc.aweme.base.ui.TagLayout(activity);
            android.widget.TextView count = new android.widget.TextView(activity);
            count.setText("100人推荐");
            tag.addView(count);
            root.addView(tag);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, tag.getVisibility());
            assertTrue(progress.isShown());
            assertTrue(caption.isShown());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, tag.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedDeclarationAndAnchorStripFollowTheirOwnSettingsAndKeepProgress() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View progress = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
        root.addView(progress);
        TextView caption = new TextView(activity);
        caption.setText("作者声明和位置只是视频里的文案");
        root.addView(caption);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2")
                .putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            View declaration = new View(activity);
            declaration.setId(2131378332);
            android.widget.LinearLayout declarationBackground = new android.widget.LinearLayout(activity);
            declarationBackground.setBackgroundColor(android.graphics.Color.BLACK);
            declarationBackground.addView(declaration);
            FrameLayout anchors = new FrameLayout(activity);
            anchors.setId(2131378377);
            TextView poi = new TextView(activity);
            poi.setText("泰州市·兴化市");
            anchors.addView(poi);
            FrameLayout anonymousAnchor = new com.ss.android.ugc.aweme.feed.anchor.framework.base.MaxWidthFrameLayout(activity);
            FrameLayout legacyAnchor = new com.ss.android.ugc.aweme.base.ui.FeedAnchorLayout(activity);
            root.addView(declarationBackground);
            root.addView(anchors);
            root.addView(anonymousAnchor);
            root.addView(legacyAnchor);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse(declaration.isShown());
            assertEquals(View.GONE, declarationBackground.getVisibility());
            assertEquals(View.GONE, anchors.getVisibility());
            assertEquals(View.GONE, anonymousAnchor.getVisibility());
            assertEquals(View.GONE, legacyAnchor.getVisibility());
            assertTrue(progress.isShown());
            assertTrue(caption.isShown());
            preferences.edit().putString(Component.FEEDBACK.key, "255").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, declaration.getVisibility());
            assertEquals(View.VISIBLE, declarationBackground.getVisibility());
            assertEquals(View.GONE, anchors.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void declarationBackgroundChangesInvalidateTargetsWithoutChangingTheTree() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(activity);
        View declaration = new View(activity);
        declaration.setId(2131378332);
        wrapper.addView(declaration);
        root.addView(wrapper);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertEquals(View.GONE, declaration.getVisibility());
            assertEquals(View.VISIBLE, wrapper.getVisibility());
            wrapper.setBackgroundColor(android.graphics.Color.BLACK);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("A newly bound declaration background must also be hidden", View.GONE, wrapper.getVisibility());
            wrapper.setBackground(null);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("An anonymous wrapper without a background is no longer a hide target", View.VISIBLE, wrapper.getVisibility());
            assertEquals(View.GONE, declaration.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void lateTextBindingOnExistingViewInvalidatesLegacyMatching() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView label = new TextView(activity);
        root.addView(label);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertEquals(View.VISIBLE, label.getVisibility());
            label.setText("分享");
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.INVISIBLE, label.getVisibility());
            label.setText("正常文案");
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.VISIBLE, label.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void reusedUntargetedViewAndEqualSizedBranchReplacementInvalidateDiscovery() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout branch = new FrameLayout(activity);
        root.addView(branch);
        View reused = new View(activity);
        branch.addView(reused);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            reused.setId(2131371909);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, reused.getVisibility());
            FrameLayout replacement = new FrameLayout(activity);
            View feedback = new View(activity);
            feedback.setId(2131371909);
            replacement.addView(feedback);
            root.removeView(branch);
            root.addView(replacement);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.VISIBLE, reused.getVisibility());
            assertEquals(View.GONE, feedback.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void customTargetInsertedAfterRetriesAreExhaustedIsDiscoveredWithoutLayout() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout branch = new FrameLayout(activity);
        root.addView(branch);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        android.widget.TextView target = new android.widget.TextView(activity);
        target.setText("延迟插入的自定义控件");
        branch.addView(target);
        com.tokai.module.config.CustomViewLocator locator = com.tokai.module.config.CustomViewLocator.capture(
                root.getRootView(), root, target);
        branch.removeView(target);
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                com.tokai.module.config.CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 20; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            branch.addView(target);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, target.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void reusedTargetIdDescriptionAndTextAreRevalidatedWithoutLayout() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View byId = new View(activity);
        byId.setId(2131374327);
        View byDescription = new View(activity);
        byDescription.setContentDescription("like");
        TextView byText = new TextView(activity);
        byText.setText("comment");
        root.addView(byId);
        root.addView(byDescription);
        root.addView(byText);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.INVISIBLE, byId.getVisibility());
        assertEquals(View.INVISIBLE, byDescription.getVisibility());
        assertEquals(View.INVISIBLE, byText.getVisibility());
        int initialSearches = hook.getViewSearches();

        byId.setId(View.NO_ID);
        byDescription.setContentDescription("video playback");
        byText.setText("video playback");
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, byId.getVisibility());
        assertEquals(View.VISIBLE, byDescription.getVisibility());
        assertEquals(View.VISIBLE, byText.getVisibility());
        assertEquals(initialSearches + 1, hook.getViewSearches());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void unresolvedCustomLocatorRetriesThenStopsUntilLayoutChanges() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        target.setId(android.R.id.text1);
        root.addView(target);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(activity.getWindow().getDecorView(), root, target);
        assertNotNull(locator);
        root.removeView(target);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        int initialSearches = hook.getViewSearches();

        for (int frame = 0; frame < 20; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        int afterRetries = hook.getViewSearches();
        assertTrue(afterRetries > initialSearches);
        assertTrue(afterRetries <= initialSearches + 7);
        for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(afterRetries, hook.getViewSearches());

        root.addView(target);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, target.getVisibility());
        int afterInsert = hook.getViewSearches();
        for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(afterInsert, hook.getViewSearches());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, target.getVisibility());
        activity.finish();
    }

    @Test
    public void reusedCustomTargetResourceIdRestoresOldViewAndFindsReplacement() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        target.setId(android.R.id.text1);
        root.addView(target);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(activity.getWindow().getDecorView(), root, target);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, target.getVisibility());

        target.setId(android.R.id.text2);
        TextView replacement = new TextView(activity);
        replacement.setId(android.R.id.text1);
        root.addView(replacement);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, target.getVisibility());
        assertEquals(View.GONE, replacement.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, replacement.getVisibility());
        activity.finish();
    }

    @Test
    public void pauseRestoresStateAndResumeStartsMonitoringAgain() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        right.setAlpha(0.75f);
        right.setClickable(true);
        right.setFocusable(true);
        root.addView(right);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(true, 40));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertFalse(right.isClickable());

        right.setVisibility(View.VISIBLE);
        right.setAlpha(1f);
        right.setClickable(true);
        right.setFocusable(true);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertEquals(0.3f, right.getAlpha(), 0.001f);
        assertFalse(right.isClickable());
        assertFalse(right.isFocusable());

        intercept(hooks.get("TokAi.FeedUi.VideoPause"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, right.getVisibility());
        assertEquals(0.75f, right.getAlpha(), 0.001f);
        assertTrue(right.isClickable());
        assertTrue(right.isFocusable());
        int rechecks = hook.getPreDrawRechecks();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(rechecks, hook.getPreDrawRechecks());
        hook.setSettings(settings(true, 20));
        idleMainLooper();
        assertEquals(View.VISIBLE, right.getVisibility());

        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertEquals(0.15f, right.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void liveEnableWorksOnSelectedCardAndDisableRemovesDrawObserver() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View button = new View(activity);
        button.setId(2131374327);
        root.addView(button);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 100));
        intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, button.getVisibility());

        hook.setSettings(enabledSettings());
        idleMainLooper();
        assertEquals(View.INVISIBLE, button.getVisibility());
        root.getViewTreeObserver().dispatchOnPreDraw();
        int rechecks = hook.getPreDrawRechecks();
        hook.setSettings(settings(false, 100));
        idleMainLooper();
        assertEquals(View.VISIBLE, button.getVisibility());
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(rechecks, hook.getPreDrawRechecks());

        hook.setSettings(settings(false, 60));
        idleMainLooper();
        assertEquals(0.6f, button.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(1f, button.getAlpha(), 0.001f);
        activity.finish();
    }

    @Test
    public void closedHookIgnoresQueuedAndLateLifecycleCallbacks() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View button = new View(activity);
        button.setId(2131374327);
        root.addView(button);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder);
        hook.close();
        idleMainLooper();
        assertEquals(0, hook.getCardScans());
        assertEquals(View.VISIBLE, button.getVisibility());
        int callbacks = hook.getSelectedCallbacks();
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        hook.setSettings(enabledSettings());
        idleMainLooper();
        assertEquals(callbacks, hook.getSelectedCallbacks());
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, button.getVisibility());
        activity.finish();
    }

    @Test
    public void componentsHaveIndependentValuesAndDoNotHideArbitraryText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View avatar = new View(activity);
        avatar.setId(2131366240);
        avatar.setAlpha(0.8f);
        root.addView(avatar);
        View like = new View(activity);
        like.setId(2131374347);
        like.setClickable(true);
        root.addView(like);
        TextView description = new TextView(activity);
        description.setText("点赞，评论和分享都是视频内容");
        root.addView(description);
        View author = new View(activity);
        author.setId(2131398470);
        author.setVisibility(View.GONE);
        root.addView(author);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.AVATAR.key, "128")
                .putString(Component.LIKE.key, "-2").putString(Component.AUTHOR.key, "128").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(0.8f * 128 / 255f, avatar.getAlpha(), 0.001f);
        assertEquals(View.GONE, like.getVisibility());
        assertFalse(like.isClickable());
        assertEquals(View.GONE, author.getVisibility());
        assertEquals(View.VISIBLE, description.getVisibility());
        assertEquals(View.VISIBLE, root.getVisibility());
        like.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, like.getVisibility());

        preferences.edit().putString(Component.LIKE.key, "255")
                .putBoolean(ComponentHideSettings.ENABLED, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertTrue(like.isClickable());
        assertEquals(0.8f, avatar.getAlpha(), 0.001f);
        assertEquals(View.GONE, author.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void independentHidePreservesProgressInsideAnotherComponentContainer() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout bottomTools = new FrameLayout(activity);
        bottomTools.setId(2131367357);
        root.addView(bottomTools);
        View tool = new View(activity);
        bottomTools.addView(tool);
        View progress = new View(activity);
        progress.setId(2131378139);
        progress.setAlpha(0.7f);
        progress.setClickable(true);
        bottomTools.addView(progress);
        View like = new View(activity);
        like.setId(2131374347);
        root.addView(like);
        View comment = new View(activity);
        comment.setId(2131371414);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putString(Component.COMMENT.key, "-2")
                .putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();

        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(View.GONE, tool.getVisibility());
        assertTrue(progress.isShown());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        assertTrue(progress.isClickable());
        preferences.edit().putString(Component.PROGRESS.key, "128").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertTrue(progress.isShown());
        assertEquals(0.7f * 128 / 255f, progress.getAlpha(), 0.001f);
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "-2").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, bottomTools.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false)
                .putString(Component.PROGRESS.key, "255").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, bottomTools.getVisibility());
        assertEquals(View.GONE, tool.getVisibility());
        assertTrue(progress.isShown());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, tool.getVisibility());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        activity.finish();
    }

    @Test
    public void allMatchingControlsAreHiddenWhenHostDuplicatesAResourceId() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View first = new View(activity);
        first.setId(2131374347);
        root.addView(first);
        View second = new View(activity);
        second.setId(2131374347);
        root.addView(second);
        View progress = new View(activity);
        progress.setId(2131378139);
        root.addView(progress);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, first.getVisibility());
        assertEquals(View.GONE, second.getVisibility());
        assertTrue(progress.isShown());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, first.getVisibility());
        assertEquals(View.VISIBLE, second.getVisibility());
        activity.finish();
    }

    @Test
    public void uniformOpacityOverridesIndividualsAndZeroRemainsClickable() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        like.setClickable(true);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "0").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(0f, like.getAlpha(), 0.001f);
        assertTrue(like.isClickable());
        preferences.edit().putString(ComponentHideSettings.UNIFORM_ALPHA, "-1").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.INVISIBLE, like.getVisibility());
        assertFalse(like.isClickable());
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertTrue(like.isClickable());
        activity.finish();
    }

    @Test
    public void collectionSettingDoesNotAffectTheAvatarFollowButton() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View collect = new View(activity);
        collect.setId(2131371073);
        root.addView(collect);
        View follow = new View(activity);
        follow.setId(2131379772);
        root.addView(follow);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COLLECT.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, collect.getVisibility());
        assertEquals(View.VISIBLE, follow.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void playerPauseRequiresCurrentAidAndOpensCommentsOnlyOncePerShowTransition() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View comment = new View(activity);
        comment.setId(2131371414);
        int[] clicks = {0};
        comment.setOnClickListener(view -> clicks[0]++);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, true).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "old");
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(0, clicks[0]);
        intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals(1, clicks[0]);
        intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(1, clicks[0]);
        intercept(hooks.get("TokAi.FeedUi.PlayerPlaying"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        holder.aid = "changed-before-queued-callback";
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(1, clicks[0]);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void manualComponentShowOpensCommentsOncePerShowTransition() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View comment = new View(activity);
        comment.setId(2131371414);
        int[] clicks = {0};
        comment.setOnClickListener(view -> clicks[0]++);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, true).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals(1, clicks[0]);
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals("Repeated shown=true does not resubmit a native comment request", 1, clicks[0]);
            hook.setComponentsTemporarilyShown(false);
            idleMainLooper();
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals(2, clicks[0]);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void gestureComponentToggleWorksWithoutPausePolicy() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View comment = new View(activity);
        comment.setId(2131371414);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        hook.setComponentsTemporarilyShown(true);
        idleMainLooper();
        assertTrue(hook.areComponentsTemporarilyShown());
        assertEquals(View.VISIBLE, comment.getVisibility());
        hook.setComponentsTemporarilyShown(false);
        idleMainLooper();
        assertFalse(hook.areComponentsTemporarilyShown());
        assertEquals(View.GONE, comment.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void switchOptionControlsWhetherPauseRevealCarriesToTheNextCard() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-1")
                .putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, false).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        holder.aid = "next";
        intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, true).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        holder.aid = "third";
        intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, like.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void disablingPauseRevealReappliesHidingImmediately() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertTrue(hook.areComponentsTemporarilyShown());
        assertEquals(View.VISIBLE, like.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertFalse(hook.areComponentsTemporarilyShown());
        assertEquals(View.GONE, like.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void customViewIsHiddenAndRestoredWhenItsPersistedRuleIsDeleted() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView custom = new TextView(activity);
        custom.setText("custom control");
        custom.setClickable(true);
        custom.setFocusable(true);
        root.addView(custom);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), custom);
        assertNotNull(locator);

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, custom.getVisibility());
        assertFalse(custom.isClickable());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.emptyList())).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, custom.getVisibility());
        assertTrue(custom.isClickable());
        assertTrue(custom.isFocusable());

        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void windowCustomRuleSurvivesFeedPauseAndDoesNotReplaceFixedComponentRule() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        FrameLayout feed = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        feed.addView(like);
        content.addView(feed);
        TextView windowControl = new TextView(activity);
        windowControl.setText("window control");
        content.addView(windowControl);
        activity.setContentView(content);

        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), feed, windowControl);
        assertNotNull(locator);
        assertEquals(CustomViewLocator.Scope.WINDOW, locator.scope);

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putString(ComponentHideSettings.CUSTOM_VIEWS,
                        CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(feed);
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.GONE, windowControl.getVisibility());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.emptyList())).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.VISIBLE, windowControl.getVisibility());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, windowControl.getVisibility());

        intercept(hooks.get("TokAi.FeedUi.VideoPause"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.GONE, windowControl.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, windowControl.getVisibility());
        activity.finish();
    }

    @Test
    public void customRuleNeverHidesFinderOverlayOrItsChildren() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout overlay = new FrameLayout(activity);
        View overlayButton = new View(activity);
        overlay.addView(overlayButton);
        root.addView(overlay);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), root, overlayButton);
        assertNotNull(locator);
        overlay.setTag("tokai.view_finder");

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(View.VISIBLE, overlayButton.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void diagnosticsKeepVideoAndImageInstallationSeparate() throws Exception {
        List<RuleScanResult> results = new java.util.ArrayList<>();
        for (RuleScanResult result : createReport().getRuleResults()) {
            if (!result.getRuleId().startsWith("image_holder")) results.add(result);
        }
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks),
                new HostScanReport("partial-host", results, false), getClass().getClassLoader(),
                enabledSettings(), ignored -> { });
        assertTrue(hook.isVideoLifecycleInstalled());
        assertFalse(hook.isImageLifecycleInstalled());
        hook.close();
        assertFalse(hook.isVideoLifecycleInstalled());
        assertFalse(hook.isImageLifecycleInstalled());
        idleMainLooper();
    }

    @Test
    public void tiktokComponentsHideDescriptionFeedbackAndEveryProgressLayer() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        View media = new View(context);
        media.setClickable(true);
        root.addView(media);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631); // VideoDescAssem: bad.xml / exd
        TextView descriptionTitle = new TextView(context);
        descriptionTitle.setId(2131396722); // bad.xml reuses title for the video title
        description.addView(descriptionTitle);
        root.addView(description);
        TextView author = new TextView(context);
        author.setId(2131396722); // bap.xml: title belongs to the author row
        FrameLayout authorRow = new FrameLayout(context);
        authorRow.setId(2131373377); // bap.xml / idn
        authorRow.addView(author);
        root.addView(authorRow);
        View collaboration = new View(context);
        collaboration.setId(2131367915); // bap.xml / ee1 (icon_collaboration)
        authorRow.addView(collaboration);
        View postTime = new View(context);
        postTime.setId(2131399722); // bap.xml / tv_post_time
        authorRow.addView(postTime);
        View fullscreenEntry = new View(context);
        fullscreenEntry.setId(2131374470); // LandscapeEntranceAssem: bd6.xml / j77
        root.addView(fullscreenEntry);
        View feedback = new View(context);
        feedback.setId(2131390619); // EarlyFeedbackButtonAssem: baj.xml / uzp
        feedback.setClickable(true);
        root.addView(feedback);
        View buffering = new View(context);
        buffering.setId(2131363871); // bcy.xml / bfp (X.0Aos)
        root.addView(buffering);
        View progress = new View(context);
        progress.setId(2131401959); // bcy.xml / video_player_progress (X.0Aov)
        root.addView(progress);
        View line = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(context);
        root.addView(line);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.DESCRIPTION.key, "-2")
                .putString(Component.FEEDBACK.key, "-2")
                .putString(Component.PROGRESS.key, "-2")
                .putString(Component.CO_CREATION.key, "-2")
                .putString(Component.TIME_LOCATION.key, "-2")
                .putString(Component.FULLSCREEN.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder(root));
            idleMainLooper();
            assertEquals("Description has no Douyin FeedDescRootLayout class", View.GONE, description.getVisibility());
            assertEquals(View.VISIBLE, author.getVisibility());
            assertEquals(View.GONE, collaboration.getVisibility());
            assertEquals(View.GONE, postTime.getVisibility());
            assertEquals(View.GONE, fullscreenEntry.getVisibility());
            assertEquals(View.GONE, feedback.getVisibility());
            assertEquals("Buffering is a separate progress renderer", View.GONE, buffering.getVisibility());
            assertEquals("Video progress is a separate progress renderer", View.GONE, progress.getVisibility());
            assertEquals(View.GONE, line.getVisibility());
            assertEquals(View.VISIBLE, media.getVisibility());
            assertTrue(media.isClickable());
            progress.setVisibility(View.VISIBLE);
            feedback.setVisibility(View.VISIBLE);
            FrameLayout reboundDescription = new FrameLayout(context);
            reboundDescription.setId(2131368631);
            root.removeView(description);
            root.addView(reboundDescription);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Assem reinflation is discovered on the next draw", View.GONE, reboundDescription.getVisibility());
            assertEquals(View.GONE, progress.getVisibility());
            assertEquals(View.GONE, feedback.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, reboundDescription.getVisibility());
            assertEquals(View.VISIBLE, feedback.getVisibility());
            assertTrue(feedback.isClickable());
            assertEquals(View.VISIBLE, collaboration.getVisibility());
            assertEquals(View.VISIBLE, postTime.getVisibility());
            assertEquals(View.VISIBLE, fullscreenEntry.getVisibility());
            assertEquals(View.VISIBLE, buffering.getVisibility());
            assertEquals(View.VISIBLE, progress.getVisibility());
            assertEquals(View.VISIBLE, line.getVisibility());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokPauseOpenCommentsDoesNotFallbackToAClickWhenNativeEntryIsUnavailable() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        View comment = new View(context);
        comment.setId(2131368349);
        int[] clicks = {0};
        comment.setOnClickListener(view -> clicks[0]++);
        root.addView(comment);
        activity.setContentView(root);
        com.ss.android.ugc.aweme.feed.adapter.VideoViewCell holder =
                new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
        holder.u = root;
        holder.aid = "direct-holder";
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, true).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), tiktokHolderReport(holder),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), holder, 0);
            idleMainLooper();
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), holder, "source-id");
            idleMainLooper();
            assertTrue(hook.areComponentsTemporarilyShown());
            assertTrue(comment.isShown());
            assertEquals("A missing native ability cannot route the request through an arbitrary view click", 0,
                    clicks[0]);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void tiktokPlaybackRequiresTheSelectedHolderEvenWhenAidMatches() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631);
        TextView caption = new TextView(context);
        caption.setText("Current holder caption");
        description.addView(caption);
        root.addView(description);
        activity.setContentView(root);
        com.ss.android.ugc.aweme.feed.adapter.VideoViewCell holder =
                new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
        holder.u = root;
        holder.aid = "direct-holder";
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.DESCRIPTION.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), tiktokHolderReport(holder),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), holder, 0);
            idleMainLooper();
            assertEquals(View.GONE, description.getVisibility());
            com.ss.android.ugc.aweme.feed.adapter.VideoViewCell otherHolder =
                    new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
            otherHolder.u = new FrameLayout(context);
            otherHolder.aid = holder.aid;
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), otherHolder, "direct-holder");
            idleMainLooper();
            assertEquals("A preloaded holder for the same work cannot reveal the current card", View.GONE,
                    description.getVisibility());
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), holder, "source-id-is-not-the-card-aid");
            idleMainLooper();
            assertTrue(caption.isShown());
            assertTrue(hook.areComponentsTemporarilyShown());
            intercept(hooks.get("TokAi.FeedUi.PlayerPlaying"), holder, "source-id-is-not-the-card-aid");
            idleMainLooper();
            assertEquals(View.GONE, description.getVisibility());
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), holder, "direct-holder");
            holder.aid = "rebound-before-pause-was-handled";
            idleMainLooper();
            assertEquals("A queued pause from the previous work cannot reveal the rebound card", View.GONE,
                    description.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void tiktokWindowProgressUsesFixedOpacityAndShowsFullyUntilTheCurrentCardLeaves() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout page = new FrameLayout(context);
        FrameLayout card = new FrameLayout(context);
        page.addView(card);
        View progress = new com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar(context);
        progress.setVisibility(View.GONE);
        progress.setAlpha(0f);
        page.addView(progress);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.PROGRESS.key, "110").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            FeedHolderFixture holder = holder(card);
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
            idleMainLooper();
            assertEquals("Numeric opacity preserves native visibility outside temporary show", View.GONE,
                    progress.getVisibility());
            progress.setVisibility(View.VISIBLE);
            for (float nativeAlpha : new float[]{1f, 0.4306161f, 0.2f, 0.0151f}) {
                progress.setAlpha(nativeAlpha);
                page.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("The observed 110/255 fade must not feed back into the baseline", 110 / 255f,
                        progress.getAlpha(), 0.001f);
            }
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
            idleMainLooper();
            assertEquals("Pause must show configured page-level progress at full opacity", 1f,
                    progress.getAlpha(), 0.001f);
            progress.setVisibility(View.GONE);
            progress.setAlpha(0f);
            page.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(progress.isShown());
            assertEquals(1f, progress.getAlpha(), 0.001f);
            intercept(hooks.get("TokAi.FeedUi.VideoPause"), holder);
            idleMainLooper();
            assertEquals(View.GONE, progress.getVisibility());
            assertEquals(0f, progress.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void tiktokNumericOpacityOverridesNativeFadeWithoutCompoundingCaptionLayers() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631);
        description.setAlpha(0f); // VideoDescAssem can bind while its 0 -> 1 animation is pending.
        TextView title = new TextView(context);
        title.setId(2131396722);
        title.setText("Caption from the current video");
        title.setAlpha(0.6f);
        description.addView(title);
        root.addView(description);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.DESCRIPTION.key, "128").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertEquals("The configured opacity must be visible even if the host bound at alpha zero",
                    128 / 255f, description.getAlpha() * title.getAlpha(), 0.001f);
            for (float nativeAlpha : new float[]{0.8f, 0.4f, 0.1f, 0f, 1f}) {
                description.setAlpha(nativeAlpha);
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("Host animation frames must not absorb or multiply the configured opacity",
                        128 / 255f, description.getAlpha() * title.getAlpha(), 0.001f);
            }
            hook.close();
            idleMainLooper();
            assertEquals("Closing releases the alpha to the host's latest value", 1f,
                    description.getAlpha(), 0.001f);
            assertEquals("Nested native styling is restored after the component override ends", 0.6f,
                    title.getAlpha(), 0.001f);
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokPausedShowDiscoversCaptionTextThatBindsAfterSelectionWithoutLayout() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631);
        description.setVisibility(View.GONE);
        description.setAlpha(0f);
        TextView desc = new TextView(context);
        desc.setId(0x7f0a2081);
        desc.setVisibility(View.GONE);
        desc.setAlpha(0f);
        description.addView(desc);
        root.addView(description);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.DESCRIPTION.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertFalse("An empty caption is still owned by the host", desc.isShown());
            desc.setText("The asynchronously bound current-video caption");
            for (int frame = 0; frame < 16; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue("Late content must be discovered even without a layout or replaced node", desc.isShown());
            assertEquals(1f, desc.getAlpha() * description.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void tiktokShowKeepsEmptyOptionalTitleHiddenWhileRevealingPopulatedDesc() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631);
        description.setVisibility(View.GONE);
        description.setAlpha(0f);
        TextView optionalTitle = new TextView(context);
        optionalTitle.setId(2131396722);
        optionalTitle.setVisibility(View.GONE);
        description.addView(optionalTitle);
        TextView desc = new TextView(context);
        desc.setId(0x7f0a2081); // bad.xml: desc, distinct from the optional title.
        desc.setText("Populated current-video caption");
        desc.setVisibility(View.GONE);
        desc.setAlpha(0f);
        description.addView(desc);
        root.addView(description);
        View gatedComment = new View(context);
        gatedComment.setId(2131368349);
        gatedComment.setEnabled(false);
        root.addView(gatedComment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.DESCRIPTION.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertTrue(desc.isShown());
            assertEquals(1f, desc.getAlpha() * description.getAlpha(), 0.001f);
            assertEquals("Empty optional title is host-owned", View.GONE, optionalTitle.getVisibility());
            assertFalse("Showing a component cannot enable a native permission gate", gatedComment.isEnabled());
            for (int frame = 0; frame < 3; frame++) {
                desc.setVisibility(View.GONE);
                desc.setAlpha(0f);
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertTrue(desc.isShown());
                assertEquals(1f, desc.getAlpha(), 0.001f);
            }
            intercept(hooks.get("TokAi.FeedUi.VideoPause"), holder(root));
            idleMainLooper();
            assertEquals("Leaving the holder releases temporary native-state overrides", View.GONE,
                    description.getVisibility());
            assertEquals(View.GONE, desc.getVisibility());
            assertEquals(0f, desc.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void tiktokTemporaryShowRevealsNativeGoneCaptionAndReleasesItsNativeState() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631);
        description.setVisibility(View.GONE);
        description.setAlpha(0f);
        TextView title = new TextView(context);
        title.setId(2131396722);
        title.setText("A caption must reappear while paused");
        description.addView(title);
        root.addView(description);
        View media = new View(context);
        media.setClickable(true);
        root.addView(media);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.DESCRIPTION.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            java.lang.ref.WeakReference<FeedHolderFixture> liveHolder = new java.lang.ref.WeakReference<>(holder(root));
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), liveHolder.get());
            idleMainLooper();
            for (int attempt = 0; attempt < 4; attempt++) System.gc();
            assertNotNull("A live host pager must own its selected holder across collection", liveHolder.get());
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
            idleMainLooper();
            assertTrue("Pause/show must reveal a verified caption bound in the native hidden state", title.isShown());
            assertEquals(1f, description.getAlpha() * title.getAlpha(), 0.001f);
            assertTrue(media.isClickable());
            assertEquals(1f, media.getAlpha(), 0.001f);
            intercept(hooks.get("TokAi.FeedUi.PlayerPlaying"), new PlayerFixture(), "current");
            idleMainLooper();
            assertEquals(View.GONE, description.getVisibility());
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertTrue(title.isShown());
            hook.close();
            idleMainLooper();
            assertEquals("Show overrides must not become the saved native visibility", View.GONE,
                    description.getVisibility());
            assertEquals(0f, description.getAlpha(), 0.001f);
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokComponentsKeepDescriptionTitleIndependentFromAuthorOpacity() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631);
        TextView descriptionTitle = new TextView(context);
        descriptionTitle.setId(2131396722);
        description.addView(descriptionTitle);
        root.addView(description);
        TextView author = new TextView(context);
        author.setId(2131396722);
        FrameLayout authorRow = new FrameLayout(context);
        authorRow.setId(2131373377); // bap.xml / idn
        authorRow.addView(author);
        root.addView(authorRow);
        TextView unrelatedTitle = new TextView(context);
        unrelatedTitle.setId(2131396722);
        root.addView(unrelatedTitle);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.AUTHOR.key, "-2")
                .putString(Component.DESCRIPTION.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder(root));
            idleMainLooper();
            assertEquals(View.GONE, author.getVisibility());
            assertEquals("TikTok reuses title inside VideoDescAssem", View.VISIBLE, descriptionTitle.getVisibility());
            assertEquals(View.VISIBLE, description.getVisibility());
            assertEquals("A title outside the verified author subtree is unrelated", View.VISIBLE,
                    unrelatedTitle.getVisibility());
            hook.close();
            idleMainLooper();
            assertEquals(View.VISIBLE, author.getVisibility());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokComponentsStartFromTheActualHolderSelectionCallback() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        FrameLayout description = new FrameLayout(context);
        description.setId(2131368631);
        root.addView(description);
        activity.setContentView(root);
        com.ss.android.ugc.aweme.feed.adapter.VideoViewCell holder =
                new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
        holder.u = root;
        holder.aid = "actual-selected";
        String owner = descriptor(holder.getClass());
        HostScanReport report = new HostScanReport("tiktok-holder", Arrays.asList(
                new RuleScanResult("feed_holder_page_selected", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", owner, "onPageSelected", "(I)V"))),
                methodResult("feed_holder_resume", owner, "onResume"),
                methodResult("feed_holder_pause", owner, "onPause"),
                classResult("feed_holder_main_root", owner),
                new RuleScanResult("feed_holder_selected", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", owner, "LJJIJIL", "(I)V")))), false);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.DESCRIPTION.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), report,
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), holder, 0);
            idleMainLooper();
            assertEquals(View.GONE, description.getVisibility());
            assertEquals(1, hook.getSelectedCallbacks());
            assertEquals(1, hook.getCardScans());
            hook.close();
            idleMainLooper();
            assertEquals(View.VISIBLE, description.getVisibility());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokComponentsHideCardShellAndBottomToolsIndependently() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        View media = new View(context);
        media.setClickable(true);
        root.addView(media);
        android.widget.LinearLayout cardShell = new android.widget.LinearLayout(context);
        cardShell.setBackgroundColor(android.graphics.Color.DKGRAY);
        View cardHeading = new View(context);
        cardHeading.setId(2131396853); // VideoUserCardAssem: bcr.xml / zj7
        View cardBody = new View(context);
        cardBody.setId(2131401351); // bcr.xml / userCardLayout
        cardShell.addView(cardHeading);
        cardShell.addView(cardBody);
        root.addView(cardShell);
        FrameLayout bottomTools = new FrameLayout(context);
        bottomTools.setId(2131377722); // b92.xml / lk3, VideoViewCell.LLLLLJLJLL
        View tool = new View(context);
        bottomTools.addView(tool);
        // Progress remains independently configurable if the host inserts it under this area.
        View progress = new View(context);
        progress.setId(2131401959);
        bottomTools.addView(progress);
        root.addView(bottomTools);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.CARD.key, "-2")
                .putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder(root));
            idleMainLooper();
            assertEquals("Card body, heading and background belong to one verified shell", View.GONE,
                    cardShell.getVisibility());
            assertEquals(View.GONE, tool.getVisibility());
            assertEquals("The shared tools container must preserve the visible progress child", View.VISIBLE,
                    bottomTools.getVisibility());
            assertEquals(View.VISIBLE, progress.getVisibility());
            assertEquals(View.VISIBLE, media.getVisibility());
            assertTrue(media.isClickable());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, cardShell.getVisibility());
            assertEquals(View.VISIBLE, cardHeading.getVisibility());
            assertEquals(View.VISIBLE, cardBody.getVisibility());
            assertEquals(View.VISIBLE, tool.getVisibility());
            assertEquals(View.VISIBLE, media.getVisibility());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokTakoHasIndependentOpacityForCardAndFloatingEntrances() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout page = new FrameLayout(context);
        FrameLayout card = new FrameLayout(context);
        FrameLayout inCard = takoNormal(context);
        inCard.setAlpha(.6f);
        card.addView(inCard);
        View media = new View(context);
        card.addView(media);
        page.addView(card);
        androidx.constraintlayout.widget.ConstraintLayout floating = takoBubble(context);
        floating.setAlpha(.4f);
        floating.setEnabled(false);
        page.addView(floating);
        com.bytedance.lighten.loader.SmartImageView chatAvatar =
                new com.bytedance.lighten.loader.SmartImageView(context);
        chatAvatar.setId(2131396564); // The icon alone is not a feed entrance.
        chatAvatar.setAlpha(.7f);
        page.addView(chatAvatar);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_NAV.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder(card));
            idleMainLooper();
            assertEquals(.6f, inCard.getAlpha(), .0001f);
            assertEquals(.4f, floating.getAlpha(), .0001f);
            preferences.edit().putString(Component.TAKO.key, "128").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(128f / 255f, inCard.getAlpha(), .0001f);
            assertEquals(128f / 255f, floating.getAlpha(), .0001f);
            assertEquals(.7f, chatAvatar.getAlpha(), .0001f);
            assertFalse(floating.isEnabled());
            assertEquals(View.VISIBLE, media.getVisibility());
            floating.setAlpha(.2f);
            page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(128f / 255f, floating.getAlpha(), .0001f);
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals(1f, inCard.getAlpha(), .0001f);
            assertEquals(1f, floating.getAlpha(), .0001f);
            hook.setComponentsTemporarilyShown(false);
            idleMainLooper();
            preferences.edit().putString(Component.TAKO.key, "-2").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.GONE, inCard.getVisibility());
            assertEquals(View.GONE, floating.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, inCard.getVisibility());
            assertEquals(.6f, inCard.getAlpha(), .0001f);
            assertEquals(View.VISIBLE, floating.getVisibility());
            assertEquals(.2f, floating.getAlpha(), .0001f);
            assertEquals(.7f, chatAvatar.getAlpha(), .0001f);
            assertFalse(floating.isEnabled());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokTakoLateFloatingEntranceIsManagedUntilSelectedCardLeaves() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout page = new FrameLayout(context);
        FrameLayout card = new FrameLayout(context);
        page.addView(card);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.TAKO.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        FeedHolderFixture holder = holder(card);
        try {
            intercept(hooks.get("TokAi.FeedUi.HideVideoActions"), holder);
            idleMainLooper();
            FrameLayout floating = takoNormal(context);
            page.addView(floating);
            for (int index = 0; index < 16; index++) page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, floating.getVisibility());
            intercept(hooks.get("TokAi.FeedUi.VideoPause"), holder);
            idleMainLooper();
            assertEquals("A detached feed scope must release the floating entrance", View.VISIBLE,
                    floating.getVisibility());
        } finally { hook.close(); activity.finish(); }
    }

    private static FrameLayout takoNormal(android.content.Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setId(2131396568);
        com.bytedance.lighten.loader.SmartImageView image =
                new com.bytedance.lighten.loader.SmartImageView(context);
        image.setId(2131396564);
        root.addView(image);
        return root;
    }

    private static androidx.constraintlayout.widget.ConstraintLayout takoBubble(android.content.Context context) {
        androidx.constraintlayout.widget.ConstraintLayout root =
                new androidx.constraintlayout.widget.ConstraintLayout(context);
        root.setId(2131396573);
        FrameLayout body = takoNormal(context);
        body.setId(2131396574);
        root.addView(body);
        View board = new View(context);
        board.setId(2131396572);
        root.addView(board);
        return root;
    }

    @Test
    public void tiktokSwipeHidesEvenWhenTheOldPlayersPauseIsForwardedToTheNewHolder() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        View comment = new View(context);
        comment.setId(2131368349);
        root.addView(comment);
        activity.setContentView(root);
        com.ss.android.ugc.aweme.feed.adapter.VideoViewCell holder =
                new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
        holder.u = root;
        String previous = "7560140020000000001", current = "7560140020000000002";
        holder.aid = previous;
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), tiktokHolderReport(holder),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), holder, 0);
            idleMainLooper();
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), holder, previous);
            idleMainLooper();
            assertTrue(hook.areComponentsTemporarilyShown());
            holder.aid = current;
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), holder, 1);
            // PlayerController forwards the old source's callback through its CURRENT holder.
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), holder, previous);
            idleMainLooper();
            assertFalse("An outgoing source must not carry pause reveal onto the new video",
                    hook.areComponentsTemporarilyShown());
            assertEquals(View.GONE, comment.getVisibility());
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), holder, current);
            idleMainLooper();
            assertTrue(hook.areComponentsTemporarilyShown());
            assertTrue(comment.isShown());
            intercept(hooks.get("TokAi.FeedUi.PlayerPlaying"), holder, previous);
            idleMainLooper();
            assertTrue("An old resume must not hide components revealed by the current pause",
                    hook.areComponentsTemporarilyShown());
            intercept(hooks.get("TokAi.FeedUi.PlayerPlaying"), holder, current);
            idleMainLooper();
            assertEquals(View.GONE, comment.getVisibility());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokLateOutgoingLifecycleCannotReleaseTheReusedSelectedRoot() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout root = new FrameLayout(context);
        View comment = new View(context);
        comment.setId(2131368349);
        root.addView(comment);
        activity.setContentView(root);
        com.ss.android.ugc.aweme.feed.adapter.VideoViewCell first =
                new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
        first.u = root; first.aid = "7560140020000000001";
        com.ss.android.ugc.aweme.feed.adapter.VideoViewCell next =
                new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
        next.u = root; next.aid = "7560140020000000002";
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), tiktokHolderReport(first),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), first, 0);
            idleMainLooper();
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), next, 1);
            intercept(hooks.get("TokAi.FeedUi.VideoPause"), first);
            idleMainLooper();
            assertEquals("The outgoing holder cannot release a root now owned by the next holder",
                    View.GONE, comment.getVisibility());
            intercept(hooks.get("TokAi.FeedUi.PlayerPause"), next, next.aid);
            idleMainLooper();
            assertTrue(hook.areComponentsTemporarilyShown());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokQueuedSelectionCannotClaimAHolderReboundBeforeMainDispatch() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout page = new FrameLayout(context), oldRoot = new FrameLayout(context), newRoot = new FrameLayout(context);
        View oldComment = new View(context), newComment = new View(context);
        oldComment.setId(2131368349); newComment.setId(2131368349);
        oldRoot.addView(oldComment); newRoot.addView(newComment);
        page.addView(oldRoot); page.addView(newRoot);
        activity.setContentView(page);
        com.ss.android.ugc.aweme.feed.adapter.VideoViewCell holder =
                new com.ss.android.ugc.aweme.feed.adapter.VideoViewCell();
        holder.u = oldRoot; holder.aid = "7560140020000000001";
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), tiktokHolderReport(holder),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), holder, 0);
            holder.u = newRoot; holder.aid = "7560140020000000002";
            idleMainLooper();
            assertEquals("An obsolete queued selection must not hide the old card", View.VISIBLE,
                    oldComment.getVisibility());
            intercept(hooks.get("TokAi.FeedUi.VideoHolderSelected"), holder, 1);
            idleMainLooper();
            assertEquals(View.GONE, newComment.getVisibility());
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokFixedTakoEntranceInTheWindowUsesItsOwnHideAndOpacitySetting() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout page = new FrameLayout(context), card = new FrameLayout(context), entrance = new FrameLayout(context);
        entrance.setId(2131395576); // Fixed entrance ViewStub assigns ylo to fvn.xml's root.
        com.bytedance.tux.icon.TuxIconView icon = new com.bytedance.tux.icon.TuxIconView(context);
        icon.setId(2131379170); // fvn.xml / mm8, a separate renderer from ba5/ba6.
        entrance.addView(icon);
        entrance.setAlpha(.6f);
        page.addView(card); page.addView(entrance);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.TAKO.key, "128").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder(card));
            idleMainLooper();
            assertEquals(128f / 255f, entrance.getAlpha(), .0001f);
            preferences.edit().putString(Component.TAKO.key, "-2").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences)); idleMainLooper();
            assertEquals(View.GONE, entrance.getVisibility());
            assertFalse(icon.isShown());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences)); idleMainLooper();
            assertTrue(icon.isShown());
            assertEquals(.6f, entrance.getAlpha(), .0001f);
        } finally { hook.close(); activity.finish(); }
    }

    @Test
    public void tiktokTakoViewStubReplacementIsFoundAndNativeVisibilityStillGatesNumericOpacity() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.Context context = tiktokComponentContext(activity);
        FrameLayout page = new FrameLayout(context), card = new FrameLayout(context);
        page.addView(card);
        android.view.ViewStub stub = new android.view.ViewStub(context);
        stub.setId(2131402763); page.addView(stub);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.TAKO.key, "110").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), context, ExtendedSettings.readFrom(preferences), ignored -> { });
        FeedHolderFixture holder = holder(card);
        try {
            intercept(hooks.get("TokAi.FeedUi.VideoResume"), holder);
            idleMainLooper();
            FrameLayout entrance = new FrameLayout(context);
            entrance.setId(2131395576);
            com.bytedance.tux.icon.TuxIconView icon = new com.bytedance.tux.icon.TuxIconView(context);
            icon.setId(2131379170); entrance.addView(icon);
            entrance.setAlpha(.6f); entrance.setVisibility(View.GONE);
            page.removeView(stub); page.addView(entrance, 1); // Child count stays unchanged.
            for (int index = 0; index < 16; index++) page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, entrance.getVisibility());
            assertEquals(110f / 255f, entrance.getAlpha(), .0001f);
            entrance.setVisibility(View.VISIBLE); entrance.setAlpha(.2f);
            page.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(icon.isShown());
            assertEquals(110f / 255f, entrance.getAlpha(), .0001f);
            hook.setComponentsTemporarilyShown(true); idleMainLooper();
            assertEquals(1f, entrance.getAlpha(), .0001f);
            intercept(hooks.get("TokAi.FeedUi.VideoPause"), holder); idleMainLooper();
            assertEquals(.2f, entrance.getAlpha(), .0001f);
            assertTrue(icon.isShown());
        } finally { hook.close(); activity.finish(); }
    }

    private static android.content.Context tiktokComponentContext(Activity activity) {
        android.content.res.Resources base = activity.getResources();
        Map<Integer, String> entries = new HashMap<>(com.tokai.module.scan.TikTokFeedResources.names());
        entries.put(2131368631, "exd");
        entries.put(0x7f0a2081, "desc");
        entries.put(2131373377, "idn");
        entries.put(2131367915, "ee1");
        entries.put(2131399722, "tv_post_time");
        entries.put(2131374470, "j77");
        entries.put(2131396853, "zj7");
        entries.put(2131401351, "userCardLayout");
        entries.put(2131377722, "lk3");
        entries.put(2131390619, "uzp");
        entries.put(2131363871, "bfp");
        entries.put(2131401959, "video_player_progress");
        entries.put(2131396564, "zbd");
        entries.put(2131396568, "zbh");
        entries.put(2131396572, "zbl");
        entries.put(2131396573, "zbm");
        entries.put(2131396574, "zbn");
        entries.put(2131379170, "mm8");
        entries.put(2131395575, "yln");
        entries.put(2131395576, "ylo");
        android.content.res.Resources resources = new android.content.res.Resources(
                base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override public String getResourcePackageName(int id) {
                if (!entries.containsKey(id)) throw new android.content.res.Resources.NotFoundException();
                return "com.zhiliaoapp.musically";
            }
            @Override public String getResourceEntryName(int id) {
                String name = entries.get(id);
                if (name == null) throw new android.content.res.Resources.NotFoundException();
                return name;
            }
        };
        return new android.content.ContextWrapper(activity) {
            @Override public android.content.res.Resources getResources() { return resources; }
        };
    }

    private static HostScanReport tiktokHolderReport(com.ss.android.ugc.aweme.feed.adapter.VideoViewCell holder)
            throws Exception {
        String owner = descriptor(holder.getClass());
        return new HostScanReport("tiktok-holder", Arrays.asList(
                new RuleScanResult("feed_holder_page_selected", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", owner, "onPageSelected", "(I)V"))),
                methodResult("feed_holder_resume", owner, "onResume"),
                methodResult("feed_holder_pause", owner, "onPause"),
                classResult("feed_holder_main_root", owner),
                new RuleScanResult("feed_holder_selected", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", owner, "LJJIJIL", "(I)V"))),
                new RuleScanResult("component_player_pause", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", owner, "onPausePlay", "(Ljava/lang/String;)V"))),
                new RuleScanResult("component_player_playing", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", owner, "onResumePlay", "(Ljava/lang/String;)V")))), false);
    }

    private static SharedPreferences componentPreferences() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("feed_ui_test", 0);
        preferences.edit().clear().putBoolean(ComponentHideSettings.ENABLED, true).commit();
        return preferences;
    }

    private FeedHolderFixture holder(FrameLayout root) {
        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        pagerHolders.add(holder);
        return holder;
    }

    private FeedUiHideHook install(Map<String, XposedInterface.Hooker> hooks,
                                   ExtendedSettings settings) throws Exception {
        return FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), settings, ignored -> { });
    }

    private static ExtendedSettings enabledSettings() {
        return settings(true, 100);
    }

    private static ExtendedSettings settings(boolean hideInterface, int actionAlpha) {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("feed_ui_test", 0);
        preferences.edit().putBoolean("hide_interface", hideInterface)
                .remove(ComponentHideSettings.ENABLED)
                .putString("video_right_alpha", Integer.toString(actionAlpha)).commit();
        return ExtendedSettings.readFrom(preferences);
    }

    private static void idleMainLooper() throws ReflectiveOperationException {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idle").invoke(shadow);
    }

    private static void idleForMillis(long millis) throws ReflectiveOperationException {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idleFor", long.class, java.util.concurrent.TimeUnit.class)
                .invoke(shadow, millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void intercept(XposedInterface.Hooker hook, Object holder, Object... arguments) throws Throwable {
        assertNotNull(hook);
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if ("proceed".equals(method.getName())) return null;
                    if ("getThisObject".equals(method.getName())) return holder;
                    if ("getArg".equals(method.getName())) return arguments[(Integer) args[0]];
                    throw new UnsupportedOperationException(method.getName());
                });
        hook.intercept(chain);
    }

    private static HostScanReport createReport() throws Exception {
        String owner = descriptor(FeedHolderFixture.class);
        List<RuleScanResult> results = Arrays.asList(
                methodResult("feed_holder_page_selected", owner, "onVideoPageSelected"),
                methodResult("feed_holder_resume", owner, "onVideoResume"),
                methodResult("feed_holder_pause", owner, "onVideoPause"),
                classResult("feed_holder_main_root", owner),
                methodResult("image_holder_page_selected", owner, "onImagePageSelected"),
                methodResult("image_holder_resume", owner, "onImageResume"),
                methodResult("image_holder_pause", owner, "onImagePause"),
                classResult("image_holder_main_root", owner),
                new RuleScanResult("component_player_pause", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", descriptor(PlayerFixture.class),
                                "onPause", "(Ljava/lang/String;)V"))),
                new RuleScanResult("component_player_playing", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", descriptor(PlayerFixture.class),
                                "onPlaying", "(Ljava/lang/String;)V"))));
        return new HostScanReport("test-host", results, false);
    }

    private static RuleScanResult methodResult(String id, String owner, String method) throws Exception {
        return new RuleScanResult(id, "method_scan", Collections.singletonList(
                new HostCandidate("test.apk", "classes.dex", owner, method, "()V")));
    }

    private static RuleScanResult classResult(String id, String owner) {
        return new RuleScanResult(id, "method_scan", Collections.singletonList(
                new HostCandidate("test.apk", "classes.dex", owner, "", "")));
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static XposedInterface createFramework(Map<String, XposedInterface.Hooker> hooks) {
        return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (frameworkProxy, frameworkMethod, frameworkArgs) -> {
                    if (!"hook".equals(frameworkMethod.getName())) return null;
                    String[] id = {""};
                    XposedInterface.HookBuilder[] builder = new XposedInterface.HookBuilder[1];
                    builder[0] = (XposedInterface.HookBuilder) Proxy.newProxyInstance(
                            XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builderProxy, builderMethod, builderArgs) -> {
                                if ("setId".equals(builderMethod.getName())) {
                                    id[0] = (String) builderArgs[0];
                                    return builder[0];
                                }
                                if ("intercept".equals(builderMethod.getName())) {
                                    hooks.put(id[0], (XposedInterface.Hooker) builderArgs[0]);
                                    return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                            new Class<?>[]{XposedInterface.HookHandle.class},
                                            (handleProxy, handleMethod, handleArgs) -> null);
                                }
                                return builder[0];
                            });
                    return builder[0];
                });
    }
}
