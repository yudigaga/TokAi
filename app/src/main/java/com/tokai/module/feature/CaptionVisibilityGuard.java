package com.tokai.module.feature;

import com.tokai.module.scan.HostScanReport;
import com.tokai.module.scan.HostCandidate;
import com.tokai.module.scan.HostFeatureSupport;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Mirrors native visibility IDs only for complete construction observed after registration. */
final class CaptionVisibilityGuard implements AutoCloseable {
    private final Map<Object, State> holders = new WeakHashMap<>(), roots = new WeakHashMap<>();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Field holderRoot, rootOwner;
    private final Method mapAdd, mapRemove, freezeFlow, freezeValue;
    private final Object freezeService;
    private final Class<?> holderType, rootType;
    private volatile boolean ready, closed;

    private CaptionVisibilityGuard(Field holderRoot, Field rootOwner, Method mapAdd, Method mapRemove,
                                   Method freezeFlow, Method freezeValue, Object freezeService) {
        this.holderRoot = holderRoot; this.rootOwner = rootOwner;
        this.mapAdd = mapAdd; this.mapRemove = mapRemove;
        this.freezeFlow = freezeFlow; this.freezeValue = freezeValue; this.freezeService = freezeService;
        holderType = holderRoot.getDeclaringClass(); rootType = rootOwner.getDeclaringClass();
    }

    static CaptionVisibilityGuard install(XposedInterface framework, HostScanReport report, ClassLoader loader) throws Exception {
        if (!HostFeatureSupport.isTikTok(report)) throw changed();
        Constructor<?> holderCtor = constructor(report, "tiktok_caption_visibility_holder_ctor", loader);
        Constructor<?> rootCtor = constructor(report, "tiktok_caption_visibility_root_ctor", loader);
        Class<?> holder = HostTargets.type(report, "tiktok_caption_visibility_holder_root", loader);
        Class<?> root = HostTargets.type(report, "tiktok_caption_visibility_root_owner", loader);
        Field holderRoot = HostTargets.field(holder, "r"), rootOwner = HostTargets.field(root, "LLJLIL");
        if (holderCtor.getDeclaringClass() != holder || rootCtor.getDeclaringClass() != root
                || holderCtor.getParameterCount() != 1 || holderCtor.getParameterTypes()[0].isPrimitive()
                || rootCtor.getParameterCount() != 2 || rootCtor.getParameterTypes()[0].isPrimitive()
                || !rootCtor.getParameterTypes()[1].isAssignableFrom(holder)
                || Modifier.isStatic(holderRoot.getModifiers()) || holderRoot.getType() != root
                || Modifier.isStatic(rootOwner.getModifiers()) || !rootOwner.getType().isAssignableFrom(holder)) throw changed();
        Method holderAdd = HostTargets.method(report, "tiktok_caption_visibility_holder_add", loader);
        Method holderRemove = HostTargets.method(report, "tiktok_caption_visibility_holder_remove", loader);
        Method rootAdd = HostTargets.method(report, "tiktok_caption_visibility_root_add", loader);
        Method rootRemove = HostTargets.method(report, "tiktok_caption_visibility_root_remove", loader);
        for (Method method : new Method[]{holderAdd, holderRemove, rootAdd, rootRemove}) {
            require(method, false, void.class, int.class);
        }
        if (holderAdd.getDeclaringClass() != holder || holderRemove.getDeclaringClass() != holder
                || rootAdd.getDeclaringClass() != root || rootRemove.getDeclaringClass() != root) throw changed();
        Method add = HostTargets.method(report, "tiktok_caption_visibility_map_add", loader);
        Method remove = HostTargets.method(report, "tiktok_caption_visibility_map_remove", loader);
        require(add, true, int.class, int.class); require(remove, true, int.class, int.class);
        Class<?> serviceType = HostTargets.type(report, "tiktok_caption_visibility_freeze_service", loader);
        Field singleton = HostTargets.field(serviceType, "LIZIZ");
        Method flow = HostTargets.method(report, "tiktok_caption_visibility_freeze_flow", loader);
        Method value = HostTargets.method(report, "tiktok_caption_visibility_freeze_value", loader);
        require(flow, false, value.getDeclaringClass()); require(value, false, Object.class);
        if (!Modifier.isStatic(singleton.getModifiers()) || !Modifier.isFinal(singleton.getModifiers())
                || singleton.getType() != serviceType || flow.getDeclaringClass() != serviceType) throw changed();
        Object service = singleton.get(null);
        if (service == null) throw changed();
        CaptionVisibilityGuard guard = new CaptionVisibilityGuard(holderRoot, rootOwner, add, remove, flow, value, service);
        try {
            // The frozen caller set includes interface/base dispatch and both constructor new/super sites.
            Set<Executable> callers = new java.util.LinkedHashSet<>();
            int declared = 0;
            for (String id : report.getResults().keySet()) if (id.startsWith("tiktok_caption_visibility_caller_")) declared++;
            if (declared != 174) throw changed();
            for (int index = 1; index <= 174; index++) {
                String id = String.format(java.util.Locale.ROOT, "tiktok_caption_visibility_caller_%03d", index);
                HostCandidate candidate = HostTargets.candidate(report, id);
                callers.add("<init>".equals(candidate.getMethodName()) ? constructor(report, id, loader)
                        : HostTargets.method(report, id, loader));
            }
            for (Executable caller : callers) if (!framework.deoptimize(caller)) throw changed();
            guard.add(framework, holderAdd, chain -> guard.mask(chain, false, true));
            guard.add(framework, holderRemove, chain -> guard.mask(chain, false, false));
            guard.add(framework, rootAdd, chain -> guard.mask(chain, true, true));
            guard.add(framework, rootRemove, chain -> guard.mask(chain, true, false));
            guard.add(framework, rootCtor, chain -> guard.construct(chain, true));
            guard.add(framework, holderCtor, chain -> guard.construct(chain, false));
            guard.ready = true;
            return guard;
        } catch (Throwable failure) {
            guard.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw new IllegalStateException("Caption visibility registration failed", failure);
        }
    }

