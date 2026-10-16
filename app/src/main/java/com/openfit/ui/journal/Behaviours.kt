package com.openfit.ui.journal

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openfit.R
import com.openfit.journal.JournalStore

/** Where a behaviour sits in the day's check-in. */
enum class JournalSection(@StringRes val title: Int) {
    Daytime(R.string.journal_section_daytime),
    Nighttime(R.string.journal_section_nighttime),
    Status(R.string.journal_section_status),
    Custom(R.string.journal_section_custom),
}

/** A behaviour the journal can ask about: one of openFit's, or the user's own ([customLabel]). */
data class Behaviour(
    val key: String,
    val section: JournalSection,
    @StringRes val label: Int = 0,
    @StringRes val question: Int = 0,
    val customLabel: String? = null,
)

@Composable
fun Behaviour.labelText(): String = customLabel ?: stringResource(label)

@Composable
fun Behaviour.questionText(): String = customLabel?.let { "$it?" } ?: stringResource(question)

/** openFit's behaviours. The keys are stored with each answer, so never rename one. */
val BEHAVIOURS: List<Behaviour> = listOf(
    Behaviour("caffeine", JournalSection.Daytime, R.string.behaviour_caffeine, R.string.behaviour_caffeine_q),
    Behaviour("late_caffeine", JournalSection.Daytime, R.string.behaviour_late_caffeine, R.string.behaviour_late_caffeine_q),
    Behaviour("added_sugar", JournalSection.Daytime, R.string.behaviour_added_sugar, R.string.behaviour_added_sugar_q),
    Behaviour("fasting", JournalSection.Daytime, R.string.behaviour_fasting, R.string.behaviour_fasting_q),
    Behaviour("meditation", JournalSection.Daytime, R.string.behaviour_meditation, R.string.behaviour_meditation_q),
    Behaviour("stretching", JournalSection.Daytime, R.string.behaviour_stretching, R.string.behaviour_stretching_q),
    Behaviour("sauna", JournalSection.Daytime, R.string.behaviour_sauna, R.string.behaviour_sauna_q),
    Behaviour("cold_exposure", JournalSection.Daytime, R.string.behaviour_cold_exposure, R.string.behaviour_cold_exposure_q),
    Behaviour("nap", JournalSection.Daytime, R.string.behaviour_nap, R.string.behaviour_nap_q),
    Behaviour("outdoor_time", JournalSection.Daytime, R.string.behaviour_outdoor_time, R.string.behaviour_outdoor_time_q),
    Behaviour("family_friends", JournalSection.Daytime, R.string.behaviour_family_friends, R.string.behaviour_family_friends_q),
    Behaviour("alcohol", JournalSection.Nighttime, R.string.behaviour_alcohol, R.string.behaviour_alcohol_q),
    Behaviour("late_meal", JournalSection.Nighttime, R.string.behaviour_late_meal, R.string.behaviour_late_meal_q),
    Behaviour("screen_in_bed", JournalSection.Nighttime, R.string.behaviour_screen_in_bed, R.string.behaviour_screen_in_bed_q),
    Behaviour("reading", JournalSection.Nighttime, R.string.behaviour_reading, R.string.behaviour_reading_q),
    Behaviour("melatonin", JournalSection.Nighttime, R.string.behaviour_melatonin, R.string.behaviour_melatonin_q),
    Behaviour("magnesium", JournalSection.Nighttime, R.string.behaviour_magnesium, R.string.behaviour_magnesium_q),
    Behaviour("sleep_aid", JournalSection.Nighttime, R.string.behaviour_sleep_aid, R.string.behaviour_sleep_aid_q),
    Behaviour("night_shift", JournalSection.Nighttime, R.string.behaviour_night_shift, R.string.behaviour_night_shift_q),
    Behaviour("illness", JournalSection.Status, R.string.behaviour_illness, R.string.behaviour_illness_q),
    Behaviour("stress", JournalSection.Status, R.string.behaviour_stress, R.string.behaviour_stress_q),
    Behaviour("anxiety", JournalSection.Status, R.string.behaviour_anxiety, R.string.behaviour_anxiety_q),
    Behaviour("headache", JournalSection.Status, R.string.behaviour_headache, R.string.behaviour_headache_q),
    Behaviour("injury", JournalSection.Status, R.string.behaviour_injury, R.string.behaviour_injury_q),
    Behaviour("travel", JournalSection.Status, R.string.behaviour_travel, R.string.behaviour_travel_q),
    Behaviour("menstruating", JournalSection.Status, R.string.behaviour_menstruating, R.string.behaviour_menstruating_q),
)

/** openFit's behaviours followed by the user's own. */
fun allBehaviours(customLabels: List<String>): List<Behaviour> =
    BEHAVIOURS + customLabels.map { Behaviour(JournalStore.customKey(it), JournalSection.Custom, customLabel = it) }
