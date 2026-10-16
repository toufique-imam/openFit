package com.openfit.ui.goals

import androidx.annotation.StringRes
import com.openfit.R
import com.openfit.profile.Profile

/** Fitbit's goal categories, in the order its Goals list and You tab use (Sleep, Activity, Health, Nutrition). */
enum class GoalCategory(@StringRes val title: Int) {
    Sleep(R.string.you_goals_sleep),
    Activity(R.string.you_goals_activity),
    Health(R.string.you_goals_health),
    Nutrition(R.string.you_goals_nutrition),
}

/**
 * Every goal the user can set, in Fitbit's Goals-list order. None has a built-in value: an unset goal shows "Set goal".
 * [listed] is false for [SleepSchedule], which only the Sleep screen uses to set bedtime and wake time together.
 */
enum class Goal(val category: GoalCategory, @StringRes val title: Int, val listed: Boolean = true) {
    Bedtime(GoalCategory.Sleep, R.string.goal_bedtime),
    WakeTime(GoalCategory.Sleep, R.string.goal_wake_time),
    SleepDuration(GoalCategory.Sleep, R.string.goal_sleep_duration),
    SleepSchedule(GoalCategory.Sleep, R.string.goal_sleep_schedule, listed = false),
    Steps(GoalCategory.Activity, R.string.tile_steps),
    Distance(GoalCategory.Activity, R.string.tile_distance),
    Energy(GoalCategory.Activity, R.string.tile_energy_burned),
    Floors(GoalCategory.Activity, R.string.tile_floors),

    /** Follows from the fitness goal (the weekly goal spread over seven days); editing it edits the fitness goal. */
    ZoneMinutes(GoalCategory.Activity, R.string.azm_title),
    ExerciseDays(GoalCategory.Activity, R.string.tile_exercise_days),
    HourlyActivity(GoalCategory.Activity, R.string.tile_hourly_activity),

    /** Maintain or improve: drives both the Cardio Load target and the weekly Active Zone Minutes goal. */
    Fitness(GoalCategory.Activity, R.string.goal_fitness),
    Weight(GoalCategory.Health, R.string.tile_weight),
    Food(GoalCategory.Nutrition, R.string.tile_food),
    Water(GoalCategory.Nutrition, R.string.tile_water),
    ;

    fun isSet(profile: Profile): Boolean = when (this) {
        Bedtime -> profile.bedtime != null
        WakeTime -> profile.wakeTime != null
        SleepSchedule -> profile.bedtime != null && profile.wakeTime != null
        SleepDuration -> profile.sleepGoalMinutes != null
        Steps -> profile.dailyStepGoal != null
        Distance -> profile.dailyDistanceGoalMeters != null
        Energy -> profile.dailyCaloriesOutGoal != null
        Floors -> profile.dailyFloorsGoal != null
        ZoneMinutes, Fitness -> true
        ExerciseDays -> profile.weeklyExerciseDaysGoal != null
        HourlyActivity -> profile.activeHours != null
        Weight -> profile.weightGoalKg != null
        Food -> profile.dailyCaloriesInGoal != null
        Water -> profile.dailyWaterGoalLiters != null
    }

    /** Whether "Reset goal" applies: the fitness goal and the zone-minutes goal derived from it always have a value. */
    val resettable: Boolean get() = this != Fitness && this != ZoneMinutes

    /** The profile without this goal (Fitbit's "Reset goal"). */
    fun reset(profile: Profile): Profile = when (this) {
        Bedtime -> profile.copy(bedtime = null)
        WakeTime -> profile.copy(wakeTime = null)
        SleepSchedule -> profile.copy(bedtime = null, wakeTime = null)
        SleepDuration -> profile.copy(sleepGoalMinutes = null)
        Steps -> profile.copy(dailyStepGoal = null)
        Distance -> profile.copy(dailyDistanceGoalMeters = null)
        Energy -> profile.copy(dailyCaloriesOutGoal = null)
        Floors -> profile.copy(dailyFloorsGoal = null)
        ZoneMinutes, Fitness -> profile
        ExerciseDays -> profile.copy(weeklyExerciseDaysGoal = null)
        HourlyActivity -> profile.copy(activeHours = null)
        Weight -> profile.copy(weightGoalKg = null)
        Food -> profile.copy(dailyCaloriesInGoal = null)
        Water -> profile.copy(dailyWaterGoalLiters = null)
    }
}

/** How many listed goals of a category are set: Fitbit's You tab shows "3 goals" and hides categories with none. */
fun setGoals(profile: Profile, category: GoalCategory): Int =
    Goal.entries.count { it.listed && it.category == category && it.isSet(profile) }
