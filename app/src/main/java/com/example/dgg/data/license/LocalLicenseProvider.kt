package com.example.dgg.data.license

import java.util.Calendar

/**
 * 本地占位实现 —— **不做任何网络请求，也不假装做过**。
 *
 * 服务端未接入前，界面显示「已授权 / 本地占位」，到期时间为一个固定的演示值。
 * 这样既能让界面完整可用，又不会让人误判为真实计费链路已打通。
 */
class LocalLicenseProvider : LicenseProvider {

    override fun status(): LicenseStatus = LicenseStatus(
        authorized = true,
        expireAt = DEMO_EXPIRE_AT,
        code = "LOCAL-DEMO-0000",
        source = SOURCE
    )

    override fun activate(code: String): Result<LicenseStatus> =
        Result.failure(IllegalStateException("服务端未接入：当前为本地占位实现，卡密校验不可用"))

    override fun unbind(): Result<Unit> =
        Result.failure(IllegalStateException("服务端未接入：解绑需要在服务端清理设备绑定"))

    companion object {
        const val SOURCE = "LOCAL_PLACEHOLDER"

        /** 与参考图保持一致的演示到期时间：2027-04-24 17:33:42 */
        private val DEMO_EXPIRE_AT: Long = Calendar.getInstance().apply {
            set(2027, Calendar.APRIL, 24, 17, 33, 42)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
