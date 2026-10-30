package com.doujiajia.app.data.ota

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.doujiajia.app.R
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.engine.TaskEngine
import java.io.File

/**
 * 调起系统安装器。
 *
 * 不自动安装、不静默安装：必须由用户在系统界面确认。任务运行中拒绝安装，
 * 因为安装会杀进程，正在跑的自动化会直接中断。
 */
object OtaInstaller {

    private const val TAG = "OTA"

    fun install(context: Context, apk: File): Result<Unit> {
        if (!apk.isFile || apk.length() <= 0) {
            return Result.failure(IllegalStateException("安装包不存在或为空"))
        }
        if (TaskEngine.progress.value.state.isActive) {
            return Result.failure(IllegalStateException("请先停止当前任务再升级"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return Result.failure(IllegalStateException("请先允许「${context.getString(R.string.app_name)}」安装未知应用，再点一次安装"))
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LogStore.i(TAG, "已调起系统安装器：${apk.name}")
        }.recoverCatching { error ->
            LogStore.e(TAG, "安装器启动失败：${error.message}")
            if (error is ActivityNotFoundException) {
                throw IllegalStateException("系统没有可用的安装器，请手动到文件管理器安装", error)
            }
            throw IllegalStateException(
                "系统拦截了安装：请允许「${context.getString(R.string.app_name)}」安装应用，或暂时关闭安全拦截",
                error
            )
        }
    }
}
