package com.openfit.profile

import android.content.Context
import com.openfit.domain.FitnessGoal
import com.openfit.domain.Sex
import java.util.Locale

enum class UnitSystem { Metric, Imperial }

enum class WeightUnit { Kilograms, Pounds }

data class Profile(
    val name: String = "",
    val age: Int? = null,
    val sex: Sex? = null,
    val units: UnitSystem = UnitSystem.Metric,
    val weightUnit: WeightUnit = WeightUnit.Kilograms,
    val dailyStepGoal: Int = 10_000,
    val fitnessGoal: FitnessGoal = FitnessGoal.Maintain,
    val sleepGoalMinutes: Int = 8 * 60,
    val weeklyExerciseDaysGoal: Int = 5,
    val dailyDistanceGoalMeters: Double = 8_047.0,
    val dailyFloorsGoal: Int = 10,
    val dailyCaloriesOutGoal: Int = 2_500,
    val dailyCaloriesInGoal: Int = 2_000,
    val dailyWaterGoalLiters: Double = 1.9,
)

class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load(): Profile {
        val imperial = Locale.getDefault().country in IMPERIAL_COUNTRIES
        val defaults = Profile(
            units = if (imperial) UnitSystem.Imperial else UnitSystem.Metric,
            weightUnit = if (imperial) WeightUnit.Pounds else WeightUnit.Kilograms,
        )
        return Profile(
            name = prefs.getString(KEY_NAME, null).orEmpty(),
            age = prefs.getInt(KEY_AGE, 0).takeIf { it > 0 },
            sex = prefs.getString(KEY_SEX, null)?.let(Sex::valueOf),
            units = prefs.getString(KEY_UNITS, null)?.let(UnitSystem::valueOf) ?: defaults.units,
            weightUnit = prefs.getString(KEY_WEIGHT_UNIT, null)?.let(WeightUnit::valueOf) ?: defaults.weightUnit,
            dailyStepGoal = prefs.getInt(KEY_STEP_GOAL, defaults.dailyStepGoal),
            fitnessGoal = prefs.getString(KEY_FITNESS_GOAL, null)?.let(FitnessGoal::valueOf) ?: defaults.fitnessGoal,
            sleepGoalMinutes = prefs.getInt(KEY_SLEEP_GOAL, defaults.sleepGoalMinutes),
            weeklyExerciseDaysGoal = prefs.getInt(KEY_EXERCISE_DAYS_GOAL, defaults.weeklyExerciseDaysGoal),
        )
    }

    fun save(profile: Profile) = prefs.edit()
        .putString(KEY_NAME, profile.name)
        .putInt(KEY_AGE, profile.age ?: 0)
        .putString(KEY_SEX, profile.sex?.name)
        .putString(KEY_UNITS, profile.units.name)
        .putString(KEY_WEIGHT_UNIT, profile.weightUnit.name)
        .putInt(KEY_STEP_GOAL, profile.dailyStepGoal)
        .putString(KEY_FITNESS_GOAL, profile.fitnessGoal.name)
        .putInt(KEY_SLEEP_GOAL, profile.sleepGoalMinutes)
        .putInt(KEY_EXERCISE_DAYS_GOAL, profile.weeklyExerciseDaysGoal)
        .apply()

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_AGE = "age"
        const val KEY_SEX = "sex"
        const val KEY_UNITS = "units"
        const val KEY_WEIGHT_UNIT = "weight_unit"
        const val KEY_STEP_GOAL = "daily_step_goal"
        const val KEY_FITNESS_GOAL = "fitness_goal"
        const val KEY_SLEEP_GOAL = "sleep_goal_minutes"
        const val KEY_EXERCISE_DAYS_GOAL = "weekly_exercise_days_goal"

        val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
    }
}
