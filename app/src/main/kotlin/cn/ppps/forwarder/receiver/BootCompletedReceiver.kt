package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.ppps.forwarder.App
import cn.ppps.forwarder.service.ForegroundService
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils

@Suppress("PrivatePropertyName")
class BootCompletedReceiver : BroadcastReceiver() {

    private val TAG: String = BootCompletedReceiver::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent?) {
        val bootAction = intent?.action
        if (bootAction != Intent.ACTION_BOOT_COMPLETED &&
            bootAction != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // USER_PRESENT、PACKAGE_RESTARTED 等都不能替换 IMEI。
            App.markBackgroundStartWithoutImeiReplacement()
            return
        }

        // 开机广播属于一次启动，检查 "imei"；只启动后台服务，绝不打开 Activity。
        // LOCKED_BOOT_COMPLETED 读不到真实 IMEI 时，BOOT_COMPLETED 会再次尝试。
        try {
            App.checkImeiPlaceholderOnBootStart()
            if (bootAction == Intent.ACTION_BOOT_COMPLETED) {
                // 设备名称为空时沿用原有自动填写规则。
                SettingUtils.fillBlankDeviceNameFromImeiIfNeeded()
            }

            if (!SettingUtils.enablePureClientMode && !ForegroundService.isRunning) {
                val serviceIntent = Intent(context, ForegroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            Log.d(TAG, "开机后台启动已处理：$bootAction；未启动界面")
        } catch (e: Exception) {
            Log.e(TAG, "开机后台启动失败：${e.message}")
        }
    }
}
