package com.openfit.validation

import android.content.Intent
import android.os.SystemClock
import com.openfit.MainActivity
import com.openfit.data.OpenFitDatabase
import com.openfit.data.SnapshotEntity
import com.openfit.health.*
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.ui.onboarding.OnboardingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DeletionDeviceTest {
    @Test fun deleteEverythingStopsSyncClearsLocalDataKeepsQuotaAndRestartsSetup() = runBlocking {
        val app = ValidationContext.app
        check(app.packageName == "com.openfit.validation.debug")
        val instrumentation = ValidationContext.instrumentation
        val database = OpenFitDatabase.get(app)
        val fake = FakeHealthClient().apply { permissions = setOf(HealthConnectGateway.read(androidx.health.connect.client.records.StepsRecord::class)) }
        val gateway = HealthConnectGateway(app, { fake.client }, database)
        HealthConnectGateway::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, gateway)
        ProfileStore(app).save(Profile(name = "Deletion fixture", age = 35))
        OnboardingStore(app).done = true
        app.getSharedPreferences("google_health_history", 0).edit().putString("first_day", "2025-01-01").commit()
        gateway.budget.call("fixture") { }
        val quota = app.getSharedPreferences("hc_budget", 0)
        val quotaBefore = quota.all
        val day = LocalDate.now().minusDays(10)
        database.snapshots().put(SnapshotEntity("fixture", "fixture", day.toString(), 1, "profile", 123, "{}"))
        DayLoadCache(app).putAll("profile", mapOf(day to DayLoad(20, 12.0)), DayLoadCache(app).generation())
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val sync = HealthSync.get(app)
        fake.delayMillis = 200
        val job = sync.startImport(background = true)
        assertTrue(job.isActive)
        instrumentation.runOnMainSync { MainActivity::class.java.getDeclaredMethod("deleteEverything").apply { isAccessible = true }.invoke(activity) }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!activity.isFinishing && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(activity.isFinishing)
        assertTrue(job.isCompleted)
        assertFalse(OnboardingStore(app).done)
        assertEquals("", ProfileStore(app).load().name)
        assertNull(database.snapshots().get("fixture"))
        assertNull(DayLoadCache(app).get("profile", day))
        assertTrue(database.healthCache().copyCoverage().isEmpty())
        assertTrue(app.getSharedPreferences("google_health_history", 0).all.isEmpty())
        assertTrue(quota.all.isNotEmpty())
        quotaBefore.filterKeys { it.startsWith("calls:") }.forEach { (key, value) ->
            assertTrue((quota.all[key] as Int) >= value as Int)
        }
    }
}
