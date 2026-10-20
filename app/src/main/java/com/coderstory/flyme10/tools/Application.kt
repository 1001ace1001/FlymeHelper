package com.coderstory.flyme10.tools

import android.app.Application
import android.os.UserManager
import com.coderstory.flyme10.tools.Misc.SharedPreferencesName
import com.umeng.commonsdk.UMConfigure

class Application : Application() {
    override fun onCreate() {
        super.onCreate()
        // Direct Boot（解锁前）仅因 directBootAware 的 PreferencesProvider/WakeReceiver 被拉起：
        // 此时 CE 存储尚未解锁，业务组件（友盟、配置迁移）一律不初始化，防止崩溃循环。
        // 注意：解锁前 App 拿到的上下文未必标记 isDeviceProtectedStorage，因此以
        // UserManager.isUserUnlocked 为准（真机崩溃日志：解锁前拉起后 IllegalStateException）。
        val um = getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) {
            return
        }
        if (applicationContext.isDeviceProtectedStorage) {
            return
        }
        migrateLegacyPrefs()
        // 友盟初始化失败不应拖垮进程（解锁过渡期等场景），任何异常一律吞掉
        try {
            UMConfigure.setLogEnabled(true)
            UMConfigure.preInit(applicationContext, "5ee5d80f978eea081640e210", "weibo")
            UMConfigure.init(
                this,
                "5ee5d80f978eea081640e210",
                "weibo",
                UMConfigure.DEVICE_TYPE_PHONE,
                ""
            )
        } catch (ignored: Throwable) {
        }
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