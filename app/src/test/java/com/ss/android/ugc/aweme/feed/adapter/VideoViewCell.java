package com.ss.android.ugc.aweme.feed.adapter;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** Only the member contracts used by AutoPlayHook in TikTok 47.5.3. */
public final class VideoViewCell {
    public FrameLayout u;
    public Panel LLLZLZ;
    public String aid;

    public Aweme getAweme() { return new Aweme(aid); }
    public void onPageSelected(int index) { }
    public void LJJIJIL(int index) { }
    public void onResume() { }
    public void onPause() { }
    public void onPlayCompleted(String aid) { }
    public void onResumePlay(String aid) { }
    public void onPausePlay(String aid) { }

    public static final class Aweme {
        private final String aid;
        Aweme(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }

    public static final class Panel {
        public PagerContext LLJZ;
        public Pager LLJLLL;
        public Dialogs LLLLLJIL = new Dialogs();
        public VideoViewCell current;
        public VideoViewCell LJLJLLL() { return current; }
    }

    public static final class Dialogs {
        public Manager manager() { return new Manager(); }
    }

    public static final class Manager {
        public Object findFragmentByTag(String tag) { return null; }
    }

    public static final class PagerContext {
        public Pager pager;
        public int count = 3;
        public int nextCalls;
        public int previousCalls;
        public int nativeEvents;
        public boolean directBranch;

        public boolean CJ() {
            nextCalls++;
            int target = pager.getCurrentItem() + 1;
            boolean accepted = target < count;
            if (accepted) {
                if (directBranch) pager.dispatchCore(target, true, true, 1);
                else pager.setCurrentItemWithDefaultVelocity(target);
            }
            nativeEvents++;
            return accepted;
        }

        public void e60() {
            previousCalls++;
            int current = pager.getCurrentItem();
            if (current >= 1 && current < count) pager.setCurrentItemWithDefaultVelocity(current - 1);
            nativeEvents++;
        }
    }

    public static final class Pager extends View {
        public interface SelectionDispatcher {
            void select(int target, boolean smooth, boolean force, int velocity);
        }
        public SelectionDispatcher dispatcher;
        public int currentItem;
        public int coreCalls;
        public boolean lastSmooth;
        public boolean lastForce;
        public int lastVelocity;

        public Pager(Context context) { super(context); }
        public int getCurrentItem() { return currentItem; }
        public int getScrollState() { return 0; }
        public boolean onTouchEvent(MotionEvent event) { return true; }

        // There is deliberately no setCurrentItem(int, boolean) in this hierarchy.
        public void LJJJ(int target, boolean smooth) { dispatchCore(target, smooth, false, 0); }
        public void setCurrentItemWithDefaultVelocity(int target) { dispatchCore(target, true, true, 1); }
        public void dispatchCore(int target, boolean smooth, boolean force, int velocity) {
            if (dispatcher == null) LJJJI(target, smooth, force, velocity);
            else dispatcher.select(target, smooth, force, velocity);
        }
        public void LJJJI(int target, boolean smooth, boolean force, int velocity) {
            coreCalls++;
            currentItem = target;
            lastSmooth = smooth;
            lastForce = force;
            lastVelocity = velocity;
        }
    }
}
