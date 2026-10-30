package com.doujiajia.app.data

import android.content.Context
import android.os.Build
import com.doujiajia.app.util.AppPermissions

/**
 * 权限保留守卫。
 *
 * 起因很具体：本应用**离开这三个权限就完全不可用**（无障碍读不到界面、悬浮窗拉不起抖音、
 * 通知权限不到位前台服务的常驻通知会被系统静默丢弃），而这几个权限恰好都属于
 * 「应用升级 / 重装 / 强制停止后可能被系统重置」的类型：
 *
 * - 无障碍：写入 Secure 表的授权，`am force-stop`、部分 ROM 的「应用清理」会清掉；
 * - 悬浮窗（`SYSTEM_ALERT_WINDOW`）：AppOps 授权，卸载重装必然丢失，降级安装也可能丢；
 * - 通知（`POST_NOTIFICATIONS`）：运行时权限，卸载重装会重置为「未询问」。
 *
 * 所以这里不做"检测一次弹一次"的一次性提示，而是记住**每个权限是否曾经被授予过**
 * （`ever_*` 只置 true、永不回清），之后每次冷启动只要发现"曾经有、现在没有"就提醒。
 * 这样用户不会在某个不经意的升级之后，对着一个静默失效的界面猜半天。
 *
 * 判断不依赖 versionCode：故意改成"曾经有 → 现在没有"，因为重装同版本、
 * 用户手动关闭同样会让应用残废，那不是升级才有的问题。
 */
object PermissionGuard {

    private const val SP = "dgg_perm_guard"
    private const val KEY_VERSION = "last_version_code"
    private const val KEY_EVER_ACCESSIBILITY = "ever_accessibility"
    private const val KEY_EVER_OVERLAY = "ever_overlay"
    private const val KEY_EVER_NOTIFICATION = "ever_notification"
    private const val KEY_LAST_ACCESSIBILITY = "last_accessibility"
    private const val KEY_LAST_OVERLAY = "last_overlay"
    private const val KEY_LAST_NOTIFICATION = "last_notification"

    /** 缺失项。顺序即「恢复向导」的顺序：先无障碍（最要紧），再悬浮窗，最后通知（能自动弹框） */
    enum class Kind(val label: String, val why: String) {
        ACCESSIBILITY("无障碍服务", "读不到抖音界面，脚本无法工作"),
        OVERLAY("悬浮窗权限", "脚本无法把抖音拉回前台，也看不到悬浮面板"),
        NOTIFICATION("通知权限", "前台服务的常驻通知与进度提示会看不见")
    }

    /** @return 曾经授予过、但**现在失效**的权限，按 [Kind] 声明顺序 */
    fun lost(context: Context, state: AppPermissions.State): List<Kind> {
        val sp = prefs(context)
        val lost = buildList {
            if (sp.getBoolean(KEY_EVER_ACCESSIBILITY, false) && !state.accessibility) {
                add(Kind.ACCESSIBILITY)
            }
            if (sp.getBoolean(KEY_EVER_OVERLAY, false) && !state.overlay) add(Kind.OVERLAY)
            if (sp.getBoolean(KEY_EVER_NOTIFICATION, false) && !state.notification) {
                add(Kind.NOTIFICATION)
            }
        }
        return lost
    }

    /**
     * 记录本次观察到的状态。**每次读权限后都要调**（[remember] 是 [Kind] 之外的唯一写入口）。
     *
     * 注意 `ever_*` 只往 true 写：一旦把"曾经授权过"清掉，后面就再也没有机会发现权限被重置了。
     */
    fun remember(context: Context, state: AppPermissions.State) {
        val sp = prefs(context)
        val edit = sp.edit()
        if (state.accessibility) edit.putBoolean(KEY_EVER_ACCESSIBILITY, true)
        if (state.overlay) edit.putBoolean(KEY_EVER_OVERLAY, true)
        if (state.notification) edit.putBoolean(KEY_EVER_NOTIFICATION, true)
        edit.putBoolean(KEY_LAST_ACCESSIBILITY, state.accessibility)
        edit.putBoolean(KEY_LAST_OVERLAY, state.overlay)
        edit.putBoolean(KEY_LAST_NOTIFICATION, state.notification)
        edit.putInt(KEY_VERSION, versionCode(context))
        edit.apply()
    }

    /** 给日志/报告用的当前快照描述 */
    fun describe(context: Context): String {
        val sp = prefs(context)
        return "ever=[无障碍=${sp.getBoolean(KEY_EVER_ACCESSIBILITY, false)} " +
            "悬浮窗=${sp.getBoolean(KEY_EVER_OVERLAY, false)} " +
            "通知=${sp.getBoolean(KEY_EVER_NOTIFICATION, false)}] " +
            "last=[无障碍=${sp.getBoolean(KEY_LAST_ACCESSIBILITY, false)} " +
            "悬浮窗=${sp.getBoolean(KEY_LAST_OVERLAY, false)} " +
            "通知=${sp.getBoolean(KEY_LAST_NOTIFICATION, false)}] " +
            "versionCode=${sp.getInt(KEY_VERSION, 0)}"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun versionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)
}
