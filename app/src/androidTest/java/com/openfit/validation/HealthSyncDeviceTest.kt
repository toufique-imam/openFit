package com.openfit.validation

import android.content.Context
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.room.Room
import com.openfit.data.OpenFitDatabase
import com.openfit.health.*
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class HealthSyncDeviceTest {
    private val app get() = ValidationContext.app
    private val today = LocalDate.now()
    private val historical = today.minusDays(480)
    private lateinit var db: OpenFitDatabase
    private lateinit var fake: FakeHealthClient
    private lateinit var gateway: HealthConnectGateway
    private lateinit var sync: HealthSync
    private lateinit var loads: DayLoadCache
    private fun at(day: LocalDate) = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
    private fun step(day: LocalDate, count: Long) = StepsRecord(at(day), null, at(day).plusSeconds(60), null, count, Metadata.manualEntryWithId("step-$day"))

    @Before fun setup() {
        check(app.packageName == "com.openfit.validation.debug")
        listOf("hc_import", "hc_changes", "hc_budget", "profile", "day_load", "day_slots").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        db = Room.inMemoryDatabaseBuilder(app, OpenFitDatabase::class.java).build()
        fake = FakeHealthClient().apply { permissions = setOf(HealthConnectGateway.read(StepsRecord::class)) }
        gateway = HealthConnectGateway(app, { fake.client }, db)
        loads = DayLoadCache(app, db)
        sync = HealthSync(app, gateway, loads, SlotCache(app, db), ProfileStore(app))
    }
    @After fun close() = runBlocking { sync.stopAndJoin(); db.close() }
    private suspend fun importAndOlder() {
        assertTrue(sync.importAll(waitForQuota = false))
        sync.startOlder()?.join()
    }

    @Test fun recentImportThenOlderHistoryAndResumeAfterQuota() = runBlocking {
        fake.records[StepsRecord::class] = listOf(step(today.minusDays(1), 100), step(historical, 240))
        fake.failOnRead = 1
        assertFalse(sync.importAll(waitForQuota = false))
        assertFalse(sync.imported)
        fake.failOnRead = null
        importAndOlder()
        assertTrue(sync.imported)
        assertEquals(240.0, gateway.store.dailyTotals(TotalMetric.Steps, historical, historical).getValue(historical), 0.01)
        assertTrue(gateway.store.firstDay <= historical)
        assertFalse(fake.reads.first().ascendingOrder)
        assertTrue(fake.reads.any { it.ascendingOrder && it.pageSize == 1 })
        assertTrue(gateway.store.copyCoverage().single().firstDay <= historical.toString())
    }

    @Test fun newlyGrantedTypeCopiesHistoryWithoutRereadingOtherHistory() = runBlocking {
        fake.records[StepsRecord::class] = listOf(step(historical, 240))
        importAndOlder()
        val before = fake.reads.size
        fake.records[WeightRecord::class] = listOf(WeightRecord(at(historical), null, Mass.kilograms(72.5), Metadata.manualEntryWithId("weight")))
        fake.permissions += HealthConnectGateway.read(WeightRecord::class)
        gateway.invalidatePermissions()
        sync.startRecent(Duration.ZERO).join()
        sync.startImport(background = true).join()
        sync.startOlder()?.join()
        // A recent refresh is expected; the existing type's historical ranges are not imported again.
        // Adding a type resets the changes token; the ordinary seven-day fallback may also run.
        val recentFrom = today.minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val existingReads = fake.reads.drop(before).filter { it.recordType == StepsRecord::class }
        assertTrue("Existing history was read again: $existingReads", existingReads.all { it.timeRangeFilter.startTime!! >= recentFrom })
        assertEquals(72.5, gateway.store.read(WeightRecord::class, at(historical).minusSeconds(1), at(historical).plusSeconds(1)).single().weight.inKilograms, 0.001)
    }

    @Test fun versionOneImportReplacesOldTotalsWithRawRecords() = runBlocking {
        app.getSharedPreferences("hc_import", 0).edit().putBoolean("imported", true).putInt("version", 1).putInt("totals_version", 1).commit()
        db.healthCache().putAggregate(com.openfit.data.AggregateEntity("day|steps|$historical", 1, at(historical).toEpochMilli(), historical.toString(), historical.toString(), ",steps,", "9999.0"))
        fake.records[StepsRecord::class] = listOf(step(historical, 240))
        importAndOlder()
        assertTrue(sync.imported)
        assertEquals(240.0, gateway.store.dailyTotals(TotalMetric.Steps, historical, historical).getValue(historical), 0.001)
        assertTrue(db.healthCache().savedDays("steps", historical.toString(), historical.toString()).isNotEmpty())
    }

    @Test fun recentSyncRereadsThreeDaysAndChangedHistoryAndForgetsScores() = runBlocking {
        fake.records[StepsRecord::class] = listOf(step(historical, 240))
        importAndOlder()
        loads.putAll("profile", mapOf(historical to DayLoad(999, 999.0)), loads.generation())
        fake.records[StepsRecord::class] = listOf(step(historical, 300), step(today.minusDays(1), 80))
        fake.changes += UpsertionChange(step(historical, 300))
        val before = fake.reads.size
        assertTrue(sync.syncRecent())
        assertNull(loads.get("profile", historical))
        assertEquals(300.0, gateway.store.dailyTotals(TotalMetric.Steps, historical, historical).getValue(historical), 0.001)
        assertEquals(80.0, gateway.store.dailyTotals(TotalMetric.Steps, today.minusDays(1), today).getValue(today.minusDays(1)), 0.001)
        val recent = fake.reads.drop(before).first { it.timeRangeFilter.endTime == today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant() }
        assertEquals(today.minusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant(), recent.timeRangeFilter.startTime)
    }

    @Test fun wipingProviderOrRevokingAccessRetainsOlderLocalCopy() = runBlocking {
        fake.records[StepsRecord::class] = listOf(step(historical, 240))
        importAndOlder()
        fake.records.clear()
        assertTrue(sync.syncRecent())
        assertEquals(240.0, gateway.store.dailyTotals(TotalMetric.Steps, historical, historical).getValue(historical), 0.001)
        fake.permissions = emptySet()
        gateway.invalidatePermissions()
        val before = fake.reads.size
        assertTrue(sync.syncRecent())
        assertEquals(before, fake.reads.size)
        assertEquals(240.0, gateway.store.dailyTotals(TotalMetric.Steps, historical, historical).getValue(historical), 0.001)
    }

    @Test fun importsJoinAndForegroundTakesOverBackground() = runBlocking {
        fake.delayMillis = 200
        val background = sync.startImport(background = true)
        assertSame(background, sync.startImport(background = true))
        val foreground = sync.startImport()
        assertNotSame(background, foreground)
        assertSame(foreground, sync.startImport())
        withTimeout(20_000) { foreground.join() }
        assertTrue(background.isCancelled)
        assertTrue(sync.imported)
    }
}
