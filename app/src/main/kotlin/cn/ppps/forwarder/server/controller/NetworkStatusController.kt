package cn.ppps.forwarder.server.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import cn.ppps.forwarder.App
import cn.ppps.forwarder.server.model.BaseRequest
import cn.ppps.forwarder.server.model.EmptyData
import com.google.gson.annotations.SerializedName
import com.yanzhenjie.andserver.annotation.CrossOrigin
import com.yanzhenjie.andserver.annotation.PostMapping
import com.yanzhenjie.andserver.annotation.RequestBody
import com.yanzhenjie.andserver.annotation.RequestMapping
import com.yanzhenjie.andserver.annotation.RequestMethod
import com.yanzhenjie.andserver.annotation.RestController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 自定义接口：POST /network/status/query
 * 与 DeviceMarkController 一样由 AndServer 注解自动注册。
 * Android 5.x 也可用；不需要 root，不依赖 dumpsys 或 gsm.sim.state 判断联网。
 */
@RestController
@RequestMapping(path = ["/network"])
class NetworkStatusController {

    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/status/query")
    fun query(@RequestBody bean: BaseRequest<EmptyData>): NetworkStatusData {
        val context = App.context
        val phone = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return NetworkStatusData(networkStatus = "UNAVAILABLE")
        val simReady = try {
            phone.simState == TelephonyManager.SIM_STATE_READY
        } catch (_: Exception) {
            false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            return NetworkStatusData(simReady = simReady, networkStatus = "PERMISSION_DENIED")
        }

        val stateRef = AtomicReference<ServiceState?>(null)
        val signalRef = AtomicReference<SignalStrength?>(null)
        val stateLatch = CountDownLatch(1)
        val signalLatch = CountDownLatch(1)
        val listenerRef = AtomicReference<PhoneStateListener?>(null)

        // Android 8+ 可以立即获取当前 ServiceState。Android 5/6/7 用监听回调。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                stateRef.set(phone.serviceState)
            } catch (_: Exception) {
                // 仍尝试异步监听
            }
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val started = CountDownLatch(1)
        val listenFailed = AtomicReference<Exception?>(null)
        val startListen = Runnable {
            try {
                // PhoneStateListener() 必须在有 Looper 的线程创建；这里固定在主线程。
                val listener = object : PhoneStateListener() {
                    override fun onServiceStateChanged(serviceState: ServiceState?) {
                        stateRef.set(serviceState)
                        stateLatch.countDown()
                    }

                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                        signalRef.set(signalStrength)
                        signalLatch.countDown()
                    }
                }
                listenerRef.set(listener)
                phone.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE or
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            } catch (e: Exception) {
                listenFailed.set(e)
            } finally {
                started.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            startListen.run()
        } else {
            mainHandler.post(startListen)
            started.await(1000, TimeUnit.MILLISECONDS)
        }

