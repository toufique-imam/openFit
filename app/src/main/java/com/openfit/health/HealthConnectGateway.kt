package com.openfit.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord

enum class HealthConnectAvailability { Available, UpdateRequired, Unavailable }

class HealthConnectGateway(private val context: Context) {

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
        else -> HealthConnectAvailability.Unavailable
    }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val DISTANCE = HealthPermission.getReadPermission(DistanceRecord::class)
        val FLOORS = HealthPermission.getReadPermission(FloorsClimbedRecord::class)
        val CALORIES = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        val HEART_RATE = HealthPermission.getReadPermission(HeartRateRecord::class)
        val RESTING_HEART_RATE = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
        val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)

        val PERMISSIONS = setOf(STEPS, DISTANCE, FLOORS, CALORIES, HEART_RATE, RESTING_HEART_RATE, SLEEP)
    }
}
