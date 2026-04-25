package top.liminalselves.app

/**
 * 阿里云推送 register 成功后的 deviceId，供 Flutter MethodChannel [getDeviceId] 读取。
 */
object AliyunPushHolder {
    @Volatile
    var deviceId: String? = null
}
