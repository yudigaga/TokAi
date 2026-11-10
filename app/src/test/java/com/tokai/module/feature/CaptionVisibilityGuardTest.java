package com.tokai.module.feature;

import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Constructor/callback model only: no ART constructor Hook or physical overlay acceptance is implied. */
public final class CaptionVisibilityGuardTest {
    interface Action { Object run() throws Throwable; }
    public interface Owner { }
    public static final class Config { }
    public static final class ContextData { }
    public static class Holder implements Owner {
        public Root r;
        int adds, removes;
        public Holder(Config config) { }
        public void pause(int reason) { adds++; }
        public void resume(int reason) { removes++; }
    }
    public static final class ChildHolder extends Holder { public ChildHolder() { super(new Config()); } }
    public static final class Root {
        public Owner LLJLIL;
        int adds, removes;
        public Root(ContextData context, Owner owner) { LLJLIL = owner; }
        public void pause(int id) { adds++; }
        public void resume(int id) { removes++; }
    }
    public static final class Mapping {
        static boolean fail;
        public static int add(int i) { if (fail) throw new IllegalStateException("unknown mapping"); return i == 1 ? 3 : i == 2 ? 4 : i == 4 ? 2 : i == 5 ? 1 : 0; }
        public static int remove(int i) { return i == 1 ? 3 : i == 2 ? 4 : 0; }
    }
    public interface Flow { Object getValue(); }
    public static final class Values implements Flow {
        Object value = Boolean.FALSE;
        boolean fail;
        @Override public Object getValue() { if (fail) throw new IllegalStateException("freeze unknown"); return value; }
    }
    public static final class Freeze {
        public static final Freeze LIZIZ = new Freeze();
        Flow flow = new Values();
        public Flow Dh() { return flow; }
    }

    @Before public void resetFreeze() { Freeze.LIZIZ.flow = new Values(); Mapping.fail = false; }

