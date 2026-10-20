package com.coderstory.flyme10.xposedcompat;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.CharacterStyle;

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

    /**
     * Some ROM string resources carry style spans (e.g. &lt;font&gt; color markup) — the
     * host view renders the text with those styles. Replacing such a value with a plain
     * String would strip the spans and the text would fall back to the view's default
     * paint (observed as "white text on white background" etc.). When the original value
     * is a Spanned, copy its CharacterStyle spans onto the replacement text so only the
     * characters change, not the styling.
     */
    private static Object withOriginalSpans(Object replacement, Object original) {
        if (!(original instanceof Spanned) || !(replacement instanceof String)) {
            return replacement;
        }
        try {
            Spanned src = (Spanned) original;
            SpannableString out = new SpannableString((String) replacement);
            Object[] spans = src.getSpans(0, src.length(), CharacterStyle.class);
            int len = out.length();
            for (Object span : spans) {
                int s = Math.min(src.getSpanStart(span), len);
                int e = Math.min(src.getSpanEnd(span), len);
                out.setSpan(span, s, e, src.getSpanFlags(span));
            }
            return out;
        } catch (Throwable t) {
            return replacement;
        }
    }

    /** Per-id throttle for hit diagnostics: first hit, then at most one per minute. */
    private static final ConcurrentHashMap<Integer, Long> HIT_LOG_AT = new ConcurrentHashMap<>();

    private static void maybeLogHit(int id, Object original, Object replaced) {
        long now = System.nanoTime() / 1_000_000L;
        Long last = HIT_LOG_AT.get(id);
        if (last != null && now - last < 60_000L) {
            return;
        }
        HIT_LOG_AT.put(id, now);
        StringBuilder sb = new StringBuilder();
        sb.append("资源替换诊断 id=0x").append(Integer.toHexString(id));
        sb.append(" 原值=").append(original == null ? "null" : original.toString());
        sb.append(" 原值类型=").append(original == null ? "null" : original.getClass().getName());
        if (original instanceof Spanned) {
            Spanned sp = (Spanned) original;
            Object[] spans = sp.getSpans(0, sp.length(), CharacterStyle.class);
            sb.append("（样式Span: ");
            for (Object s : spans) {
                sb.append(s.getClass().getSimpleName()).append(' ');
            }
            sb.append("）");
        }
        sb.append(" 替换后=").append(replaced);
        sb.append("\n调用栈:");
        StackTraceElement[] st = new Exception().getStackTrace();
        int n = Math.min(st.length, 18);
        for (int i = 0; i < n; i++) {
            sb.append("\n  at ").append(st[i].toString());
        }
        XposedBridge.log(sb.toString());
    }

    private static final XC_MethodHook STRING_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Entry e = BY_ID.get(param.args[0]);
            if (e != null) {
                Object v = effectiveValue(e);
                maybeLogHit((Integer) param.args[0], param.getResult(), v);
                if (v instanceof String) {
                    param.setResult(withOriginalSpans(v, param.getResult()));
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
                maybeLogHit((Integer) param.args[0], param.getResult(), v);
                if (v instanceof String) {
                    param.setResult(withOriginalSpans(v, param.getResult()));
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
                maybeLogHit((Integer) param.args[0], param.getResult(), v);
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
