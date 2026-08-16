package com.deepseekharness.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** 关于对话框：GitHub 仓库 / QQ 交流群入口（欢迎页 + 配置页版本号共用） */
object AboutDialog {

    const val GITHUB_URL = "https://github.com/Vengisk/dsh-android"
    const val QQ_GROUP = "960636357"

    @JvmStatic
    fun show(ctx: Context) {
        var version = "1.0.6"
        try {
            version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (ignored: Exception) {
        }
        AlertDialog.Builder(ctx)
            .setTitle("DeepSeek Harness v$version")
            .setMessage(
                "DeepSeek Harness 安卓启动器\n\n" +
                    "🌟 GitHub：$GITHUB_URL\n" +
                    "🐧 QQ 交流群：$QQ_GROUP"
            )
            .setPositiveButton("GitHub") { _, _ -> openBrowser(ctx, GITHUB_URL) }
            .setNeutralButton("QQ 群") { _, _ -> openQQGroup(ctx) }
            .setNegativeButton("关闭", null)
            .show()
    }

    @JvmStatic
    fun openBrowser(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "打不开，请手动访问：$url", Toast.LENGTH_SHORT).show()
        }
    }

    @JvmStatic
    fun openQQGroup(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "mqqapi://card/show_pslcard?src_type=internal&version=1" +
                            "&uin=$QQ_GROUP&card_type=group"
                    )
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "打不开 QQ，请手动搜索群号：$QQ_GROUP", Toast.LENGTH_SHORT).show()
        }
    }
}
