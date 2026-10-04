package dev.memoh.feature.sessions

import java.time.ZoneId
import java.time.ZonedDateTime

internal enum class ScheduleMode(val label: String) {
    Minutes("每隔几分钟"), Hourly("每小时"), Daily("每天"), Weekly("每周"), Monthly("每月"), Yearly("每年"), Cron("高级 Cron")
}

internal data class ScheduleRule(
    val mode: ScheduleMode = ScheduleMode.Daily,
    val hour: String = "9", val minute: String = "0", val interval: String = "30",
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5), val monthDays: String = "1", val month: String = "1",
    val advanced: String = "0 9 * * *",
) {
    fun expression(): String {
        if (mode == ScheduleMode.Cron) return advanced.trim()
        fun number(value: String, range: IntRange, label: String): Int =
            requireNotNull(value.toIntOrNull()?.takeIf { it in range }) { "${label}应为 ${range.first}–${range.last}" }
        if (mode == ScheduleMode.Minutes) return "*/${number(interval, 1..59, "间隔分钟")} * * * *"
        val m = number(minute, 0..59, "分钟")
        if (mode == ScheduleMode.Hourly) return "$m * * * *"
        val h = number(hour, 0..23, "小时")
        return when (mode) {
            ScheduleMode.Weekly -> {
                require(weekdays.isNotEmpty() && weekdays.all { it in 0..6 }) { "请选择至少一天" }
                "$m $h * * ${weekdays.sorted().joinToString(",") }"
            }
            ScheduleMode.Monthly, ScheduleMode.Yearly -> {
                val days = monthDays.split(',').map { number(it.trim(), 1..31, "日期") }.distinct().sorted()
                val mo = if (mode == ScheduleMode.Yearly) number(month, 1..12, "月份").toString() else "*"
                if (mode == ScheduleMode.Yearly) require(days.all { it <= java.time.Month.of(mo.toInt()).maxLength() }) { "所选月份没有这个日期" }
                "$m $h ${days.joinToString(",")} $mo *"
            }
            else -> "$m $h * * *"
        }
    }

    companion object {
        fun from(pattern: String): ScheduleRule {
            val f = pattern.trim().split(Regex("\\s+"))
            val custom = ScheduleRule(mode = ScheduleMode.Cron, advanced = pattern)
            if (f.size != 5) return custom
            if (f.drop(1).all { it == "*" } && f[0].startsWith("*/")) {
                val n = f[0].drop(2).toIntOrNull()?.takeIf { it in 1..59 } ?: return custom
                return custom.copy(mode = ScheduleMode.Minutes, interval = n.toString())
            }
            val m = f[0].toIntOrNull()?.takeIf { it in 0..59 } ?: return custom
            if (f.drop(1).all { it == "*" }) return custom.copy(mode = ScheduleMode.Hourly, minute = m.toString())
            val h = f[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return custom
            val base = custom.copy(hour = h.toString(), minute = m.toString())
            if (f.drop(2).all { it == "*" }) return base.copy(mode = ScheduleMode.Daily)
            if (f[2] == "*" && f[3] == "*") {
                val days = f[4].split(',').map { it.toIntOrNull()?.takeIf { day -> day in 0..6 } ?: return custom }.toSet()
                return base.copy(mode = ScheduleMode.Weekly, weekdays = days)
            }
            if (f[4] == "*" && f[2].split(',').all { it.toIntOrNull() in 1..31 }) {
                if (f[3] == "*") return base.copy(mode = ScheduleMode.Monthly, monthDays = f[2])
                if (f[3].toIntOrNull() in 1..12) return base.copy(mode = ScheduleMode.Yearly, monthDays = f[2], month = f[3])
            }
            return custom
        }
    }
}

private val descriptors = mapOf("@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *", "@monthly" to "0 0 1 * *",
    "@weekly" to "0 0 * * 0", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *", "@hourly" to "0 * * * *")
private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private val days = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

/** robfig/cron uses optional seconds, ranges, lists, steps, names and descriptors. */
private fun cronFields(pattern: String): List<String> {
    val fields = pattern.trim().split(Regex("\\s+"))
    val value = if (fields.firstOrNull()?.startsWith("CRON_TZ=") == true || fields.firstOrNull()?.startsWith("TZ=") == true) {
        ZoneId.of(fields.first().substringAfter('=')); fields.drop(1).joinToString(" ")
    } else pattern.trim()
    return (descriptors[value] ?: value).split(Regex("\\s+"))
}

private fun cronValues(field: String, range: IntRange, names: List<String> = emptyList()): Set<Int> {
    fun number(text: String): Int = names.indexOf(text.uppercase(java.util.Locale.ROOT)).takeIf { it >= 0 }?.plus(range.first)
        ?: requireNotNull(text.toIntOrNull()) { "Cron 字段无效：$field" }
    return field.split(',').flatMap { part ->
        val stepParts = part.split('/')
        require(stepParts.size in 1..2) { "Cron 步长无效" }
        val step = if (stepParts.size == 2) requireNotNull(stepParts[1].toIntOrNull()?.takeIf { it > 0 }) { "Cron 步长应为正整数" } else 1
        val base = stepParts[0]
        val limits = base.split('-')
        require(limits.size in 1..2) { "Cron 范围无效" }
        val start = if (base == "*" || base == "?") range.first else number(limits[0])
        val end = if (base == "*" || base == "?" || stepParts.size == 2 && limits.size == 1) range.last
            else if (limits.size == 2) number(limits[1]) else start
        require(start in range && end in range && end >= start) { "Cron 字段应在 ${range.first}–${range.last} 内" }
        (start..end step step).toList()
    }.toSet()
}

internal fun validateSchedulePattern(pattern: String) {
    val f = cronFields(pattern)
    if (f.firstOrNull() == "@every") {
        val duration = f.getOrNull(1).orEmpty()
        val tokens = Regex("([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|μs|ms|s|m|h)").findAll(duration).toList()
        require(f.size == 2 && tokens.joinToString("") { it.value } == duration && tokens.any { it.groupValues[1].toDouble() > 0 }) { "请填写有效的间隔，例如 @every 30m" }
        return
    }
    require(f.size in 5..6) { "Cron 应为五段或六段表达式" }
    if (f.size == 6) cronValues(f[0], 0..59)
    val standard = f.takeLast(5)
    cronValues(standard[0], 0..59); cronValues(standard[1], 0..23); cronValues(standard[2], 1..31)
    cronValues(standard[3], 1..12, months); cronValues(standard[4], 0..6, days)
}

internal fun nextPreset(pattern: String, timezone: String, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
    if (pattern.trim().startsWith("@every")) return null // The interval starts at server registration, not at the device's clock.
    return runCatching {
        val f = cronFields(pattern)
        require(f.size in 5..6)
        val seconds = if (f.size == 6) cronValues(f.first(), 0..59) else setOf(0)
        val p = f.takeLast(5)
        val minute = cronValues(p[0], 0..59); val hour = cronValues(p[1], 0..23)
        val dom = cronValues(p[2], 1..31); val month = cronValues(p[3], 1..12, months); val dow = cronValues(p[4], 0..6, days)
        val zone = pattern.trim().split(Regex("\\s+"))[0].takeIf { it.startsWith("CRON_TZ=") || it.startsWith("TZ=") }?.substringAfter('=') ?: timezone
        val local = now.withZoneSameInstant(ZoneId.of(zone))
        (0L..(366L * 5)).asSequence().map { local.toLocalDate().plusDays(it) }
            .filter { date -> date.monthValue in month &&
                (if (p[2].contains('*') || p[2].contains('?') || p[4].contains('*') || p[4].contains('?')) date.dayOfMonth in dom && date.dayOfWeek.value % 7 in dow
                 else date.dayOfMonth in dom || date.dayOfWeek.value % 7 in dow) }
            .mapNotNull { date ->
                hour.asSequence().flatMap { h -> minute.asSequence().flatMap { m -> seconds.asSequence().flatMap { s ->
                    val time = date.atTime(h, m, s)
                    local.zone.rules.getValidOffsets(time).asSequence().map { ZonedDateTime.ofLocal(time, local.zone, it) }
                } } }.filter { it.isAfter(local) }.minByOrNull { it.toInstant() }
            }.firstOrNull()
    }.getOrNull()
}

internal fun scheduleDescription(pattern: String): String = runCatching {
    val rule = ScheduleRule.from(pattern)
    val time = "%02d:%02d".format(rule.hour.toInt(), rule.minute.toInt())
    when (rule.mode) {
        ScheduleMode.Minutes -> "每隔 ${rule.interval} 分钟"
        ScheduleMode.Hourly -> "每小时 ${rule.minute} 分"
        ScheduleMode.Daily -> "每天 $time"
        ScheduleMode.Weekly -> "每周${rule.weekdays.sortedBy { if (it == 0) 7 else it }.joinToString("、") { listOf("日", "一", "二", "三", "四", "五", "六")[it] }} $time"
        ScheduleMode.Monthly -> "每月 ${rule.monthDays} 日 $time"
        ScheduleMode.Yearly -> "每年 ${rule.month} 月 ${rule.monthDays} 日 $time"
        ScheduleMode.Cron -> pattern
    }
}.getOrDefault(pattern)
