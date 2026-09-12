package com.dayforge.domain.service

import com.dayforge.data.model.HabitSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 活跃度计算器
 *
 * 规则：
 * - 满分 100 分
 * - 每次错过应打卡日扣固定值
 * - 扣分值根据 Schedule 类型确定：
 *   - Daily: 15 分
 *   - Weekly (指定天数): 12 分
 *   - Weekly (每7天): 30 分
 *   - Monthly: 45 分
 *   - Custom: 30 分
 * - 窗口外的错过不计入
 * - 活跃度最低为 0 分
 */
object ActivityRateCalculator {

    /**
     * 各类型的单次扣分值
     */
    fun getDeductionPerMiss(schedule: HabitSchedule): Int {
        return when (schedule) {
            is HabitSchedule.Daily -> 15
            is HabitSchedule.Weekly -> {
                if (schedule.daysOfWeek.isEmpty()) 30  // 每7天
                else 12  // 指定天数
            }
            is HabitSchedule.Monthly -> 45
            is HabitSchedule.Custom -> 30
        }
    }

    /**
     * 各类型的窗口大小（应打卡日数量）
     */
    fun getWindowSize(schedule: HabitSchedule): Int {
        return when (schedule) {
            is HabitSchedule.Daily -> 7
            is HabitSchedule.Weekly -> {
                if (schedule.daysOfWeek.isEmpty()) 4  // 每7天
                else 8  // 指定天数，约3周
            }
            is HabitSchedule.Monthly -> 3
            is HabitSchedule.Custom -> 4
        }
    }

    /**
     * 计算活跃度
     *
     * @param schedule 习惯的 Schedule 类型
     * @param createdAt 习惯创建时间（毫秒）
     * @param completions 打卡记录捕获的业务日期，不随设备时区变化
     * @param now 当前时间（毫秒）
     * @return 活跃度 (0-100)
     */
    fun calculate(
        schedule: HabitSchedule,
        createdAt: Long,
        completions: List<LocalDate>,
        now: Long = System.currentTimeMillis()
    ): Int {
        // 1. 生成窗口内的应打卡日序列
        val windowDays = generateWindowCheckInDays(schedule, createdAt, now)

        // 2. 获取单次扣分值
        val deduction = getDeductionPerMiss(schedule)

        // 3. 统计未完成的应打卡日数量
        val completedDays = completions.toSet()
        val missedCount = windowDays.count { millisToLocalDate(it) !in completedDays }

        // 4. 计算活跃度
        val totalDeduction = missedCount * deduction
        return (100 - totalDeduction).coerceAtLeast(0)
    }

    /**
     * 生成窗口内的应打卡日序列
     *
     * 从今天往回找，收集最近 N 个应打卡日（包括今天如果是应打卡日）
     *
     * @param schedule 习惯的 Schedule 类型
     * @param createdAt 习惯创建时间（毫秒）
     * @param now 当前时间（毫秒）
     * @return 应打卡日列表（毫秒，已标准化为当天0点），从远到近排序
     */
    fun generateWindowCheckInDays(
        schedule: HabitSchedule,
        createdAt: Long,
        now: Long
    ): List<Long> {
        val windowSize = getWindowSize(schedule)
        val today = millisToLocalDate(now)
        val creationDate = millisToLocalDate(createdAt)

        // A habit created today has not missed its first opportunity yet. Without
        // this guard a new daily habit immediately starts at 85% before the user
        // has had a full day to complete it.
        if (creationDate == today) return emptyList()

        val checkInDays = mutableListOf<LocalDate>()
        var currentDate = today
        var count = 0

        // 从今天往回找，收集 windowSize 个应打卡日
        while (count < windowSize && currentDate >= creationDate) {
            if (ScheduleValidator.isCheckInAllowedOnDate(schedule, createdAt, currentDate)) {
                checkInDays.add(0, currentDate)  // 插入到开头，保持从远到近的顺序
                count++
            }
            currentDate = currentDate.minusDays(1)
        }

        return checkInDays.map { localDateToMillis(it) }
    }

    /**
     * 毫秒转 LocalDate
     */
    private fun millisToLocalDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    /**
     * LocalDate 转毫秒（当天0点）
     */
    private fun localDateToMillis(date: LocalDate): Long {
        return date.atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
