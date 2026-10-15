package com.openfit.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.openfit.domain.Exercise

private const val DEFAULT_EXERCISE_NAME = "Workout"

/** Fitbit's names for Health Connect exercise types. */
private val EXERCISE_NAMES: Map<Int, String> = mapOf(
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walk",
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Run",
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill run",
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hike",
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Outdoor bike",
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Bike",
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Swim",
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Swim",
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Weights",
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Weights",
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rowing machine",
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Stairclimber",
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Dancing",
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton",
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Soccer",
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Basketball",
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "Martial arts",
)

internal fun ExerciseSessionRecord.toExercise(): Exercise =
    Exercise(title?.takeIf { it.isNotBlank() } ?: EXERCISE_NAMES[exerciseType] ?: DEFAULT_EXERCISE_NAME, startTime, endTime)
