package com.openfit.validation

import android.content.Context
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Energy
import androidx.room.Room
import com.openfit.data.OpenFitDatabase
import com.openfit.domain.SLOTS_PER_DAY
import com.openfit.domain.Slot
import com.openfit.health.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class HealthStoreDeviceTest {
    private val app get() = ValidationContext.app
    private lateinit var db: OpenFitDatabase
    private lateinit var fake: FakeHealthClient
    private lateinit var store: HealthStore
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.now(zone).minusDays(10)
    private fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant()
    private fun step(date: LocalDate, count: Long, second: Long = 0) = StepsRecord(at(date).plusSeconds(second), null, at(date).plusSeconds(second + 60), null, count, Metadata.unknownRecordingMethod())

    @Before fun setup() {
        check(app.packageName == "com.openfit.validation.debug")
        listOf("hc_budget", "hc_changes", "day_load", "day_slots").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        db = Room.inMemoryDatabaseBuilder(app, OpenFitDatabase::class.java).build()
        fake = FakeHealthClient()
        store = HealthStore(app, { fake.client }, HealthConnectBudget(app), database = db)
    }
    @After fun close() { db.close() }

    @Test fun pagesSaveCompleteNewestDaysBeforeQuotaFailureAndResume() = runBlocking {
        fake.records[StepsRecord::class] = (0 until 5_100).map { step(day, 1, it.toLong()) } + step(day.plusDays(1), 99)
        fake.failOnRead = 2
        try {
            store.syncRecords(StepsRecord::class, day, day.plusDays(1), force = false)
            fail("Expected quota")
        } catch (error: Exception) {
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is QuotaGuardException })
        }
        assertFalse(fake.reads.first().ascendingOrder)
        assertEquals(listOf(day.plusDays(1).toString()), db.healthCache().savedDays("steps", day.toString(), day.plusDays(1).toString()))
        fake.failOnRead = null
        store.syncRecords(StepsRecord::class, day, day.plusDays(1), force = false)
        assertEquals(2, db.healthCache().savedDays("steps", day.toString(), day.plusDays(1).toString()).size)
        assertTrue(fake.reads.drop(2).all { it.timeRangeFilter.endTime == at(day.plusDays(1)) })
    }

    @Test fun missingDayIsFilledOnceForceRefreshesAndPagesNeverCallRemote() = runBlocking {
        fake.records[StepsRecord::class] = listOf(step(day, 50))
        store.syncDays(StepsRecord::class, listOf(day), force = false)
        val count = fake.reads.size
        store.syncDays(StepsRecord::class, listOf(day), force = false)
        assertEquals(count, fake.reads.size)
        assertEquals(50.0, store.dailyTotals(TotalMetric.Steps, day, day).getValue(day), 0.001)
        store.firstDay = day
        assertTrue(store.read(StepsRecord::class, at(day.minusDays(1)), at(day)).isEmpty())
        store.hourly(TotalMetric.Steps, day)
        store.minuteSteps(day)
        assertEquals(count, fake.reads.size)
        fake.records[StepsRecord::class] = listOf(step(day, 80))
        store.syncDays(StepsRecord::class, listOf(day), force = true)
        assertEquals(count + 1, fake.reads.size)
        assertEquals(80.0, store.dailyTotals(TotalMetric.Steps, day, day).getValue(day), 0.001)
    }

    @Test fun interruptedUpgradeRefreshSkipsDaysCompletedSinceUpgradeStarted() = runBlocking {
        fake.records[StepsRecord::class] = (0 until 5_100).map { step(day, 1, it.toLong()) } + step(day.plusDays(1), 99)
        val since = System.currentTimeMillis()
        fake.failOnRead = 2
        runCatching { store.syncRecords(StepsRecord::class, day, day.plusDays(1), force = true, refreshedAfter = since) }
        assertEquals(listOf(day.plusDays(1).toString()), db.healthCache().daysRefreshedSince("steps", day.toString(), day.plusDays(1).toString(), since))
        fake.failOnRead = null
        store.syncRecords(StepsRecord::class, day, day.plusDays(1), force = true, refreshedAfter = since)
        assertTrue(fake.reads.drop(2).all { it.timeRangeFilter.endTime == at(day.plusDays(1)) })
    }

    @Test fun midnightIntervalsHaveDailyHourlyMinuteAndWorkoutShares() = runBlocking {
        val start = at(day.plusDays(1)).minusSeconds(60)
        val end = start.plusSeconds(120)
        val meta = Metadata.unknownRecordingMethod()
        fake.records[StepsRecord::class] = listOf(StepsRecord(start, null, end, null, 120, meta))
        fake.records[DistanceRecord::class] = listOf(DistanceRecord(start, null, end, null, Length.meters(100.0), meta))
        fake.records[TotalCaloriesBurnedRecord::class] = listOf(TotalCaloriesBurnedRecord(start, null, end, null, Energy.kilocalories(10.0), meta))
        listOf(StepsRecord::class, DistanceRecord::class, TotalCaloriesBurnedRecord::class).forEach { store.syncRecords(it, day, day.plusDays(1), false) }
        val reads = fake.reads.size
        val totals = store.dailyTotals(TotalMetric.Steps, day, day.plusDays(1))
        assertEquals(60.0, totals.getValue(day), 0.001)
        assertEquals(60.0, totals.getValue(day.plusDays(1)), 0.001)
        assertEquals(60.0, store.hourly(TotalMetric.Steps, day.plusDays(1)).getValue(0), 0.001)
        assertEquals(60.0, store.minuteSteps(day.plusDays(1)).values.sum(), 0.001)
        val workout = requireNotNull(store.sessionTotals(start.plusSeconds(60), end))
        assertEquals(50.0, workout.distanceMeters!!, 0.001)
        assertEquals(5.0, workout.caloriesKcal!!, 0.001)
        assertEquals(reads, fake.reads.size)
    }

    @Test fun roomCachesPersistInvalidateAndRejectPreSyncWriters() = runBlocking {
        val loads = DayLoadCache(app, db)
        val slots = SlotCache(app, db)
        val series = List(SLOTS_PER_DAY) { Slot(if (it % 3 == 0) null else 60 + it % 10, it < 50, it in 80..85) }
        loads.putAll("profile", mapOf(day to DayLoad(20, 12.5)), loads.generation())
        slots.putAll(mapOf(day to series), slots.generation())
        assertEquals(DayLoad(20, 12.5), DayLoadCache(app, db).get("profile", day))
        assertEquals(series, SlotCache(app, db).get(day))
        val oldLoad = loads.generation()
        val oldSlots = slots.generation()
        loads.forgetFrom(day)
        slots.forget(listOf(day))
        loads.putAll("profile", mapOf(day to DayLoad(1, 1.0)), oldLoad)
        slots.putAll(mapOf(day to series), oldSlots)
        assertNull(loads.get("profile", day))
        assertNull(slots.get(day))
        assertTrue(app.getSharedPreferences("day_load", 0).all.isEmpty())
        assertTrue(app.getSharedPreferences("day_slots", 0).all.isEmpty())
    }

    @Test fun timeZoneChangeRefilesSavedRecordsAndTotalsWithoutRemoteReads() = runBlocking {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val date = LocalDate.now().minusDays(10)
            val start = date.atTime(0, 30).toInstant(java.time.ZoneOffset.UTC)
            fake.records[StepsRecord::class] = listOf(StepsRecord(start, null, start.plusSeconds(60), null, 120, Metadata.manualEntryWithId("travel-steps")))
            store.syncRecords(StepsRecord::class, date, date, false)
            assertEquals(120.0, store.dailyTotals(TotalMetric.Steps, date, date).getValue(date), 0.001)
            val remoteReads = fake.reads.size
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val moved = date.minusDays(1)
            val totals = store.dailyTotals(TotalMetric.Steps, moved, date)
            assertEquals(120.0, totals.getValue(moved), 0.001)
            assertNull(totals[date])
            assertEquals(120.0, store.hourly(TotalMetric.Steps, moved).getValue(start.atZone(ZoneId.of("America/Los_Angeles")).hour), 0.001)
            assertEquals(1, store.read(StepsRecord::class, start.minusSeconds(1), start.plusSeconds(120)).size)
            assertEquals(remoteReads, fake.reads.size)
            assertTrue(db.healthCache().savedDays("refile:steps", "0001-01-01", "9999-12-31").isEmpty())
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun oldDeletionTracksItsOriginalDaysAndSurvivesAStoppedSync() = runBlocking {
        val old = day.minusDays(240)
        val start = at(old.plusDays(1)).minusSeconds(60)
        fake.permissions = setOf(HealthConnectGateway.read(StepsRecord::class))
        fake.records[StepsRecord::class] = listOf(StepsRecord(start, null, start.plusSeconds(120), null, 120, Metadata.manualEntryWithId("old-deletion")))
        store.firstDay = old
        store.syncRecords(StepsRecord::class, old, old.plusDays(1), false)
        store.changedDays(fake.permissions) // Create the initial changes token.
        fake.records.clear()
        fake.changes += androidx.health.connect.client.changes.DeletionChange("old-deletion")
        val expected = setOf(old, old.plusDays(1))
        assertEquals(expected, store.changedDays(fake.permissions)?.get(StepsRecord::class))

        // The changes token advanced, but no record copy succeeded before this process was replaced.
        val restarted = HealthStore(app, { fake.client }, HealthConnectBudget(app), database = db)
        assertEquals(expected, restarted.changedDays(fake.permissions)?.get(StepsRecord::class))
        restarted.syncDays(StepsRecord::class, expected, true)
        restarted.acknowledgeChanges(fake.permissions)
        assertTrue(restarted.dailyTotals(TotalMetric.Steps, old, old.plusDays(1)).isEmpty())
        assertTrue(restarted.changedDays(fake.permissions).orEmpty().isEmpty())
    }
}
