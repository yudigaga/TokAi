package com.tokai.module.feature;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** TikTok's exact cached getter owner cannot be expressed as a Java source identifier. */
final class TikTokRequestedSpeedFixture {
    final Method getter;
    final FeedGestureToolsHookTest.IPlayerManager player;
    float requested = 1.25f;
    final float engineSpeed = 1f;
    int reads;
    int writes;

    TikTokRequestedSpeedFixture() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xcafebabe); out.writeShort(0); out.writeShort(52);
            out.writeShort(7);
            out.writeByte(1); out.writeUTF("X/037d");
            out.writeByte(7); out.writeShort(1);
            out.writeByte(1); out.writeUTF("java/lang/Object");
            out.writeByte(7); out.writeShort(3);
            out.writeByte(1); out.writeUTF("getSpeed");
            out.writeByte(1); out.writeUTF("()F");
            out.writeShort(0x0601); out.writeShort(2); out.writeShort(4);
            out.writeShort(0); out.writeShort(0); out.writeShort(1);
            out.writeShort(0x0401); out.writeShort(5); out.writeShort(6); out.writeShort(0);
            out.writeShort(0);
        }
        byte[] definition = bytes.toByteArray();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass("X.037d", definition, 0, definition.length); }
        };
        Method define = loader.getClass().getDeclaredMethod("define");
        define.setAccessible(true);
        Class<?> owner = (Class<?>) define.invoke(loader);
        getter = owner.getMethod("getSpeed");
        player = (FeedGestureToolsHookTest.IPlayerManager) Proxy.newProxyInstance(loader,
                new Class<?>[]{owner, FeedGestureToolsHookTest.IPlayerManager.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSpeed": reads++; return requested;
                        case "setSpeed": writes++; requested = (Float) args[0]; return null;
                        case "isPlaying": return true;
                        case "play": return "started";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "TikTok requested-speed fixture";
                        default: return null;
                    }
                });
    }
}
