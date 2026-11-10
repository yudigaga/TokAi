package com.tokai.module.feature;

import android.view.View;

import com.tokai.module.scan.HostScanReport;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Enters the current cell's native comment action, including its host permission gates. */
public final class TikTokNativeCommentOpener {
    private final Class<?> holderType;
    private final Class<?> abilityType;
    private final Class<?> itemType;
    private final Field holderRoot;
    private final Field holderScope;
    private final Method holderAweme;
    private final Method resolveAbility;
    private final Method open;
    private final Method currentItem;
    private final Method container;
    private final Method itemAweme;
    private final Method aid;

    private TikTokNativeCommentOpener(Class<?> holderType, Class<?> abilityType, Class<?> itemType,
                                      Field holderRoot, Field holderScope, Method holderAweme,
                                      Method resolveAbility, Method open, Method currentItem,
                                      Method container, Method itemAweme, Method aid) {
        this.holderType = holderType;
        this.abilityType = abilityType;
        this.itemType = itemType;
        this.holderRoot = holderRoot;
        this.holderScope = holderScope;
        this.holderAweme = holderAweme;
        this.resolveAbility = resolveAbility;
        this.open = open;
        this.currentItem = currentItem;
        this.container = container;
        this.itemAweme = itemAweme;
        this.aid = aid;
    }

    public static TikTokNativeCommentOpener resolve(HostScanReport report, ClassLoader loader) throws Exception {
        Class<?> holderType = HostTargets.type(report, "feed_holder_main_root", loader);
        Class<?> abilityType = HostTargets.type(report, "tiktok_comment_ability", loader);
        Class<?> itemType = HostTargets.type(report, "adv_gesture_item_params", loader);
        Method holderAweme = HostTargets.method(report, "feed_aweme_accessor", loader);
        Method resolver = HostTargets.method(report, "tiktok_comment_ability_resolver", loader);
        Method open = HostTargets.method(report, "tiktok_comment_open", loader);
        Method item = HostTargets.method(report, "tiktok_comment_item", loader);
        Field root = HostTargets.field(holderType, "u");
        Field scope = HostTargets.field(holderType, "t");
        Method container = open.getDeclaringClass().getMethod("getContainerView");
        Method itemAweme = itemType.getMethod("getAweme");
        Method aid = holderAweme.getReturnType().getMethod("getAid");
        Class<?>[] resolverParams = resolver.getParameterTypes();
        if (holderAweme.getDeclaringClass() != holderType || holderAweme.getParameterCount() != 0
                || !View.class.isAssignableFrom(root.getType())
                || !Modifier.isStatic(resolver.getModifiers()) || resolverParams.length != 3
                || resolverParams[0] != scope.getType() || resolverParams[1] != Class.class
                || resolverParams[2] != String.class || !resolver.getReturnType().isAssignableFrom(abilityType)
                || !abilityType.isInterface() || !abilityType.isAssignableFrom(open.getDeclaringClass())
                || Modifier.isStatic(open.getModifiers()) || open.getReturnType() != void.class
                || open.getParameterCount() != 1 || open.getParameterTypes()[0] != int.class
                || !Modifier.isStatic(item.getModifiers()) || item.getParameterCount() != 1
                || !item.getParameterTypes()[0].isAssignableFrom(open.getDeclaringClass())
                || !View.class.isAssignableFrom(container.getReturnType())
                || itemAweme.getReturnType() != holderAweme.getReturnType() || aid.getReturnType() != String.class) {
            throw new IllegalStateException("Native comment cell relationship changed");
        }
        return new TikTokNativeCommentOpener(holderType, abilityType, itemType, root, scope,
                holderAweme, resolver, open, item, container, itemAweme, aid);
    }

    /** True means the host action returned, not that its native gates displayed a panel. */
    public boolean open(Object holder, View root) throws ReflectiveOperationException {
        if (!holderType.isInstance(holder) || root == null || holderRoot.get(holder) != root
                || !root.isAttachedToWindow() || !root.isShown()) return false;
        Object expectedAweme = invoke(holderAweme, holder);
        Object expectedAid = expectedAweme == null ? null : invoke(aid, expectedAweme);
        if (!(expectedAid instanceof String) || ((String) expectedAid).isEmpty()) return false;
        Object scope = holderScope.get(holder);
        if (scope == null) return false;
        Object ability = invoke(resolveAbility, null, scope, abilityType, null);
        if (!abilityType.isInstance(ability) || !open.getDeclaringClass().isInstance(ability)) return false;
        Object control = invoke(container, ability);
        if (!(control instanceof View) || !belongsToRoot((View) control, root)) return false;
        Object item = invoke(currentItem, null, ability);
        if (!itemType.isInstance(item)) return false;
        Object boundAweme = invoke(itemAweme, item);
        if (boundAweme == null || !expectedAid.equals(invoke(aid, boundAweme))) return false;
        Object latestAweme = invoke(holderAweme, holder);
        if (holderRoot.get(holder) != root || holderScope.get(holder) != scope
                || latestAweme == null || !expectedAid.equals(invoke(aid, latestAweme))) return false;
        // jw2(1) enters qq/common-app-diff, then zq's normal icon action and permission gates.
        // jw2(2) directly publishes a DataCenter event and deliberately is not used here.
        invoke(open, ability, 1);
        return true;
    }

    private static boolean belongsToRoot(View view, View root) {
        for (View current = view; current != null;
             current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current == root) return true;
        }
        return false;
    }

    private static Object invoke(Method method, Object owner, Object... arguments) throws ReflectiveOperationException {
        try { return method.invoke(owner, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
