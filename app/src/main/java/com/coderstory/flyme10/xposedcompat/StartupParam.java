package com.coderstory.flyme10.xposedcompat;

/**
 * Legacy-shaped zygote init parameter. Kept for source compatibility only; the modern API
 * no longer injects modules into zygote.
 */
public final class StartupParam {

    /** Path to the module APK; always empty in the modern API port. */
    public final String modulePath = "";

    public String getModulePath() {
        return modulePath;
    }
}
