package com.tokai.module.ui;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.tokai.module")
public final class HostSettingsEntryVisibilityObserverTest {
    @Test public void readbackWaitsForAttachmentAndPreDraw() {
        try (Fixture fixture = new Fixture()) {
            HostSettingsEntryVisibility.observe(fixture.activity, fixture.entry, fixture::record);
            fixture.entry.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue("An unattached constructed entry is not visible evidence", fixture.logs.isEmpty());

            fixture.attach();
            assertTrue(fixture.entry.isAttachedToWindow());
            assertTrue("Attachment still needs a pre-draw readback", fixture.logs.isEmpty());
            fixture.draw();
            assertEquals(1, fixture.logs.size());
            assertTrue(fixture.last().contains("rootAttached=true;shown=true;size=200x60"));
            assertTrue(fixture.last().contains("visible="));
            assertFalse(fixture.last().contains("visible=none"));
            assertTrue("Ordinary evidence must fit the simple log payload", fixture.last().length() <= 160);
            fixture.draw();
            assertEquals("Unchanged frames do not repeat the event", 1, fixture.logs.size());
        }
    }

    @Test public void observesVisibilityChangesAndDoesNotLogEachPositionChange() {
        try (Fixture fixture = new Fixture()) {
            fixture.attach();
            HostSettingsEntryVisibility.observe(fixture.activity, fixture.entry, fixture::record);
            assertTrue(fixture.logs.isEmpty());
            fixture.draw();
            fixture.entry.offsetTopAndBottom(1);
            fixture.draw();
            assertEquals(1, fixture.logs.size());

            fixture.entry.setVisibility(View.INVISIBLE);
            fixture.draw();
            assertEquals(2, fixture.logs.size());
            assertEquals("not_shown", fixture.reasons.get(1));
            assertTrue(fixture.last().contains("shown=false"));
            fixture.draw();
            assertEquals(2, fixture.logs.size());

            fixture.entry.setVisibility(View.VISIBLE);
            fixture.draw();
            assertEquals(3, fixture.logs.size());
            assertTrue(fixture.last().contains("shown=true"));
        }
    }

    @Test public void detachRemovesTheOldPreDrawListenerAndReattachStartsANewReadback() {
        try (Fixture fixture = new Fixture()) {
            fixture.attach();
            HostSettingsEntryVisibility.observe(fixture.activity, fixture.entry, fixture::record);
            fixture.draw();
            ViewTreeObserver oldTree = fixture.entry.getViewTreeObserver();
            fixture.host.removeView(fixture.entry);
            assertFalse(fixture.entry.isAttachedToWindow());
            oldTree.dispatchOnPreDraw();
            assertEquals(1, fixture.logs.size());

            fixture.host.addView(fixture.entry, new FrameLayout.LayoutParams(200, 60));
            fixture.entry.layout(0, 100, 200, 160);
            assertEquals("Reattachment itself is not visible evidence", 1, fixture.logs.size());
            fixture.draw();
            assertEquals("A new attached draw must get a fresh readback", 2, fixture.logs.size());
            fixture.draw();
            assertEquals("The old listener must not survive alongside the new one", 2,
                    fixture.logs.size());
        }
    }

    @Test public void reportsTransparentTextAndAncestorAlphaWithoutReadingText() {
        try (Fixture fixture = new Fixture()) {
            fixture.attach();
            HostSettingsEntryVisibility.observe(fixture.activity, fixture.entry, fixture::record);
            fixture.draw();
            fixture.entry.setTextColor(0x00112233);
            fixture.host.setAlpha(0f);
            fixture.draw();
            assertEquals("transparent", fixture.reasons.get(1));
            assertTrue(fixture.last().contains("textTransparent=true;alphaZero=true"));
            assertFalse(fixture.last().contains("Private fixture text"));
        }
    }

    @Test public void absentInsetsAreUnknownAndModernInsetsIncludeTheDisplayCutout() {
        try (Fixture fixture = new Fixture()) {
            fixture.decor.layout(0, 0, 400, 800);
            assertNull(HostSettingsEntryVisibility.safeViewport(fixture.decor, null));
            WindowInsets insets = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 36))
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 40, 0, 0))
                    .build();
            assertEquals(new Rect(0, 40, 400, 764),
                    HostSettingsEntryVisibility.safeViewport(fixture.decor, insets));
        }
    }

    @Test @Config(sdk = 28) public void legacyInsetsAlsoHonorTheDisplayCutoutSafeInsets() {
        try (Fixture fixture = new Fixture()) {
            fixture.decor.layout(0, 0, 400, 800);
            WindowInsets insets = legacyInsets();
            // API 28 exposes the cutout through WindowInsets even without the typed Insets API.
            WindowInsets cutoutInsets = legacyInsets();
            ReflectionHelpers.setField(cutoutInsets, "mDisplayCutout",
                    new DisplayCutout(new Rect(0, 40, 0, 0), new ArrayList<>()));
            assertEquals(new Rect(0, 24, 400, 764),
                    HostSettingsEntryVisibility.safeViewport(fixture.decor, insets));
            assertEquals(new Rect(0, 40, 400, 764),
                    HostSettingsEntryVisibility.safeViewport(fixture.decor, cutoutInsets));
        }
    }

    private static WindowInsets legacyInsets() {
        return ReflectionHelpers.callConstructor(WindowInsets.class,
                ReflectionHelpers.ClassParameter.from(Rect.class, new Rect(0, 24, 0, 36)));
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityController<Activity> lifecycle = Robolectric.buildActivity(Activity.class).setup();
        final Activity activity = lifecycle.get();
        final View decor = activity.getWindow().getDecorView();
        final FrameLayout host = new FrameLayout(activity);
        final TextView entry = new TextView(activity);
        final List<String> reasons = new ArrayList<>();
        final List<String> logs = new ArrayList<>();

        Fixture() {
            entry.setText("Private fixture text");
            entry.setTextColor(0xff112233);
            activity.setContentView(host);
        }

        void attach() {
            host.addView(entry, new FrameLayout.LayoutParams(200, 60));
            decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 400, 800);
            host.layout(0, 0, 400, 800);
            entry.layout(0, 100, 200, 160);
        }

        void draw() { decor.getViewTreeObserver().dispatchOnPreDraw(); }
        void record(String reason, String evidence) { reasons.add(reason); logs.add(evidence); }
        String last() { return logs.get(logs.size() - 1); }
        @Override public void close() { lifecycle.pause().stop().destroy(); }
    }
}
