package dev.memoh.android.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MonitorState(val running: Boolean = false, val description: String = "后台监控未运行")

object MonitorStatus {
    private val mutable = MutableStateFlow(MonitorState())
    val state = mutable.asStateFlow()
    fun update(running: Boolean, description: String) { mutable.value = MonitorState(running, description) }
}
