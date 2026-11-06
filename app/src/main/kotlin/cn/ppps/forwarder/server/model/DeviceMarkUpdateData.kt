package cn.ppps.forwarder.server.model

import com.google.gson.annotations.SerializedName

/** POST /config/device-mark/update 的 data */
data class DeviceMarkUpdateData(
    @SerializedName("extra_device_mark")
    var extraDeviceMark: String? = null,
)
