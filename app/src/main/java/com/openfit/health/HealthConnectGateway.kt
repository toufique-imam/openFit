package com.openfit.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

enum class HealthConnectAvailability { Available, UpdateRequired, Unavailable }

class HealthConnectGateway(private val context: Context) {

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
        else -> HealthConnectAvailability.Unavailable
    }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    fun requestablePermissions(): Set<String> = buildSet {
        addAll(READ_PERMISSIONS)
        if (isAvailable(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)) add(read(SkinTemperatureRecord::class))
        if (isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        if (isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    private fun isAvailable(feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        fun read(type: KClass<out Record>): String = HealthPermission.getReadPermission(type)

        private val READ_PERMISSIONS = listOf(
            StepsRecord::class,
            DistanceRecord::class,
            FloorsClimbedRecord::class,
            TotalCaloriesBurnedRecord::class,
            NutritionRecord::class,
            HydrationRecord::class,
            HeartRateRecord::class,
            RestingHeartRateRecord::class,
            HeartRateVariabilityRmssdRecord::class,
            OxygenSaturationRecord::class,
            RespiratoryRateRecord::class,
            Vo2MaxRecord::class,
            SleepSessionRecord::class,
            ExerciseSessionRecord::class,
            WeightRecord::class,
            BodyFatRecord::class,
        ).map(::read)
    }
}
