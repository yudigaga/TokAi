package com.tokai.module.feature;

import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** TikTok 47.5.3 native feed spaces; visibility ownership stays with HostBehaviorHooks. */
final class TikTokVideoSpacesAdapter {
    private final Method update;
    private final Class<?> ownerType;
    private final Class<?> resultType;
    private final Field[] fields;
    private final List<Replay> results = new ArrayList<>();

    TikTokVideoSpacesAdapter(Method update) throws ReflectiveOperationException {
        this(update, "LLJLLL", "LLJZ");
    }

    TikTokVideoSpacesAdapter(Method update, String... names) throws ReflectiveOperationException {
        if (update == null || Modifier.isStatic(update.getModifiers())
                || update.getReturnType() != void.class || update.getParameterCount() != 1
                || update.getParameterTypes()[0].isPrimitive() || names == null || names.length == 0) {
            throw new NoSuchMethodException("Native feed space update signature changed");
        }
        this.update = update;
        ownerType = update.getDeclaringClass();
        resultType = update.getParameterTypes()[0];
        fields = new Field[names.length];
        for (int index = 0; index < names.length; index++) fields[index] = viewField(ownerType, names[index]);
        update.setAccessible(true);
    }

    View[] views(Object component) throws ReflectiveOperationException {
        requireOwner(component);
        View[] views = new View[fields.length];
        for (int index = 0; index < fields.length; index++) views[index] = (View) fields[index].get(component);
        return views;
    }

    boolean owns(Object component) { return ownerType.isInstance(component); }

    /** Record only an argument that the host actually supplied to its scanned wq method. */
    synchronized void rememberResult(Object component, Object result) {
        requireOwner(component);
        if (!resultType.isInstance(result)) {
            throw new IllegalArgumentException("Native feed space result type changed");
        }
        Iterator<Replay> iterator = results.iterator();
        while (iterator.hasNext()) {
            Replay previous = iterator.next();
            Object owner = previous.owner.get();
            if (owner == null || previous.result.get() == null) iterator.remove();
            else if (owner == component) {
                previous.result = new WeakReference<>(result);
                return;
            }
        }
        results.add(new Replay(component, result));
    }

    /** Recompute native space heights after the visibility owner finishes a live toggle. */
    void reflowAll() throws ReflectiveOperationException {
        List<Replay> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(results); }
        for (Replay replay : snapshot) {
            Object owner = replay.owner.get();
            Object result = replay.result.get();
            if (owner == null || result == null) continue;
            View[] spaces = views(owner);
            boolean attached = true;
            for (View space : spaces) {
                if (space == null || !space.isAttachedToWindow()) { attached = false; break; }
            }
            if (!attached) continue;
            // Both native wq branches honor GONE and notify their own cell adaptation pipeline.
            update.invoke(owner, result);
        }
        synchronized (this) {
            results.removeIf(replay -> replay.owner.get() == null || replay.result.get() == null);
        }
    }

    synchronized void clear() { results.clear(); }

    private void requireOwner(Object component) {
        if (!ownerType.isInstance(component)) {
            throw new IllegalArgumentException("Native feed space owner type changed");
        }
    }

    private static Field viewField(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = HostTargets.field(owner, name);
        if (field.getType() != View.class || Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException("Native feed space View field changed: " + name);
        }
        return field;
    }

    private static final class Replay {
        final WeakReference<Object> owner;
        WeakReference<Object> result;
        Replay(Object owner, Object result) {
            this.owner = new WeakReference<>(owner);
            this.result = new WeakReference<>(result);
        }
    }
}
