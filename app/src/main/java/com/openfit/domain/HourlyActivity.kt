package com.openfit.domain

enum class HourStatus { Met, Missed, Upcoming }

data class HourlyActivity(val window: IntRange, val hours: List<HourStatus>) {
    val met: Int get() = hours.count { it == HourStatus.Met }
}

const val ACTIVE_HOUR_STEPS = 250L
val DEFAULT_ACTIVE_HOURS = 9..17

fun hourlyActivity(
    hourlySteps: Map<Int, Long>,
    currentHour: Int?,
    window: IntRange = DEFAULT_ACTIVE_HOURS,
): HourlyActivity = HourlyActivity(
    window = window,
    hours = window.map { hour ->
        val met = (hourlySteps[hour] ?: 0) >= ACTIVE_HOUR_STEPS
        when {
            met -> HourStatus.Met
            currentHour != null && hour >= currentHour -> HourStatus.Upcoming
            else -> HourStatus.Missed
        }
    },
)
