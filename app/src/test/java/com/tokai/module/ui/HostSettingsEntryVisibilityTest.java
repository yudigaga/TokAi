package com.tokai.module.ui;

import android.graphics.Insets;
import android.graphics.Rect;
import android.text.Layout;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.compose.ui.platform.ComposeView;

import com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Geometry contract for the full-screen FrameLayout used by TikTok's settings activity. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.tokai.module",
        qualifiers = "zh-rCN-w480dp-h1067dp-xxhdpi")
public final class HostSettingsEntryVisibilityTest {
    private static final int WIDTH = 1440;
    private static final int HEIGHT = 3200;
    private static final int STATUS_BAR_TOP = 96;
    private static final int CUTOUT_TOP = 120;
    private static final int NAVIGATION_BOTTOM = 96;
    private static final String ENTRY_TAG = "TokAi.HostSettingsEntry";

    @Test
    public void composeEntryTextAndTouchTargetFitInsideTheStatusBarSafeViewport() {
        verifySafeViewport(false);
    }

    @Test
    public void composeEntryTextAndTouchTargetFitInsideTheTallerDisplayCutoutSafeViewport() {
        verifySafeViewport(true);
    }

    @Test @Config(sdk = 28)
    public void legacyWindowInsetsKeepTheEntryBelowTheDisplayCutout() {
        ActivityController<SettingContainerActivity> lifecycle =
                Robolectric.buildActivity(SettingContainerActivity.class).setup();
        try {
            SettingContainerActivity activity = lifecycle.get();
            FrameLayout nativeRoot = new FrameLayout(activity);
            nativeRoot.setFitsSystemWindows(true);
            View wrapped = HostSettingsEntry.wrapCompose(activity, nativeRoot, () -> { });
            FrameLayout host = new FrameLayout(activity);
            host.addView(wrapped, new FrameLayout.LayoutParams(-1, -1));
            activity.setContentView(host);
            WindowInsets insets = ReflectionHelpers.callConstructor(WindowInsets.class,
                    ReflectionHelpers.ClassParameter.from(Rect.class, new Rect(0, STATUS_BAR_TOP, 0, NAVIGATION_BOTTOM)));
            ReflectionHelpers.setField(insets, "mDisplayCutout", new DisplayCutout(
                    new Rect(0, CUTOUT_TOP, 0, 0), java.util.Collections.emptyList()));
            host.dispatchApplyWindowInsets(insets);
            layoutViewport(host);
            View entry = host.findViewWithTag(ENTRY_TAG);
            assertEquals(CUTOUT_TOP, touchBoundsIn(host, entry).top);
            assertTrue(nativeRoot.getHeight() > 0);
            assertEquals(NAVIGATION_BOTTOM, wrapped.getPaddingBottom());
        } finally { lifecycle.pause().stop().destroy(); }
    }

