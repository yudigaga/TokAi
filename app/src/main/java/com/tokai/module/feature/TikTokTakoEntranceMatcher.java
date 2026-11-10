package com.tokai.module.feature;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.tokai.module.scan.HostAdaptation;

/** Identifies the feed's native Tako entrance without matching the chat page. */
public final class TikTokTakoEntranceMatcher {
    private static final int NORMAL_ROOT = 2131396568; // ba5 / zbh
    private static final int BUBBLE_ROOT = 2131396573; // ba6 / zbm
    private static final int BUBBLE_BODY = 2131396574; // zbn
    private static final int BUBBLE_BOARD = 2131396572; // zbl
    private static final int ICON = 2131396564; // zbd
    private static final int IN_CELL_ROOT = 2131395575; // fvn ViewStub inflatedId / yln
    private static final int FIXED_ROOT = 2131395576; // fvn ViewStub inflatedId / ylo
    private static final int FIXED_ICON = 2131379170; // fvn / mm8
    private static final int MAX_CHILDREN = 16;

    private TikTokTakoEntranceMatcher() { }

    public static boolean matches(View view, boolean verifyResourceNames) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup root = (ViewGroup) view;
        if (root.getChildCount() > MAX_CHILDREN) return false;
        if (view instanceof FrameLayout && root.getChildCount() == 1
                && (resource(view, IN_CELL_ROOT, "yln", verifyResourceNames)
                || resource(view, FIXED_ROOT, "ylo", verifyResourceNames))) {
            View icon = child(root, FIXED_ICON, "mm8", verifyResourceNames);
            return icon != null && hasClass(icon, "com.bytedance.tux.icon.TuxIconView");
        }
        if (view instanceof FrameLayout && resource(view, NORMAL_ROOT, "zbh", verifyResourceNames)) {
            return icon(root, verifyResourceNames);
        }
        if (hasClass(view, "androidx.constraintlayout.widget.ConstraintLayout")
                && resource(view, BUBBLE_ROOT, "zbm", verifyResourceNames)) {
            View body = child(root, BUBBLE_BODY, "zbn", verifyResourceNames);
            View board = child(root, BUBBLE_BOARD, "zbl", verifyResourceNames);
            return body instanceof FrameLayout && board != null && icon((FrameLayout) body, verifyResourceNames);
        }
        return false;
    }

    private static boolean icon(ViewGroup parent, boolean verifyNames) {
        View image = child(parent, ICON, "zbd", verifyNames);
        return image != null && hasClass(image, "com.bytedance.lighten.loader.SmartImageView");
    }

    /** All ownership checks are shallow, bounded, and reject duplicate native identities. */
    private static View child(ViewGroup parent, int id, String name, boolean verifyNames) {
        if (parent.getChildCount() > MAX_CHILDREN) return null;
        View found = null;
        for (int index = 0; index < parent.getChildCount(); index++) {
            View next = parent.getChildAt(index);
            if (next.getId() != id) continue;
            if (found != null || !resource(next, id, name, verifyNames)) return null;
            found = next;
        }
        return found;
    }

    private static boolean resource(View view, int id, String name, boolean verifyNames) {
        if (view.getId() != id) return false;
        if (!verifyNames) return true;
        try {
            return HostAdaptation.TARGET_PACKAGE.equals(view.getResources().getResourcePackageName(id))
                    && name.equals(view.getResources().getResourceEntryName(id));
        } catch (android.content.res.Resources.NotFoundException unavailable) {
            return false;
        }
    }

    private static boolean hasClass(View view, String name) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return true;
        }
        return false;
    }
}
