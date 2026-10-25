package com.example.dgg.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 抖音客户端的定位与拉起。
 *
 * 只认官方包名；未安装时不静默失败，而是给出可执行提示（跳应用商店）。
 */
object DouyinLauncher {

    const val PACKAGE = "com.ss.android.ugc.aweme"

    fun isInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    /**
     * @return true=已拉起；false=未安装或拉起失败（调用方负责提示）
     */
    fun launch(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 跳到应用商店的抖音详情页，作为未安装时的兜底 */
    fun openMarket(context: Context): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(market) }.isSuccess) return true

        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.douyin.com/download")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(web) }.isSuccess
    }
}
