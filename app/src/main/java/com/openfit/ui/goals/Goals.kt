package com.openfit.ui.goals

import androidx.annotation.StringRes
import com.openfit.R
import com.openfit.profile.Profile

/** Fitbit's goal categories, in the order its Goals screen lists them. */
enum class GoalCategory(@StringRes val title: Int) {
    Sleep(R.string.you_goals_sleep),
    Activity(R.string.you_goals_activity),
    Nutrition(R.string.you_goals_nutrition),
    Health(R.string.you_goals_health),
}

/** Every goal the user can set. None has a built-in value: an unset goal shows "Set goal" instead. */
enum class Goal(val category: GoalCategory, @StringRes val title: Int) {
    SleepSchedule(GoalCategory.Sleep, R.string.goal_sleep_schedule),
    SleepDuration(GoalCategory.Sleep, R.string.goal_sleep_duration),
    Steps(GoalCategory.Activity, R.string.tile_steps),
    Distance(GoalCategory.Activity, R.string.tile_distance),
    Energy(GoalCategory.Activity, R.string.tile_energy_burned),
    Floors(GoalCategory.Activity, R.string.tile_floors),
    ExerciseDays(GoalCategory.Activity, R.string.tile_exercise_days),
    HourlyActivity(GoalCategory.Activity, R.string.tile_hourly_activity),

    /** Maintain or improve: drives both the Cardio Load target and the weekly Active Zone Minutes goal. */
    Fitness(GoalCategory.Activity, R.string.goal_fitness),
    Food(GoalCategory.Nutrition, R.string.goal_food),
    Water(GoalCategory.Nutrition, R.string.tile_water),
    Weight(GoalCategory.Health, R.string.tile_weight),
    ;

    fun isSet(profile: Profile): Boolean = when (this) {
        SleepSchedule -> profile.bedtime != null && profile.wakeTime != null
        SleepDuration -> profile.sleepGoalMinutes != null
        Steps -> profile.dailyStepGoal != null
        Distance -> profile.dailyDistanceGoalMeters != null
        Energy -> profile.dailyCaloriesOutGoal != null
        Floors -> profile.dailyFloorsGoal != null
        ExerciseDays -> profile.weeklyExerciseDaysGoal != null
        HourlyActivity -> profile.activeHours != null
        Fitness -> true
        Food -> profile.dailyCaloriesInGoal != null
        Water -> profile.dailyWaterGoalLiters != null
        Weight -> profile.weightGoalKg != null
    }

    /** The profile without this goal (Fitbit's "Reset goal"). The fitness goal always has a value. */
    fun reset(profile: Profile): Profile = when (this) {
        SleepSchedule -> profile.copy(bedtime = null, wakeTime = null)
        SleepDuration -> profile.copy(sleepGoalMinutes = null)
        Steps -> profile.copy(dailyStepGoal = null)
        Distance -> profile.copy(dailyDistanceGoalMeters = null)
        Energy -> profile.copy(dailyCaloriesOutGoal = null)
        Floors -> profile.copy(dailyFloorsGoal = null)
        ExerciseDays -> profile.copy(weeklyExerciseDaysGoal = null)
        HourlyActivity -> profile.copy(activeHours = null)
        Fitness -> profile
        Food -> profile.copy(dailyCaloriesInGoal = null)
        Water -> profile.copy(dailyWaterGoalLiters = null)
        Weight -> profile.copy(weightGoalKg = null)
    }
}

/** How many goals of a category are set, for the You tab's "3 active". */
fun activeGoals(profile: Profile, category: GoalCategory): Int = Goal.entries.count { it.category == category && it.isSet(profile) }
