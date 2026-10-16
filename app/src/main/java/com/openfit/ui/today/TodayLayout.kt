package com.openfit.ui.today

import android.content.Context
import androidx.annotation.StringRes
import com.openfit.R
import com.openfit.ui.MetricPage
import com.openfit.ui.RootTab

/** Today's sections, in Fitbit's default order. Each can also be a bottom-bar tab. */
enum class TodaySection(@StringRes val title: Int) {
    Recovery(R.string.section_recovery),
    Activity(R.string.section_activity),
    Health(R.string.section_health),
    StressMindfulness(R.string.section_stress_mindfulness),
    Nutrition(R.string.section_nutrition),
}

/** A card on Today; [focusLabel] names it in the focus area and its picker (Fitbit's "Zone Min", "Sleep"). */
enum class TodayTile(val section: TodaySection, @StringRes val label: Int, @StringRes val focusLabel: Int, val page: MetricPage?) {
    SleepDuration(TodaySection.Recovery, R.string.tile_sleep_duration, R.string.focus_sleep, MetricPage.Sleep),
    Readiness(TodaySection.Recovery, R.string.readiness, R.string.focus_readiness, MetricPage.Readiness),
    EnergyBank(TodaySection.Recovery, R.string.energy_title, R.string.energy_title, MetricPage.EnergyBank),
    CardioLoad(TodaySection.Activity, R.string.cardio_load, R.string.cardio_load, MetricPage.CardioLoad),
    ExerciseDays(TodaySection.Activity, R.string.tile_exercise_days, R.string.tile_exercise_days, MetricPage.Exercise),
    ZoneMinutes(TodaySection.Activity, R.string.tile_zone_minutes, R.string.focus_zone_min_short, MetricPage.ZoneMinutes),
    Steps(TodaySection.Activity, R.string.tile_steps, R.string.tile_steps, MetricPage.Steps),
    HourlyActivity(TodaySection.Activity, R.string.tile_hourly_activity, R.string.tile_hourly_activity, MetricPage.Hourly),
    Distance(TodaySection.Activity, R.string.tile_distance, R.string.tile_distance, MetricPage.Distance),
    EnergyBurned(TodaySection.Activity, R.string.tile_energy_burned, R.string.tile_energy_burned, MetricPage.Energy),
    Floors(TodaySection.Activity, R.string.tile_floors, R.string.tile_floors, MetricPage.Floors),
    HealthMetrics(TodaySection.Health, R.string.hm_title, R.string.hm_title, MetricPage.HealthMetrics),
    Heart(TodaySection.Health, R.string.tile_heart, R.string.tile_heart, MetricPage.Heart),
    Vo2Max(TodaySection.Health, R.string.vo2_title, R.string.vo2_title, MetricPage.Vo2Max),
    PulseAge(TodaySection.Health, R.string.age_title, R.string.age_title, MetricPage.PulseAge),
    Weight(TodaySection.Health, R.string.tile_weight, R.string.tile_weight, MetricPage.Weight),
    Stress(TodaySection.StressMindfulness, R.string.stress_title, R.string.stress_title, MetricPage.Stress),
    MindfulDays(TodaySection.StressMindfulness, R.string.tile_mindful_days, R.string.tile_mindful_days, null),
    Food(TodaySection.Nutrition, R.string.tile_food, R.string.tile_food, MetricPage.Food),
    Water(TodaySection.Nutrition, R.string.tile_water, R.string.tile_water, MetricPage.Water),
}

