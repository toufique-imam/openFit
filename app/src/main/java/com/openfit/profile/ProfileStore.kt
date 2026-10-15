package com.openfit.profile

import android.content.Context

data class Profile(
    val age: Int? = null,
    val dailyStepGoal: Int = 10_000,
    val dailyZoneMinutesGoal: Int = 22,
)

class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load(): Profile = Profile(
        age = prefs.getInt(KEY_AGE, 0).takeIf { it > 0 },
        dailyStepGoal = prefs.getInt(KEY_STEP_GOAL, Profile().dailyStepGoal),
        dailyZoneMinutesGoal = prefs.getInt(KEY_ZONE_GOAL, Profile().dailyZoneMinutesGoal),
    )

    fun save(profile: Profile) = prefs.edit()
        .putInt(KEY_AGE, profile.age ?: 0)
        .putInt(KEY_STEP_GOAL, profile.dailyStepGoal)
        .putInt(KEY_ZONE_GOAL, profile.dailyZoneMinutesGoal)
        .apply()

    private companion object {
        const val KEY_AGE = "age"
        const val KEY_STEP_GOAL = "daily_step_goal"
        const val KEY_ZONE_GOAL = "daily_zone_minutes_goal"
    }
}
