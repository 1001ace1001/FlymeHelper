package com.coderstory.flyme10.xposedcompat;

/**
 * Legacy-shaped information about the package being loaded.
 */
public final class LoadPackageParam {

    /** Name of the package that has just been loaded. */
    public final String packageName;

    /** Classloader of the package's code. */
    public final ClassLoader classLoader;

    public LoadPackageParam(String packageName, ClassLoader classLoader) {
        this.packageName = packageName;
        this.classLoader = classLoader;
    }
}
