package com.example.skipstart.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.skipstart.MainActivity

/**
 * 屏幕与跳转工具（说明书目录 util/ScreenUtils）：
 * - [screenSize]：真实屏幕尺寸，供规则区域判定（右上角 0.6w/0.25h）与比例兜底坐标换算；
 * - [launchApp] / [bringSelfToFront]：学习模式的「自动帮你打开目标 App / 一键返回」。
 */
object ScreenUtils {

    fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 按包名启动目标应用（学习模式「自动帮你打开目标 App」）。 */
    fun launchApp(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 回到本应用（学习捕获后一键返回查看结果）。 */
    fun bringSelfToFront(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        )
        true
    }.getOrDefault(false)
}
