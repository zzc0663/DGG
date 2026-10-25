package com.example.dgg.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

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
     * 读 Secure 表而不是 AccessibilityManager：后者在部分机型上有缓存延迟，
     * 且本应用的无障碍服务要到 M1 才存在，这里按包名前缀判断即可兼容两个阶段。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val prefix = "${context.packageName}/"
        return raw.split(':').any { it.trim().startsWith(prefix) }
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
