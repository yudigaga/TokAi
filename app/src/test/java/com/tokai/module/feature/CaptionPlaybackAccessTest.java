package com.tokai.module.feature;

import android.view.View;
import android.widget.FrameLayout;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Typed holder/metadata fixture replay, without a player, network, Hook runtime or device. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class CaptionPlaybackAccessTest {
    public static final class CaptionModel { public List<Object> captionList; }
    public static final class Video {
        CaptionModel captions;
        boolean fail;
        public CaptionModel getCaptionModel() {
            if (fail) throw new IllegalStateException("metadata unavailable");
            return captions;
        }
    }
    public static final class Work {
        String aid = "work-a";
        Video video = new Video();
        boolean burned, foreign, lite, translated, translatedUnknown;
        public Video getVideo() { return video; }
        public String getAid() { return aid; }
        public boolean getIsPlayingTranslatedVideo() {
            if (translatedUnknown) throw new IllegalStateException("translated status unknown");
            return translated;
        }
    }
    public static final class Media {
        String aid = "work-a";
        public String getSourceId() { return aid; }
    }
    public interface Clock {
        long getCurrentPosition();
        Media LJIL();
    }
    public static final class Player implements Clock {
        long position = 1234;
        boolean fail;
        Media media = new Media();
        Runnable duringRead;
        @Override public long getCurrentPosition() {
            if (fail) throw new IllegalStateException("clock unavailable");
            if (duringRead != null) duringRead.run();
            return position;
        }
        @Override public Media LJIL() { return media; }
    }
    public static final class Panel {
        Holder current;
        Player player = new Player();
        boolean fail;
        public Holder LJLJLLL() { if (fail) throw new IllegalStateException("panel unavailable"); return current; }
        public Player getPlayerManager() { return player; }
    }
    public static final class Holder {
        public View u = new FrameLayout(RuntimeEnvironment.getApplication());
        public Panel LLLZLZ;
        Work work = new Work();
        public Work getAweme() { return work; }
        public void LJJIJIL(int index) { }
        public void onPageSelected(int index) { }
        public void onResumePlay(String aid) { }
        public void onPausePlay(String aid) { }
        public void su(boolean down) { }
    }
    public static final class Policy {
        static boolean fail;
        public static boolean burned(Work work, Boolean original) { check(); return work.burned; }
        public static boolean foreign(Work work, Boolean original) { check(); return work.foreign; }
        public static boolean lite(Work work) { check(); return work.lite; }
        static void check() { if (fail) throw new IllegalStateException("policy unavailable"); }
        public static Object badLite(Work work) { return false; }
    }
    public interface Keva { boolean pending(String aid); }
    public static final class Service implements Keva {
        boolean pending, fail;
        @Override public boolean pending(String aid) { if (fail) throw new IllegalStateException("pending unknown"); return pending; }
    }
    public static final class Services {
        static Service instance;
        public static Keva get() { return instance; }
    }

    @Before public void resetPolicies() { Policy.fail = false; Services.instance = new Service(); }

    @Test public void resolvesFiveCallbacksAndReadsTheCapturedHolderFields() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        assertEquals(method(Holder.class, "LJJIJIL", int.class), access.selected);
        assertEquals(method(Holder.class, "onPageSelected", int.class), access.pageSelected);
        assertEquals(method(Holder.class, "onResumePlay", String.class), access.resumed);
        assertEquals(method(Holder.class, "onPausePlay", String.class), access.paused);
        assertEquals(method(Holder.class, "su", boolean.class), access.unselected);
        assertSame(f.holder.work, access.aweme(f.holder)); assertSame(f.holder.u, access.root(f.holder));
        assertTrue(access.isCurrent(f.holder));
    }

    @Test public void readsPanelPlayerMillisecondsWithoutUsingAnyGlobalPlayer() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        assertEquals(1234, access.positionMillis(f.holder));
        f.panel.player.position = 0; assertEquals(0, access.positionMillis(f.holder));
        f.panel.player.position = 65_432; assertEquals(65_432, access.positionMillis(f.holder));
    }

    @Test public void currentHolderUsesObjectIdentityEvenForDuplicateWorkIds() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        Holder other = new Holder(); other.LLLZLZ = f.panel;
        assertEquals(f.holder.work.aid, other.work.aid);
        assertFalse(access.isCurrent(other)); assertEquals(-1, access.positionMillis(other));
        f.panel.current = other;
        assertFalse(access.isCurrent(f.holder)); assertEquals(-1, access.positionMillis(f.holder));
        assertTrue(access.isCurrent(other));
    }

    @Test public void nullMissingWrongOrFailingPanelAndPlayerDoNotReturnAClock() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        assertNull(access.aweme(null)); assertNull(access.root(new Object()));
        assertFalse(access.isCurrent(null)); assertEquals(-1, access.positionMillis(new Object()));
        f.holder.LLLZLZ = null; assertFalse(access.isCurrent(f.holder)); assertEquals(-1, access.positionMillis(f.holder));
        f.holder.LLLZLZ = f.panel; f.panel.fail = true;
        assertFalse(access.isCurrent(f.holder)); assertEquals(-1, access.positionMillis(f.holder));
        f.panel.fail = false; f.panel.player = null; assertEquals(-1, access.positionMillis(f.holder));
    }

    @Test public void negativeFailedAndUnknownSourceClocksStayUnavailable() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        f.panel.player.position = -10; assertEquals(-1, access.positionMillis(f.holder));
        f.panel.player.position = 4000; f.panel.player.fail = true; assertEquals(-1, access.positionMillis(f.holder));
        f.panel.player.fail = false; f.panel.player.media = null; assertEquals(-1, access.positionMillis(f.holder));
        f.panel.player.media = new Media(); f.panel.player.media.aid = null; assertEquals(-1, access.positionMillis(f.holder));
        f.panel.player.media.aid = ""; assertEquals(-1, access.positionMillis(f.holder));
        f.panel.player.media.aid = "old-work"; assertEquals(-1, access.positionMillis(f.holder));
    }

    @Test public void selectionWorkOrActiveSourceChangingDuringClockReadIsDiscarded() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        f.panel.player.duringRead = () -> f.panel.current = new Holder();
        assertEquals(-1, access.positionMillis(f.holder));
        f.panel.current = f.holder;
        f.panel.player.duringRead = () -> f.holder.work.aid = "work-b";
        assertEquals(-1, access.positionMillis(f.holder));
        f.holder.work.aid = "work-a";
        f.panel.player.duringRead = () -> f.panel.player.media.aid = "work-b";
        assertEquals(-1, access.positionMillis(f.holder));
    }

    @Test public void aRealNonemptyCaptionListPreservesNativeCaptionsWithoutRequiringAnyGetterForTheList() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        f.holder.work.video.captions = new CaptionModel();
        f.holder.work.video.captions.captionList = List.of(new Object());
        assertTrue(access.hasNativeCaptions(f.holder.work));
    }

    @Test public void nullModelAndNullOrEmptyCaptionListsCanBeKnownAbsenceWhenAllGuardsSucceed() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        assertFalse(access.hasNativeCaptions(f.holder.work));
        f.holder.work.video.captions = new CaptionModel();
        assertFalse(access.hasNativeCaptions(f.holder.work));
        f.holder.work.video.captions.captionList = List.of();
        assertFalse(access.hasNativeCaptions(f.holder.work));
    }

    @Test public void absentWorkVideoIdentityOrUnknownMetadataNeverStartsRecognition() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        assertTrue(access.hasNativeCaptions(null)); assertTrue(access.hasNativeCaptions(new Object()));
        f.holder.work.video = null; assertTrue(access.hasNativeCaptions(f.holder.work));
        f.holder.work.video = new Video(); f.holder.work.video.fail = true;
        assertTrue(access.hasNativeCaptions(f.holder.work));
        f.holder.work.video.fail = false; f.holder.work.aid = null;
        assertTrue(access.hasNativeCaptions(f.holder.work));
        f.holder.work.aid = ""; assertTrue(access.hasNativeCaptions(f.holder.work));
    }

    @Test public void burnedInForeignOriginalLiteAndPendingRemovalGatesStillBlockModuleAsr() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        f.holder.work.burned = true; assertTrue(access.hasNativeCaptions(f.holder.work)); f.holder.work.burned = false;
        f.holder.work.foreign = true; assertTrue(access.hasNativeCaptions(f.holder.work)); f.holder.work.foreign = false;
        f.holder.work.lite = true; assertTrue(access.hasNativeCaptions(f.holder.work)); f.holder.work.lite = false;
        Services.instance.pending = true; assertTrue(access.hasNativeCaptions(f.holder.work));
    }

    @Test public void aFailingPolicyOrMissingKevaFailsClosedInsteadOfAssumingNoNativeCaptions() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        Policy.fail = true; assertTrue(access.hasNativeCaptions(f.holder.work));
        Policy.fail = false; Services.instance = null; assertTrue(access.hasNativeCaptions(f.holder.work));
        Services.instance = new Service(); Services.instance.fail = true;
        assertTrue(access.hasNativeCaptions(f.holder.work));
    }

    @Test public void sameAidTranslatedVariantSwitchBlocksLateOriginalAudioResultsAndUnknownStatusFailsClosed() throws Exception {
        Fixture f = new Fixture(); CaptionPlaybackAccess access = f.access();
        assertFalse(access.hasNativeCaptions(f.holder.work));
        String originalAid = f.holder.work.aid;
        f.holder.work.translated = true;
        assertEquals(originalAid, f.holder.work.aid);
        assertTrue(access.hasNativeCaptions(f.holder.work));
        f.holder.work.translated = false;
        assertFalse(access.hasNativeCaptions(f.holder.work));
        f.holder.work.translatedUnknown = true;
        assertTrue(access.hasNativeCaptions(f.holder.work));
    }

    @Test public void missingNonuniqueWrongShapedAndNonTikTokBindingsFailAtConstruction() throws Exception {
        Fixture f = new Fixture();
        f.rows.remove("tiktok_caption_player_position"); assertRejected(f);
        f = new Fixture(); f.rows.put("tiktok_caption_player_position", new RuleScanResult("tiktok_caption_player_position", "interface", List.of()));
        assertRejected(f);
        f = new Fixture(); f.rows.put("tiktok_caption_lite_gate", result("tiktok_caption_lite_gate", method(Policy.class, "badLite", Work.class)));
        assertRejected(f);
        f = new Fixture(); f.rows.remove("tiktok_feed_materialized"); assertRejected(f);
    }

    private static void assertRejected(Fixture f) throws Exception {
        try { f.access(); fail("Invalid host contract must reject installation"); }
        catch (IllegalStateException | ReflectiveOperationException expected) { }
    }

    private static Method method(Class<?> owner, String name, Class<?>... arguments) throws Exception {
        return owner.getMethod(name, arguments);
    }
    private static RuleScanResult result(String id, Method target) {
        return new RuleScanResult(id, "interface", List.of(new HostCandidate("base.apk", "classes.dex",
                HostTargets.descriptor(target.getDeclaringClass()), target.getName(), HostTargets.descriptor(target))));
    }
    private static RuleScanResult type(String id, Class<?> target) {
        return new RuleScanResult(id, "interface", List.of(new HostCandidate("base.apk", "classes.dex",
                HostTargets.descriptor(target), "", "")));
    }
    private static final class Fixture {
        final Map<String, RuleScanResult> rows = new HashMap<>();
        final Holder holder = new Holder();
        final Panel panel = new Panel();
        Fixture() throws Exception {
            holder.LLLZLZ = panel; panel.current = holder;
            rows.put("tiktok_feed_materialized", type("tiktok_feed_materialized", Holder.class));
            add("feed_holder_selected", Holder.class, "LJJIJIL", int.class);
            add("feed_holder_page_selected", Holder.class, "onPageSelected", int.class);
            add("component_player_playing", Holder.class, "onResumePlay", String.class);
            add("component_player_pause", Holder.class, "onPausePlay", String.class);
            add("tiktok_caption_holder_unselected", Holder.class, "su", boolean.class);
            add("feed_aweme_accessor", Holder.class, "getAweme");
            rows.put("feed_holder_main_root", type("feed_holder_main_root", Holder.class));
            rows.put("tiktok_caption_holder_panel", type("tiktok_caption_holder_panel", Holder.class));
            add("auto_slide_current_holder", Panel.class, "LJLJLLL");
            add("adv_gesture_panel_player", Panel.class, "getPlayerManager");
            add("tiktok_caption_player_position", Clock.class, "getCurrentPosition");
            add("tiktok_caption_player_media", Clock.class, "LJIL");
            add("tiktok_caption_playback_aid", Media.class, "getSourceId");
            add("tiktok_caption_aweme_id", Work.class, "getAid");
            add("tiktok_caption_translated_playback", Work.class, "getIsPlayingTranslatedVideo");
            add("tiktok_caption_aweme_video", Work.class, "getVideo");
            add("tiktok_caption_video_metadata", Video.class, "getCaptionModel");
            rows.put("tiktok_caption_caption_items", type("tiktok_caption_caption_items", CaptionModel.class));
            add("tiktok_caption_burned_in_gate", Policy.class, "burned", Work.class, Boolean.class);
            add("tiktok_caption_foreign_original_gate", Policy.class, "foreign", Work.class, Boolean.class);
            add("tiktok_caption_lite_gate", Policy.class, "lite", Work.class);
            add("tiktok_caption_keva_service", Services.class, "get");
            add("tiktok_caption_pending_removal", Keva.class, "pending", String.class);
        }
        void add(String id, Class<?> owner, String name, Class<?>... arguments) throws Exception {
            rows.put(id, result(id, method(owner, name, arguments)));
        }
        CaptionPlaybackAccess access() throws Exception {
            return new CaptionPlaybackAccess(new HostScanReport("fixture", new ArrayList<>(rows.values()), false), getClass().getClassLoader());
        }
    }
}
