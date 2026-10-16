package com.openfit.health

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import com.openfit.health.HealthConnectGateway.Companion.read
import com.openfit.profile.ProfileStore
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** Where an import has got to: [done] of [total] steps, now on [stage]. */
data class ImportProgress(val stage: ImportStage, val done: Int, val total: Int)

enum class ImportStage { Reading, HeartRate, Scoring }

/**
 * Keeps openFit's copy of Health Connect filled, like Pulse's import: once, the last [HISTORY_DAYS] days of every record
 * type (30 without the history permission, all Health Connect gives then), then the scores worked out from that copy
 * (cardio load and zone minutes per day, Energy Bank's slots) so no screen has to. After that, [refresh] follows the
 * changes feed and re-reads the last days. Both stop when the quota budget runs low and pick up where they left off.
 */
class HealthSync(
    context: Context,
    private val gateway: HealthConnectGateway,
    private val dayLoadCache: DayLoadCache,
    private val slotCache: SlotCache,
    private val profileStore: ProfileStore,
) {
    private val prefs = context.getSharedPreferences("hc_import", Context.MODE_PRIVATE)

    /** Whether the import has yet to cover the window the permissions allow. */
    suspend fun importNeeded(): Boolean = prefs.getInt(KEY_DAYS, 0) < windowDays(gateway.grantedPermissions())

    /**
     * Reads the history into the copy. Returns false when it stopped early (the quota, or Health Connect failing): what
     * it saved stays saved, and the next run reads only the rest.
     */
    suspend fun importHistory(onProgress: (ImportProgress) -> Unit = {}): Boolean {
        val granted = gateway.grantedPermissions()
        val days = windowDays(granted)
        val today = LocalDate.now()
        val from = today.minusDays(days - 1L)
        val types = SAVED_TYPES.keys.filter { read(it) in granted && it != HeartRateRecord::class }
        val heartRate = read(HeartRateRecord::class) in granted
        val weeks = if (heartRate) (days + WEEK - 1) / WEEK else 0
        val total = types.size + weeks + SCORING_STEPS
        var done = 0
        return try {
            types.forEach { type ->
                onProgress(ImportProgress(ImportStage.Reading, done, total))
                gateway.store.save(type, from, today)
                done++
            }
            // Heart rate is most of the reads: a week at a time, newest first, so recent screens fill first.
            repeat(weeks) { week ->
                onProgress(ImportProgress(ImportStage.HeartRate, done, total))
                val last = today.minusDays(week * WEEK.toLong())
                gateway.store.save(HeartRateRecord::class, maxOf(from, last.minusDays(WEEK - 1L)), last)
                done++
            }
            onProgress(ImportProgress(ImportStage.Scoring, done, total))
            val profile = profileStore.load()
            if (profile.age != null) {
                HealthConnectSource(gateway, dayLoadCache).history(from, today.minusDays(1), profile, backfillDays = days)
            }
            done++
            onProgress(ImportProgress(ImportStage.Scoring, done, total))
            EnergySource(gateway, slotCache).days(today, today)
            done++
            onProgress(ImportProgress(ImportStage.Scoring, done, total))
            prefs.edit().putInt(KEY_DAYS, days).putLong(KEY_AT, System.currentTimeMillis()).apply()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /**
     * What opening the app does (at most every [REFRESH_EVERY_MINUTES]), and the background sync: follows the changes
     * feed for older days, and reads the last [RECENT_DAYS] days again whatever is saved, as Pulse does. The feed alone
     * would do, but a watch syncing late is common and a missed change would leave a wrong day; three days are cheap.
     */
    suspend fun refresh(onlyIfDue: Boolean = false) {
        val now = System.currentTimeMillis()
        if (onlyIfDue && now - prefs.getLong(KEY_REFRESHED_AT, 0) < REFRESH_EVERY_MINUTES * 60_000) return
        val granted = gateway.grantedPermissions()
        gateway.store.syncChanges(granted)
        val today = LocalDate.now()
        val from = today.minusDays(RECENT_DAYS - 1)
        try {
            SAVED_TYPES.keys.filter { read(it) in granted }.forEach { gateway.store.save(it, from, today, force = true) }
            gateway.store.forgetAggregatesFrom(from)
            prefs.edit().putLong(KEY_REFRESHED_AT, now).apply()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The quota, or Health Connect failing: the saved days stand, and the next run tries again.
        }
    }

    private fun windowDays(granted: Set<String>): Int =
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted) HISTORY_DAYS else DAYS_WITHOUT_HISTORY

    private companion object {
        const val HISTORY_DAYS = 180
        /** Health Connect gives an app without the history permission the 30 days before it was granted. */
        const val DAYS_WITHOUT_HISTORY = 30
        const val WEEK = 7
        const val SCORING_STEPS = 2
        const val RECENT_DAYS = 3L
        const val REFRESH_EVERY_MINUTES = 15L
        const val KEY_REFRESHED_AT = "refreshed_at"
        const val KEY_DAYS = "days"
        const val KEY_AT = "at"
    }
}
