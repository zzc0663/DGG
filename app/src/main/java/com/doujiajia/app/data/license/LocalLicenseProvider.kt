package com.doujiajia.app.data.license

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * 本地占位实现 —— **不发网络请求，也不假装做过**。
 *
 * 仅用于没有服务端时的界面演示。正式构建注入 RemoteLicenseProvider。
 */
class LocalLicenseProvider : LicenseProvider {

    private val _state = MutableStateFlow(
        LicenseStatus(
            authorized = true,
            expireAt = DEMO_EXPIRE_AT,
            code = "LOCAL-DEMO-0000",
            source = SOURCE,
            remainingDays = 180,
            message = "本地占位授权，未连接服务器"
        )
    )
    override val state: StateFlow<LicenseStatus> = _state.asStateFlow()

    override suspend fun initialize() = Unit

    override suspend fun activate(code: String): Result<LicenseStatus> =
        Result.failure(IllegalStateException("服务端未接入：当前为本地占位实现，卡密校验不可用"))

    override suspend fun handshake(): Result<LicenseStatus> = Result.success(_state.value)

    override suspend fun unbind(): Result<Unit> =
        Result.failure(IllegalStateException("服务端未接入：解绑需要在服务端清理设备绑定"))

    companion object {
        const val SOURCE = "LOCAL_PLACEHOLDER"

        /** 演示到期时间：2027-04-24 17:33:42 */
        private val DEMO_EXPIRE_AT: Long = Calendar.getInstance().apply {
            set(2027, Calendar.APRIL, 24, 17, 33, 42)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
