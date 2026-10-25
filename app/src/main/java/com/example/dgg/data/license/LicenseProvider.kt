package com.example.dgg.data.license

/** 授权状态快照，界面直接消费这个对象。 */
data class LicenseStatus(
    val authorized: Boolean,
    /** 到期时间戳（毫秒），null 表示永久或未知 */
    val expireAt: Long?,
    /** 激活卡密（已打码） */
    val code: String?,
    /** 状态来源：LOCAL_PLACEHOLDER / REMOTE */
    val source: String
)

/**
 * 授权能力抽象。
 *
 * 当前只有 [LocalLicenseProvider] 一个占位实现（服务端另行开发）。
 * 服务端就绪后新增 RemoteLicenseProvider 并在注入处替换即可，界面无需改动。
 */
interface LicenseProvider {

    fun status(): LicenseStatus

    /** 激活卡密。占位实现恒返回失败，避免让人误以为已联网校验。 */
    fun activate(code: String): Result<LicenseStatus>

    /** 解绑当前设备 */
    fun unbind(): Result<Unit>
}
