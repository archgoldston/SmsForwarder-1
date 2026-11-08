package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
            bootAction != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        // 收到开机广播时，Android 会先创建 App(Application)：其 onCreate
        // 已负责初始化短信转发与后台服务。此处只兜底启动服务，绝不启动 Activity。
        try {
            if (bootAction == Intent.ACTION_BOOT_COMPLETED) {
                // 锁屏启动阶段没拿到 IMEI 时，解锁后的正常开机广播再试一次。
                SettingUtils.fillDeviceNameFromImeiIfNeeded()
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