    synchronized boolean unblocked(Object holder) {
        if (!ready || closed || !holderType.isInstance(holder)) return false;
        try {
            Object root = holderRoot.get(holder);
            if (!rootType.isInstance(root) || rootOwner.get(root) != holder || !clear(holders.get(holder)) || !clear(roots.get(root))) return false;
            Object flow = freezeFlow.invoke(freezeService);
            return flow != null && Boolean.FALSE.equals(freezeValue.invoke(flow))
                    && holderRoot.get(holder) == root && rootOwner.get(root) == holder
                    && clear(holders.get(holder)) && clear(roots.get(root));
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }

    private Object construct(XposedInterface.Chain chain, boolean isRoot) throws Throwable {
        Object owner = chain.getThisObject();
        Map<Object, State> map = isRoot ? roots : holders;
        State entry = null;
        // A base constructor returning does not prove a derived holder has finished initializing.
        if (owner != null && owner.getClass() == (isRoot ? rootType : holderType)) {
            synchronized (this) {
                if (!closed && !map.containsKey(owner)) { entry = new State(); map.put(owner, entry); }
            }
        }
        Object result = chain.proceed();
        if (entry != null) synchronized (this) { if (!closed && map.get(owner) == entry) entry.initialized = true; }
        return result;
    }

    private Object mask(XposedInterface.Chain chain, boolean isRoot, boolean adding) throws Throwable {
        Object owner = chain.getThisObject();
        if (adding) recordSafely(owner, isRoot, true, chain.getArg(0));
        Object result = chain.proceed();
        if (!adding) recordSafely(owner, isRoot, false, chain.getArg(0));
        return result;
    }

    private void recordSafely(Object owner, boolean isRoot, boolean adding, Object argument) {
        if (closed || owner == null) return;
        try {
            int input = ((Number) argument).intValue();
            int id = isRoot ? input : (Integer) (adding ? mapAdd : mapRemove).invoke(null, input);
            synchronized (this) {
                if (closed) return;
                apply((isRoot ? roots : holders).computeIfAbsent(owner, ignored -> new State()), adding, id);
                Object partner = isRoot ? rootOwner.get(owner) : holderRoot.get(owner);
                if (partner != null && (isRoot ? holderRoot.get(partner) == owner : rootOwner.get(partner) == owner)) {
                    apply((isRoot ? holders : roots).computeIfAbsent(partner, ignored -> new State()), adding, id);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            synchronized (this) { (isRoot ? roots : holders).computeIfAbsent(owner, ignored -> new State()).invalid = true; }
        }
    }

    private static void apply(State state, boolean adding, int id) {
        if (adding) state.ids.add(id); else state.ids.remove(id);
    }
    private static boolean clear(State state) { return state != null && state.initialized && !state.invalid && state.ids.isEmpty(); }

    private void add(XposedInterface framework, Executable target, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = framework.hook(target).setId("TokAi.CaptionVisibility." + handles.size())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(hooker);
        if (handle == null) throw changed();
        handles.add(handle);
    }

    @Override public void close() {
        List<XposedInterface.HookHandle> pending;
        synchronized (this) {
            if (closed) return;
            closed = true; ready = false;
            pending = new ArrayList<>(handles);
            handles.clear(); holders.clear(); roots.clear();
        }
        for (int index = pending.size() - 1; index >= 0; index--) {
            try { pending.get(index).unhook(); } catch (Throwable ignored) { }
        }
    }

    private static Constructor<?> constructor(HostScanReport report, String id, ClassLoader loader) throws Exception {
        HostCandidate candidate = HostTargets.candidate(report, id);
        if (!"<init>".equals(candidate.getMethodName())) throw changed();
        for (Constructor<?> constructor : HostTargets.type(report, id, loader).getDeclaredConstructors()) {
            StringBuilder descriptor = new StringBuilder("(");
            for (Class<?> parameter : constructor.getParameterTypes()) descriptor.append(HostTargets.descriptor(parameter));
            if (descriptor.append(")V").toString().equals(candidate.getMethodDescriptor())) {
                constructor.setAccessible(true); return constructor;
            }
        }
        throw changed();
    }
    private static void require(Method method, boolean isStatic, Class<?> returns, Class<?>... parameters) {
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != returns
                || !Arrays.equals(method.getParameterTypes(), parameters)) throw changed();
    }
    private static IllegalStateException changed() { return new IllegalStateException("TikTok caption visibility contract changed"); }
    private static final class State { boolean initialized, invalid; final Set<Integer> ids = new HashSet<>(); }
}
