package com.coderstory.flyme10.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 空接收器：宿主进程在 PreferencesProvider 不可达（App 处于 stopped 状态——
 * 安装/升级后未打开过、或被系统强制停止）时，会发送携带
 * FLAG_INCLUDE_STOPPED_PACKAGES 的显式广播唤醒本组件以清除 stopped 状态，
 * 使 provider 随后可被普通 uid 的宿主进程访问（uid 1000 的系统进程不受此拦截）。
 *
 * Direct Boot（解锁前）模式下仅被拉起、不执行任何业务逻辑。
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
    }
}