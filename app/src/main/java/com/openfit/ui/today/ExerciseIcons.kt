package com.openfit.ui.today

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Pool
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.health.connect.client.records.ExerciseSessionRecord

/** An icon for a Health Connect exercise type, as Fitbit's activity rows show one. */
fun exerciseIcon(type: Int): ImageVector = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> Icons.AutoMirrored.Outlined.DirectionsWalk
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> Icons.AutoMirrored.Outlined.DirectionsRun
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> Icons.AutoMirrored.Outlined.DirectionsBike
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> Icons.Outlined.Hiking
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> Icons.Outlined.Pool
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> Icons.Outlined.SelfImprovement
    else -> Icons.Outlined.FitnessCenter
}
