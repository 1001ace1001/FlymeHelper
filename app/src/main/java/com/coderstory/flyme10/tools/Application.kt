package com.coderstory.flyme10.tools

import android.app.Application
import com.coderstory.flyme10.tools.Misc.SharedPreferencesName
import com.umeng.commonsdk.UMConfigure

class Application : Application() {
    override fun onCreate() {
        super.onCreate()
        if (applicationContext.isDeviceProtectedStorage) {
            // Direct Boot（解锁前）模式：仅因 directBootAware 的 PreferencesProvider 被拉起，
            // 不初始化友盟等业务组件，保证解锁前 provider 稳定可用
            return
        }
        migrateLegacyPrefs()
        UMConfigure.setLogEnabled(true)
        UMConfigure.preInit(applicationContext, "5ee5d80f978ea081640e210", "weibo")
        UMConfigure.init(
            this,
            "5ee5d80f978eea081640e210",
            "weibo",
            UMConfigure.DEVICE_TYPE_PHONE,
            ""
        )
    }

    /**
     * v6 起配置统一存放在设备加密存储（DE）。把旧版本写在 CE 的配置一次性搬到 DE：
     * 仅在 DE 为空且 CE 有值时执行，不会回滚用户较新的 DE 配置。
     */
    private fun migrateLegacyPrefs() {
        try {
            val de = applicationContext
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(SharedPreferencesName, MODE_PRIVATE)
            if (de.all.isEmpty()) {
                val ce = getSharedPreferences(SharedPreferencesName, MODE_PRIVATE)
                if (ce.all.isNotEmpty()) {
                    val editor = de.edit()
                    for ((key, value) in ce.all) {
                        when (value) {
                            is String -> editor.putString(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                        }
                    }
                    editor.commit()
                }
            }
        } catch (ignored: Exception) {
        }
    }
}