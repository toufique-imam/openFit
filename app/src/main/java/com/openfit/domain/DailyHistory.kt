package com.openfit.domain

import java.time.LocalDate

data class DailyHistory(
    val zoneMinutes: Map<LocalDate, Int> = emptyMap(),
    val cardioLoad: Map<LocalDate, Double> = emptyMap(),
    val restingBpm: Map<LocalDate, Int> = emptyMap(),
    val hrvMs: Map<LocalDate, Double> = emptyMap(),
    val sleepMinutes: Map<LocalDate, Int> = emptyMap(),
    /** Minutes in the moderate (fat burn), vigorous (cardio) and peak zones per day. */
    val heartZoneMinutes: Map<LocalDate, ZoneMinutes> = emptyMap(),
    /** Days in the requested range whose load is still to be computed (backfill continues on the next call). */
    val pendingDays: Int = 0,
)

fun mergeHistory(byPriority: List<DailyHistory>): DailyHistory =
    byPriority.reversed().fold(DailyHistory()) { merged, next ->
        DailyHistory(
            zoneMinutes = merged.zoneMinutes + next.zoneMinutes,
            cardioLoad = merged.cardioLoad + next.cardioLoad,
            restingBpm = merged.restingBpm + next.restingBpm,
            hrvMs = merged.hrvMs + next.hrvMs,
            sleepMinutes = merged.sleepMinutes + next.sleepMinutes,
            heartZoneMinutes = merged.heartZoneMinutes + next.heartZoneMinutes,
            pendingDays = maxOf(merged.pendingDays, next.pendingDays),
        )
    }
