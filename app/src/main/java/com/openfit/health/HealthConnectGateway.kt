package com.openfit.health

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.health.connect.client.HealthConnectClient
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

class HealthConnectGateway internal constructor(
    private val context: Context,
    clientFactory: () -> HealthConnectClient = { HealthConnectClient.getOrCreate(context) },
    private val database: com.openfit.data.OpenFitDatabase? = null,
    private val sdkStatus: () -> Int = { HealthConnectClient.getSdkStatus(context) },
) {

    private val client: HealthConnectClient by lazy(clientFactory)

    /** How much of Health Connect's read quota openFit has used. */
    val budget: HealthConnectBudget by lazy { HealthConnectBudget(context) }

    /** openFit's saved copy of Health Connect: every read goes through it. */
    val store: HealthStore by lazy { HealthStore(context, { client }, budget, database = database ?: com.openfit.data.OpenFitDatabase.get(context)) }

    fun availability(): HealthConnectAvailability = when (sdkStatus()) {
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

    /**
     * Everything openFit reads, asked for whether or not this Health Connect supports it: connect-client's feature
     * check says skin temperature and mindfulness are unavailable on every Health Connect app (Android 13 and below),
     * yet a current one grants and serves them. A permission it can't grant simply stays ungranted.
     */
    fun requestablePermissions(): Set<String> = READ_PERMISSIONS.toSet() + OPTIONAL_PERMISSIONS

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

        /** Asked for, but not every Health Connect has them, so a missing one never raises the Today banner. */
        @OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)
        val OPTIONAL_PERMISSIONS = setOf(
            read(SkinTemperatureRecord::class),
            read(androidx.health.connect.client.records.MindfulnessSessionRecord::class),
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
