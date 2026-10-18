package com.coderstory.flyme10.xposedcompat;

import android.app.Application;
import android.content.Context;

/**
 * Legacy-shaped helper for information about the current app.
 */
public final class AndroidAppHelper {

    /** Stashed host Application, set as soon as attachBaseContext runs (see ResourceCompat). */
    public static volatile Context hostContext;

    private AndroidAppHelper() {
    }

    /**
     * Returns the main {@link android.app.Application} object in the current process.
     */
    public static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Object app = activityThread.getMethod("currentApplication").invoke(thread);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            XposedApi.log(t);
            return null;
        }
    }

    /**
     * Returns any usable {@link Context} in the current process: the host Application
     * if it exists yet, the context stashed at attachBaseContext, or the ActivityThread
     * system context. Used to reach ContentResolver before the host Application is
     * created (module load happens earlier than app code).
     */
    public static Context anyContext() {
        Context c = currentApplication();
        if (c != null) {
            return c;
        }
        c = hostContext;
        if (c != null) {
            return c;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object sys = activityThread.getMethod("getSystemContext").invoke(thread);
                return sys instanceof Context ? (Context) sys : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
