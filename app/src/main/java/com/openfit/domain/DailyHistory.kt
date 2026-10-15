package com.openfit.domain

import java.time.LocalDate

data class DailyHistory(
    val zoneMinutes: Map<LocalDate, Int> = emptyMap(),
    val cardioLoad: Map<LocalDate, Double> = emptyMap(),
    val restingBpm: Map<LocalDate, Int> = emptyMap(),
    val hrvMs: Map<LocalDate, Double> = emptyMap(),
    val sleepMinutes: Map<LocalDate, Int> = emptyMap(),
)

fun mergeHistory(byPriority: List<DailyHistory>): DailyHistory =
    byPriority.reversed().fold(DailyHistory()) { merged, next ->
        DailyHistory(
            zoneMinutes = merged.zoneMinutes + next.zoneMinutes,
            cardioLoad = merged.cardioLoad + next.cardioLoad,
            restingBpm = merged.restingBpm + next.restingBpm,
            hrvMs = merged.hrvMs + next.hrvMs,
            sleepMinutes = merged.sleepMinutes + next.sleepMinutes,
        )
    }
