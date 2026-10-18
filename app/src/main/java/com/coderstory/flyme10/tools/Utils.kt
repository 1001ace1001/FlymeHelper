package com.coderstory.flyme10.tools

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

class Utils {
    companion object {
        fun convertDpToPixel(context: Context, dp: Int): Int {
            val density = context.resources.displayMetrics.density
            return (dp.toFloat() * density).roundToInt()
        }

        fun getMySharedPreferences(
            context: Context?,
            fileName: String?
        ): SharedPreferences {
            // v6：统一改用设备加密存储（DE，/data/user_de）。CE 存储在重启后首次解锁前不可读，
            // 是"开机后第一次解锁 hook 全不生效"的根源；DE 在 Direct Boot 阶段即可被
            // directBootAware 的 PreferencesProvider 读取。App 端所有读写均经由此函数，
            // 改这一处即完成全量切换（旧 CE 配置由 Application 启动时一次性迁移）。
            val de = context!!.createDeviceProtectedStorageContext()
            var result: SharedPreferences
            try {
                result = de.getSharedPreferences(fileName, Context.MODE_WORLD_READABLE)
            } catch (exp: Exception) {
                result = de.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            }
            return result
        }
    }
}