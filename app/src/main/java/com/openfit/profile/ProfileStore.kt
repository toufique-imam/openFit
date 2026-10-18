@file:UseSerializers(InstantSerializer::class, DurationSerializer::class, LocalDateSerializer::class, IntRangeSerializer::class, LocalTimeSerializer::class)

package com.openfit.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import com.openfit.domain.DurationSerializer
import com.openfit.domain.InstantSerializer
import com.openfit.domain.IntRangeSerializer
import com.openfit.domain.LocalDateSerializer
import com.openfit.domain.LocalTimeSerializer
import android.content.Context
import android.content.SharedPreferences
import com.openfit.domain.FitnessGoal
import com.openfit.domain.Sex
import com.openfit.domain.WeekStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.util.Locale

enum class UnitSystem { Metric, Imperial }

enum class WeightUnit { Kilograms, Pounds }

/**
 * The user's details and goals. Goals are null until the user sets them: openFit never invents a target, and screens
 * offer to set one instead.
 */
@Serializable
data class Profile(
    val name: String = "",
    /** Age in years: from [birthDate] when the user gave one (so it moves on with birthdays), else as entered. */
    val age: Int? = null,
    val birthDate: LocalDate? = null,
    val sex: Sex? = null,
    val units: UnitSystem = UnitSystem.Metric,
    val weightUnit: WeightUnit = WeightUnit.Kilograms,
    val fitnessGoal: FitnessGoal = FitnessGoal.Maintain,
    val dailyStepGoal: Int? = null,
    val dailyDistanceGoalMeters: Double? = null,
    val dailyFloorsGoal: Int? = null,
    val dailyCaloriesOutGoal: Int? = null,
    val weeklyExerciseDaysGoal: Int? = null,
    /** Hourly activity: the hours to move 250 steps in, first to last (9..17 is 9am–6pm). */
    val activeHours: IntRange? = null,
    val sleepGoalMinutes: Int? = null,
    val bedtime: LocalTime? = null,
    val wakeTime: LocalTime? = null,
    val weeklyMindfulDaysGoal: Int? = null,
    val dailyCaloriesInGoal: Int? = null,
    val dailyWaterGoalLiters: Double? = null,
    val weightGoalKg: Double? = null,
    /** The first day of the week for week charts, the Today week strip and weekly goals. */
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    /** Planned rest days: Cardio Load asks only for an easy day on them, and weekly goals spread over the other days. */
    val restDays: Set<DayOfWeek> = emptySet(),
)

class ProfileStore internal constructor(private val prefs: SharedPreferences, private val today: () -> LocalDate = LocalDate::now) {
    constructor(context: Context) : this(context.getSharedPreferences("profile", Context.MODE_PRIVATE))

    init {
        WeekStart.first = weekStart()
    }

