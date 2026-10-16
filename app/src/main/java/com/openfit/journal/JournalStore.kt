package com.openfit.journal

import android.content.Context
import java.time.LocalDate

/**
 * The journal on the phone: which behaviours it asks, the user's own behaviours, and each day's yes/no answers.
 * Nothing is asked until the user chooses; an unanswered behaviour is simply absent from a day.
 */
class JournalStore(context: Context) {

    private val prefs = context.getSharedPreferences("journal", Context.MODE_PRIVATE)

    fun asked(): List<String> = prefs.getString(KEY_ASKED, null)?.split(LIST_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    fun setAsked(keys: List<String>) {
        prefs.edit().putString(KEY_ASKED, keys.joinToString(LIST_SEPARATOR)).apply()
    }

    /** The user's own behaviours, by label; their keys are [customKey] of the label. */
    fun customLabels(): List<String> = prefs.getString(KEY_CUSTOM, null)?.split(LIST_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    /** Adds a behaviour of the user's own and asks it from now on; returns its key, or null if the label is unusable or taken. */
    fun addCustom(label: String): String? {
        val clean = label.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().take(MAX_LABEL_LENGTH)
        if (clean.isEmpty()) return null
        val key = customKey(clean)
        if (customLabels().any { customKey(it) == key }) return null
        prefs.edit().putString(KEY_CUSTOM, (customLabels() + clean).joinToString(LIST_SEPARATOR)).apply()
        setAsked(asked() + key)
        return key
    }

    fun answers(day: LocalDate): Map<String, Boolean> = parse(prefs.getString(dayKey(day), null))

    /** Sets or, with null, clears one answer for a day. */
    fun setAnswer(day: LocalDate, key: String, value: Boolean?) {
        val updated = answers(day).toMutableMap()
        if (value == null) updated.remove(key) else updated[key] = value
        prefs.edit().apply {
            if (updated.isEmpty()) remove(dayKey(day)) else putString(dayKey(day), format(updated))
        }.apply()
    }

    /** Every day's answers between [from] and [until], inclusive. */
    fun answersBetween(from: LocalDate, until: LocalDate): Map<LocalDate, Map<String, Boolean>> =
        prefs.all.mapNotNull { (key, value) ->
            val day = key.removePrefix(DAY_PREFIX).takeIf { key.startsWith(DAY_PREFIX) }?.let(LocalDate::parse) ?: return@mapNotNull null
            if (day < from || day > until) null else day to parse(value as? String)
        }.toMap()

    private fun dayKey(day: LocalDate) = "$DAY_PREFIX$day"

    private fun parse(entry: String?): Map<String, Boolean> =
        entry?.split(LIST_SEPARATOR)?.mapNotNull { part ->
            val (key, value) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            key to (value == "1")
        }?.toMap().orEmpty()

    private fun format(answers: Map<String, Boolean>): String = answers.entries.joinToString(LIST_SEPARATOR) { "${it.key}=${if (it.value) 1 else 0}" }

    companion object {
        private const val KEY_ASKED = "asked"
        private const val KEY_CUSTOM = "custom"
        private const val DAY_PREFIX = "day:"
        private const val LIST_SEPARATOR = ","
        const val MAX_LABEL_LENGTH = 32

        fun customKey(label: String): String = "custom_" + label.lowercase().replace(' ', '_')
    }
}
