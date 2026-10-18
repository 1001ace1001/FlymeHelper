package com.coderstory.flyme10.xposedcompat;

import java.lang.reflect.Member;

import io.github.libxposed.api.XposedInterface;

/**
 * Legacy-shaped method hook callback, re-implemented on top of the modern libxposed API
 * (io.github.libxposed:api). See {@link HookerImpl} for the bridging logic.
 */
public class XC_MethodHook {

    public XC_MethodHook() {
    }

    /**
     * @param priority kept for source compatibility with the legacy API, ignored by the
     *                 modern API (all hooks use the default priority).
     */
    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {

        /** The hooked method/constructor. */
        public Member method;

        /** The {@code this} reference for an instance method, or {@code null} for static methods. */
        public Object thisObject;

        /** Arguments to the method call. */
        public Object[] args;

        private Object result = null;
        private Throwable throwable = null;
        boolean returnEarly = false;

        /** Returns the result of the method call. */
        public Object getResult() {
            return result;
        }

        /**
         * Modify the result of the method call.
         * <p>If called from {@link #beforeHookedMethod}, it prevents the call to the original method.
         */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        /** Returns the {@link Throwable} thrown by the method, or {@code null}. */
        public Throwable getThrowable() {
            return throwable;
        }

        /** Returns true if an exception was thrown by the method. */
        public boolean hasThrowable() {
            return throwable != null;
        }

        /**
         * Modify the exception thrown of the method call.
         * <p>If called from {@link #beforeHookedMethod}, it prevents the call to the original method.
         */
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        /** Returns the result of the method call, or throws the Throwable caused by it. */
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }

    /** An object with which the method/constructor can be unhooked. */
    public class Unhook {

        private final XposedInterface.HookHandle handle;

        Unhook(XposedInterface.HookHandle handle) {
            this.handle = handle;
        }

        /** Returns the method/constructor that has been hooked. */
        public Member getHookedMethod() {
            return handle == null ? null : handle.getExecutable();
        }

        public void unhook() {
            if (handle != null) {
                handle.unhook();
            }
        }

        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }
    }

    Unhook newUnhook(XposedInterface.HookHandle handle) {
        return new Unhook(handle);
    }
}
