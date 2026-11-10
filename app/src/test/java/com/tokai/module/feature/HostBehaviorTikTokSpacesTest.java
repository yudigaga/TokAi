package com.tokai.module.feature;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.tokai.module.config.LocalOptions;
import com.tokai.module.scan.*;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostBehaviorTikTokSpacesTest {
    public static final class Screen extends TikTokVideoSpacesAdapterTest.Screen {
        @Override public void wq(TikTokVideoSpacesAdapterTest.Result result) { super.wq(result); }
        public void init(View root, Bundle state) { }
        public void restoreTop() { LLJLLL.setVisibility(View.VISIBLE); }
    }

    public static final class Video {
        final int width, height;
        final FakeLandscapeInfo fakeLandscapeInfo;
        Video(int width, int height) { this(width, height, null); }
        Video(int width, int height, FakeLandscapeInfo fakeLandscapeInfo) {
            this.width = width; this.height = height; this.fakeLandscapeInfo = fakeLandscapeInfo;
        }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public FakeLandscapeInfo getFakeLandscapeInfo() { return fakeLandscapeInfo; }
    }
    public static final class FakeLandscapeInfo {
        final float left, top, right, bottom;
        FakeLandscapeInfo(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public float getLeft() { return left; }
        public float getTop() { return top; }
        public float getRight() { return right; }
        public float getBottom() { return bottom; }
    }
    public static final class Aweme {
        final Video video;
        Aweme(Video video) { this.video = video; }
        public Video getVideo() { return video; }
        public Object getPhotoModeImageInfo() { return null; }
        public Object getRoomFeedCellStruct() { return null; }
    }
    public static final class PaddingValues {
        final int top, bottom;
        PaddingValues(int top, int bottom) { this.top = top; this.bottom = bottom; }
        public int getTop() { return top; }
        public int getBottom() { return bottom; }
    }
    public static final class Operator {
        final PaddingValues paddingValues;
        Operator() { this(new PaddingValues(0, 0)); }
        Operator(PaddingValues paddingValues) { this.paddingValues = paddingValues; }
        public PaddingValues getPaddingValues() { return paddingValues; }
    }
    public static final class VideoResult {
        final int width, height;
        final Float x, y;
        final Operator operator;
        public VideoResult(int width, int height, Float x, Float y, Operator operator) {
            this.width = width; this.height = height; this.x = x; this.y = y; this.operator = operator;
        }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public Float getTranslateX() { return x; }
        public Float getTranslateY() { return y; }
        public Operator getResultOperator() { return operator; }
    }
    public static final class VideoCell {
        View video, cover;
        public View getVideoView() { return video; }
        public View dG1() { return cover; }
    }
    public static final class Cell {
        public FrameLayout LLJLL, LLJZ, LLJLLIL;
        public LinearLayout LLJLLL;
        public View LLLJIL, LLLJL, LLLL, LLLLII;
        Aweme aweme;
        final VideoCell cell = new VideoCell();
        VideoResult nativeResult, applied;
        public Aweme Fp() { return aweme; }
        public VideoCell Op() { return cell; }
        public VideoResult lN2() { return nativeResult; }
        public void onViewCreated(View root) { }
        public void onRenderFirstFrame(String aid) { }
        public void unBind() { nativeResult = null; }
        public void onHostDestroy() { }
        public void lq(PaddingValues padding) {
            if (padding == null) return;
            View[] spaces = {LLLJIL, LLLJL, LLLL, LLLLII};
            for (int index = 0; index < spaces.length; index++) {
                View space = spaces[index];
                if (space == null) continue;
                ViewGroup.LayoutParams params = space.getLayoutParams();
                params.height = index % 2 == 0 ? padding.getTop() : padding.getBottom();
                space.setLayoutParams(params);
            }
        }
        public void aq(VideoResult result, String reason) {
            applied = result;
            if (result == null) return;
            for (View view : new View[]{LLJZ, LLJLL, cell.cover, cell.video}) {
                if (view == null) continue;
                ViewGroup.LayoutParams params = view.getLayoutParams();
                params.width = result.width; params.height = result.height;
                view.setLayoutParams(params);
                if (result.x != null) view.setTranslationX(result.x);
                if (result.y != null) view.setTranslationY(result.y);
            }
        }
    }

    @Test public void lateCellPaddingCannotRecreateThe136PixelTopGapAfterScreenSpacesAreHidden() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Screen screen = new Screen();
        Cell component = new Cell();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        screen.LLJLLL = new View(activity);
        screen.LLJZ = new View(activity);
        FrameLayout stage = new FrameLayout(activity);
        root.addView(screen.LLJLLL, new LinearLayout.LayoutParams(-1, 0));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(screen.LLJZ, new LinearLayout.LayoutParams(-1, 0));

        // TikTok bcs.xml has its own dug/du1 spaces around the weighted video viewport.
        component.LLJLLL = new LinearLayout(activity);
        component.LLJLLL.setOrientation(LinearLayout.VERTICAL);
        component.LLLJIL = cellSpace(activity);
        component.LLLJL = cellSpace(activity);
        component.LLJLLIL = new FrameLayout(activity);
        component.LLJLLL.addView(component.LLLJIL, new LinearLayout.LayoutParams(-1, 0));
        component.LLJLLL.addView(component.LLJLLIL, new LinearLayout.LayoutParams(-1, 0, 1));
        component.LLJLLL.addView(component.LLLJL, new LinearLayout.LayoutParams(-1, 0));
        stage.addView(component.LLJLLL, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout player = new FrameLayout(activity);
        component.cell.video = new View(activity);
        player.addView(component.cell.video, new FrameLayout.LayoutParams(1440, 2560, Gravity.CENTER));
        component.LLJLLIL.addView(player, new FrameLayout.LayoutParams(-1, -1));
        component.LLJZ = new FrameLayout(activity);
        component.LLJLL = new FrameLayout(activity);
        component.LLJLLIL.addView(component.LLJZ, new FrameLayout.LayoutParams(1440, 2560, Gravity.CENTER));
        component.LLJLLIL.addView(component.LLJLL, new FrameLayout.LayoutParams(1440, 2560, Gravity.CENTER));

        // TikTok bcv.xml independently repeats the same padding around its sticker viewport.
        LinearLayout stickerArea = new LinearLayout(activity);
        stickerArea.setOrientation(LinearLayout.VERTICAL);
        component.LLLL = cellSpace(activity);
        component.LLLLII = cellSpace(activity);
        FrameLayout stickerViewport = new FrameLayout(activity);
        stickerArea.addView(component.LLLL, new LinearLayout.LayoutParams(-1, 0));
        stickerArea.addView(stickerViewport, new LinearLayout.LayoutParams(-1, 0, 1));
        stickerArea.addView(component.LLLLII, new LinearLayout.LayoutParams(-1, 0));
        stage.addView(stickerArea, new FrameLayout.LayoutParams(-1, -1));
        View controls = new View(activity);
        stage.addView(controls, new FrameLayout.LayoutParams(100, 200, Gravity.RIGHT | Gravity.BOTTOM));
        activity.setContentView(root);
        component.aweme = new Aweme(new Video(720, 1280));
        component.nativeResult = new VideoResult(1440, 2560, 12f, 24f, new Operator());
        Harness harness = new Harness();
        Method padding = Cell.class.getMethod("lq", PaddingValues.class);
        Method apply = Cell.class.getMethod("aq", VideoResult.class, String.class);
        try (HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.api,
                new HostScanReport("fixture", spaceRules(), false), getClass().getClassLoader(),
                null, new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { })) {
            harness.invoke(Screen.class.getMethod("wq", TikTokVideoSpacesAdapterTest.Result.class), screen,
                    new TikTokVideoSpacesAdapterTest.Result(0, 0));
            harness.invoke(padding, component, component.nativeResult.operator.getPaddingValues());
            measure(root, 1440, 3200);
            harness.invoke(apply, component, component.nativeResult, "initial full viewport");
            measure(root, 1440, 3200);
            assertEquals(3200, component.applied.height);
            int controlsLeft = controls.getLeft(), controlsTop = controls.getTop();

            // FeedCell.Wp applies lq(ax0()) BEFORE super.Wp reaches the hooked aq method.
            // Re-delivery of native operator padding reproduced viewport 3200 -> 3064 in the log.
            component.nativeResult = new VideoResult(1440, 2560, 12f, 24f,
                    new Operator(new PaddingValues(136, 0)));
            harness.invoke(padding, component, component.nativeResult.operator.getPaddingValues());
            measure(root, 1440, 3200);
            harness.invoke(apply, component, component.nativeResult, "late native cell padding");
            measure(root, 1440, 3200);
            assertEquals(View.GONE, screen.LLJLLL.getVisibility());
            assertEquals(View.GONE, screen.LLJZ.getVisibility());
            assertEquals("Cell padding must not shrink the viewport after the cover hook has run; output="
                            + component.applied.width + "x" + component.applied.height,
                    3200, component.LLJLLIL.getHeight());
            assertEquals(3200, stickerViewport.getHeight());
            View video = component.cell.video;
            assertEquals(1800, video.getWidth());
            assertEquals(3200, video.getHeight());
            assertEquals("The video must cover screen y=0, not only its local viewport origin",
                    0, component.LLJLLL.getTop() + component.LLJLLIL.getTop()
                            + player.getTop() + video.getTop());
            assertEquals(0f, video.getTranslationY(), 0f);
            for (View space : new View[]{component.LLLJIL, component.LLLJL, component.LLLL, component.LLLLII}) {
                assertEquals(View.GONE, space.getVisibility());
            }
            assertEquals(controlsLeft, controls.getLeft());
            assertEquals(controlsTop, controls.getTop());
            assertEquals(1f, controls.getScaleX(), 0f);
            assertEquals(1f, controls.getScaleY(), 0f);

            // Hidden spaces keep receiving the native dimensions, so disable restores the latest values.
            harness.invoke(padding, component, new PaddingValues(136, 44));
            measure(root, 1440, 3200);
            assertEquals(3200, component.LLJLLIL.getHeight());
            hooks.setOptions(new LocalOptions(Collections.emptyMap()));
            measure(root, 1440, 3200);
            assertEquals(3020, component.LLJLLIL.getHeight());
            assertEquals(3020, stickerViewport.getHeight());
            assertSame(component.nativeResult, component.applied);
            assertEquals(1440, video.getWidth());
            assertEquals(2560, video.getHeight());
            for (View space : new View[]{component.LLLJIL, component.LLLJL, component.LLLL, component.LLLLII}) {
                assertEquals(View.INVISIBLE, space.getVisibility());
            }
            assertEquals(136, component.LLLJIL.getLayoutParams().height);
            assertEquals(44, component.LLLJL.getLayoutParams().height);
            assertEquals(controlsLeft, controls.getLeft());
            assertEquals(controlsTop, controls.getTop());

            hooks.setOptions(new LocalOptions(Map.of("hide_video_black_edges", true)));
            measure(root, 1440, 3200);
            harness.invoke(padding, component, new PaddingValues(136, 52));
            measure(root, 1440, 3200);
            harness.invoke(apply, component, component.nativeResult, "native padding rebound");
            measure(root, 1440, 3200);
            assertEquals(3200, component.LLJLLIL.getHeight());
            assertEquals(3200, video.getHeight());
            hooks.close();
            measure(root, 1440, 3200);
            assertEquals(3012, component.LLJLLIL.getHeight());
            assertEquals(3012, stickerViewport.getHeight());
            assertSame(component.nativeResult, component.applied);
            assertEquals(1440, video.getWidth());
            assertEquals(2560, video.getHeight());
        } finally { activity.finish(); }
    }

    private static View cellSpace(Activity activity) {
        View view = new View(activity);
        view.setVisibility(View.INVISIBLE);
        return view;
    }

    @Test public void sourceVideoCoversTheWholeScreenWithoutScalingControls() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Screen screen = new Screen();
        Cell component = new Cell();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        component.LLJLLL = root;
        screen.LLJLLL = new View(activity); screen.LLJZ = new View(activity);
        component.LLJLLIL = new FrameLayout(activity);
        FrameLayout player = new FrameLayout(activity);
        component.cell.video = new View(activity);
        component.LLJZ = new FrameLayout(activity);
        component.LLJLL = new FrameLayout(activity);
        component.cell.cover = new View(activity);
        player.addView(component.cell.video, new FrameLayout.LayoutParams(1440, 2560, Gravity.CENTER));
        component.LLJZ.addView(component.cell.cover, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        component.LLJLLIL.addView(player, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        component.LLJLLIL.addView(component.LLJZ, new FrameLayout.LayoutParams(1440, 2560, Gravity.CENTER));
        component.LLJLLIL.addView(component.LLJLL, new FrameLayout.LayoutParams(1440, 2560, Gravity.CENTER));
        View controls = new View(activity);
        component.LLJLLIL.addView(controls, new FrameLayout.LayoutParams(100, 200, Gravity.RIGHT | Gravity.BOTTOM));
        root.addView(screen.LLJLLL, new LinearLayout.LayoutParams(-1, 72));
        root.addView(component.LLJLLIL, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(screen.LLJZ, new LinearLayout.LayoutParams(-1, 152));
        activity.setContentView(root);
        component.aweme = new Aweme(new Video(1440, 2560));
        Aweme portraitAweme = component.aweme;
        component.nativeResult = new VideoResult(1440, 2560, 12f, 24f, new Operator());
        VideoResult portraitNativeResult = component.nativeResult;
        Harness harness = new Harness();
        List<RuleScanResult> rules = spaceRules();
        Method apply = Cell.class.getMethod("aq", VideoResult.class, String.class);
        try (HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.api, new HostScanReport("fixture", rules, false),
                getClass().getClassLoader(), null, new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { })) {
            harness.invoke(Screen.class.getMethod("wq", TikTokVideoSpacesAdapterTest.Result.class), screen,
                    new TikTokVideoSpacesAdapterTest.Result(72, 152));
            measure(root, 1440, 3200);
            int controlsLeft = controls.getLeft(), controlsTop = controls.getTop();
            harness.invoke(apply, component, component.nativeResult, "video adaption finish");
            measure(root, 1440, 3200);
            View video = component.cell.video;
            assertEquals("viewport=" + component.LLJLLIL.getWidth() + "x" + component.LLJLLIL.getHeight()
                    + ", video=" + video.getWidth() + "x" + video.getHeight()
                    + ", applied=" + component.applied.width + "x" + component.applied.height,
                    3200, video.getHeight());
            assertEquals("Equal scaling preserves the source aspect ratio", 1800, video.getWidth());
            assertEquals(-180, video.getLeft());
            assertEquals(0, video.getTop());
            assertEquals(0f, video.getTranslationX(), 0f);
            assertEquals(0f, video.getTranslationY(), 0f);
            assertEquals(1800, component.LLJZ.getWidth());
            assertEquals(3200, component.LLJZ.getHeight());
            assertEquals(1800, component.LLJLL.getWidth());
            assertEquals(3200, component.LLJLL.getHeight());
            assertEquals(1f, controls.getScaleX(), 0f);
            assertEquals(1f, controls.getScaleY(), 0f);
            assertEquals(controlsLeft, controls.getLeft());
            assertEquals(controlsTop, controls.getTop());

            component.aweme = new Aweme(new Video(2560, 1440,
                    new FakeLandscapeInfo(0.05f, 0.10f, 0.85f, 0.80f)));
            component.nativeResult = new VideoResult(1000, 1800, -40f, 110f, new Operator());
            harness.invoke(apply, component, component.nativeResult, "fake landscape adaption");
            measure(root, 1440, 3200);
            assertNotSame("The native fake-landscape contain dimensions must be replaced by cover dimensions",
                    component.nativeResult, component.applied);
            assertEquals(2540, video.getWidth());
            assertEquals(4572, video.getHeight());
            assertEquals(127f, video.getTranslationX(), 0.1f);
            assertEquals(228.6f, video.getTranslationY(), 0.1f);
            double visibleLeft = video.getLeft() + video.getTranslationX() + video.getWidth() * 0.05d;
            double visibleRight = video.getLeft() + video.getTranslationX() + video.getWidth() * 0.85d;
            double visibleTop = video.getTop() + video.getTranslationY() + video.getHeight() * 0.10d;
            double visibleBottom = video.getTop() + video.getTranslationY() + video.getHeight() * 0.80d;
            assertTrue("Fake-landscape visible crop must cover viewport width", visibleLeft <= 0 && visibleRight >= 1440);
            assertEquals("Fake-landscape visible crop must align at viewport top", 0d, visibleTop, 1d);
            assertEquals("Fake-landscape visible crop must reach viewport bottom", 3200d, visibleBottom, 1d);

            component.aweme = portraitAweme;
            component.nativeResult = portraitNativeResult;
            harness.invoke(apply, component, component.nativeResult, "portrait video rebound");
            measure(root, 1440, 3200);
            assertEquals(1800, video.getWidth());
            assertEquals(3200, video.getHeight());

            hooks.setOptions(new LocalOptions(Collections.emptyMap()));
            measure(root, 1440, 3200);
            assertSame("Disabling must replay the last genuine host result", component.nativeResult, component.applied);
            assertEquals(1440, video.getWidth());
            assertEquals(2560, video.getHeight());
            assertEquals(12f, video.getTranslationX(), 0f);
            assertEquals(24f, video.getTranslationY(), 0f);
            hooks.setOptions(new LocalOptions(Map.of("hide_video_black_edges", true)));
            measure(root, 1440, 3200);
            harness.invoke(apply, component, component.nativeResult, "native viewport changed");
            measure(root, 1440, 3200);
            assertEquals("top=" + screen.LLJLLL.getVisibility() + "/" + screen.LLJLLL.getLayoutParams().height
                    + ", bottom=" + screen.LLJZ.getVisibility() + "/" + screen.LLJZ.getLayoutParams().height
                    + ", viewport=" + component.LLJLLIL.getHeight() + ", applied=" + component.applied.height,
                    3200, video.getHeight());
            hooks.close();
            assertSame("Closing must restore the host's native dimensions", component.nativeResult, component.applied);
            measure(root, 1440, 3200);
            assertEquals(1440, video.getWidth());
            assertEquals(2560, video.getHeight());
        } finally { activity.finish(); }
    }

    private static List<RuleScanResult> spaceRules() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        Method update = Screen.class.getMethod("wq", TikTokVideoSpacesAdapterTest.Result.class);
        rules.add(rule("tiktok_feed_materialized", update));
        rules.add(rule("adv_ui_black_spaces", update));
        rules.add(rule("adv_ui_black_spaces_init", Screen.class.getMethod("init", View.class, Bundle.class)));
        rules.add(rule("adv_ui_black_spaces_restore", Screen.class.getMethod("restoreTop")));
        rules.add(rule("adv_video_cell_spaces", Cell.class.getMethod("lq", PaddingValues.class)));
        addVideoRules(rules);
        return rules;
    }

    private static void addVideoRules(List<RuleScanResult> rules) throws Exception {
        for (String[] target : new String[][]{
                {"adv_video_cover_apply", "aq"}, {"adv_video_cover_aweme", "Fp"},
                {"adv_video_cover_cell", "Op"}, {"adv_video_cover_current", "lN2"},
                {"adv_video_cover_init", "onViewCreated"}, {"adv_video_cover_unbind", "unBind"},
                {"adv_video_cover_destroy", "onHostDestroy"}, {"adv_video_cover_first_frame", "onRenderFirstFrame"}}) {
            for (Method method : Cell.class.getDeclaredMethods()) {
                if (method.getName().equals(target[1])) rules.add(rule(target[0], method));
            }
        }
        for (String[] target : new String[][]{
                {"aweme_video", "getVideo"}, {"aweme_is_image", "getPhotoModeImageInfo"},
                {"aweme_is_live", "getRoomFeedCellStruct"}}) {
            for (Method method : Aweme.class.getDeclaredMethods()) {
                if (method.getName().equals(target[1])) rules.add(rule(target[0], method));
            }
        }
        for (String[] target : new String[][]{
                {"adv_video_cover_source_width", "getWidth"}, {"adv_video_cover_source_height", "getHeight"},
                {"adv_video_cover_source_fake_landscape", "getFakeLandscapeInfo"}}) {
            for (Method method : Video.class.getDeclaredMethods()) {
                if (method.getName().equals(target[1])) rules.add(rule(target[0], method));
            }
        }
        rules.add(rule("adv_video_cell_render_view", VideoCell.class.getMethod("getVideoView")));
        rules.add(new RuleScanResult("adv_video_cover_result", "fixture", List.of(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(VideoResult.class), "", ""))));
    }

    private static RuleScanResult rule(String id, Method method) {
        return new RuleScanResult(id, "fixture", List.of(new HostCandidate("fixture", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method))));
    }

    @Test public void nativeSpacesAreHiddenBeforeAdaptationAndLiveDisableRestoresTheViewport() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Screen screen = new Screen();
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        screen.LLJLLL = new View(activity); screen.LLJZ = new View(activity);
        View viewport = new View(activity);
        root.addView(screen.LLJLLL, new LinearLayout.LayoutParams(-1, 36));
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(screen.LLJZ, new LinearLayout.LayoutParams(-1, 92));
        activity.setContentView(root);
        Harness harness = new Harness();
        Method update = Screen.class.getMethod("wq", TikTokVideoSpacesAdapterTest.Result.class);
        List<RuleScanResult> rules = spaceRules();
        try (HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.api, new HostScanReport("fixture", rules, false),
                getClass().getClassLoader(), null, new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { })) {
            assertTrue("TikTok native spaces must have a working installation", hooks.supports("hide_video_black_edges"));
            TikTokVideoSpacesAdapterTest.Result result = new TikTokVideoSpacesAdapterTest.Result(36, 92);
            harness.invoke(update, screen, result);
            measure(root);
            assertEquals("The host must observe GONE before calculating native space heights", 0, screen.LLJLLL.getLayoutParams().height);
            assertEquals(1000, viewport.getMeasuredHeight());
            harness.invoke(Screen.class.getMethod("restoreTop"), screen);
            assertEquals("Native restore callbacks must not bypass the active option", View.GONE, screen.LLJLLL.getVisibility());
            hooks.setOptions(new LocalOptions(Collections.emptyMap()));
            measure(root);
            assertEquals(View.VISIBLE, screen.LLJLLL.getVisibility());
            assertEquals(36, screen.LLJLLL.getLayoutParams().height);
            assertEquals(92, screen.LLJZ.getLayoutParams().height);
            assertEquals(872, viewport.getMeasuredHeight());
            hooks.setOptions(new LocalOptions(Map.of("hide_video_black_edges", true)));
            measure(root);
            assertEquals(1000, viewport.getMeasuredHeight());
            hooks.close();
            measure(root);
            assertEquals("Closing the module restores native space dimensions", 872, viewport.getMeasuredHeight());
        } finally { activity.finish(); }
    }

    private static void measure(View view) {
        measure(view, 500, 1000);
    }

    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
            if (!method.getName().equals("hook")) return null;
            Method target = (Method) args[0];
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                if (!call.getName().equals("intercept")) return builder;
                hooks.put(target, (XposedInterface.Hooker) values[0]);
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, ignored) -> { if (action.getName().equals("unhook")) hooks.remove(target); return null; });
            });
        });
        Object invoke(Method method, Object owner, Object... args) throws Throwable {
            if (!hooks.containsKey(method)) return method.invoke(owner, args);
            return hooks.get(method).intercept((XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class}, (proxy, call, values) -> {
                if (call.getName().equals("getThisObject")) return owner;
                if (call.getName().equals("getArg")) return args[(Integer) values[0]];
                if (call.getName().equals("getArgs")) return Arrays.asList(args);
                if (call.getName().equals("proceed")) {
                    Object[] forwarded = values != null && values.length > 0 ? (Object[]) values[0] : args;
                    try { return method.invoke(owner, forwarded); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                }
                return null;
            }));
        }
    }
}
