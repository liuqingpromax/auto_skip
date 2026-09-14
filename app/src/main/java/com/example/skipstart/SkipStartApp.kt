package com.example.skipstart

import android.app.Application

/**
 * Application：进程启动时初始化 AppGraph（各仓库）。
 */
class SkipStartApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
