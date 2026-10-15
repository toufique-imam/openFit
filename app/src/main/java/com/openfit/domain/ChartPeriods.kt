package com.openfit.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

enum class ChartPeriod { Day, Week, Month, Year }

/** One point on a Week/Month/Year chart: a day (or a month on the Year chart) with its load and target range. */
data class PeriodPoint(val start: LocalDate, val load: Double?, val range: LoadRange?)

/** A row in the list under a chart: a day, week or month and its load (a daily average for weeks and months). */
data class PeriodRow(val start: LocalDate, val end: LocalDate, val load: Double)

/** Chart y-axis: Fitbit labels 0, a rounded middle and a top 20 % above the highest value or band. */
data class ChartAxis(val max: Double, val middle: Int)

private const val AXIS_HEADROOM = 1.2
private const val AXIS_STEP = 5

fun chartAxis(highest: Double): ChartAxis = axisWithMiddle((highest * AXIS_HEADROOM).coerceAtLeast(AXIS_STEP * 2.0))

/**
 * Fitbit's middle label: half the top, rounded up to a step of 1, 2 or 5 x 10^n, the first of those at least 5 % of
 * the range (else the 5 x 10^n one). 139 -> 70, 123 -> 65, 106 -> 55.
 */
fun axisWithMiddle(max: Double): ChartAxis {
    val minimumStep = max * 0.05
    val magnitude = 10.0.pow(floor(log10(minimumStep)))
    val step = listOf(1.0, 2.0, 5.0).map { it * magnitude }.firstOrNull { it >= minimumStep } ?: (5 * magnitude)
    return ChartAxis(max, (ceil(max / 2 / step) * step).toInt())
}

/** Day chart: Fitbit scales it to the ring's full scale (0.45 × chronic weekly load), stretching only for big days. */
fun dayChartAxis(range: LoadRange?, load: Double): ChartAxis {
    if (range != null && load <= range.scale) return axisWithMiddle(range.scale)
    return chartAxis(maxOf(range?.high ?: 0.0, load))
}

fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

/** The days the period containing [anchor] covers. */
fun periodDays(period: ChartPeriod, anchor: LocalDate): List<LocalDate> {
    val (start, end) = when (period) {
        ChartPeriod.Day -> anchor to anchor
        ChartPeriod.Week -> weekStart(anchor).let { it to it.plusDays(6) }
        ChartPeriod.Month -> YearMonth.from(anchor).let { it.atDay(1) to it.atEndOfMonth() }
        ChartPeriod.Year -> anchor.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
    }
    return generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.toList()
}

fun shiftPeriod(period: ChartPeriod, anchor: LocalDate, steps: Long): LocalDate = when (period) {
    ChartPeriod.Day -> anchor.plusDays(steps)
    ChartPeriod.Week -> anchor.plusWeeks(steps)
    ChartPeriod.Month -> anchor.plusMonths(steps)
    ChartPeriod.Year -> anchor.plusYears(steps)
}

fun dailyPoints(days: List<LocalDate>, loads: Map<LocalDate, Double>, ranges: Map<LocalDate, LoadRange>): List<PeriodPoint> =
    days.map { PeriodPoint(it, loads[it], ranges[it]) }

/** Year chart: each month's average daily load and its average target range. */
fun monthlyPoints(year: Int, loads: Map<LocalDate, Double>, ranges: Map<LocalDate, LoadRange>): List<PeriodPoint> =
    (1..12).map { month ->
        val days = YearMonth.of(year, month).let { ym -> (1..ym.lengthOfMonth()).map(ym::atDay) }
        val monthRanges = days.mapNotNull { ranges[it] }
        PeriodPoint(
            start = days.first(),
            load = averagePerDay(days, loads),
            range = monthRanges.takeIf { it.isNotEmpty() }?.let { list ->
                LoadRange(list.map { it.low }.average(), list.map { it.high }.average(), list.map { it.scale }.average())
            },
        )
    }

/** Average of the days with a non-zero value, as Fitbit averages ("per day (avg)", "Daily average"). */
fun averagePerDay(days: List<LocalDate>, loads: Map<LocalDate, Double>): Double? =
    days.mapNotNull { loads[it]?.takeIf { value -> value > 0 } }.takeIf { it.isNotEmpty() }?.average()

fun daysInTarget(points: List<PeriodPoint>): Int =
    points.count { point -> point.load != null && point.range != null && point.load in point.range }

/** Week tab list: each tracked day, newest first. */
fun dayRows(days: List<LocalDate>, loads: Map<LocalDate, Double>): List<PeriodRow> =
    days.sortedDescending().mapNotNull { day -> loads[day]?.let { PeriodRow(day, day, it) } }

/** Month tab list: the Sunday weeks touching the month, newest first, as daily averages. */
fun weekRows(month: YearMonth, loads: Map<LocalDate, Double>): List<PeriodRow> =
    generateSequence(weekStart(month.atDay(1))) { it.plusWeeks(1) }
        .takeWhile { it <= month.atEndOfMonth() }
        .mapNotNull { start ->
            val days = (0L until 7L).map(start::plusDays)
            averagePerDay(days, loads)?.let { PeriodRow(start, start.plusDays(6), it) }
        }
        .toList()
        .reversed()

/** Year tab list: each tracked month, newest first, as daily averages. */
fun monthRows(year: Int, loads: Map<LocalDate, Double>): List<PeriodRow> =
    (12 downTo 1).mapNotNull { month ->
        val ym = YearMonth.of(year, month)
        averagePerDay((1..ym.lengthOfMonth()).map(ym::atDay), loads)?.let { PeriodRow(ym.atDay(1), ym.atEndOfMonth(), it) }
    }

private const val UNDER_MONTH = 0.8
private const val IMPROVE_MONTH = 1.1
private const val OVER_MONTH = 1.3
private const val MIN_DAYS_FOR_MONTH_STATUS = 7

/**
 * The Month tab's headline: the month's average daily acute:chronic ratio in Fitbit's four monthly bands
 * (undertrained < 0.8 <= maintained < 1.1 <= improved < 1.3 <= overtrained). Needs a week of data.
 */
fun monthStatus(month: YearMonth, loads: Map<LocalDate, Double>, until: LocalDate): TrainingStatus? {
    val days = (1..month.lengthOfMonth()).map(month::atDay).filter { it <= until }
    if (days.count { loads[it] != null } < MIN_DAYS_FOR_MONTH_STATUS) return null
    val ratio = days.mapNotNull { acuteChronicRatio(it, loads) }.takeIf { it.isNotEmpty() }?.average() ?: return null
    return when {
        ratio < UNDER_MONTH -> TrainingStatus.Undertraining
        ratio < IMPROVE_MONTH -> TrainingStatus.Maintaining
        ratio < OVER_MONTH -> TrainingStatus.Improving
        else -> TrainingStatus.Overtraining
    }
}