        try {
            // HTTP 工作线程最多等约 2 秒；查询不会在没有数据时谎报“无信号”。
            if (stateRef.get() == null && Looper.myLooper() != Looper.getMainLooper()) {
                stateLatch.await(1500, TimeUnit.MILLISECONDS)
            }
            if (signalRef.get() == null && Looper.myLooper() != Looper.getMainLooper()) {
                signalLatch.await(250, TimeUnit.MILLISECONDS)
            }
            val serviceState = stateRef.get()
                ?: return NetworkStatusData(
                    simReady = simReady,
                    networkStatus = if (listenFailed.get() != null) "PERMISSION_DENIED" else "UNKNOWN"
                )

            val voiceState = serviceState.state
            // Android 5.x 的 ServiceState.toString() 前两个值是语音/数据注册状态。
            // 优先解析它们，以识别“仅数据已注册”的 LTE 卡；解析失败时 dataState=null。
            val serviceValues = Regex("^\\s*(?:ServiceState:\\s*)?([0-3])\\s+([0-3])(?:\\s|$)")
                .find(serviceState.toString())
            val dataState = serviceValues?.groupValues?.getOrNull(2)?.toIntOrNull()
            val registered = simReady && (voiceState == ServiceState.STATE_IN_SERVICE ||
                dataState == ServiceState.STATE_IN_SERVICE)
            val status = when {
                registered -> "IN_SERVICE"
                voiceState == ServiceState.STATE_POWER_OFF &&
                    (dataState == null || dataState == ServiceState.STATE_POWER_OFF) -> "POWER_OFF"
                voiceState == ServiceState.STATE_EMERGENCY_ONLY ||
                    dataState == ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
                voiceState == ServiceState.STATE_OUT_OF_SERVICE ||
                    dataState == ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                else -> "UNKNOWN"
            }

            val operator = if (registered) {
                serviceState.operatorAlphaLong?.takeIf { it.isNotBlank() }
                    ?: phone.networkOperatorName?.takeIf { it.isNotBlank() }
                    ?: ""
            } else ""
            val operatorCode = if (registered) {
                serviceState.operatorNumeric?.takeIf { it.isNotBlank() }
                    ?: phone.networkOperator.orEmpty()
            } else ""
            val networkType = if (registered) networkTypeName(phone) else ""
            val dbm = if (registered) readSignalDbm(signalRef.get(), networkType) else null

            return NetworkStatusData(
                simReady = simReady,
                networkRegistered = registered,
                networkStatus = status,
                operator = operator,
                operatorCode = operatorCode,
                networkType = networkType,
                signalDbm = dbm,
                voiceState = voiceState,
                dataState = dataState
            )
        } finally {
            // 停用临时监听，防止每次刷新都增加一个常驻监听器。
            mainHandler.post {
                try {
                    listenerRef.get()?.let {
                        phone.listen(it, PhoneStateListener.LISTEN_NONE)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun networkTypeName(phone: TelephonyManager): String {
        val type = try {
            // getNetworkType() 在旧 Android 上也存在；getDataNetworkType() 需较新 API。
            phone.networkType
        } catch (_: Exception) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        return when (type) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
            else -> "Unknown"
        }
    }

    private fun readSignalDbm(signal: SignalStrength?, networkType: String): Int? {
        if (signal == null) return null
        // Android 5.x 的输出格式（与高通 410 实测一致）：
        // SignalStrength: 99 0 -120 -160 -120 -1 -1 25 -93 -11 36 ... gsm|lte
        // 从左至右第 9 个数字（下标 8）是 LTE RSRP，单位 dBm。
        if (networkType == "LTE") {
            val parts = signal.toString().substringAfter("SignalStrength:", "")
                .trim().split(Regex("\\s+"))
            val rsrp = parts.getOrNull(8)?.toIntOrNull()
            if (rsrp != null && rsrp in -150..-44) return rsrp
            // LTE 数据布局不匹配时不拿旧 CDMA 数值冒充当前 LTE 信号。
            return null
        }
        val cdmaDbm = signal.cdmaDbm
        return cdmaDbm.takeIf { it in -150..-44 }
    }
}

data class NetworkStatusData(
    @SerializedName("sim_ready") val simReady: Boolean = false,
    // null：状态未知，不应当等同于 false（未注册）
    @SerializedName("network_registered") val networkRegistered: Boolean? = null,
    @SerializedName("network_status") val networkStatus: String = "UNKNOWN",
    @SerializedName("operator") val operator: String = "",
    @SerializedName("operator_code") val operatorCode: String = "",
    @SerializedName("network_type") val networkType: String = "",
    @SerializedName("signal_dbm") val signalDbm: Int? = null,
    @SerializedName("voice_state") val voiceState: Int? = null,
    @SerializedName("data_state") val dataState: Int? = null,
    @SerializedName("observed_at") val observedAt: Long = System.currentTimeMillis()
)
