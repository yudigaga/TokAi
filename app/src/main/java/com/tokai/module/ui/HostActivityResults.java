package com.tokai.module.ui;

import java.lang.reflect.Method;

/** Exact AndroidX names recovered from TikTok 47.5.3 base classes.dex. */
final class HostActivityResults {
    private HostActivityResults() { }

    static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException stripped) {
            String actual;
            switch (name) {
                case "androidx.activity.result.contract.ActivityResultContract": actual = "X.015b"; break;
                case "androidx.activity.result.ActivityResultCallback": actual = "X.013L"; break;
                case "androidx.activity.result.ActivityResultRegistry": actual = "X.013R"; break;
                case "androidx.activity.result.ActivityResultLauncher": actual = "X.013N"; break;
                case "androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult": actual = "X.01uR"; break;
                default: throw stripped;
            }
            return Class.forName(actual, false, loader);
        }
    }

    static Method register(Class<?> registry, Class<?> contract, Class<?> callback)
            throws NoSuchMethodException {
        return registry.getMethod("X.013R".equals(registry.getName()) ? "LIZLLL" : "register",
                String.class, contract, callback);
    }

    static boolean callback(Method method) {
        return "onActivityResult".equals(method.getName())
                || "X.013L".equals(method.getDeclaringClass().getName()) && "LIZ".equals(method.getName());
    }

    static void launch(Class<?> type, Object launcher, Object input) throws ReflectiveOperationException {
        if ("X.013N".equals(type.getName())) {
            type.getMethod("LIZ", Object.class, Class.forName("X.01Yo", false, type.getClassLoader()))
                    .invoke(launcher, input, null);
        } else type.getMethod("launch", Object.class).invoke(launcher, input);
    }

    static void unregister(Class<?> type, Object launcher) throws ReflectiveOperationException {
        type.getMethod("X.013N".equals(type.getName()) ? "LIZIZ" : "unregister").invoke(launcher);
    }

    static int resultCode(Object result) throws ReflectiveOperationException {
        try { return (Integer) result.getClass().getMethod("getResultCode").invoke(result); }
        catch (NoSuchMethodException inlined) {
            java.lang.reflect.Field field = result.getClass().getField("resultCode");
            if (field.getType() != int.class) throw inlined;
            return field.getInt(result);
        }
    }

    static android.content.Intent resultData(Object result) throws ReflectiveOperationException {
        try { return (android.content.Intent) result.getClass().getMethod("getData").invoke(result); }
        catch (NoSuchMethodException inlined) {
            java.lang.reflect.Field field = result.getClass().getField("data");
            if (field.getType() != android.content.Intent.class) throw inlined;
            return (android.content.Intent) field.get(result);
        }
    }
}
