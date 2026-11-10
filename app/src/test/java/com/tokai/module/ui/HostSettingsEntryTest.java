package com.tokai.module.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.tokai.module")
public final class HostSettingsEntryTest {
    @Test
    public void insertsOnceIntoSettingsScrollAndOpensTheSameInHostPanel() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(new View(activity));
        content.addView(new View(activity));
        scroll.addView(content);
        activity.setContentView(scroll);
        AtomicInteger clicks = new AtomicInteger();
        View entry = HostSettingsEntry.attach(activity, ScrollView.class, clicks::incrementAndGet);
        assertNotNull(entry);
        assertSame(entry, content.getChildAt(1));
        entry.performClick();
        assertEquals(1, clicks.get());
        assertSame(entry, HostSettingsEntry.attach(activity, ScrollView.class, clicks::incrementAndGet));
        assertEquals(3, content.getChildCount());
        activity.finish();
    }

    @Test
    public void refusesAmbiguousHostScrollContainers() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        root.addView(new ScrollView(activity));
        root.addView(new ScrollView(activity));
        activity.setContentView(root);
        assertNull(HostSettingsEntry.attach(activity, ScrollView.class,
                () -> fail("No entry should be installed")));
        assertEquals(2, root.getChildCount());
        activity.finish();
    }

    @Test
    public void composeRootIsWrappedBeforeAttachmentAndKeepsTheSameNativeView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout compose = new FrameLayout(activity);
        AtomicInteger clicks = new AtomicInteger();
        View wrapped = HostSettingsEntry.wrapCompose(activity, compose, clicks::incrementAndGet);
        assertTrue(wrapped instanceof LinearLayout);
        LinearLayout root = (LinearLayout) wrapped;
        assertSame(compose, root.getChildAt(1));
        assertEquals(1f, ((LinearLayout.LayoutParams) compose.getLayoutParams()).weight, 0f);
        activity.setContentView(wrapped);
        View entry = HostSettingsEntry.attachCompose(activity, compose, clicks::incrementAndGet);
        assertSame(root.getChildAt(0), entry);
        entry.performClick();
        assertEquals(1, clicks.get());
        assertSame(entry, HostSettingsEntry.attachCompose(activity, compose, clicks::incrementAndGet));
        assertEquals(2, root.getChildCount());
        activity.finish();
    }

    @Test
    public void refusesToReparentAnAlreadyAttachedFragmentRoot() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout compose = new FrameLayout(activity);
        activity.setContentView(compose);
        ViewGroup originalParent = (ViewGroup) compose.getParent();
        assertNull(HostSettingsEntry.attachCompose(activity, compose, () -> fail("No entry")));
        assertSame(originalParent, compose.getParent());
        try {
            HostSettingsEntry.wrapCompose(activity, compose, () -> fail("No entry"));
            fail("Already attached root must not be reparented");
        } catch (IllegalStateException expected) { }
        activity.finish();
    }
}
