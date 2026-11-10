package com.tokai.module.feature;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Matches the original 47.5.3 boolean ancestor listener and its two identity fields. */
@RunWith(RobolectricTestRunner.class)
public final class TikTokSpeedGestureNativeGateTest {
    public static final class Root extends FrameLayout {
        public Detector LLJJIJI;
        Root(Context context) { super(context); LLJJIJI = new Detector(this); }
    }
    public static final class Detector {
        public final View LLJJIJIIJIL;
        public long LLJJL;
        long simulatedClock, clockSeenAtUp;
        final List<String> received = new ArrayList<>(); boolean armed;
        Detector(View root) { LLJJIJIIJIL = root; }
        public boolean onTouch(View view, MotionEvent event) {
            assertSame(LLJJIJIIJIL, view);
            int action = event.getActionMasked(); received.add(action + ":" + event.getX());
            if (action == MotionEvent.ACTION_DOWN) { armed = true; LLJJL = ++simulatedClock; }
            if (action == MotionEvent.ACTION_UP) clockSeenAtUp = LLJJL;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) armed = false;
            return action == MotionEvent.ACTION_MOVE;
        }
    }

    @Test public void unclaimedNativeStreamIsForwardedOnceWithItsBooleanResult() throws Throwable {
        Harness h = new Harness(); Root root = h.root();
        try (SpeedGestureNativeGate gate = h.install()) {
            assertFalse(h.emit(root,100,MotionEvent.ACTION_DOWN,99));
            assertTrue(h.emit(root,100,MotionEvent.ACTION_MOVE,101));
            assertFalse(h.emit(root,100,MotionEvent.ACTION_UP,101));
            assertEquals(Arrays.asList("0:99.0","2:101.0","1:101.0"),root.LLJJIJI.received);
        }
    }

    @Test public void claimedShortTapCancelsNativeDownThenReplaysNativeCoordinatesAndUpInOrder() throws Throwable {
        Harness h = new Harness(); Root root = h.root(); Detector detector = root.LLJJIJI;
        try (SpeedGestureNativeGate gate = h.install()) {
            h.emit(root,200,MotionEvent.ACTION_DOWN,99);
            SpeedGestureNativeGate.Lease lease = h.hold(gate,root,200);
            assertFalse(lease.isEmpty()); assertFalse(detector.armed);
            assertFalse(h.emit(root,200,MotionEvent.ACTION_UP,101));
            lease.replayDown();
            assertEquals(Arrays.asList("0:99.0","3:99.0","0:99.0","1:101.0"),detector.received);
            assertFalse(detector.armed);
            assertEquals("replay must preserve the native 300 ms UP decision clock",1L,detector.clockSeenAtUp);
        }
    }

    @Test public void cancelledClaimRestoresNativeMovesBeforeTheFollowingUp() throws Throwable {
        Harness h = new Harness(); Root root = h.root(); Detector detector = root.LLJJIJI;
        try (SpeedGestureNativeGate gate = h.install()) {
            h.emit(root,300,MotionEvent.ACTION_DOWN,90);
            SpeedGestureNativeGate.Lease lease = h.hold(gate,root,300);
            assertFalse(h.emit(root,300,MotionEvent.ACTION_MOVE,110));
            lease.replayDown();
            h.emit(root,300,MotionEvent.ACTION_UP,120);
            assertEquals(Arrays.asList("0:90.0","3:90.0","0:90.0","2:110.0","1:120.0"),detector.received);
            assertFalse(detector.armed);
        }
    }

    @Test public void childDownIsReplayedBetweenNativeDownAndTheBufferedNativeUp() throws Throwable {
        Harness h = new Harness(); Root root = h.root(); Detector detector = root.LLJJIJI;
        try (SpeedGestureNativeGate gate = h.install()) {
            h.emit(root,350,MotionEvent.ACTION_DOWN,90);
            SpeedGestureNativeGate.Lease lease = h.hold(gate,root,350);
            h.emit(root,350,MotionEvent.ACTION_UP,91);
            lease.replayDown(() -> detector.received.add("child_down"));
            assertEquals(Arrays.asList("0:90.0","3:90.0","0:90.0","child_down","1:91.0"),detector.received);
            assertFalse(detector.armed);
        }
    }

    @Test public void confirmedModuleGestureDiscardsOnlyItsNativeTailAndReleasesTheNextDown() throws Throwable {
        Harness h = new Harness(); Root root = h.root(); Detector detector = root.LLJJIJI;
        try (SpeedGestureNativeGate gate = h.install()) {
            h.emit(root,400,MotionEvent.ACTION_DOWN,90);
            SpeedGestureNativeGate.Lease lease = h.hold(gate,root,400); lease.discard();
            h.emit(root,400,MotionEvent.ACTION_MOVE,91); h.emit(root,400,MotionEvent.ACTION_UP,92);
            assertEquals(Arrays.asList("0:90.0","3:90.0"),detector.received);
            h.emit(root,500,MotionEvent.ACTION_DOWN,70); h.emit(root,500,MotionEvent.ACTION_UP,70);
            assertEquals(Arrays.asList("0:90.0","3:90.0","0:70.0","1:70.0"),detector.received);
        }
    }

    @Test public void neighboringCardAndReplacedListenerCannotBeCapturedByTheHeldCard() throws Throwable {
        Harness h = new Harness(); Root held = h.root(), neighbor = h.root(); Detector old = held.LLJJIJI;
        try (SpeedGestureNativeGate gate = h.install()) {
            h.emit(held,600,MotionEvent.ACTION_DOWN,10);
            SpeedGestureNativeGate.Lease lease = h.hold(gate,held,600);
            h.emit(neighbor,600,MotionEvent.ACTION_DOWN,20); h.emit(neighbor,600,MotionEvent.ACTION_UP,21);
            assertEquals(Arrays.asList("0:20.0","1:21.0"),neighbor.LLJJIJI.received);
            held.LLJJIJI = new Detector(held); lease.replayDown();
            assertEquals(Arrays.asList("0:10.0","3:10.0"),old.received);
            h.emit(held,700,MotionEvent.ACTION_DOWN,30); h.emit(held,700,MotionEvent.ACTION_UP,31);
            assertEquals(Arrays.asList("0:30.0","1:31.0"),held.LLJJIJI.received);
        }
    }

    @Test public void staleLeaseCannotReplayOrReleaseANewerTouchStream() throws Throwable {
        Harness h = new Harness(); Root root = h.root(); Detector detector = root.LLJJIJI;
        try (SpeedGestureNativeGate gate = h.install()) {
            h.emit(root,800,MotionEvent.ACTION_DOWN,10);
            SpeedGestureNativeGate.Lease old = h.hold(gate,root,800);
            h.emit(root,900,MotionEvent.ACTION_DOWN,20);
            SpeedGestureNativeGate.Lease current = h.hold(gate,root,900);
            old.replayDown();
            h.emit(root,900,MotionEvent.ACTION_UP,21); current.replayDown();
            assertEquals(Arrays.asList("0:10.0","3:10.0","0:20.0","3:20.0","0:20.0","1:21.0"),detector.received);
        }
    }

    @Test public void closeCancelsClaimedNativeSchedulingAndMakesItsLeaseInert() throws Throwable {
        Harness h = new Harness(); Root root = h.root(); Detector detector = root.LLJJIJI;
        SpeedGestureNativeGate gate = h.install();
        h.emit(root,1000,MotionEvent.ACTION_DOWN,10);
        SpeedGestureNativeGate.Lease lease = h.hold(gate,root,1000);
        assertFalse(detector.armed); gate.close(); lease.replayDown();
        assertEquals(Arrays.asList("0:10.0","3:10.0"),detector.received);
        h.emit(root,1100,MotionEvent.ACTION_DOWN,20); h.emit(root,1100,MotionEvent.ACTION_UP,21);
        assertEquals(Arrays.asList("0:10.0","3:10.0","0:20.0","1:21.0"),detector.received);
    }

    private static final class Harness {
        final AtomicReference<XposedInterface.Hooker> hooker = new AtomicReference<>();
        Root root() { return new Root(RuntimeEnvironment.getApplication()); }
        SpeedGestureNativeGate install() throws Exception {
            AtomicReference<Object> builder = new AtomicReference<>();
            Object handle = Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),new Class<?>[]{XposedInterface.HookHandle.class},(p,m,a)->{ if("unhook".equals(m.getName()))hooker.set(null);return null; });
            builder.set(Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),new Class<?>[]{XposedInterface.HookBuilder.class},(p,m,a)->{
                if("intercept".equals(m.getName())){ hooker.set((XposedInterface.Hooker)a[0]);return handle; }return builder.get();
            }));
            XposedInterface framework=(XposedInterface)Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),new Class<?>[]{XposedInterface.class},(p,m,a)->"hook".equals(m.getName())?builder.get():null);
            Method touch=Detector.class.getMethod("onTouch",View.class,MotionEvent.class);
            List<RuleScanResult> rules=Arrays.asList(rule("tiktok_gesture_native_touch",Detector.class,touch.getName(),HostTargets.descriptor(touch)),
                    rule("tiktok_gesture_native_root",Root.class,"",""),rule("tiktok_gesture_native_owner",Detector.class,"",""));
            return SpeedGestureNativeGate.install(framework,new HostScanReport("native-tiktok-shape",rules,false),getClass().getClassLoader(),ignored->{ });
        }
        SpeedGestureNativeGate.Lease hold(SpeedGestureNativeGate gate,Root root,long time) {
            View child=new View(root.getContext());root.addView(child);
            MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,7,8,0);
            try{return gate.hold(child,down);}finally{down.recycle();}
        }
        boolean emit(Root root,long time,int action,float x) throws Throwable {
            MotionEvent event=MotionEvent.obtain(time,time+10+action,action,x,1,0); Detector detector=root.LLJJIJI;
            try {
                if(hooker.get()==null)return detector.onTouch(root,event);
                XposedInterface.Chain chain=(XposedInterface.Chain)Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),new Class<?>[]{XposedInterface.Chain.class},(p,m,a)->{
                    if("getThisObject".equals(m.getName()))return detector;
                    if("getArg".equals(m.getName()))return ((Integer)a[0])==0?root:event;
                    if("getArgs".equals(m.getName()))return Arrays.asList(root,event);
                    if("proceed".equals(m.getName()))return detector.onTouch(root,event);
                    throw new UnsupportedOperationException(m.getName());
                });
                return (Boolean)hooker.get().intercept(chain);
            } finally {event.recycle();}
        }
    }
    private static RuleScanResult rule(String id,Class<?> owner,String name,String descriptor) {
        return new RuleScanResult(id,"gesture",Collections.singletonList(new HostCandidate("shape.apk","classes.dex",HostTargets.descriptor(owner),name,descriptor)));
    }
}
