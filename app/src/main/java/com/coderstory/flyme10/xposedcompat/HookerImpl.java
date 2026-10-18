package com.coderstory.flyme10.xposedcompat;

import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Bridges the legacy before/proceed/after hook model to the interceptor-chain model of the
 * modern libxposed API.
 * <p>
 * Mapping rules (matching the legacy de.robv.android.xposed.XposedBridge semantics):
 * <ul>
 *     <li>Callback exceptions in before/after are caught and logged; the call proceeds.</li>
 *     <li>If {@code setResult}/{@code setThrowable} is called in before, the original
 *     executable is not invoked (returnEarly).</li>
 *     <li>after hooks always run, even after an early return.</li>
 *     <li>Argument modifications made by before hooks are passed to the original executable.</li>
 *     <li>A throwable set after proceeding is re-thrown to the caller.</li>
 * </ul>
 */
final class HookerImpl implements XposedInterface.Hooker {

    private final XC_MethodHook callback;

    HookerImpl(XC_MethodHook callback) {
        this.callback = callback;
    }

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = chain.getExecutable();
        param.thisObject = chain.getThisObject();
        List<Object> chainArgs = chain.getArgs();
        param.args = chainArgs == null ? new Object[0] : chainArgs.toArray();

        try {
            callback.beforeHookedMethod(param);
        } catch (Throwable t) {
            XposedApi.log(t);
        }

        if (!param.returnEarly) {
            try {
                param.setResult(chain.proceed(param.args));
            } catch (Throwable t) {
                param.setThrowable(t);
            }
        }

        try {
            callback.afterHookedMethod(param);
        } catch (Throwable t) {
            XposedApi.log(t);
        }

        if (param.hasThrowable()) {
            throw param.getThrowable();
        }
        return param.getResult();
    }
}