    fun load(): Profile {
        val imperial = Locale.getDefault().country in IMPERIAL_COUNTRIES
        val birthDate = prefs.getString(KEY_BIRTH_DATE_ISO, null)?.let(LocalDate::parse)
            ?: prefs.getLong(KEY_BIRTH_DATE, 0L).takeIf { it != 0L }?.let { LocalDate.ofEpochDay(it - 1) }
        return Profile(
            name = prefs.getString(KEY_NAME, null).orEmpty(),
            age = birthDate?.let(::ageOn) ?: int(KEY_AGE),
            birthDate = birthDate,
            sex = prefs.getString(KEY_SEX, null)?.let(Sex::valueOf),
            units = prefs.getString(KEY_UNITS, null)?.let(UnitSystem::valueOf) ?: if (imperial) UnitSystem.Imperial else UnitSystem.Metric,
            weightUnit = prefs.getString(KEY_WEIGHT_UNIT, null)?.let(WeightUnit::valueOf) ?: if (imperial) WeightUnit.Pounds else WeightUnit.Kilograms,
            fitnessGoal = prefs.getString(KEY_FITNESS_GOAL, null)?.let(FitnessGoal::valueOf) ?: FitnessGoal.Maintain,
            dailyStepGoal = int(KEY_STEP_GOAL) ?: DEFAULT_STEPS,
            dailyDistanceGoalMeters = double(KEY_DISTANCE_GOAL) ?: DEFAULT_DISTANCE_METERS,
            dailyFloorsGoal = int(KEY_FLOORS_GOAL) ?: DEFAULT_FLOORS,
            dailyCaloriesOutGoal = int(KEY_ENERGY_GOAL),
            weeklyExerciseDaysGoal = int(KEY_EXERCISE_DAYS_GOAL) ?: DEFAULT_EXERCISE_DAYS,
            activeHours = activeHours() ?: DEFAULT_ACTIVE_HOURS,
            sleepGoalMinutes = int(KEY_SLEEP_GOAL) ?: DEFAULT_SLEEP_MINUTES,
            bedtime = time(KEY_BEDTIME),
            wakeTime = time(KEY_WAKE_TIME),
            weeklyMindfulDaysGoal = int(KEY_MINDFUL_DAYS_GOAL),
            dailyCaloriesInGoal = int(KEY_FOOD_GOAL),
            dailyWaterGoalLiters = double(KEY_WATER_GOAL),
            weightGoalKg = double(KEY_WEIGHT_GOAL),
            weekStart = weekStart(),
            restDays = prefs.getStringSet(KEY_REST_DAYS, null).orEmpty().mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }.toSet(),
        )
    }

    fun save(profile: Profile) {
        prefs.edit().apply {
            putString(KEY_NAME, profile.name)
            putString(KEY_SEX, profile.sex?.name)
            putString(KEY_UNITS, profile.units.name)
            putString(KEY_WEIGHT_UNIT, profile.weightUnit.name)
            putString(KEY_FITNESS_GOAL, profile.fitnessGoal.name)
            putInt(KEY_AGE, profile.age ?: 0)
            // An age typed over a birth date's (in the profile dialog) replaces the birth date.
            val birthDate = profile.birthDate?.takeIf { ageOn(it) == profile.age }
            putString(KEY_BIRTH_DATE_ISO, birthDate?.toString())
            remove(KEY_BIRTH_DATE)
            putInt(KEY_STEP_GOAL, profile.dailyStepGoal ?: 0)
            putFloat(KEY_DISTANCE_GOAL, profile.dailyDistanceGoalMeters?.toFloat() ?: 0f)
            putInt(KEY_FLOORS_GOAL, profile.dailyFloorsGoal ?: 0)
            putInt(KEY_ENERGY_GOAL, profile.dailyCaloriesOutGoal ?: 0)
            putInt(KEY_EXERCISE_DAYS_GOAL, profile.weeklyExerciseDaysGoal ?: 0)
            putInt(KEY_ACTIVE_HOURS_FIRST, profile.activeHours?.first?.plus(1) ?: 0)
            putInt(KEY_ACTIVE_HOURS_LAST, profile.activeHours?.last?.plus(1) ?: 0)
            putInt(KEY_SLEEP_GOAL, profile.sleepGoalMinutes ?: 0)
            putInt(KEY_BEDTIME, profile.bedtime?.toSecondOfDay()?.div(60)?.plus(1) ?: 0)
            putInt(KEY_WAKE_TIME, profile.wakeTime?.toSecondOfDay()?.div(60)?.plus(1) ?: 0)
            putInt(KEY_MINDFUL_DAYS_GOAL, profile.weeklyMindfulDaysGoal ?: 0)
            putInt(KEY_FOOD_GOAL, profile.dailyCaloriesInGoal ?: 0)
            putFloat(KEY_WATER_GOAL, profile.dailyWaterGoalLiters?.toFloat() ?: 0f)
            putFloat(KEY_WEIGHT_GOAL, profile.weightGoalKg?.toFloat() ?: 0f)
            putString(KEY_WEEK_START, profile.weekStart.name)
            putStringSet(KEY_REST_DAYS, profile.restDays.mapTo(HashSet()) { it.name })
        }.apply()
        WeekStart.first = profile.weekStart
    }

    private fun weekStart(): DayOfWeek =
        prefs.getString(KEY_WEEK_START, null)?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } } ?: DayOfWeek.SUNDAY

    private fun ageOn(birthDate: LocalDate): Int = Period.between(birthDate, today()).years

    /** Zero means "not set" for every stored number. */
    private fun int(key: String): Int? = prefs.getInt(key, 0).takeIf { it > 0 }

    private fun double(key: String): Double? = prefs.getFloat(key, 0f).takeIf { it > 0f }?.toDouble()

    /** Hours are stored + 1, so midnight is distinguishable from "not set". */
    private fun activeHours(): IntRange? {
        val first = int(KEY_ACTIVE_HOURS_FIRST) ?: return null
        val last = int(KEY_ACTIVE_HOURS_LAST) ?: return null
        return (first - 1)..(last - 1)
    }

    /** Times are stored as minute of day + 1, so midnight is distinguishable from "not set". */
    private fun time(key: String): LocalTime? = int(key)?.let { LocalTime.ofSecondOfDay((it - 1) * 60L) }

    private companion object {
        // Fitbit's goals for a new account, used until the user sets their own (Fitbit has no "no goal" for these).
        const val DEFAULT_STEPS = 10_000
        /** 5 miles. */
        const val DEFAULT_DISTANCE_METERS = 8_046.72
        const val DEFAULT_FLOORS = 10
        const val DEFAULT_EXERCISE_DAYS = 5
        const val DEFAULT_SLEEP_MINUTES = 8 * 60
        /** 9am–6pm. */
        val DEFAULT_ACTIVE_HOURS = 9..17

        const val KEY_NAME = "name"
        const val KEY_AGE = "age"
        const val KEY_BIRTH_DATE = "birth_date_epoch_day"
        const val KEY_BIRTH_DATE_ISO = "birth_date"
        const val KEY_SEX = "sex"
        const val KEY_UNITS = "units"
        const val KEY_WEIGHT_UNIT = "weight_unit"
        const val KEY_FITNESS_GOAL = "fitness_goal"
        const val KEY_STEP_GOAL = "daily_step_goal"
        const val KEY_DISTANCE_GOAL = "daily_distance_goal_m"
        const val KEY_FLOORS_GOAL = "daily_floors_goal"
        const val KEY_ENERGY_GOAL = "daily_energy_goal_kcal"
        const val KEY_EXERCISE_DAYS_GOAL = "weekly_exercise_days_goal"
        const val KEY_ACTIVE_HOURS_FIRST = "active_hours_first"
        const val KEY_ACTIVE_HOURS_LAST = "active_hours_last"
        const val KEY_SLEEP_GOAL = "sleep_goal_minutes"
        const val KEY_BEDTIME = "bedtime_minute"
        const val KEY_WAKE_TIME = "wake_time_minute"
        const val KEY_MINDFUL_DAYS_GOAL = "weekly_mindful_days_goal"
        const val KEY_FOOD_GOAL = "daily_food_goal_kcal"
        const val KEY_WATER_GOAL = "daily_water_goal_l"
        const val KEY_WEIGHT_GOAL = "weight_goal_kg"
        const val KEY_WEEK_START = "week_start"
        const val KEY_REST_DAYS = "rest_days"

        val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
    }
}
