package com.coderstory.flyme10.xposedcompat;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Holder of the runtime {@link XposedInterface} instance provided by the modern Xposed
 * framework (API 101+). Set once by the module entry class in
 * {@code io.github.libxposed.api.XposedModuleInterface#onModuleLoaded}.
 */
public final class XposedApi {

    public static volatile XposedInterface api;

    private static final String TAG = "FlymeHelper";

    private XposedApi() {
    }

    public static boolean isAvailable() {
        return api != null;
    }

    public static XposedInterface get() {
        XposedInterface x = api;
        if (x == null) {
            throw new IllegalStateException("Xposed framework is not attached to this module");
        }
        return x;
    }

    public static void log(String msg) {
        XposedInterface x = api;
        if (x != null) {
            x.log(Log.INFO, TAG, msg);
        } else {
            // Module app process without the framework attached (e.g. plain app usage)
            Log.i(TAG, msg);
        }
    }

    public static void log(Throwable t) {
        XposedInterface x = api;
        if (x != null) {
            x.log(Log.ERROR, TAG, Log.getStackTraceString(t), t);
        } else {
            Log.e(TAG, Log.getStackTraceString(t));
        }
    }
}
