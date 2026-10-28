package com.example.dgg.data.license

import kotlinx.coroutines.flow.StateFlow

/** 授权状态快照，界面直接消费这个对象。 */
data class LicenseStatus(
    val authorized: Boolean,
    /** 到期时间戳（毫秒），null 表示未知 */
    val expireAt: Long?,
    /** 激活卡密（已打码） */
    val code: String?,
    /** 状态来源：LOCAL_PLACEHOLDER / REMOTE */
    val source: String,
    /** 是否处于离线宽限模式 */
    val offline: Boolean = false,
    /** 服务端计算的剩余天数 */
    val remainingDays: Long? = null,
    /** 面向用户的提示（未激活/离线/错误） */
    val message: String? = null
)

/**
 * 授权能力抽象。
 *
 * 网络调用是异步的，所以状态通过 [state] 暴露，激活/握手/解绑都是挂起函数。
 * 当前正式实现是 RemoteLicenseProvider；LocalLicenseProvider 仅保留给离线演示。
 */
interface LicenseProvider {

    val state: StateFlow<LicenseStatus>

    /** 启动时调用：读本地 token，尝试握手；无 token → 未激活。 */
    suspend fun initialize()

    suspend fun activate(code: String): Result<LicenseStatus>

    /** App 回前台/手动刷新时调用。 */
    suspend fun handshake(): Result<LicenseStatus>

    /** 解绑当前设备。 */
    suspend fun unbind(): Result<Unit>
}
