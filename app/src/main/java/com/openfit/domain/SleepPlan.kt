package com.openfit.domain

import java.time.ZonedDateTime

data class SleepPlan(val windDown: ZonedDateTime, val bedtime: ZonedDateTime, val wake: ZonedDateTime)

/** Subtract elapsed minutes, including nights when a clock change shortens or lengthens local time. */
fun sleepPlan(wake: ZonedDateTime, minutesInBed: Int, windDownMinutes: Int): SleepPlan {
    require(minutesInBed in 1..1440)
    require(windDownMinutes in 0..180)
    val bedtime = wake.minusMinutes(minutesInBed.toLong())
    return SleepPlan(bedtime.minusMinutes(windDownMinutes.toLong()), bedtime, wake)
}
