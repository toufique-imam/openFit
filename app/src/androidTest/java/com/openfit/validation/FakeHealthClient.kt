package com.openfit.validation

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import com.openfit.health.QuotaGuardException
import com.openfit.health.filedAt
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

internal class FakeHealthClient {
    var permissions: Set<String> = emptySet()
    var historyVisibleFrom: Instant = Instant.MIN
    val records = ConcurrentHashMap<KClass<out Record>, List<Record>>()
    val changes = CopyOnWriteArrayList<Change>()
    val reads = CopyOnWriteArrayList<ReadRecordsRequest<*>>()
    @Volatile var failOnRead: Int? = null
    @Volatile var delayMillis = 0L
    private val controller = Proxy.newProxyInstance(PermissionController::class.java.classLoader, arrayOf(PermissionController::class.java)) { _, method, _ ->
        when (method.name) { "getGrantedPermissions" -> permissions; "revokeAllPermissions" -> Unit; else -> error(method.name) }
    } as PermissionController
    private val features = object : HealthConnectFeatures {
        override fun getFeatureStatus(feature: Int) = HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
    }
    val client = Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader, arrayOf(HealthConnectClient::class.java)) { _, method, args ->
        when (method.name) {
            "getPermissionController" -> controller
            "getFeatures" -> features
            "getChangesToken" -> changes.size.toString()
            "getChanges" -> ChangesResponse(changes.drop((args!![0] as String).toInt()), changes.size.toString(), false, false)
            "readRecords" -> {
                val request = args!![0] as ReadRecordsRequest<*>
                reads += request
                if (delayMillis > 0) Thread.sleep(delayMillis)
                if (failOnRead == reads.size) throw QuotaGuardException("Injected quota pause")
                val from = request.timeRangeFilter.startTime ?: Instant.MIN
                val until = request.timeRangeFilter.endTime ?: Instant.MAX
                val cutoff = if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in permissions) Instant.MIN else historyVisibleFrom
                val visible = records[request.recordType].orEmpty().filter { it.filedAt >= from && it.filedAt < until && it.filedAt >= cutoff }
                val ordered = if (request.ascendingOrder) visible.sortedBy { it.filedAt } else visible.sortedByDescending { it.filedAt }
                val offset = request.pageToken?.toInt() ?: 0
                val selected = ordered.drop(offset).take(request.pageSize)
                ReadRecordsResponse(selected, (offset + selected.size).takeIf { it < ordered.size }?.toString())
            }
            "toString" -> "FakeHealthClient"
            else -> error("Unexpected remote call: ${method.name}")
        }
    } as HealthConnectClient
}
