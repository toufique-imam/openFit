package com.openfit.health

/** Fill gaps in Health Connect from the cloud without counting the same observation twice. */
internal fun preferredRecords(type: String, own: List<SavedRecord>, cloud: List<SavedRecord>): List<SavedRecord> {
    if (own.isEmpty()) return cloud
    if (cloud.isEmpty()) return own
    if (type in DAILY_READINGS) return own
    if (type == "heart_rate" || type == "skin_temperature") {
        // Cloud heart rate is a minute average; a raw watch sample in that minute takes precedence.
        val occupied = own.flatMap { it.times?.asIterable() ?: emptyList() }.mapTo(HashSet()) { it / 60_000 }
        return own + cloud.mapNotNull { record ->
            val times = record.times ?: return@mapNotNull null
            val kept = times.indices.filter { times[it] / 60_000 !in occupied }
            if (kept.isEmpty()) null else record.copy(
                start = maxOf(record.start, times[kept.first()]),
                times = kept.map { times[it] }.toLongArray(),
                values = kept.map { record.values!![it] }.toDoubleArray(),
            )
        }
    }
    return own + cloud.filter { candidate ->
        own.none { record ->
            val end = record.end ?: record.start
            val candidateEnd = candidate.end ?: candidate.start
            if (end == record.start || candidateEnd == candidate.start) record.start == candidate.start
            else record.start < candidateEnd && candidate.start < end
        }
    }
}

// These cloud measurements summarize a whole day; Health Connect's daily readings replace that summary.
private val DAILY_READINGS = setOf("resting_heart_rate", "hrv", "spo2", "breathing_rate", "vo2_max")
