package com.example.skipstart.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 主界面骨架：底部导航切换页面。
 * 首页 / 日志 / 规则 / 学习（阶段6）/ 设置。
 */
@Composable
fun MainScreen() {
    var selected by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (selected) {
                0 -> HomeScreen(
                    onOpenLogs = { selected = 1 },
                    onOpenLearning = { selected = 3 },
                )
                1 -> LogScreen()
                2 -> RuleListScreen()
                3 -> LearningScreen()
                else -> SettingsScreen()
            }
        }
        NavigationBar {
            NavigationBarItem(
                selected = selected == 0,
                onClick = { selected = 0 },
                icon = { Icon(Icons.Filled.Home, contentDescription = "首页") },
                label = { Text("首页") },
            )
            NavigationBarItem(
                selected = selected == 1,
                onClick = { selected = 1 },
                icon = { Icon(Icons.Filled.DateRange, contentDescription = "日志") },
                label = { Text("日志") },
            )
            NavigationBarItem(
                selected = selected == 2,
                onClick = { selected = 2 },
                icon = {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "规则")
                },
                label = { Text("规则") },
            )
            NavigationBarItem(
                selected = selected == 3,
                onClick = { selected = 3 },
                icon = { Icon(Icons.Filled.Edit, contentDescription = "学习") },
                label = { Text("学习") },
            )
            NavigationBarItem(
                selected = selected == 4,
                onClick = { selected = 4 },
                icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                label = { Text("设置") },
            )
        }
    }
}
