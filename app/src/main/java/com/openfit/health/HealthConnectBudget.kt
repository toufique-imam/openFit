package com.openfit.health

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
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

/** Names the sync a Health Connect call belongs to ("import", "recent"): every call should carry one. */
class HcSource(val name: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HcSource>
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
class HealthConnectBudget internal constructor(
    private val prefs: SharedPreferences,
    private val debuggable: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, clock: () -> Long = System::currentTimeMillis) : this(
        context.getSharedPreferences("hc_budget", Context.MODE_PRIVATE),
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        clock,
    )

    private class Bucket(val name: String, val capacity: Int, val windowMs: Long)

    suspend fun <T> call(kind: String, block: suspend () -> T): T {
        val background = coroutineContext[HcPriority]?.background == true
        // Who asked: the sync's tag, or (a bug: pages must not read Health Connect) the first openFit frame.
        val source = if (debuggable) coroutineContext[HcSource]?.name ?: "UNTAGGED ${caller()}" else ""
        synchronized(this) {
            val now = clock()
            pruneCounters()
            val levels = BUCKETS.map { it to level(it, now) }
            val short = levels.firstOrNull { (bucket, level) -> level < 1 + if (background) bucket.capacity * RESERVE else 0.0 }
            if (short != null) {
                if (debuggable) Log.d(TAG, "$kind <- $source held back (${short.first.name})")
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
        if (debuggable) Log.d(TAG, "$kind <- $source${if (background) " (background)" else ""}")
        return try {
            block()
        } catch (error: Exception) {
            if (error !is QuotaGuardException && error.message?.contains("quota", ignoreCase = true) == true) limited()
            throw error
        }
    }

    /** Debug builds: what a call asked and got, logged after it (`adb logcat -s openFit.hc`). */
    fun note(message: () -> String) {
        if (debuggable) Log.d(TAG, "    ${message()}")
    }

    /** A read served from what openFit saved, without calling Health Connect. */
    fun savedRead() = count(KEY_SAVED)

    /**
     * How long until [calls] reads fit in every bucket (above the reserve, for [background] reads), at the rate Health
     * Connect refills them; zero when they fit now.
     */
    fun waitFor(calls: Int, background: Boolean): java.time.Duration = synchronized(this) {
        require(calls >= 0 && BUCKETS.all { calls <= it.capacity * if (background) 1 - RESERVE else 1.0 }) {
            "Requested batch cannot fit in the Health Connect budget"
        }
        val now = clock()
        val millis = BUCKETS.maxOf { bucket ->
            val short = calls + (if (background) bucket.capacity * RESERVE else 0.0) - level(bucket, now)
            if (short <= 0) 0L else ceil(short * bucket.windowMs / bucket.capacity).toLong()
        }
        java.time.Duration.ofMillis(millis)
    }

    /** Whether a background read would be allowed now. */
    fun hasRoom(background: Boolean = true): Boolean = synchronized(this) {
        val now = clock()
        BUCKETS.all { level(it, now) >= 1 + if (background) it.capacity * RESERVE else 0.0 }
    }

    fun usage(): BudgetUsage = synchronized(this) {
        pruneCounters()
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
     * The bucket's level now: what was left, plus what refilled since, up to its capacity. It survives a restart of the
     * phone, as Health Connect's does (seen on Android 13, 16 Oct 2026).
     */
    private fun level(bucket: Bucket, now: Long): Double {
        val at = prefs.getLong("at:${bucket.name}", -1)
        if (at < 0) return bucket.capacity.toDouble()
        val left = prefs.getFloat("level:${bucket.name}", bucket.capacity.toFloat()).toDouble()
        val refilled = (now - at).coerceAtLeast(0) * bucket.capacity / bucket.windowMs.toDouble()
        return minOf(bucket.capacity.toDouble(), left + refilled)
    }

    /** The first openFit frame outside the store and the budget: who caused this call. */
    private fun caller(): String = Throwable().stackTrace
        .firstOrNull { frame -> frame.className.startsWith("com.openfit") && INTERNAL.none { frame.className.startsWith(it) } }
        ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"

    private fun count(key: String) = synchronized(this) {
        pruneCounters()
        prefs.edit().putInt(dayKey(key), prefs.getInt(dayKey(key), 0) + 1).apply()
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun dayKey(key: String) = "$key:${today()}"

    /** Keep one week of display counters; token levels and the recent-call ring are never pruned. */
    private fun pruneCounters() {
        val today = today()
        if (prefs.getString(KEY_PRUNED, null) == today.toString()) return
        val oldest = today.minusDays(COUNTER_DAYS - 1).toString()
        val editor = prefs.edit().putString(KEY_PRUNED, today.toString())
        prefs.all.keys.filter { key ->
            val prefix = key.substringBefore(':')
            prefix in setOf(KEY_CALLS, KEY_SAVED, KEY_DENIED) && key.substringAfter(':') < oldest
        }.forEach(editor::remove)
        editor.apply()
    }

    private companion object {
        /**
         * Health Connect's background buckets: openFit is not always in the foreground when it reads (the import goes
         * on when the user switches app), and Health Connect then counts against the smaller ones. Seen on Android 13,
         * 17 Oct 2026: limited after 1,044 reads in a few minutes.
         */
        val BUCKETS = listOf(
            Bucket("15 min", capacity = 1_000, windowMs = 15 * 60_000L),
            Bucket("24 h", capacity = 8_000, windowMs = 24 * 3_600_000L),
        )
        /** The share of each bucket that background reads leave for the user. */
        const val RESERVE = 0.25
        const val TAG = "openFit.hc"
        val INTERNAL = listOf("com.openfit.health.HealthStore", "com.openfit.health.HealthConnectBudget")
        const val RECENT_MINUTES = 15
        const val KEY_CALLS = "calls"
        const val KEY_SAVED = "saved"
        const val KEY_DENIED = "denied"
        const val KEY_LAST_CALL = "last_call"
        const val KEY_LIMITED = "limited_at"
        const val KEY_PRUNED = "counters_pruned_on"
        const val COUNTER_DAYS = 7L
    }
}
