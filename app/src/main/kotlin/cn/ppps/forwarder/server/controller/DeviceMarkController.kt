package cn.ppps.forwarder.server.controller

import cn.ppps.forwarder.server.model.BaseRequest
import cn.ppps.forwarder.server.model.DeviceMarkUpdateData
import cn.ppps.forwarder.utils.SettingUtils
import com.yanzhenjie.andserver.annotation.CrossOrigin
import com.yanzhenjie.andserver.annotation.PostMapping
import com.yanzhenjie.andserver.annotation.RequestBody
import com.yanzhenjie.andserver.annotation.RequestMapping
import com.yanzhenjie.andserver.annotation.RequestMethod
import com.yanzhenjie.andserver.annotation.RestController
import com.yanzhenjie.andserver.error.HttpException

/**
 * 增量接口：只更新设备备注，不修改任何原版 Controller、界面或安全设置。
 * 请求与原版 /config/query 一样经过 AppMessageConverter：
 * 服务端设为「不需要」时不强制签名；原来选择的其他模式照旧处理。
 */
@RestController
@RequestMapping(path = ["/config"])
class DeviceMarkController {

    @CrossOrigin(methods = [RequestMethod.POST])
    @PostMapping("/device-mark/update")
    fun updateDeviceMark(@RequestBody bean: BaseRequest<DeviceMarkUpdateData>): String {
        val newMark = bean.data.extraDeviceMark?.trim() ?: ""
        if (newMark.isEmpty()) {
            throw HttpException(400, "extra_device_mark cannot be empty")
        }
        if (newMark.codePointCount(0, newMark.length) > 80 ||
            newMark.toByteArray(Charsets.UTF_8).size > 240) {
            throw HttpException(400, "extra_device_mark is too long")
        }

        SettingUtils.extraDeviceMark = newMark
        return "success"
    }
}