/** Fitbit's focus presets: the first metric large, the rest small beneath it. */
enum class FocusPreset(@StringRes val title: Int, val metrics: List<TodayTile>, val paired: TodayTile? = null) {
    /** Daily Readiness shares Cardio load's ring, as on Fitbit. */
    CardioFitness(R.string.focus_build_cardio, listOf(TodayTile.CardioLoad, TodayTile.ExerciseDays, TodayTile.SleepDuration, TodayTile.Steps), paired = TodayTile.Readiness),
    GetActive(R.string.focus_get_active, listOf(TodayTile.Steps, TodayTile.ZoneMinutes, TodayTile.Distance, TodayTile.EnergyBurned)),
    SleepBetter(R.string.focus_sleep_better, listOf(TodayTile.SleepDuration, TodayTile.Steps, TodayTile.ZoneMinutes, TodayTile.MindfulDays)),
    ManageWeight(R.string.focus_manage_weight, listOf(TodayTile.EnergyBurned, TodayTile.Steps, TodayTile.Food, TodayTile.Weight)),
    ManageStress(R.string.focus_manage_stress, listOf(TodayTile.Stress, TodayTile.MindfulDays, TodayTile.SleepDuration)),
    HeartHealth(R.string.focus_heart_health, listOf(TodayTile.Heart, TodayTile.HealthMetrics, TodayTile.ZoneMinutes, TodayTile.ExerciseDays)),
    Custom(R.string.focus_custom, emptyList()),
}

data class TodayLayout(
    val focus: FocusPreset = FocusPreset.CardioFitness,
    /** The custom focus: four slots, empty ones null. */
    val custom: List<TodayTile?> = List(CUSTOM_SLOTS) { null },
    val order: List<TodaySection> = TodaySection.entries,
    val hidden: Set<TodayTile> = emptySet(),
    /** Bottom-bar tabs besides Today and You (shown in [RootTab] order). */
    val tabs: Set<RootTab> = setOf(RootTab.Journal),
) {
    val focusMetrics: List<TodayTile> get() = if (focus == FocusPreset.Custom) custom.filterNotNull() else focus.metrics

    /** The bottom bar, left to right. */
    val bar: List<RootTab> get() = RootTab.entries.filter { !it.optional || it in tabs }

    fun moved(section: TodaySection, by: Int): TodayLayout {
        val from = order.indexOf(section)
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (from == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(from)) })
    }

    companion object {
        const val CUSTOM_SLOTS = 4
        /** Material's bottom bar holds five destinations: Today, You and three more. */
        const val MAX_EXTRA_TABS = 3
    }
}

class TodayLayoutStore(context: Context) {

    private val prefs = context.getSharedPreferences("today_layout", Context.MODE_PRIVATE)

    fun load(): TodayLayout {
        val default = TodayLayout()
        // Names from an older version that no longer exist are skipped.
        val order = names("order").mapNotNull { name -> TodaySection.entries.find { it.name == name } }
        return TodayLayout(
            focus = prefs.getString("focus", null)?.let { name -> FocusPreset.entries.find { it.name == name } } ?: default.focus,
            custom = prefs.getString("custom", null)?.split(',')?.map { name -> TodayTile.entries.find { it.name == name } }
                ?.let { slots -> List(TodayLayout.CUSTOM_SLOTS) { slots.getOrNull(it) } } ?: default.custom,
            // Sections added in a later version go last.
            order = if (order.isEmpty()) default.order else order + (TodaySection.entries - order.toSet()),
            hidden = names("hidden").mapNotNull { name -> TodayTile.entries.find { it.name == name } }.toSet(),
            tabs = if (prefs.contains("tabs")) names("tabs").mapNotNull { name -> RootTab.entries.find { it.name == name && it.optional } }.take(TodayLayout.MAX_EXTRA_TABS).toSet() else default.tabs,
        )
    }

    fun save(layout: TodayLayout) {
        prefs.edit()
            .putString("focus", layout.focus.name)
            .putString("custom", layout.custom.joinToString(",") { it?.name.orEmpty() })
            .putString("order", layout.order.joinToString(",") { it.name })
            .putString("hidden", layout.hidden.joinToString(",") { it.name })
            .putString("tabs", layout.tabs.joinToString(",") { it.name })
            .apply()
    }

    private fun names(key: String): List<String> = prefs.getString(key, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
}
