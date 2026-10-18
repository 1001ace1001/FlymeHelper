package com.coderstory.flyme10.xposedcompat;

/**
 * Facade exposing the legacy XResources.setReplacement signatures. Registered replacements
 * are deferred until the app's Resources object is available (see {@link ResourceCompat}).
 */
public final class XResCompat {

    private final String targetPackage;

    XResCompat(String targetPackage) {
        this.targetPackage = targetPackage;
    }

    public String getPackageName() {
        return targetPackage;
    }

    /**
     * Replaces the resource with the given numeric id. The id follows the same convention
     * as the legacy API (i.e. a raw resource id as used by the original code).
     */
    public void setReplacement(int id, Object replacement) {
        ResourceCompat.addIntReplacement(targetPackage, id, replacement);
    }

    /**
     * Replaces the resource with the given fully qualified name.
     */
    @SuppressWarnings("unused")
    public void setReplacement(String fullName, Object replacement) {
        int sep = fullName.lastIndexOf(':');
        String pkg = sep > 0 ? fullName.substring(0, sep) : targetPackage;
        String typeAndName = sep > 0 ? fullName.substring(sep + 1) : fullName;
        int slash = typeAndName.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("fullName must be of the form package:type/name");
        }
        addNameReplacement(pkg, typeAndName.substring(0, slash), typeAndName.substring(slash + 1), replacement);
    }

    /**
     * Replaces the resource with the given name (as seen by the module author).
     */
    public void setReplacement(String pkg, String type, String name, Object replacement) {
        addNameReplacement(pkg, type, name, replacement);
    }

    /**
     * Replaces the resource with the given name, but only while the boolean
     * preference {@code gateKey} is enabled. The gate is evaluated whenever the
     * host queries the resource (emulation layer), so toggling the setting in the
     * module app takes effect without restarting the host app.
     */
    public void setReplacement(String pkg, String type, String name, Object replacement, String gateKey) {
        ResourceCompat.addNameReplacement(targetPackage, pkg, type, name, replacement, gateKey);
    }

    private void addNameReplacement(String pkg, String type, String name, Object replacement) {
        ResourceCompat.addNameReplacement(targetPackage, pkg, type, name, replacement);
    }
}
