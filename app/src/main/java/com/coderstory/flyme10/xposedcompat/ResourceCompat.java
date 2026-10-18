package com.coderstory.flyme10.xposedcompat;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deferred resource replacement ("reshook") for the modern libxposed API.
 * <p>
 * The modern API (101/102) has no resource replacement callbacks — LSPosed removed the
 * legacy XResources bridge for modern modules. Replacements registered via
 * {@link XResCompat} are therefore queued per process and applied as soon as the host
 * app's {@link Resources} object is available:
 * <ol>
 *   <li>Legacy XResources bridge — used when the host framework provides
 *       {@code setReplacement} on the Resources object (kept for frameworks that still do).</li>
 *   <li>Fallback emulation — hook the {@code Resources.getString}/{@code getText} getters
 *       and serve the replacement values by resolved resource id. Same technique as
 *       EzHookTool / HyperCeiler's ResourcesTool. Covers lookups made from Java code
 *       (e.g. {@code context.getString(id)}); XML layout/style references that resolve
 *       natively cannot be intercepted this way and are skipped.</li>
 * </ol>
 */
public final class ResourceCompat {

    private static final class Replacement {
        final String targetPackage;
        final int id;
        final String pkg;
        final String type;
        final String name;
        final Object value;
        /** Optional preference key that must be true for the replacement to apply. */
        final String gateKey;

        Replacement(String targetPackage, int id, String pkg, String type, String name, Object value, String gateKey) {
            this.targetPackage = targetPackage;
            this.id = id;
            this.pkg = pkg;
            this.type = type;
            this.name = name;
            this.value = value;
            this.gateKey = gateKey;
        }
    }

    private static final List<Replacement> PENDING = new ArrayList<>();
    private static boolean appHookInstalled = false;

    /** Replacements served by the emulation layer, by resolved resource id. */
    private static final ConcurrentHashMap<Integer, Entry> BY_ID = new ConcurrentHashMap<>();
    private static volatile boolean emulationInstalled = false;

    private static final class Entry {
        final Object value;
        final String gateKey;

        Entry(Object value, String gateKey) {
            this.value = value;
            this.gateKey = gateKey;
        }
    }

    /** Lazily created prefs used to evaluate gated replacements at query time. */
    private static volatile XSharedPreferences gatePrefs;