    private static void verifySafeViewport(boolean hasCutout) {
        ActivityController<SettingContainerActivity> lifecycle =
                Robolectric.buildActivity(SettingContainerActivity.class);
        SettingContainerActivity activity = lifecycle.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        lifecycle.setup();
        try {
            activity.getWindow().setDecorFitsSystemWindows(false);
            // Host fb8.xml is a match-parent FrameLayout; it does not pre-inset the fragment.
            FrameLayout hostFrame = new FrameLayout(activity);
            hostFrame.setFitsSystemWindows(false);
            ComposeView nativeCompose = new ComposeView(activity);
            // The real host applies this to the original root before onCreateView returns.
            nativeCompose.setFitsSystemWindows(true);
            AtomicInteger clicks = new AtomicInteger();
            View wrapped = HostSettingsEntry.wrapCompose(activity, nativeCompose,
                    clicks::incrementAndGet);
            hostFrame.addView(wrapped, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(hostFrame);

            WindowInsets insets = windowInsets(hasCutout);
            hostFrame.dispatchApplyWindowInsets(insets);
            layoutViewport(activity.getWindow().getDecorView());
            // Drive the exact host viewport, independently of Robolectric's display rounding.
            layoutViewport(hostFrame);

            assertSame("The original native Compose root must be retained", wrapped,
                    nativeCompose.getParent());
            assertTrue("The host Compose root must keep a usable content area",
                    nativeCompose.getMeasuredWidth() > 0 && nativeCompose.getMeasuredHeight() > 0);
            assertTrue("The host's root insets contract must be preserved",
                    nativeCompose.getFitsSystemWindows());

            TextView entry = hostFrame.findViewWithTag(ENTRY_TAG);
            assertNotNull("The entry must be installed", entry);
            assertEquals("TokAi 模块设置", entry.getText().toString());
            assertTrue("The entry must participate in the visible attached hierarchy", entry.isShown());
            assertTrue("The entry must be clickable", entry.isClickable());
            assertNotNull("Text must have a measured layout", entry.getLayout());
            assertTrue("Text must have a nonempty measured line", entry.getLayout().getLineCount() > 0);

            Insets unsafeEdges = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            assertEquals(hasCutout ? CUTOUT_TOP : STATUS_BAR_TOP, unsafeEdges.top);
            assertEquals(NAVIGATION_BOTTOM, unsafeEdges.bottom);
            Rect safeViewport = new Rect(unsafeEdges.left, unsafeEdges.top,
                    WIDTH - unsafeEdges.right, HEIGHT - unsafeEdges.bottom);
            Rect touchBounds = touchBoundsIn(hostFrame, entry);
            Rect textBounds = textBoundsIn(hostFrame, entry);
            Rect visibleBounds = new Rect();
            assertTrue("The entry must have a nonempty visible rectangle",
                    entry.getLocalVisibleRect(visibleBounds));
            hostFrame.offsetDescendantRectToMyCoords(entry, visibleBounds);
            assertTrue("Entry geometry must clear system UI: safe=" + safeViewport
                            + "; touch=" + touchBounds + "; text=" + textBounds
                            + "; visible=" + visibleBounds,
                    !touchBounds.isEmpty() && !textBounds.isEmpty()
                            && safeViewport.contains(touchBounds)
                            && safeViewport.contains(textBounds)
                            && visibleBounds.contains(touchBounds));
            Rect hostBounds = new Rect();
            nativeCompose.getDrawingRect(hostBounds);
            hostFrame.offsetDescendantRectToMyCoords(nativeCompose, hostBounds);
            assertTrue("The entry must leave the host settings content below it",
                    hostBounds.top >= touchBounds.bottom && hostBounds.bottom <= HEIGHT);
            hostFrame.dispatchApplyWindowInsets(insets);
            layoutViewport(hostFrame);
            assertEquals("Repeated host inset delivery must not accumulate padding",
                    touchBounds, touchBoundsIn(hostFrame, entry));
            assertTrue(entry.performClick());
            assertEquals("The visible entry must still open the settings callback", 1, clicks.get());
        } finally {
            lifecycle.pause().stop().destroy();
        }
    }

    private static WindowInsets windowInsets(boolean hasCutout) {
        Insets statusBars = Insets.of(0, STATUS_BAR_TOP, 0, 0);
        Insets navigationBars = Insets.of(0, 0, 0, NAVIGATION_BOTTOM);
        WindowInsets.Builder builder = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), statusBars)
                .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), statusBars)
                .setInsets(WindowInsets.Type.navigationBars(), navigationBars)
                .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), navigationBars)
                .setVisible(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars(), true);
        if (hasCutout) {
            Insets cutoutInsets = Insets.of(0, CUTOUT_TOP, 0, 0);
            builder.setDisplayCutout(new DisplayCutout(cutoutInsets, null,
                            new Rect(600, 0, 840, CUTOUT_TOP), null, null))
                    .setInsets(WindowInsets.Type.displayCutout(), cutoutInsets)
                    .setInsetsIgnoringVisibility(WindowInsets.Type.displayCutout(), cutoutInsets);
        }
        return builder.build();
    }

    private static void layoutViewport(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
    }

    private static Rect touchBoundsIn(ViewGroup viewport, View entry) {
        Rect bounds = new Rect();
        entry.getHitRect(bounds);
        viewport.offsetDescendantRectToMyCoords((View) entry.getParent(), bounds);
        return bounds;
    }

    private static Rect textBoundsIn(ViewGroup viewport, TextView entry) {
        Layout text = entry.getLayout();
        Rect bounds = new Rect(
                entry.getTotalPaddingLeft() + (int) Math.floor(text.getLineLeft(0)),
                entry.getTotalPaddingTop() + text.getLineTop(0),
                entry.getTotalPaddingLeft() + (int) Math.ceil(text.getLineRight(0)),
                entry.getTotalPaddingTop() + text.getLineBottom(0));
        viewport.offsetDescendantRectToMyCoords(entry, bounds);
        return bounds;
    }
}
