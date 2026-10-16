@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

enum class HourStatus { Met, Missed, Upcoming }

data class HourlyActivity(val window: IntRange, val hours: List<HourStatus>) {
    val met: Int get() = hours.count { it == HourStatus.Met }
}

const val ACTIVE_HOUR_STEPS = 250L

/** [window] is the user's hourly goal: the hours, first to last, that each need [ACTIVE_HOUR_STEPS]. */
fun hourlyActivity(
    hourlySteps: Map<Int, Long>,
    currentHour: Int?,
    window: IntRange,
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

fun hoursMet(hourlySteps: Map<Int, Double>, window: IntRange): Int =
    window.count { (hourlySteps[it] ?: 0.0) >= ACTIVE_HOUR_STEPS }

/** "Your day at a glance": minutes with steps, inactive stretches of [MIN_INACTIVE_MINUTES]+, and the longest one. */
@Serializable
data class ActivityGlance(val activeMinutes: Int, val inactiveMinutes: Int, val longestInactiveMinutes: Int)

const val MIN_INACTIVE_MINUTES = 10

/** Within [fromMinute] until [untilMinute] (epoch minutes): a minute is active when it has any steps. */
fun activityGlance(minuteSteps: Map<Long, Double>, fromMinute: Long, untilMinute: Long): ActivityGlance {
    var active = 0
    var inactive = 0
    var longest = 0
    var run = 0
    fun closeRun() {
        if (run >= MIN_INACTIVE_MINUTES) inactive += run
        longest = maxOf(longest, run)
        run = 0
    }
    for (minute in fromMinute until untilMinute) {
        if ((minuteSteps[minute] ?: 0.0) > 0.0) {
            active++
            closeRun()
        } else {
            run++
        }
    }
    closeRun()
    return ActivityGlance(active, inactive, longest)
}