    private static boolean gateEnabled(String gateKey) {
        try {
            XSharedPreferences p = gatePrefs;
            if (p == null) {
                synchronized (ResourceCompat.class) {
                    p = gatePrefs;
                    if (p == null) {
                        p = new XSharedPreferences("com.coderstory.flyme10", "UserSettings");
                        gatePrefs = p;
                    }
                }
            }
            return p.getBoolean(gateKey, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object effectiveValue(Entry e) {
        if (e.gateKey != null && !gateEnabled(e.gateKey)) {
            return null;
        }
        return e.value;
    }

    private static final XC_MethodHook STRING_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Entry e = BY_ID.get(param.args[0]);
            if (e != null) {
                Object v = effectiveValue(e);
                if (v instanceof String) {
                    param.setResult(v);
                }
            }
        }
    };

    private static final XC_MethodHook TEXT_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Entry e = BY_ID.get(param.args[0]);
            if (e != null) {
                Object v = effectiveValue(e);
                if (v instanceof String) {
                    param.setResult(v);
                }
            }
        }
    };

    private static final XC_MethodHook FORMAT_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Entry e = BY_ID.get(param.args[0]);
            if (e != null) {
                Object v = effectiveValue(e);
                if (v instanceof String) {
                    try {
                        param.setResult(String.format((String) v, (Object[]) param.args[1]));
                    } catch (Throwable ignored) {
                        param.setResult(v);
                    }
                }
            }
        }
    };

    private ResourceCompat() {
    }

    static void addIntReplacement(String targetPackage, int id, Object value) {
        addIntReplacement(targetPackage, id, value, null);
    }

    static void addIntReplacement(String targetPackage, int id, Object value, String gateKey) {
        if (id == 0) {
            return;
        }
        synchronized (PENDING) {
            PENDING.add(new Replacement(targetPackage, id, null, null, null, value, gateKey));
        }
    }

    static void addNameReplacement(String targetPackage, String pkg, String type, String name, Object value) {
        addNameReplacement(targetPackage, pkg, type, name, value, null);
    }

    static void addNameReplacement(String targetPackage, String pkg, String type, String name, Object value, String gateKey) {
        synchronized (PENDING) {
            PENDING.add(new Replacement(targetPackage, -1, pkg, type, name, value, gateKey));
        }
    }

    /**
     * Installs the hook that applies pending replacements once the app's Resources are
     * bound. Call once per process from the module entry (onModuleLoaded).
     */
    public static synchronized void install() {
        if (appHookInstalled) {
            return;
        }
        appHookInstalled = true;
        try {
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.thisObject instanceof Application) {
                        apply((Application) param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            XposedApi.log(t);
        }
    }

    private static void apply(Application app) {
        // Stash the host context as early as possible: the provider channel
        // (XSharedPreferences) needs it to query the module app's PreferencesProvider.
        AndroidAppHelper.hostContext = app;
        List<Replacement> mine;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            mine = new ArrayList<>(PENDING.size());
            String pkg = app.getPackageName();
            for (Replacement r : PENDING) {
                if (r.targetPackage.equals(pkg)) {
                    mine.add(r);
                }
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        Resources res;
        try {
            res = app.getResources();
        } catch (Throwable t) {
            XposedApi.log(t);
            return;
        }

        // 1) Legacy XResources bridge, when the host framework still provides it.
        int bridged = 0;
        for (Replacement r : mine) {
            try {
                if (r.gateKey != null && !gateEnabled(r.gateKey)) {
                    continue; // feature switch is off
                }
                if (r.id > 0) {
                    Method m = res.getClass().getMethod("setReplacement", int.class, Object.class);
                    m.invoke(res, r.id, r.value);
                } else {
                    Method m = res.getClass().getMethod("setReplacement",
                            String.class, String.class, String.class, Object.class);
                    m.invoke(res, r.pkg, r.type, r.name, r.value);
                }
                bridged++;
            } catch (Throwable t) {
                break;
            }
        }
        if (bridged == mine.size()) {
            XposedApi.log("ResourceCompat: 通过旧版XResources桥应用了 " + mine.size() + " 项资源替换 → " + app.getPackageName());
            return;
        }

        // 2) Emulation: intercept Resources getters (EzHookTool / HyperCeiler technique).
        installEmulation(app, res, mine);
    }

    private static void installEmulation(Application app, Resources res, List<Replacement> mine) {
        int resolved = 0;
        synchronized (ResourceCompat.class) {
            for (Replacement r : mine) {
                try {
                    int id;
                    if (r.id > 0) {
                        id = r.id;
                    } else {
                        id = res.getIdentifier(r.name, r.type, r.pkg);
                        if (id == 0) {
                            XposedApi.log("ResourceCompat: 无法解析资源 " + r.pkg + ":" + r.type
                                    + "/" + r.name + "，跳过模拟替换");
                            continue;
                        }
                    }
                    BY_ID.put(id, new Entry(r.value, r.gateKey));
                    resolved++;
                } catch (Throwable t) {
                    XposedApi.log(t);
                }
            }
        }
        if (resolved == 0) {
            return;
        }
        if (!emulationInstalled) {
            emulationInstalled = true;
            try {
                XposedBridge.hookMethod(Resources.class.getMethod("getString", int.class), STRING_HOOK);
                XposedBridge.hookMethod(Resources.class.getMethod("getText", int.class), TEXT_HOOK);
                XposedBridge.hookMethod(Resources.class.getMethod("getString", int.class, Object[].class), FORMAT_HOOK);
                XposedApi.log("ResourceCompat: 已启用资源模拟替换（拦截 Resources getString/getText）");
            } catch (Throwable t) {
                XposedApi.log(t);
            }
        }
        XposedApi.log("ResourceCompat: 资源模拟替换已应用 " + resolved + " 项 → " + app.getPackageName());
    }
}
