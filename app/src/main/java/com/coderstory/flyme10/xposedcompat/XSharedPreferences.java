package com.coderstory.flyme10.xposedcompat;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Legacy-shaped read-only preferences for hooked processes.
 *
 * <p>Channels (in order):</p>
 * <ol>
 *   <li>the module app's exported {@code PreferencesProvider} — a cross-process
 *       {@code ContentResolver.call("getAll")} that always reflects the app's
 *       current (device-encrypted) storage, available even before the first
 *       unlock thanks to the provider being directBootAware;</li>
 *   <li>the framework's remote preferences service
 *       ({@link XposedInterface#getRemotePreferences}), for frameworks that
 *       actually serve it.</li>
 * </ol>
 *
 * <p>The legacy file-direct XML parsing path has been removed: on modern
 * frameworks the module's shared_prefs file is {@code 0600} behind SELinux and
 * can never be read from hooked processes, so the code was dead weight.</p>
 */
public final class XSharedPreferences {

    private static final String MODULE_PACKAGE = "com.coderstory.flyme10";

    private final String packageName;
    private final String prefFileName;   // display name, always with .xml
    private final String groupName;      // group name (no .xml)

    private android.content.SharedPreferences remote;
    private boolean remoteChecked = false;

    /**
     * Process-wide cache of values fetched through the module app's exported
     * PreferencesProvider (content://...PreferencesProvider, call method "getAll").
     * Bounded by a short TTL to keep IPC cheap.
     */
    private static final long PROVIDER_TTL_MS = 1000L;
    private static final Object PROVIDER_LOCK = new Object();
    private static volatile Map<String, Object> providerCache;
    private static volatile long providerCacheAt = Long.MIN_VALUE;

    private static Map<String, Object> callProvider(String groupName) {
        try {
            android.content.Context ctx = AndroidAppHelper.anyContext();
            if (ctx == null) {
                return null;
            }
            Uri uri = Uri.parse("content://" + MODULE_PACKAGE + ".PreferencesProvider");
            Bundle b = ctx.getContentResolver().call(uri, "getAll", groupName, null);
            if (b == null) {
                return null;
            }
            Map<String, Object> out = new HashMap<>();
            for (String k : b.keySet()) {
                out.put(k, b.get(k));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile boolean wakeAttempted = false;

    /**
     * 安装/升级后未打开过、或被系统强制停止的 App 处于 stopped 状态：普通 uid 的宿主进程
     * 对其 provider 的访问会被系统拦截（uid 1000 的系统进程豁免——真机日志中
     * flyme.update 与 system:ui 每次都能读到配置，而 systemui/launcher 全部失败）。
     * 发送携带 FLAG_INCLUDE_STOPPED_PACKAGES 的显式广播唤醒一次以清除该状态。
     */
    private static void wakeModule() {
        if (wakeAttempted) {
            return;
        }
        wakeAttempted = true;
        try {
            android.content.Context ctx = AndroidAppHelper.anyContext();
            if (ctx == null) {
                return;
            }
            Intent i = new Intent();
            i.setClassName(MODULE_PACKAGE, MODULE_PACKAGE + ".tools.WakeReceiver");
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(i);
            XposedBridge.log("偏好通道: provider 不可达，已发送 stopped 唤醒广播");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static Map<String, Object> providerGetAll(String groupName) {
        synchronized (PROVIDER_LOCK) {
            long now = SystemClock.elapsedRealtime();
            Map<String, Object> cached = providerCache;
            if (cached != null && now - providerCacheAt < PROVIDER_TTL_MS) {
                return cached;
            }
            Map<String, Object> fresh = callProvider(groupName);
            if (fresh == null) {
                wakeModule(); // 解除 stopped 状态后重试一次
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
                fresh = callProvider(groupName);
            }
            if (fresh != null) {
                providerCache = fresh;
                providerCacheAt = now;
                return fresh;
            }
            return cached; // stale values are better than none
        }
    }

    public XSharedPreferences(String packageName, String prefFileName) {
        this.packageName = packageName;
        this.prefFileName = prefFileName.endsWith(".xml") ? prefFileName : prefFileName + ".xml";
        this.groupName = stripXml(prefFileName);
    }

    public XSharedPreferences(String prefFileName) {
        this(MODULE_PACKAGE, prefFileName);
    }

    /** Kept for source compatibility with the legacy API; the file is never read. */
    public XSharedPreferences(File file) {
        this(MODULE_PACKAGE, stripXml(file.getName()));
    }

    private static String stripXml(String name) {
        return name.endsWith(".xml") ? name.substring(0, name.length() - 4) : name;
    }

    /** Tries to obtain the framework-served remote preferences once. */
    private synchronized void ensureRemote() {
        if (remoteChecked) {
            return;
        }
        remoteChecked = true;
        try {
            XposedInterface x = XposedApi.api;
            if (x != null) {
                remote = x.getRemotePreferences(groupName);
            }
        } catch (Throwable t) {
            // Embedded framework or preferences service unavailable
            remote = null;
        }
    }

    /** Whether the framework's remote preferences service is in use. */
    public boolean isRemote() {
        ensureRemote();
        return remote != null;
    }

    public Map<String, Object> getAll() {
        // 1) App 端导出的 PreferencesProvider：跨进程读取 App 实时配置
        //    （DE 存储 + directBootAware，重启后首次解锁前即可使用）
        Map<String, Object> fromProvider = providerGetAll(groupName);
        if (fromProvider != null && !fromProvider.isEmpty()) {
            return new HashMap<>(fromProvider);
        }
        // 2) 框架远程偏好服务（getRemotePreferences）
        ensureRemote();
        if (remote != null) {
            try {
                Map<String, Object> out = new HashMap<>();
                for (Map.Entry<String, ?> e : remote.getAll().entrySet()) {
                    out.put(e.getKey(), e.getValue());
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Throwable ignored) {
                // fall through to empty
            }
        }
        return new HashMap<String, Object>();
    }

    /**
     * Diagnostic helper for the module load probe: per-channel item counts.
     * "-1" marks a channel that is unavailable.
     */
    public String channelReport() {
        int p;
        try {
            Map<String, Object> m = providerGetAll(groupName);
            p = m == null ? -1 : m.size();
        } catch (Throwable t) {
            p = -1;
        }
        int r;
        ensureRemote();
        if (remote != null) {
            try {
                r = remote.getAll().size();
            } catch (Throwable t) {
                r = -1;
            }
        } else {
            r = -1;
        }
        return "provider=" + p + " 远程=" + r;
    }

    public boolean getBoolean(String key, boolean defValue) {
        Object v = getAll().get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    public String getString(String key, String defValue) {
        Object v = getAll().get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @SuppressWarnings("unused")
    public int getInt(String key, int defValue) {
        Object v = getAll().get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @SuppressWarnings("unused")
    public long getLong(String key, long defValue) {
        Object v = getAll().get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @SuppressWarnings("unused")
    public Set<String> getStringSet(String key, Set<String> defValue) {
        Object v = getAll().get(key);
        //noinspection unchecked
        return v instanceof Set ? (Set<String>) v : defValue;
    }

    @SuppressWarnings("unused")
    public String getPrefFileName() {
        return prefFileName;
    }

    @SuppressWarnings("unused")
    public String getPackageName() {
        return packageName;
    }
}