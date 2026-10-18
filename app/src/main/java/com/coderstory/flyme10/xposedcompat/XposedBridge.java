package com.coderstory.flyme10.xposedcompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Legacy-shaped static hook API, re-implemented on top of the modern libxposed API.
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
        XposedApi.log(text);
    }

    public static void log(Throwable t) {
        XposedApi.log(t);
    }

    /**
     * Hooks a method or constructor with the given callback.
     */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Executable)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod);
        }
        XposedInterface x = XposedApi.get();
        Executable executable = (Executable) hookMethod;
        try {
            executable.setAccessible(true);
        } catch (Throwable ignored) {
        }
        XposedInterface.HookHandle handle = x.hook(executable).intercept(new HookerImpl(callback));
        return callback.newUnhook(handle);
    }

    /**
     * Hook all methods with a certain name in a class. In addition to the declared methods
     * of the class itself, inherited methods are hooked as well so that e.g. hooks on
     * framework methods implemented by a parent class work.
     */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                            XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        if (hookClass == null || methodName == null) {
            return result;
        }
        Class<?> c = hookClass;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    try {
                        result.add(hookMethod(m, callback));
                    } catch (Throwable t) {
                        log(t);
                    }
                }
            }
            c = c.getSuperclass();
        }
        return result;
    }

    /**
     * Hook all constructors of a class.
     */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        if (hookClass == null) {
            return result;
        }
        for (Constructor<?> ctor : hookClass.getDeclaredConstructors()) {
            try {
                result.add(hookMethod(ctor, callback));
            } catch (Throwable t) {
                log(t);
            }
        }
        return result;
    }
}
