package com.example.skipstart.util

import android.util.Log

/**
 * 统一日志入口。
 * 阶段 1：仅输出到 Logcat，用于验证无障碍服务事件链路；
 * 阶段 2 起：与 LogRepository 联动，落盘供日志页展示。
 */
object Logger {

    private const val TAG = "SkipStart"

    fun d(tag: String, msg: String) = Log.d(TAG, "[$tag] $msg")
    fun i(tag: String, msg: String) = Log.i(TAG, "[$tag] $msg")
    fun w(tag: String, msg: String) = Log.w(TAG, "[$tag] $msg")
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e(TAG, "[$tag] $msg", t)
}
