package com.openfit.health

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class HealthConnectGateway private constructor(private val context: Context) {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /** How much of Health Connect's read quota openFit has used. */
    val budget: HealthConnectBudget by lazy { HealthConnectBudget(context) }

    /** openFit's saved copy of Health Connect: every read goes through it. */
    val store: HealthStore by lazy { HealthStore(context, { client }, budget) }

    fun availability(): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
        else -> HealthConnectAvailability.Unavailable
    }

    private val permissionsLock = Mutex()
    @Volatile private var granted: Pair<Long, Set<String>>? = null

    /**
     * The granted permissions, asked of Health Connect at most every [PERMISSIONS_TTL_MS]: every source checks them, and
     * each ask is a round trip. [invalidatePermissions] after the user changes them.
     */
    suspend fun grantedPermissions(): Set<String> = permissionsLock.withLock {
        granted?.takeIf { SystemClock.elapsedRealtime() - it.first < PERMISSIONS_TTL_MS }?.second
            ?: client.permissionController.getGrantedPermissions().also { granted = SystemClock.elapsedRealtime() to it }
    }

    fun invalidatePermissions() {
        granted = null
    }

    fun requestablePermissions(): Set<String> = buildSet {
        addAll(READ_PERMISSIONS)
        if (isAvailable(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)) add(read(SkinTemperatureRecord::class))
        @OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)
        if (isAvailable(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)) add(read(androidx.health.connect.client.records.MindfulnessSessionRecord::class))
        if (isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        if (isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    private fun isAvailable(feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        @Volatile private var instance: HealthConnectGateway? = null

        /**
         * The one gateway of this process, shared by the app and the background work, so they share one copy, one
         * set of locks and one quota model.
         */
        fun get(context: Context): HealthConnectGateway = instance ?: synchronized(this) {
            instance ?: HealthConnectGateway(context.applicationContext).also { instance = it }
        }
        private const val PERMISSIONS_TTL_MS = 30_000L

        val OPTIONAL_PERMISSIONS = setOf(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )

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
