package com.tokai.module.feature;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Named model APIs verified in TikTok 47.5.3, shared by media extraction and metadata. */
final class HostMediaModels {
    private HostMediaModels() { }
    static Object photoInfo(Object work) { return HostTargets.invoke(work, "getPhotoModeImageInfo"); }
    static Object liveRoom(Object work) { return HostTargets.invoke(work, "getRoomFeedCellStruct"); }
    static boolean isTikTokWork(Object work) {
        if (work == null) return false;
        try {
            for (String name : new String[]{"getPhotoModeImageInfo", "getRoomFeedCellStruct"}) {
                Method method = work.getClass().getMethod(name);
                if (method.getReturnType().isPrimitive() || Modifier.isStatic(method.getModifiers())) return false;
            }
            return true;
        } catch (NoSuchMethodException | RuntimeException unavailable) { return false; }
    }
}
