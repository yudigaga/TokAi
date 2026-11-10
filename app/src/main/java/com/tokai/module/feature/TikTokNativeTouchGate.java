package com.tokai.module.feature;

import android.view.MotionEvent;
import android.view.View;
import com.tokai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/** TikTok sends ancestor listener DOWN before the selected child can claim its gesture. */
final class TikTokNativeTouchGate implements AutoCloseable {
    private final Method touch;
    private final Field listenerField;
    private final Field ownerField;
    private final Field downClockField;
    private final Consumer<String> log;
    private final ThreadLocal<Boolean> replaying = new ThreadLocal<>();
    private final List<NativeStart> starts = new ArrayList<>();
    private final List<Binding> bindings = new ArrayList<>();
    private XposedInterface.HookHandle handle;
    private boolean closed;

    private TikTokNativeTouchGate(Method touch, Field listenerField, Field ownerField, Field downClockField, Consumer<String> log) {
        this.touch = touch; this.listenerField = listenerField; this.ownerField = ownerField;
        this.downClockField = downClockField;
        this.log = log == null ? ignored -> { } : log;
    }

    static TikTokNativeTouchGate install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Consumer<String> log) throws Exception {
        Method touch = HostTargets.method(report, "tiktok_gesture_native_touch", loader);
        Class<?> root = HostTargets.type(report, "tiktok_gesture_native_root", loader);
        Class<?> detector = HostTargets.type(report, "tiktok_gesture_native_owner", loader);
        Field listener = HostTargets.field(root, "LLJJIJI");
        Field owner = HostTargets.field(detector, "LLJJIJIIJIL");
        Field downClock = HostTargets.field(detector, "LLJJL");
        if (touch.getDeclaringClass() != detector || Modifier.isStatic(touch.getModifiers())
                || Modifier.isAbstract(touch.getModifiers()) || touch.getReturnType() != boolean.class
                || touch.getParameterCount() != 2 || touch.getParameterTypes()[0] != View.class
                || touch.getParameterTypes()[1] != MotionEvent.class
                || !View.class.isAssignableFrom(root) || listener.getType() != detector
                || owner.getType() != View.class || downClock.getType() != long.class
                || Modifier.isStatic(downClock.getModifiers())) throw new IllegalStateException("TikTok native touch contract changed");
        TikTokNativeTouchGate gate = new TikTokNativeTouchGate(touch, listener, owner, downClock, log);
        gate.handle = framework.hook(touch).setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setId("TokAi.Gesture.TikTokNativeTouch").setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(gate::intercept);
        if (gate.handle == null) throw new IllegalStateException("Empty TikTok native touch hook handle");
        return gate;
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (Boolean.TRUE.equals(replaying.get())) return chain.proceed();
        Object detector = chain.getThisObject();
        Object rootValue = chain.getArg(0), eventValue = chain.getArg(1);
        if (!(rootValue instanceof View) || !(eventValue instanceof MotionEvent)) return chain.proceed();
        View root = (View) rootValue; MotionEvent event = (MotionEvent) eventValue;
        if (!owns(detector, root)) return chain.proceed();
        boolean suppress = false;
        synchronized (this) {
            if (!closed) {
                prune();
                Binding binding = find(detector);
                int action = event.getActionMasked();
                if (binding != null && action == MotionEvent.ACTION_DOWN
                        && (binding.discarded || binding.downTime != event.getDownTime())) {
                    remove(binding); binding = null;
                }
                if (binding != null && binding.root.get() == root && binding.downTime == event.getDownTime()) {
                    suppress = true;
                    if (!binding.discarded) binding.events.add(MotionEvent.obtain(event));
                    if (terminal(action)) binding.terminalSeen = true;
                } else if (action == MotionEvent.ACTION_DOWN) {
                    removeStart(detector);
                    starts.add(new NativeStart(detector, root, event));
                } else if (terminal(action)) removeStart(detector);
            }
        }
        // false leaves dispatch available to the selected child; this boolean listener is not a void detector.
        if (suppress) return Boolean.FALSE;
        Object result = chain.proceed();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) synchronized (this) {
            NativeStart start = findStart(detector);
            if (start != null && start.down.getDownTime() == event.getDownTime()) {
                try { start.nativeWallTime = downClockField.getLong(detector); start.clockObserved = true; }
                catch (ReflectiveOperationException | RuntimeException unavailable) { removeStart(detector); record("native_down_clock_unavailable"); }
            }
        }
        return result;
    }

    Lease hold(View child, MotionEvent down) {
        if (child == null || down == null || down.getActionMasked() != MotionEvent.ACTION_DOWN) return Lease.empty();
        List<Binding> held = new ArrayList<>();
        List<Cancel> cancellations = new ArrayList<>();
        synchronized (this) {
            if (closed) return Lease.empty();
            prune();
            for (View root = child; root != null; root = root.getParent() instanceof View ? (View) root.getParent() : null) {
                if (!listenerField.getDeclaringClass().isInstance(root)) continue;
                try {
                    Object detector = listenerField.get(root);
                    if (!owns(detector, root)) continue;
                    Binding binding = find(detector);
                    if (binding != null && binding.downTime == down.getDownTime() && !binding.discarded) {
                        held.add(binding); break;
                    }
                    if (binding != null) remove(binding);
                    NativeStart start = findStart(detector);
                    // If this listener did not receive DOWN, there is no native stream to cancel or replay.
                    if (start == null || start.root.get() != root || !start.clockObserved
                            || start.down.getDownTime() != down.getDownTime()) continue;
                    binding = new Binding(detector, root, down.getDownTime());
                    binding.nativeWallTime = start.nativeWallTime;
                    if (start != null && start.root.get() == root && start.down.getDownTime() == down.getDownTime()) {
                        binding.events.add(MotionEvent.obtain(start.down));
                        MotionEvent cancel = MotionEvent.obtain(start.down); cancel.setAction(MotionEvent.ACTION_CANCEL);
                        cancellations.add(new Cancel(binding, detector, root, cancel));
                        removeStart(detector);
                    }
                    bindings.add(binding); held.add(binding);
                    break; // Only the selected child's closest native card root belongs to this lease.
                } catch (ReflectiveOperationException | RuntimeException failure) { record("owner_unavailable"); }
            }
        }
        for (Cancel cancel : cancellations) {
            try { invoke(cancel.detector, cancel.root, cancel.event); record("native_down_cancelled"); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                synchronized (this) { remove(cancel.binding); held.remove(cancel.binding); }
                record("native_cancel_failed");
            } finally { cancel.event.recycle(); }
        }
        return held.isEmpty() ? Lease.empty() : new Lease(this, held);
    }

    private void finish(List<Binding> held, boolean replay, Runnable afterNativeDown) {
        List<Replay> events = new ArrayList<>();
        synchronized (this) {
            for (Binding binding : closed ? Collections.<Binding>emptyList() : held) {
                if (!bindings.contains(binding)) continue; // A stale lease cannot release a newer DOWN.
                if (!replay) { binding.discarded = true; binding.recycle(); continue; }
                Object detector = binding.detector.get(); View root = binding.root.get();
                bindings.remove(binding);
                if (detector != null && root != null && owns(detector, root)) {
                    events.add(new Replay(detector, root, binding.downTime, binding.nativeWallTime, binding.terminalSeen, binding.events));
                    binding.events.clear();
                }
                binding.recycle();
            }
        }
        boolean continued = false;
        for (Replay pending : events) {
            RuntimeException continuationFailure = null;
            try {
                for (MotionEvent event : pending.events) {
                    synchronized (this) { if (closed || find(pending.detector) != null) break; }
                    if (!owns(pending.detector, pending.root)) break;
                    invoke(pending.detector, pending.root, event);
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        // The native UP path uses wall time, rather than MotionEvent.eventTime, for its 300 ms branch.
                        downClockField.setLong(pending.detector, pending.nativeWallTime);
                    }
                    if (!continued && afterNativeDown != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        continued = true;
                        try { afterNativeDown.run(); }
                        catch (RuntimeException failure) { continuationFailure = failure; throw failure; }
                    }
                }
                record("native_sequence_replayed");
            } catch (ReflectiveOperationException | RuntimeException failure) {
                if (!pending.events.isEmpty()) {
                    MotionEvent cancel = MotionEvent.obtain(pending.events.get(pending.events.size() - 1));
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { invoke(pending.detector, pending.root, cancel); } catch (ReflectiveOperationException | RuntimeException ignored) { }
                    finally { cancel.recycle(); }
                }
                synchronized (this) {
                    if (!closed && !pending.terminalSeen && find(pending.detector) == null) {
                        Binding tail = new Binding(pending.detector, pending.root, pending.downTime);
                        tail.discarded = true; bindings.add(tail);
                    }
                }
                record("native_replay_failed_cancelled");
                if (continuationFailure == failure) throw continuationFailure;
            } finally { pending.recycle(); }
        }
        if (!continued && afterNativeDown != null) afterNativeDown.run();
    }

    private void invoke(Object detector, View root, MotionEvent event) throws ReflectiveOperationException {
        replaying.set(true);
        try { touch.invoke(detector, root, event); } finally { replaying.remove(); }
    }
    private boolean owns(Object detector, View root) {
        if (detector == null || root == null || !touch.getDeclaringClass().isInstance(detector)
                || !listenerField.getDeclaringClass().isInstance(root)) return false;
        try { return listenerField.get(root) == detector && ownerField.get(detector) == root; }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }
    private Binding find(Object detector) { for (Binding b : bindings) if (b.detector.get() == detector) return b; return null; }
    private NativeStart findStart(Object detector) { for (NativeStart s : starts) if (s.detector.get() == detector) return s; return null; }
    private void remove(Binding binding) { bindings.remove(binding); binding.recycle(); }
    private void removeStart(Object detector) {
        for (Iterator<NativeStart> it = starts.iterator(); it.hasNext();) {
            NativeStart s = it.next(); if (s.detector.get() == detector) { s.down.recycle(); it.remove(); }
        }
    }
    private void prune() {
        for (Iterator<Binding> it = bindings.iterator(); it.hasNext();) {
            Binding b = it.next(); if (b.detector.get() == null || b.root.get() == null) { b.recycle(); it.remove(); }
        }
        for (Iterator<NativeStart> it = starts.iterator(); it.hasNext();) {
            NativeStart s = it.next(); if (s.detector.get() == null || s.root.get() == null) { s.down.recycle(); it.remove(); }
        }
    }
    private static boolean terminal(int action) { return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL; }
    private void record(String stage) { try { log.accept("TikTok native touch gate " + stage); } catch (Throwable ignored) { } }
    @Override public void close() {
        XposedInterface.HookHandle installed;
        synchronized (this) {
            if (closed) return; closed = true;
            for (Binding b : bindings) b.recycle(); bindings.clear();
            for (NativeStart s : starts) s.down.recycle(); starts.clear();
            installed = handle; handle = null;
        }
        if (installed != null) try { installed.unhook(); } catch (Throwable ignored) { record("unhook_failed"); }
    }

    static final class Lease {
        static Lease empty() { return new Lease(null, Collections.emptyList()); }
        private final WeakReference<TikTokNativeTouchGate> owner;
        private final List<Binding> held; private boolean finished;
        Lease(TikTokNativeTouchGate gate, List<Binding> held) { owner = new WeakReference<>(gate); this.held = new ArrayList<>(held); }
        synchronized boolean isEmpty() { return held.isEmpty(); }
        void replayDown() { finish(true, null); }
        void replayDown(Runnable afterNativeDown) { finish(true, afterNativeDown); }
        void discard() { finish(false, null); }
        private void finish(boolean replay, Runnable afterNativeDown) {
            List<Binding> snapshot;
            synchronized (this) { if (finished) return; finished = true; snapshot = new ArrayList<>(held); held.clear(); }
            TikTokNativeTouchGate gate = owner.get(); owner.clear();
            if (gate != null) gate.finish(snapshot, replay, afterNativeDown);
            else if (afterNativeDown != null) afterNativeDown.run();
        }
    }
    private static final class Binding {
        final WeakReference<Object> detector; final WeakReference<View> root; final long downTime;
        final List<MotionEvent> events = new ArrayList<>(); boolean discarded, terminalSeen; long nativeWallTime;
        Binding(Object detector, View root, long downTime) { this.detector = new WeakReference<>(detector); this.root = new WeakReference<>(root); this.downTime = downTime; }
        void recycle() { for (MotionEvent e : events) e.recycle(); events.clear(); }
    }
    private static final class NativeStart {
        final WeakReference<Object> detector; final WeakReference<View> root; final MotionEvent down;
        long nativeWallTime; boolean clockObserved;
        NativeStart(Object d, View r, MotionEvent e) { detector = new WeakReference<>(d); root = new WeakReference<>(r); down = MotionEvent.obtain(e); }
    }
    private static final class Cancel {
        final Binding binding; final Object detector; final View root; final MotionEvent event;
        Cancel(Binding b, Object d, View r, MotionEvent e) { binding = b; detector = d; root = r; event = e; }
    }
    private static final class Replay {
        final Object detector; final View root; final long downTime, nativeWallTime; final boolean terminalSeen; final List<MotionEvent> events;
        Replay(Object d, View r, long time, long wallTime, boolean terminal, List<MotionEvent> source) { detector = d; root = r; downTime = time; nativeWallTime = wallTime; terminalSeen = terminal; events = new ArrayList<>(source); }
        void recycle() { for (MotionEvent e : events) e.recycle(); events.clear(); }
    }
}
