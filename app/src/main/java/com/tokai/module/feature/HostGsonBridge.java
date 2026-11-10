package com.tokai.module.feature;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact Gson API/annotation aliases verified in TikTok 47.5.3; no structural first-match fallback. */
public final class HostGsonBridge {
    private HostGsonBridge() { }

    public static Method serializerMethod(Class<?> gsonClass) throws NoSuchMethodException {
        for (String name : new String[]{"toJson", "LJIILL"}) {
            Method method;
            try { method = gsonClass.getMethod(name, Object.class); }
            catch (NoSuchMethodException absent) { continue; }
            if (method.getReturnType() != String.class || Modifier.isStatic(method.getModifiers())) {
                throw new NoSuchMethodException("Host Gson serializer shape changed: " + name);
            }
            return method;
        }
        throw new NoSuchMethodException("No verified host Gson serializer");
    }

    public static String toJson(Object gson, Object value) throws ReflectiveOperationException {
        Object result = serializerMethod(gson.getClass()).invoke(gson, value);
        if (!(result instanceof String)) throw new IllegalStateException("Host Gson returned a non-string result");
        return (String) result;
    }

    public static String toJson(Object value) throws ReflectiveOperationException {
        ClassLoader loader = value == null ? HostGsonBridge.class.getClassLoader() : value.getClass().getClassLoader();
        return toJsonFromHost(value, loader);
    }

    public static String toJsonFromHost(Object value, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.google.gson.Gson", true, loader);
        return toJson(type.getConstructor().newInstance(), value);
    }

    public static String serializedName(Field field) {
        for (Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (!"com.google.gson.annotations.SerializedName".equals(name) && !"X.02up".equals(name)) continue;
            try {
                Method value = annotation.annotationType().getMethod("value");
                if (value.getReturnType() != String.class || value.getParameterCount() != 0) return null;
                return (String) value.invoke(annotation);
            } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
        }
        return null;
    }

    public static boolean matchesName(Field field, String requested) {
        if (requested == null) return false;
        if (requested.equals(serializedName(field))) return true;
        for (Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (!"com.google.gson.annotations.SerializedName".equals(name) && !"X.02up".equals(name)) continue;
            try {
                Method alternate = annotation.annotationType().getMethod("alternate");
                if (alternate.getReturnType() != String[].class || alternate.getParameterCount() != 0) return false;
                for (String alias : (String[]) alternate.invoke(annotation)) if (requested.equals(alias)) return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
        }
        return false;
    }
}
