package com.coderstory.flyme10.xposedcompat;

import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Legacy-shaped read-only preferences.
 *
 * <p>Primary source on modern frameworks (API 101+): the framework's remote
 * preferences service ({@link XposedInterface#getRemotePreferences}), which
 * serves the module app's {@code shared_prefs/<name>.xml} to hooked processes
 * through the framework's privileged daemon. Reading the file directly from a
 * hooked process is impossible there: the file is {@code 0600} and owned by the
 * module's uid (the legacy frameworks used to relax permissions; modern ones
 * do not).</p>
 *
 * <p>Fallback (no framework attached, embedded framework, or service failure
 * on a call): parse the module app's {@code shared_prefs/<name>.xml} file
 * directly, exactly like the legacy XSharedPreferences did.</p>
 */
public final class XSharedPreferences {

    private static final String MODULE_PACKAGE = "com.coderstory.flyme10";

    private final String packageName;
    private final String prefFileName;   // display name, always with .xml
    private final String groupName;      // remote preferences group (no .xml)
    private final File[] candidates;
    private Map<String, Object> values = Collections.emptyMap();
    private long lastModified = 0L;

    private android.content.SharedPreferences remote;
    private boolean remoteChecked = false;

    /**
     * Process-wide cache of values fetched through the module app's exported
     * PreferencesProvider (content://...PreferencesProvider, call method "getAll").
     * This channel always reflects the app's current file, independent of any
     * framework preference sync. Bounded by a short TTL to keep IPC cheap.
     */
    private static final long PROVIDER_TTL_MS = 1000L;
    private static final Object PROVIDER_LOCK = new Object();
    private static volatile Map<String, Object> providerCache;
    private static volatile long providerCacheAt = Long.MIN_VALUE;

    private static Map<String, Object> providerGetAll(String groupName) {
        synchronized (PROVIDER_LOCK) {
            long now = SystemClock.elapsedRealtime();
            Map<String, Object> cached = providerCache;
            if (cached != null && now - providerCacheAt < PROVIDER_TTL_MS) {
                return cached;
            }
            Map<String, Object> fresh = null;
            try {
                android.content.Context ctx = AndroidAppHelper.anyContext();
                if (ctx != null) {
                    Uri uri = Uri.parse("content://" + MODULE_PACKAGE + ".PreferencesProvider");
                    Bundle b = ctx.getContentResolver().call(uri, "getAll", groupName, null);
                    if (b != null) {
                        fresh = new HashMap<>();
                        for (String k : b.keySet()) {
                            fresh.put(k, b.get(k));
                        }
                    }
                }
            } catch (Throwable ignored) {
                fresh = null; // provider not available (early boot / not installed / denied)
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
        this(packageName, prefFileName,
                new File("/data/data/" + packageName + "/shared_prefs/" + prefFileName + ".xml"));
    }

    public XSharedPreferences(String prefFileName) {
        this(MODULE_PACKAGE, prefFileName);
    }

    public XSharedPreferences(File file) {
        this(MODULE_PACKAGE, stripXml(file.getName()), file);
    }

    private XSharedPreferences(String packageName, String prefFileName, File file) {
        this.packageName = packageName;
        this.prefFileName = prefFileName.endsWith(".xml") ? prefFileName : prefFileName + ".xml";
        this.groupName = stripXml(prefFileName);
        this.candidates = candidatePaths(file);
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
            // Embedded framework or preferences service unavailable: fall back to the file.
            remote = null;
        }
    }

    /** Whether the framework's remote preferences service is in use. */
    public boolean isRemote() {
        ensureRemote();
        return remote != null;
    }

    private static File[] candidatePaths(File file) {
        String path = file.getPath();
        if (path.startsWith("/data/data/")) {
            String rest = path.substring("/data/data/".length());
            return new File[]{
                    file,
                    new File("/data/user/0/" + rest),
                    new File("/data/user_de/0/" + rest),
            };
        }
        if (path.startsWith("/data/user/0/")) {
            String rest = path.substring("/data/user/0/".length());
            return new File[]{
                    file,
                    new File("/data/data/" + rest),
                    new File("/data/user_de/0/" + rest),
            };
        }
        if (path.startsWith("/data/user_de/0/")) {
            String rest = path.substring("/data/user_de/0/".length());
            return new File[]{
                    file,
                    new File("/data/data/" + rest),
                    new File("/data/user/0/" + rest),
            };
        }
        return new File[]{file};
    }

    private File currentFile() {
        for (File f : candidates) {
            if (f.exists() && f.canRead()) {
                return f;
            }
        }
        return null;
    }

    /** Reloads the file if it has changed since the last read. */
    public synchronized void reload() {
        File f = currentFile();
        if (f == null) {
            values = Collections.emptyMap();
            return;
        }
        long mtime = f.lastModified();
        if (mtime == lastModified && !values.isEmpty()) {
            return;
        }
        Map<String, Object> parsed = parse(f);
        if (parsed != null) {
            values = parsed;
            lastModified = mtime;
        }
    }

    private static Map<String, Object> parse(File file) {
        Map<String, Object> out = new HashMap<>();
        XmlPullParser parser = Xml.newPullParser();
        try (FileInputStream is = new FileInputStream(file)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(is, null);
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("boolean".equals(tag)) {
                        out.put(parser.getAttributeValue(null, "name"),
                                Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                    } else if ("string".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        // text may arrive in multiple chunks
                        StringBuilder sb = new StringBuilder();
                        int next = parser.next();
                        while (next == XmlPullParser.TEXT) {
                            sb.append(parser.getText());
                            next = parser.next();
                        }
                        out.put(name, sb.toString());
                        continue;
                    } else if ("int".equals(tag)) {
                        out.put(parser.getAttributeValue(null, "name"),
                                Integer.parseInt(parser.getAttributeValue(null, "value")));
                    } else if ("long".equals(tag)) {
                        out.put(parser.getAttributeValue(null, "name"),
                                Long.parseLong(parser.getAttributeValue(null, "value")));
                    } else if ("float".equals(tag)) {
                        out.put(parser.getAttributeValue(null, "name"),
                                Float.parseFloat(parser.getAttributeValue(null, "value")));
                    } else if ("set".equals(tag)) {
                        String name = parser.getAttributeValue(null, "name");
                        Set<String> set = new HashSet<>();
                        int depth = parser.getDepth();
                        // consume nested <string> entries
                        while (!(parser.next() == XmlPullParser.END_TAG
                                && "set".equals(parser.getName()) && parser.getDepth() == depth)) {
                            if (parser.getEventType() == XmlPullParser.START_TAG
                                    && "string".equals(parser.getName())) {
                                set.add(parser.nextText());
                            }
                        }
                        out.put(name, set);
                    }
                }
                event = parser.next();
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public Map<String, Object> getAll() {
        ensureRemote();
        Map<String, Object> out = null;
        if (remote != null) {
            try {
                out = new HashMap<>();
                for (Map.Entry<String, ?> e : remote.getAll().entrySet()) {
                    out.put(e.getKey(), e.getValue());
                }
                // The remote service can return an empty snapshot right after process
                // start (or before the app side has ever written anything). Only trust it
                // when non-empty, otherwise fall back to the provider/file below.
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Throwable ignored) {
                out = null;
                // fall back to the provider / file for this call
            }
        }
        Map<String, Object> fromProvider = providerGetAll(groupName);
        if (fromProvider != null && !fromProvider.isEmpty()) {
            return new HashMap<>(fromProvider);
        }
        reload();
        if (!values.isEmpty()) {
            return new HashMap<>(values);
        }
        return out != null ? out : new HashMap<String, Object>();
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
    public String getFile() {
        File f = currentFile();
        return f == null ? candidates[0].getPath() : f.getPath();
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
