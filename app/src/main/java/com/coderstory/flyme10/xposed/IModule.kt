package com.coderstory.flyme10.xposed

import com.coderstory.flyme10.xposedcompat.InitPackageResourcesParam
import com.coderstory.flyme10.xposedcompat.LoadPackageParam
import com.coderstory.flyme10.xposedcompat.StartupParam

interface IModule {
    fun handleInitPackageResources(respray: InitPackageResourcesParam)
    fun handleLoadPackage(param: LoadPackageParam)
    fun initZygote(startupParam: StartupParam?)
}
