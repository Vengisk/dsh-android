package com.deepseekharness.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台安全确认通知的按钮接收器：用户点「允许/拒绝」后
 * 通知 HttpShellService 释放挂起的确认。
 */
class ConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        HttpShellService.instance?.resolveConfirm(ACTION_ALLOW == intent.action)
    }

    companion object {
        const val ACTION_ALLOW = "com.deepseekharness.app.CONFIRM_ALLOW"
        const val ACTION_DENY = "com.deepseekharness.app.CONFIRM_DENY"
    }
}
