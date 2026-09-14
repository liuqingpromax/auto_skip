package com.example.skipstart.service

import com.example.skipstart.data.AppSettings
import com.example.skipstart.engine.Rule

/**
 * 防误触守卫（说明书第 9 章，阶段 4 收口）：
 * 集中执行硬性校验——总开关、冷启动窗口（默认 8 秒）、
 * 每次启动最多一次（maxClicksPerLaunch）、点击冷却（默认 2 秒）。
 *
 * 全局设置为硬性上限：窗口取规则与全局的较小值、冷却取较大值，只会更严格。
 */
class AntiTouchGuard {

    private var launchTime = 0L
    private var launchPkg: String? = null
    private var clicksThisLaunch = 0
    private var lastClickTime = 0L
    private var windowExpired = false

    val currentLaunchPkg: String? get() = launchPkg
    val clickCountThisLaunch: Int get() = clicksThisLaunch
    val isWindowExpired: Boolean get() = windowExpired

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Denied(val reason: String) : Verdict
    }

    /** 目标包进入前台：记录冷启动时间，重置本轮状态。 */
    fun onTargetLaunch(pkg: String, now: Long) {
        launchTime = now
        launchPkg = pkg
        clicksThisLaunch = 0
        windowExpired = false
    }

    fun canProceed(rule: Rule, now: Long, settings: AppSettings): Verdict {
        if (!settings.masterEnabled) return Verdict.Denied("总开关已关闭")
        if (launchTime == 0L) return Verdict.Denied("无冷启动记录")
        if (windowExpired) return Verdict.Denied("窗口已结束")

        // 窗口：规则与全局取较小值（更严格）
        val windowMs = minOf(rule.launchWindowMs, settings.launchWindowMs)
        if (now - launchTime > windowMs) {
            windowExpired = true
            return Verdict.Denied("超过冷启动窗口（${windowMs}ms）")
        }

        if (clicksThisLaunch >= rule.maxClicksPerLaunch) {
            return Verdict.Denied("本轮已点击（最多 ${rule.maxClicksPerLaunch} 次）")
        }

        // 冷却：规则与全局取较大值（更严格）
        val cooldownMs = maxOf(rule.cooldownMs, settings.cooldownMs)
        if (now - lastClickTime < cooldownMs) {
            return Verdict.Denied("点击冷却中（${cooldownMs}ms）")
        }
        return Verdict.Allowed
    }

    /** 成功点击：计入本轮次数并刷新冷却起点。 */
    fun recordClick(now: Long) {
        clicksThisLaunch++
        lastClickTime = now
    }

    /** 失败尝试：同样刷新冷却起点，避免失败后立即重试。 */
    fun recordFailedAttempt(now: Long) {
        lastClickTime = now
    }

    fun markWindowExpired() {
        windowExpired = true
    }
}
