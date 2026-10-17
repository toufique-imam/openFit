package com.openfit.health

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant

/** Count each asleep instant once even when two connected apps export the same night. */
internal fun totalAsleepDuration(sessions: List<SleepSessionRecord>): Duration {
    val spans = sessions.flatMap { session ->
        if (session.stages.isEmpty()) listOf(session.startTime to session.endTime)
        else {
            // Missing stage detail still counts as sleep, as in HealthConnectSource's original duration calculation.
            var cursor = session.startTime
            buildList {
                session.stages.forEach { stage ->
                    if (cursor < stage.startTime) add(cursor to stage.startTime)
                    if (stage.stage !in AWAKE) add(stage.startTime to stage.endTime)
                    cursor = stage.endTime
                }
                if (cursor < session.endTime) add(cursor to session.endTime)
            }
        }
    }.sortedBy { it.first }
    var end = Instant.MIN
    var duration = Duration.ZERO
    spans.forEach { (start, until) ->
        if (until > end) duration += Duration.between(maxOf(start, end), until)
        end = maxOf(end, until)
    }
    return duration
}

private val AWAKE = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