    @Test public void onlyBothObservedSuccessfulConstructorsEstablishAnUnblockedBaseline() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder old = new Holder(new Config()); old.r = new Root(new ContextData(), old);
            assertFalse(guard.unblocked(old));
            Holder fresh = h.fresh(); assertTrue(guard.unblocked(fresh));
            ChildHolder child = new ChildHolder(); child.r = new Root(new ContextData(), child);
            h.construct(holderCtor(), child, () -> null); h.construct(rootCtor(), child.r, () -> null);
            assertFalse(guard.unblocked(child));
            assertFalse(guard.unblocked(null)); assertFalse(guard.unblocked(new Object()));
        }
    }

    @Test public void preexistingEventsAndALateConstructorDoNotEraseUnknownOrExistingMaskState() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder old = new Holder(new Config()); old.r = new Root(new ContextData(), old);
            h.invoke(holderAdd(), old, 1); h.invoke(holderRemove(), old, 1);
            h.construct(holderCtor(), old, () -> null);
            h.construct(rootCtor(), old.r, () -> null);
            assertFalse(guard.unblocked(old));
        }
    }

    @Test public void masksArrivingInsideConstructionSurviveTheSuccessfulConstructorReturn() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder holder = h.freshDuringConstruction(1);
            assertFalse(guard.unblocked(holder));
            h.invoke(holderRemove(), holder, 1);
            assertTrue(guard.unblocked(holder));
        }
    }

    @Test public void mappedLegacyAndDirectRootRoutesShareIdempotentBlockingIds() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder holder = h.fresh();
            h.invoke(holderAdd(), holder, 1); h.invoke(holderAdd(), holder, 1);
            assertFalse(guard.unblocked(holder));
            h.invoke(rootRemove(), holder.r, 3);
            assertTrue(guard.unblocked(holder));
            h.invoke(rootAdd(), holder.r, 4); assertFalse(guard.unblocked(holder));
            h.invoke(holderRemove(), holder, 2); assertTrue(guard.unblocked(holder));
            assertEquals(2, holder.adds); assertEquals(1, holder.removes);
            assertEquals(1, holder.r.adds); assertEquals(1, holder.r.removes);
            for (int reason : new int[]{4, 5, 99}) {
                h.invoke(holderAdd(), holder, reason); assertFalse(guard.unblocked(holder));
                h.invoke(rootRemove(), holder.r, Mapping.add(reason)); assertTrue(guard.unblocked(holder));
            }
        }
    }

    @Test public void oneRemovalDoesNotClearAnUnrelatedMaskAndNormalPlaybackResumeIsNotAMaskReset() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder holder = h.fresh();
            h.invoke(rootAdd(), holder.r, 1); h.invoke(rootAdd(), holder.r, 2);
            h.invoke(rootRemove(), holder.r, 1); assertFalse(guard.unblocked(holder));
            h.invoke(holderRemove(), holder, 1); assertFalse(guard.unblocked(holder));
            h.invoke(rootRemove(), holder.r, 2); assertTrue(guard.unblocked(holder));
        }
    }

    @Test public void nativeFreezeTrueNullWrongTypeOrFailureFailsClosed() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder holder = h.fresh(); Values values = (Values) Freeze.LIZIZ.flow;
            assertTrue(guard.unblocked(holder));
            for (Object unknown : new Object[]{Boolean.TRUE, null, "false"}) {
                values.value = unknown; assertFalse(guard.unblocked(holder));
            }
            values.value = Boolean.FALSE; values.fail = true; assertFalse(guard.unblocked(holder));
            values.fail = false; assertTrue(guard.unblocked(holder));
            Freeze.LIZIZ.flow = null; assertFalse(guard.unblocked(holder));
        }
    }

    @Test public void aReplacedUnknownOrWrongOwnerRootFailsClosedAndRecyclingDoesNotClearMasks() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder holder = h.fresh(); Root original = holder.r;
            holder.r = new Root(new ContextData(), holder); assertFalse(guard.unblocked(holder));
            h.construct(rootCtor(), holder.r, () -> null); assertTrue(guard.unblocked(holder));
            holder.r.LLJLIL = new Holder(new Config()); assertFalse(guard.unblocked(holder));
            holder.r = original; h.invoke(holderAdd(), holder, 2);
            Root replacement = new Root(new ContextData(), holder);
            h.construct(rootCtor(), replacement, () -> null); holder.r = replacement;
            assertFalse(guard.unblocked(holder));
            h.invoke(rootRemove(), replacement, 4); assertTrue(guard.unblocked(holder));
        }
    }

    @Test public void failedConstructionAndNativeMaskRemovalExceptionsDoNotCreateAnUnblockedState() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder incomplete = new Holder(new Config()); Root root = new Root(new ContextData(), incomplete); incomplete.r = root;
            h.construct(rootCtor(), root, () -> null);
            IllegalStateException expected = new IllegalStateException("native failed");
            try { h.construct(holderCtor(), incomplete, () -> { throw expected; }); fail(); }
            catch (IllegalStateException actual) { assertSame(expected, actual); }
            assertFalse(guard.unblocked(incomplete));
            Holder holder = h.fresh(); h.invoke(rootAdd(), holder.r, 7);
            try { h.call(rootRemove(), holder.r, new Object[]{7}, () -> { throw expected; }); fail(); }
            catch (IllegalStateException actual) { assertSame(expected, actual); }
            assertFalse(guard.unblocked(holder));
        }
    }

    @Test public void aMappingFailureDuringConstructionCannotBeErasedByTheConstructorReturning() throws Throwable {
        Harness h = new Harness();
        try (CaptionVisibilityGuard guard = h.install()) {
            Holder holder = new Holder(new Config()); holder.r = new Root(new ContextData(), holder);
            h.construct(rootCtor(), holder.r, () -> null);
            Mapping.fail = true;
            h.construct(holderCtor(), holder, () -> { h.invoke(holderAdd(), holder, 1); return null; });
            Mapping.fail = false;
            assertFalse(guard.unblocked(holder));
        }
    }

    @Test public void registrationFailureRollsBackAndCloseRevokesAllHandlesAndVisibility() throws Throwable {
        Harness denied = new Harness(); denied.rejectDeoptimization = true;
        try { denied.install(); fail("deoptimization must fail before registration"); }
        catch (IllegalStateException expected) { assertTrue(denied.hooks.isEmpty()); }
        Harness rejected = new Harness(); rejected.reject = rootCtor();
        try { rejected.install(); fail("registration must fail"); }
        catch (IllegalStateException expected) { assertTrue(rejected.hooks.isEmpty()); }
        Harness h = new Harness(); CaptionVisibilityGuard guard = h.install(); Holder holder = h.fresh();
        assertTrue(guard.unblocked(holder)); guard.close(); guard.close();
        assertFalse(guard.unblocked(holder)); assertTrue(h.hooks.isEmpty());
    }

    private static Method holderAdd() throws Exception { return Holder.class.getMethod("pause", int.class); }
    private static Method holderRemove() throws Exception { return Holder.class.getMethod("resume", int.class); }
    private static Method rootAdd() throws Exception { return Root.class.getMethod("pause", int.class); }
    private static Method rootRemove() throws Exception { return Root.class.getMethod("resume", int.class); }
    private static Constructor<?> holderCtor() throws Exception { return Holder.class.getConstructor(Config.class); }
    private static Constructor<?> rootCtor() throws Exception { return Root.class.getConstructor(ContextData.class, Owner.class); }
    private static String descriptor(Executable target) {
        if (target instanceof Method) return HostTargets.descriptor((Method) target);
        StringBuilder value = new StringBuilder("(");
        for (Class<?> type : target.getParameterTypes()) value.append(HostTargets.descriptor(type));
        return value.append(")V").toString();
    }
    private static RuleScanResult result(String id, Executable target) {
        return new RuleScanResult(id, "interface", List.of(new HostCandidate("base.apk", "classes.dex",
                HostTargets.descriptor(target.getDeclaringClass()), target instanceof Constructor ? "<init>" : target.getName(), descriptor(target))));
    }
    private static RuleScanResult type(String id, Class<?> target) {
        return new RuleScanResult(id, "interface", List.of(new HostCandidate("base.apk", "classes.dex", HostTargets.descriptor(target), "", "")));
    }

    private static final class Harness {
        final Map<Executable, XposedInterface.Hooker> hooks = new HashMap<>();
        Executable reject;
        boolean rejectDeoptimization;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, operation, args) -> {
                    if (operation.getName().equals("deoptimize")) return !rejectDeoptimization;
                    if (!operation.getName().equals("hook")) return null;
                    Executable target = (Executable) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, method, values) -> {
                                if (!method.getName().equals("intercept")) return builder;
                                if (target.equals(reject)) throw new IllegalStateException("fixture registration rejected");
                                hooks.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                        (handle, call, ignored) -> { if (call.getName().equals("unhook")) hooks.remove(target); return null; });
                            });
                });
        CaptionVisibilityGuard install() throws Exception {
            List<RuleScanResult> rows = new ArrayList<>();
            rows.add(type("tiktok_feed_materialized", Holder.class));
            rows.add(result("tiktok_caption_visibility_holder_ctor", holderCtor()));
            rows.add(result("tiktok_caption_visibility_root_ctor", rootCtor()));
            rows.add(type("tiktok_caption_visibility_holder_root", Holder.class));
            rows.add(type("tiktok_caption_visibility_root_owner", Root.class));
            rows.add(result("tiktok_caption_visibility_holder_add", holderAdd()));
            rows.add(result("tiktok_caption_visibility_holder_remove", holderRemove()));
            rows.add(result("tiktok_caption_visibility_root_add", rootAdd()));
            rows.add(result("tiktok_caption_visibility_root_remove", rootRemove()));
            rows.add(result("tiktok_caption_visibility_map_add", Mapping.class.getMethod("add", int.class)));
            rows.add(result("tiktok_caption_visibility_map_remove", Mapping.class.getMethod("remove", int.class)));
            rows.add(type("tiktok_caption_visibility_freeze_service", Freeze.class));
            rows.add(result("tiktok_caption_visibility_freeze_flow", Freeze.class.getMethod("Dh")));
            rows.add(result("tiktok_caption_visibility_freeze_value", Flow.class.getMethod("getValue")));
            for (int index = 1; index <= 174; index++) {
                rows.add(result(String.format(java.util.Locale.ROOT, "tiktok_caption_visibility_caller_%03d", index), holderAdd()));
            }
            return CaptionVisibilityGuard.install(framework, new HostScanReport("fixture", rows, false), getClass().getClassLoader());
        }
        Holder fresh() throws Throwable { return freshDuringConstruction(-1); }
        Holder freshDuringConstruction(int reason) throws Throwable {
            Holder holder = new Holder(new Config());
            construct(holderCtor(), holder, () -> {
                Root root = new Root(new ContextData(), holder);
                construct(rootCtor(), root, () -> null); holder.r = root;
                if (reason >= 0) invoke(holderAdd(), holder, reason);
                return null;
            });
            return holder;
        }
        Object construct(Constructor<?> constructor, Object owner, Action action) throws Throwable {
            return call(constructor, owner, new Object[constructor.getParameterCount()], action);
        }
        Object invoke(Method method, Object owner, Object... args) throws Throwable {
            return call(method, owner, args, () -> {
                try { return method.invoke(owner, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        Object call(Executable target, Object owner, Object[] args, Action action) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, ignored) -> {
                        switch (method.getName()) {
                            case "getExecutable": return target;
                            case "getThisObject": return owner;
                            case "getArgs": return Arrays.asList(args);
                            case "getArg": return args[(Integer) ignored[0]];
                            case "proceed": return action.run();
                            default: return null;
                        }
                    });
            XposedInterface.Hooker hook = hooks.get(target);
            return hook == null ? action.run() : hook.intercept(chain);
        }
    }
}
