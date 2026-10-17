package dev.memoh.core.model

import java.util.Locale

fun runtimeStatusLabel(status: String?): String = when (status?.trim()?.lowercase(Locale.ROOT)) {
    "running" -> "运行中"
    "paused" -> "已暂停"
    "created" -> "已创建"
    "stopped" -> "已停止"
    "exited" -> "已退出"
    else -> "状态未知"
}

data class RuntimeMetric(val label: String, val value: String)

/** Live allotments take precedence; an omitted sample remains unknown. */
fun ContainerMetrics.resourceMetrics(): List<RuntimeMetric> {
    val desired = resourceLimits?.desired
    val cpu = metrics?.cpu
    val memory = metrics?.memory
    val storage = metrics?.storage
    val cores = cpu?.cores?.takeIf { it > 0 }?.toDouble()
        ?: desired?.cpuMillicores?.takeIf { it > 0 }?.div(1000.0)
    val memoryLimit = memory?.limitBytes?.takeIf { it > 0 } ?: desired?.memoryBytes?.takeIf { it > 0 }
    val storageLimit = storage?.limitBytes?.takeIf { it > 0 } ?: desired?.storageBytes?.takeIf { it > 0 }
    fun label(name: String, limit: String?) = limit?.let { "$name · $it" } ?: name
    return listOf(
        RuntimeMetric(label("CPU", cores?.let { "${String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.')} 核" }),
            cpu?.usagePercent?.takeIf { it.isFinite() && it >= 0 }?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "—"),
        RuntimeMetric(label("内存", memoryLimit?.let(::runtimeBytes)),
            if (memory?.usageBytes == 0L) "0 MiB" else runtimeBytes(memory?.usageBytes)),
        RuntimeMetric(label("存储", storageLimit?.let(::runtimeBytes)), runtimeBytes(storage?.usedBytes)),
    )
}

private fun runtimeBytes(value: Long?): String {
    if (value == null || value < 0) return "—"
    var size = value.toDouble()
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return String.format(Locale.ROOT, if (unit == 0 || size >= 100) "%.0f %s" else "%.1f %s", size, units[unit])
}
