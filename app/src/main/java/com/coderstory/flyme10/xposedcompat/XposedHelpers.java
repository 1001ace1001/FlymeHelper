package com.coderstory.flyme10.xposedcompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Legacy-shaped reflection helpers, re-implemented with plain Java reflection (no runtime
 * dependency on the legacy Xposed bridge).
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static class ClassNotFoundError extends Error {

        private static final long serialVersionUID = -9179845689965300330L;

        public ClassNotFoundError(String detailMessage) {
            super(detailMessage);
        }

        public ClassNotFoundError(Throwable cause) {
            super(cause);
        }
    }

    // ------------------------------------------------------------------ classes

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        ClassLoader cl = classLoader;
        if (cl == null) {
            cl = XposedHelpers.class.getClassLoader();
        }
        try {
            return Class.forName(className, false, cl);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(e);
        }
    }

    // ------------------------------------------------------------------ methods

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName, Object... parameterTypesAndCallback) {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                          Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0) {
            throw new IllegalArgumentException("No hook callback given. You must pass at least one parameter" +
                    " which is the callback to register (and optionally parameter types before it).");
        }
        XC_MethodHook callback = extractCallback(parameterTypesAndCallback);
        Class<?>[] types = resolveParameterTypes(clazz, parameterTypesAndCallback);
        Method m = findMethodExact(clazz, methodName, types);
        return XposedBridge.hookMethod(m, callback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader classLoader,
                                                               Object... parameterTypesAndCallback) {
        return findAndHookConstructor(findClass(className, classLoader), parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        XC_MethodHook callback = extractCallback(parameterTypesAndCallback);
        Class<?>[] types = resolveParameterTypes(clazz, parameterTypesAndCallback);
        Constructor<?> ctor = findConstructorExact(clazz, types);
        return XposedBridge.hookMethod(ctor, callback);
    }

    private static XC_MethodHook extractCallback(Object[] parameterTypesAndCallback) {
        Object last = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(last instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("Last parameter must be an XC_MethodHook callback");
        }
        return (XC_MethodHook) last;
    }

    private static Class<?>[] resolveParameterTypes(Class<?> clazz, Object[] parameterTypesAndCallback) {
        int n = parameterTypesAndCallback.length - 1;
        Class<?>[] types = new Class<?>[n];
        for (int i = 0; i < n; i++) {
            Object t = parameterTypesAndCallback[i];
            if (t instanceof Class) {
                types[i] = (Class<?>) t;
            } else if (t instanceof String) {
                types[i] = findClass((String) t, clazz.getClassLoader());
            } else if (t == null) {
                throw new IllegalArgumentException("Parameter type may not be null");
            } else {
                throw new IllegalArgumentException("Parameter type must be a Class or String, got " + t.getClass());
            }
        }
        return types;
    }

    /** Exact lookup walking the class hierarchy, falling back to public method lookup. */
    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && Arrays.equals(m.getParameterTypes(), parameterTypes)) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException ignored) {
        }
        throw new NoSuchMethodError("Method " + methodName + " with parameters " +
                Arrays.toString(parameterTypes) + " not found in " + clazz.getName());
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) {
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (Arrays.equals(ctor.getParameterTypes(), parameterTypes)) {
                return ctor;
            }
        }
        throw new NoSuchMethodError("Constructor with parameters " + Arrays.toString(parameterTypes) +
                " not found in " + clazz.getName());
    }

    // ------------------------------------------------------------------ fields

    public static Field findField(Class<?> originClass, String fieldName) {
        for (Class<?> c = originClass; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldError("Field " + fieldName + " not found in " + originClass.getName());
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (Throwable t) {
            throw rethrow("get field " + fieldName + " of " + obj.getClass(), t);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (Throwable t) {
            throw rethrow("set field " + fieldName + " of " + obj.getClass(), t);
        }
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (Throwable t) {
            throw rethrow("set field " + fieldName + " of " + obj.getClass(), t);
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f.getType() == int.class) {
                return f.getInt(obj);
            }
            Object v = f.get(obj);
            return v instanceof Number ? ((Number) v).intValue() : 0;
        } catch (Throwable t) {
            throw rethrow("get field " + fieldName + " of " + obj.getClass(), t);
        }
    }

    // ------------------------------------------------------------------ calls

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Method m = findMethodBestMatch(obj.getClass(), methodName, args);
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            throw rethrow("call " + methodName + " of " + obj.getClass(), t);
        }
    }

    @SuppressWarnings("unused")
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Method m = findMethodBestMatch(clazz, methodName, args);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            throw rethrow("call static " + methodName + " of " + clazz, t);
        }
    }

    private static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        List<Method> candidates = new ArrayList<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterTypes().length == args.length) {
                    if (isCompatible(m.getParameterTypes(), args)) {
                        candidates.add(m);
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            try {
                return clazz.getMethod(methodName, typesOf(args));
            } catch (NoSuchMethodException e) {
                throw new NoSuchMethodError("Method " + methodName + " matching " +
                        Arrays.toString(args) + " not found in " + clazz.getName());
            }
        }
        // Prefer an exact signature match if available
        for (Method m : candidates) {
            if (Arrays.equals(m.getParameterTypes(), typesOf(args))) {
                return m;
            }
        }
        return candidates.get(0);
    }

    private static Class<?>[] typesOf(Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        return types;
    }

    private static boolean isCompatible(Class<?>[] paramTypes, Object... args) {
        if (paramTypes.length != args.length) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = args[i];
            Class<?> p = paramTypes[i];
            if (arg == null) {
                if (p.isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> a = arg.getClass();
            if (p.isPrimitive()) {
                p = box(p);
                if (p == null) {
                    return false;
                }
            }
            if (!p.isAssignableFrom(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> primitive) {
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == char.class) return Character.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        return null;
    }

    private static RuntimeException rethrow(String what, Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new RuntimeException("Failed to " + what + ": " + cause, cause);
    }

    @SuppressWarnings("unused")
    private static boolean isStatic(Member m) {
        return Modifier.isStatic(m.getModifiers());
    }
}
