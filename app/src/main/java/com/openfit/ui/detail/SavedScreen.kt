package com.openfit.ui.detail

import com.openfit.R
import com.openfit.data.SnapshotStore
import com.openfit.ui.Notices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.time.LocalDate

/**
 * Saved-data-first for a detail screen: the state last shown for a period opens at once from Room, Health Connect
 * replaces it, and a failed reload keeps it on screen with a toast instead of an error page.
 */
class SavedScreen<R : Any>(
    private val store: SnapshotStore,
    private val kind: String,
    private val serializer: KSerializer<R>,
    /** Bump when the screen's state changes shape or meaning. */
    private val version: Int = 1,
) {
    suspend fun restore(name: String): R? = withContext(Dispatchers.IO) { store.get(kind, name, version, serializer)?.value }

    suspend fun save(name: String, date: LocalDate, value: R) {
        withContext(Dispatchers.IO) { store.put(kind, name, date, version, serializer, value, profileKey = "") }
    }
}

/** Whether Health Connect refused a read for going over its rate limit. */
fun isRateLimited(error: Throwable): Boolean = error.message?.contains("quota", ignoreCase = true) == true

/**
 * After a failed reload: true when something is on screen to keep (the saved state, or what was loaded before), and
 * then a toast says the data is saved; false means the screen should show the error.
 */
fun keepAfterFailure(error: Throwable, showing: Boolean): Boolean {
    if (!showing) return false
    Notices.post(if (isRateLimited(error)) R.string.hc_busy_saved else R.string.hc_failed_saved)
    return true
}
