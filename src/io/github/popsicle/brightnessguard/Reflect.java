package io.github.popsicle.brightnessguard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/** Reads exact names only; no field writes and no fuzzy mutation of unknown firmware. */
public final class Reflect {
    private Reflect() {}
    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> MISSING = new ConcurrentHashMap<>();

    public static Object read(Object target, String name) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        String key = c.getName() + "#" + name;
        if (MISSING.containsKey(key)) return null;
        try {
            Field f = FIELDS.get(key);
            if (f == null) {
                for (Class<?> p = c; p != null; p = p.getSuperclass()) {
                    try { f = p.getDeclaredField(name); f.setAccessible(true); break; }
                    catch (NoSuchFieldException ignored) {}
                }
                if (f == null) { MISSING.put(key, true); return null; }
                FIELDS.put(key, f);
            }
            return f.get(target);
        } catch (Throwable ignored) { return null; }
    }

    public static double number(Object target, String name) {
        Object value = read(target, name);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    public static Method method(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m; }
            catch (Throwable ignored) {}
        }
        return null;
    }

    public static Object invoke(Object target, String name) throws Exception {
        if (target == null) return null;
        Method m = method(target.getClass(), name);
        return m == null ? null : m.invoke(target);
    }
}
