package com.coderstory.flyme10.xposedcompat;

/**
 * Legacy-shaped information about the resources being loaded. The modern libxposed API has
 * no resource replacement callbacks, so replacements registered on {@link #res} are
 * deferred and applied once the host framework's Resources are ready (see
 * {@link ResourceCompat}).
 */
public final class InitPackageResourcesParam {

    /** The name of the package for which resources are being loaded. */
    public final String packageName;

    /** Replacement facade with the legacy XResources.setReplacement signatures. */
    public final XResCompat res;

    public InitPackageResourcesParam(String packageName) {
        this.packageName = packageName;
        this.res = new XResCompat(packageName);
    }
}
