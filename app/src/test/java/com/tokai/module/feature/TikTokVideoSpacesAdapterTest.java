package com.tokai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/** Replays the proven screen component space contract against an actual weighted viewport. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TikTokVideoSpacesAdapterTest {
    private final List<Activity> activities = new ArrayList<>();
    @After public void finishActivities() { for (Activity activity : activities) activity.finish(); }
    public static final class Result {
        final int top;
        final int bottom;
        Result(int top, int bottom) { this.top = top; this.bottom = bottom; }
    }

    public static class Screen {
        public View LLJLLL;
        public View LLJZ;
        // These old Douyin names are unrelated on TikTok and must never be selected.
        public final View i = new View(RuntimeEnvironment.getApplication());
        public final View j = new View(RuntimeEnvironment.getApplication());
        int calls;
        boolean fail;
        Result last;

        public void wq(Result result) {
            calls++;
            last = result;
            if (fail) throw new IllegalStateException("Host reflow failed");
            // ScreenAdaptionComponent.wq and both 03O6 paths use GONE to suppress space height.
            LLJLLL.getLayoutParams().height = LLJLLL.getVisibility() == View.GONE ? 0 : result.top;
            LLJZ.getLayoutParams().height = LLJZ.getVisibility() == View.GONE ? 0 : result.bottom;
            LLJLLL.requestLayout();
            LLJZ.requestLayout();
        }
    }

    public static final class DerivedScreen extends Screen { }
    public static final class EqualScreen extends Screen {
        @Override public boolean equals(Object other) { return other instanceof EqualScreen; }
        @Override public int hashCode() { return 1; }
    }
    public static final class WrongTypes {
        public Object LLJLLL;
        public View LLJZ;
        public void wq(Result result) { }
    }
    public static final class StaticField {
        public static View LLJLLL;
        public View LLJZ;
        public void wq(Result result) { }
    }
    public static final class LegacyOnly {
        public View i;
        public View j;
        public void wq(Result result) { }
    }

    @Test public void hidingTheTwoNativeSpacesExpandsWeightedViewportAndNativeReplayRestoresIt() throws Exception {
        Activity activity = activity();
        Context context = activity;
        DerivedScreen screen = new DerivedScreen();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        screen.LLJLLL = new View(context);
        screen.LLJZ = new View(context);
        View viewport = new View(context);
        root.addView(screen.LLJLLL, new LinearLayout.LayoutParams(-1, 36));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(screen.LLJZ, new LinearLayout.LayoutParams(-1, 92));
        activity.setContentView(root);
        TikTokVideoSpacesAdapter adapter = new TikTokVideoSpacesAdapter(Screen.class.getMethod("wq", Result.class));
        Result nativeResult = new Result(36, 92);
        adapter.rememberResult(screen, nativeResult);
        measure(root);
        assertEquals(872, viewport.getMeasuredHeight());

        for (View space : adapter.views(screen)) space.setVisibility(View.GONE);
        adapter.reflowAll();
        measure(root);
        assertEquals(1000, viewport.getMeasuredHeight());
        assertEquals(0, screen.LLJLLL.getLayoutParams().height);
        assertEquals(0, screen.LLJZ.getLayoutParams().height);
        assertEquals(View.VISIBLE, screen.i.getVisibility());
        assertEquals(View.VISIBLE, screen.j.getVisibility());
        assertSame(nativeResult, screen.last);
        assertEquals(1, screen.calls);

        for (View space : adapter.views(screen)) space.setVisibility(View.VISIBLE);
        adapter.reflowAll();
        measure(root);
        assertEquals(872, viewport.getMeasuredHeight());
        assertEquals(36, screen.LLJLLL.getLayoutParams().height);
        assertEquals(92, screen.LLJZ.getLayoutParams().height);
        assertEquals(2, screen.calls);
        assertEquals(1f, viewport.getScaleX(), 0f);
        assertEquals(1f, viewport.getScaleY(), 0f);
    }

    @Test public void unboundViewsAreAllowedAndOnlyActualNativeResultsAreReplayed() throws Exception {
        Screen screen = new Screen();
        TikTokVideoSpacesAdapter adapter = new TikTokVideoSpacesAdapter(Screen.class.getMethod("wq", Result.class));
        assertNull(adapter.views(screen)[0]);
        assertNull(adapter.views(screen)[1]);
        adapter.reflowAll();
        assertEquals(0, screen.calls);
        assertThrows(IllegalArgumentException.class, () -> adapter.rememberResult(screen, "wrong result"));
        adapter.reflowAll();
        assertEquals(0, screen.calls);
    }

    @Test public void changingNativeResultReplacesTheReplayAndClearStopsFurtherCalls() throws Exception {
        Screen screen = boundScreen();
        TikTokVideoSpacesAdapter adapter = new TikTokVideoSpacesAdapter(Screen.class.getMethod("wq", Result.class));
        adapter.rememberResult(screen, new Result(36, 92));
        Result latest = new Result(20, 40);
        adapter.rememberResult(screen, latest);
        adapter.reflowAll();
        assertSame(latest, screen.last);
        assertEquals(20, screen.LLJLLL.getLayoutParams().height);
        adapter.clear();
        adapter.reflowAll();
        assertEquals(1, screen.calls);
    }

    @Test public void detachedComponentsWaitAndReplayUsesOwnerIdentity() throws Exception {
        TikTokVideoSpacesAdapter adapter = new TikTokVideoSpacesAdapter(Screen.class.getMethod("wq", Result.class));
        EqualScreen first = new EqualScreen();
        EqualScreen second = new EqualScreen();
        Result firstResult = new Result(10, 20);
        Result secondResult = new Result(30, 40);
        adapter.rememberResult(first, firstResult);
        adapter.rememberResult(second, secondResult);
        adapter.reflowAll();
        assertEquals(0, first.calls);
        assertEquals(0, second.calls);
        bind(first);
        bind(second);
        adapter.reflowAll();
        assertEquals(1, first.calls);
        assertEquals(1, second.calls);
        assertSame(firstResult, first.last);
        assertSame(secondResult, second.last);
    }

    @Test public void aNativeFailureIsPropagatedForTheOwningHookGroupToRollBack() throws Exception {
        Screen screen = boundScreen();
        screen.fail = true;
        TikTokVideoSpacesAdapter adapter = new TikTokVideoSpacesAdapter(Screen.class.getMethod("wq", Result.class));
        adapter.rememberResult(screen, new Result(36, 92));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, adapter::reflowAll);
        assertEquals("Host reflow failed", failure.getCause().getMessage());
    }

    @Test public void legacyOrChangedFieldsFailClosedBeforeAnyVisibilityWrite() throws Exception {
        for (Class<?> owner : new Class<?>[]{WrongTypes.class, StaticField.class, LegacyOnly.class}) {
            Method method = owner.getMethod("wq", Result.class);
            assertThrows(ReflectiveOperationException.class, () -> new TikTokVideoSpacesAdapter(method));
        }
    }

    private Screen boundScreen() {
        Screen screen = new Screen();
        bind(screen);
        return screen;
    }

    private void bind(Screen screen) {
        Activity activity = activity();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        screen.LLJLLL = new View(activity);
        screen.LLJZ = new View(activity);
        root.addView(screen.LLJLLL, new LinearLayout.LayoutParams(-1, 36));
        root.addView(screen.LLJZ, new LinearLayout.LayoutParams(-1, 92));
        activity.setContentView(root);
    }

    private Activity activity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activities.add(activity);
        return activity;
    }

    private static void measure(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 500, 1000);
    }
}
