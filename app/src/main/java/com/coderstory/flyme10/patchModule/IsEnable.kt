package com.coderstory.flyme10.patchModule


import com.coderstory.flyme10.tools.XposedHelper
import com.coderstory.flyme10.xposed.IModule
import com.coderstory.flyme10.xposedcompat.InitPackageResourcesParam
import com.coderstory.flyme10.xposedcompat.LoadPackageParam
import com.coderstory.flyme10.xposedcompat.StartupParam
import com.coderstory.flyme10.xposedcompat.XC_MethodReplacement

class IsEnable : XposedHelper(), IModule {
    override fun handleInitPackageResources(respray: InitPackageResourcesParam) {}
    override fun handleLoadPackage(param: LoadPackageParam) {
        if (param.packageName == "com.coderstory.flyme10") {
            findAndHookMethod(
                "com.coderstory.flyme10.activity.MainActivity",
                param.classLoader,
                "isEnable",
                XC_MethodReplacement.returnConstant(true)
            )
        }
    }

    override fun initZygote(startupParam: StartupParam?) {}
}