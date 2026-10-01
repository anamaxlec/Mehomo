package dev.memoh.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import dev.memoh.core.designsystem.component.MemohNavItem
import dev.memoh.feature.sessions.BotFeature

enum class MainSection(val label: String, val icon: ImageVector, val feature: BotFeature? = null,
    val implemented: Boolean = true, val pendingNote: String = "") {
    Chats("对话", Icons.AutoMirrored.Filled.Chat),
    Terminal("终端", Icons.Filled.Terminal),
    Desktop("桌面", Icons.Filled.DesktopWindows),
    Profile("我的", Icons.Filled.Person),
    Memory("记忆", Icons.Filled.Psychology, BotFeature.Memory),
    Schedules("日程", Icons.Filled.CalendarMonth, BotFeature.Schedules),
    Usage("用量", Icons.Filled.BarChart, BotFeature.Usage),
    Apps("应用", Icons.Filled.Apps, BotFeature.Apps),
    Skills("技能", Icons.Filled.AutoAwesome, BotFeature.Skills),
    Mcp("MCP", Icons.Filled.Extension, BotFeature.Mcp),
    Files("文件", Icons.Filled.Folder, BotFeature.Files),
    Browser("浏览器", Icons.Filled.Language);
    val navItem get() = MemohNavItem(label, icon)
}
