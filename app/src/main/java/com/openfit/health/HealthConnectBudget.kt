package com.openfit.health

import android.content.Context
import android.os.SystemClock
import java.time.LocalDate
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Marks reads nobody is waiting for (a prefetch, the changes feed): they leave a reserve of the budget for what the user
 * opens. Reads without it are the user's.
 */
class HcPriority private constructor(val background: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HcPriority> {
        val Background = HcPriority(background = true)
    }
}

/** Thrown before a call that the budget would not cover; its message mentions the quota, as Health Connect's does. */
class QuotaGuardException(message: String) : Exception(message)

/** How openFit has used Health Connect, for the profile page. */
data class BudgetUsage(
    val callsToday: Int,
    val callsLast15Min: Int,
    val available15Min: Int,
    val available24H: Int,
    val savedReadsToday: Int,
    val deniedToday: Int,
    val lastCall: Pair<String, Long>?,
    val lastLimitedAt: Long?,
)

/**
 * A model of Health Connect's read quota for openFit, so openFit stops before Health Connect does. Health Connect gives
 * each app token buckets that refill continuously: 2,000 reads per 15 minutes and 16,000 per 24 hours in the foreground
 * (android.health.connect.ratelimiter.RateLimiter; every API call costs one, however many records it returns). The day
 * bucket refills at only ~11 calls a minute, so once it is spent openFit is locked out for a long time.
 *
 * Every call to Health Connect goes through [call]: it refills the buckets, refuses the call when they would run dry
 * (background reads keep [RESERVE] of each for the user), spends one, and counts it. A rate-limit error from Health
 * Connect itself (its model can be ahead of ours, say after a reinstall) empties both buckets.
 */
class HealthConnectBudget(context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    private val prefs = context.getSharedPreferences("hc_budget", Context.MODE_PRIVATE)

    private class Bucket(val name: String, val capacity: Int, val windowMs: Long)

    suspend fun <T> call(kind: String, block: suspend () -> T): T {
        val background = coroutineContext[HcPriority]?.background == true
        synchronized(this) {
            val now = clock()
            val levels = BUCKETS.map { it to level(it, now) }
            val short = levels.firstOrNull { (bucket, level) -> level < 1 + if (background) bucket.capacity * RESERVE else 0.0 }
            if (short != null) {
                count(KEY_DENIED)
                throw QuotaGuardException("Health Connect read quota nearly used (${short.first.name}); waiting for it to refill")
            }
            val editor = prefs.edit()
            levels.forEach { (bucket, level) ->
                editor.putFloat("level:${bucket.name}", (level - 1).toFloat()).putLong("at:${bucket.name}", now)
            }
            editor.putString(KEY_LAST_CALL, "$kind|$now").putInt(dayKey(KEY_CALLS), prefs.getInt(dayKey(KEY_CALLS), 0) + 1)
            // Calls of the last 15 minutes, as a ring of per-minute counts.
            val minute = now / 60_000
            val slot = "minute:${minute % RECENT_MINUTES}"
            val stale = prefs.getLong("$slot:at", -1) != minute
            editor.putLong("$slot:at", minute).putInt(slot, if (stale) 1 else prefs.getInt(slot, 0) + 1)
            editor.apply()
        }
        return try {
            block()
        } catch (error: Exception) {
            if (error !is QuotaGuardException && error.message?.contains("quota", ignoreCase = true) == true) limited()
            throw error
        }
    }

    /** A read served from what openFit saved, without calling Health Connect. */
    fun savedRead() = count(KEY_SAVED)

    /** Whether a background read would be allowed now. */
    fun hasRoom(background: Boolean = true): Boolean = synchronized(this) {
        val now = clock()
        BUCKETS.all { level(it, now) >= 1 + if (background) it.capacity * RESERVE else 0.0 }
    }

    fun usage(): BudgetUsage = synchronized(this) {
        val now = clock()
        val minute = now / 60_000
        val recent = (0 until RECENT_MINUTES).sumOf { offset ->
            val m = minute - offset
            val slot = "minute:${m % RECENT_MINUTES}"
            if (prefs.getLong("$slot:at", -1) == m) prefs.getInt(slot, 0) else 0
        }
        BudgetUsage(
            callsToday = prefs.getInt(dayKey(KEY_CALLS), 0),
            callsLast15Min = recent,
            available15Min = level(BUCKETS[0], now).toInt(),
            available24H = level(BUCKETS[1], now).toInt(),
            savedReadsToday = prefs.getInt(dayKey(KEY_SAVED), 0),
            deniedToday = prefs.getInt(dayKey(KEY_DENIED), 0),
            lastCall = prefs.getString(KEY_LAST_CALL, null)?.split('|')?.let { (kind, at) -> kind to at.toLong() },
            lastLimitedAt = prefs.getLong(KEY_LIMITED, 0).takeIf { it > 0 },
        )
    }

    private fun limited() = synchronized(this) {
        val now = clock()
        val editor = prefs.edit().putLong(KEY_LIMITED, now)
        BUCKETS.forEach { editor.putFloat("level:${it.name}", 0f).putLong("at:${it.name}", now) }
        editor.apply()
    }

    /**
     * The bucket's level now: what was left, plus what refilled since, up to its capacity. Health Connect keeps its
     * buckets in memory, so a restart of the phone gives every app full ones: then so does this model.
     */
    private fun level(bucket: Bucket, now: Long): Double {
        val boot = now - SystemClock.elapsedRealtime()
        if (kotlin.math.abs(boot - prefs.getLong(KEY_BOOT, boot)) > BOOT_SLACK_MS) {
            val editor = prefs.edit().putLong(KEY_BOOT, boot)
            BUCKETS.forEach { editor.remove("at:${it.name}").remove("level:${it.name}") }
            editor.apply()
        } else if (!prefs.contains(KEY_BOOT)) {
            prefs.edit().putLong(KEY_BOOT, boot).apply()
        }
        val at = prefs.getLong("at:${bucket.name}", -1)
        if (at < 0) return bucket.capacity.toDouble()
        val left = prefs.getFloat("level:${bucket.name}", bucket.capacity.toFloat()).toDouble()
        val refilled = (now - at).coerceAtLeast(0) * bucket.capacity / bucket.windowMs.toDouble()
        return minOf(bucket.capacity.toDouble(), left + refilled)
    }

    private fun count(key: String) {
        prefs.edit().putInt(dayKey(key), prefs.getInt(dayKey(key), 0) + 1).apply()
    }

    private fun dayKey(key: String) = "$key:${LocalDate.now()}"

    private companion object {
        val BUCKETS = listOf(
            Bucket("15 min", capacity = 2_000, windowMs = 15 * 60_000L),
            Bucket("24 h", capacity = 16_000, windowMs = 24 * 3_600_000L),
        )
        /** The share of each bucket that background reads leave for the user. */
        const val RESERVE = 0.25
        const val RECENT_MINUTES = 15
        const val KEY_CALLS = "calls"
        const val KEY_SAVED = "saved"
        const val KEY_DENIED = "denied"
        const val KEY_LAST_CALL = "last_call"
        const val KEY_LIMITED = "limited_at"
        const val KEY_BOOT = "boot_at"
        /** Clock adjustments move the computed boot time a little; a restart moves it by much more. */
        const val BOOT_SLACK_MS = 60_000L
    }
}
