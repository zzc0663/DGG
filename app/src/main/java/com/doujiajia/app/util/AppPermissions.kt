package com.doujiajia.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.doujiajia.app.service.DggAccessibilityService

/**
 * 权限状态检测与系统设置跳转。
 *
 * 状态一律**实时读取系统**，不缓存、不硬编码——界面上那些「未授权 / 已授权」
 * 必须反映真实情况，否则用户点进去发现对不上，就没法信任后续任何提示。
 */
object AppPermissions {

    data class State(
        val accessibility: Boolean,
        val overlay: Boolean,
        val notification: Boolean,
        val batteryUnrestricted: Boolean
    )

    fun state(context: Context) = State(
        accessibility = isAccessibilityServiceEnabled(context),
        overlay = canDrawOverlays(context),
        notification = areNotificationsEnabled(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
    )

    /**
     * 无障碍是否**真的可用**。三个条件全部满足才算：
     *
     * 1. 系统无障碍**总开关**打开（`ACCESSIBILITY_ENABLED`）；
     * 2. 我们的服务在 `ENABLED_ACCESSIBILITY_SERVICES` 里（用户在列表里勾过）；
     * 3. 本进程里的服务实例已连接（`DggAccessibilityService.isConnected()`）。
     *
     * 只查第 2 条是不够的——2026-10-31 真机就是这个坑：用户在系统设置里关掉了**总开关**，
     * `accessibility_enabled=0`，但 `enabled_accessibility_services` 里**仍然列着**我们的服务：
     *
     * ```
     * accessibility_enabled = 0
     * enabled_accessibility_services = com.doujiajia.app/…DggAccessibilityService
     * ```
     *
     * 于是界面三个点一直是绿的，而点「开始服务」会被 [EngineController] 挡下来
     * （它判的是 `isConnected()`）报「无障碍服务未开启」——界面和引擎各说一套。
     *
     * 第 3 条同时也是「服务被系统杀掉/解绑」的判据：那种情况下设置里的列表同样还留着。
     * 代价是进程刚起来、系统还没把服务重新绑上时（通常 1 秒内）会短暂显示未授权，
     * 所以界面侧要配合定时重读（见 MainActivity 的权限轮询）。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val master = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!master) return false
        val raw = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val prefix = "${context.packageName}/"
        if (raw.split(':').none { it.trim().startsWith(prefix) }) return false
        return DggAccessibilityService.isConnected()
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // ==================== 跳转 ====================

    fun openAccessibilitySettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), ACTION_APP_DETAILS, context)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        safeStart(context, intent, ACTION_APP_DETAILS, context)
    }

    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(ACTION_APP_DETAILS)
        }
        safeStart(context, intent, ACTION_APP_DETAILS, context)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        val intent = if (direct.resolveActivity(context.packageManager) != null) {
            direct
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        safeStart(context, intent, ACTION_APP_DETAILS, context)
    }

    private const val ACTION_APP_DETAILS = Settings.ACTION_APPLICATION_DETAILS_SETTINGS

    /** 单层兜底：目标页不存在就退到应用详情页，再不行就静默失败（外层负责提示） */
    private fun safeStart(
        context: Context,
        primary: Intent,
        fallbackAction: String,
        fallbackContext: Context
    ) {
        val resolvable = primary.resolveActivity(fallbackContext.packageManager) != null
        val target = if (resolvable) primary else Intent(
            fallbackAction,
            Uri.parse("package:${fallbackContext.packageName}")
        )
        runCatching { fallbackContext.startActivity(target) }
    }
}
