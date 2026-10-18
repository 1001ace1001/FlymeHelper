package com.coderstory.flyme10.xposed

import com.coderstory.flyme10.BuildConfig
import com.coderstory.flyme10.R
import com.coderstory.flyme10.patchModule.FuckAd
import com.coderstory.flyme10.patchModule.HideApp
import com.coderstory.flyme10.patchModule.IsEnable
import com.coderstory.flyme10.patchModule.Others
import com.coderstory.flyme10.patchModule.SystemUi
import com.coderstory.flyme10.patchModule.ThemePatcher
import com.coderstory.flyme10.xposedcompat.InitPackageResourcesParam
import com.coderstory.flyme10.xposedcompat.LoadPackageParam
import com.coderstory.flyme10.xposedcompat.ResourceCompat
import com.coderstory.flyme10.xposedcompat.XposedApi
import com.coderstory.flyme10.xposedcompat.XposedBridge
import com.coderstory.flyme10.xposedcompat.XSharedPreferences
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Module entry of the modern libxposed API (io.github.libxposed:api:102.0.0).
 * Registered in META-INF/xposed/java_init.list.
 */
class Start : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedApi.api = this
        XposedBridge.log("Flyme助手已加载（libxposed API $apiVersion，框架 $frameworkName $frameworkVersion）")
        // 诊断：配置读取来源与数量（远程服务 = 框架 getRemotePreferences，文件 = 直读降级）
        val probe = XSharedPreferences(BuildConfig.APPLICATION_ID, "UserSettings")
        XposedBridge.log(
            "偏好读取: ${probe.all.size} 项配置（远程服务=${probe.isRemote}）"
        )
        // The modern API has no resource callbacks; install the deferred replacement hook.
        ResourceCompat.install()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val lpparam = LoadPackageParam(param.packageName, param.defaultClassLoader)
        IsEnable().handleLoadPackage(lpparam)
        HideApp().handleLoadPackage(lpparam)
        Others().handleLoadPackage(lpparam)
        ThemePatcher().handleLoadPackage(lpparam)
        FuckAd().handleLoadPackage(lpparam)
        SystemUi().handleLoadPackage(lpparam)

        // resource replacements (was handleInitPackageResources)
        val resparam = InitPackageResourcesParam(param.packageName)
        if (resparam.packageName == "com.meizu.customizecenter") {
            resparam.res.setReplacement(
                R.style.ThemeOverlay_MaterialComponents_MaterialAlertDialog_Picker_Date_Header_Text_Day,
                "阿巴阿巴阿巴~~"
            )
        }
        Others().handleInitPackageResources(resparam)
        SystemUi().handleInitPackageResources(resparam)
        ThemePatcher().handleInitPackageResources(resparam)
    }
}
