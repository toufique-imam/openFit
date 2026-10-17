package com.openfit.ui.today

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Spa
import com.openfit.domain.stressBand
import com.openfit.ui.stress.stressBandName
import androidx.compose.ui.graphics.Shape
import com.openfit.health.ExerciseSession
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Timelapse
import com.openfit.ui.age.ageDifferenceShort
import com.openfit.ui.age.fixed
import com.openfit.domain.EnergyBand
import com.openfit.domain.energyBand
import kotlin.math.roundToInt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfit.R
import com.openfit.ui.MetricPage
import com.openfit.domain.MetricReading
import com.openfit.ui.vitals.abbreviation
import com.openfit.ui.weight.goalText
import com.openfit.ui.goals.hourText
import com.openfit.domain.LoadRange
import com.openfit.domain.Readiness
import com.openfit.domain.ReadinessLevel
import com.openfit.domain.hourlyActivity
import com.openfit.profile.Profile
import com.openfit.profile.UnitSystem
import com.openfit.profile.WeightUnit
import com.openfit.ui.theme.MetricColor
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.OpenFitIcons
import com.openfit.ui.theme.SectionHeaderStyle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private const val METERS_PER_MILE = 1_609.344
private const val FL_OZ_PER_LITER = 33.814
private const val POUNDS_PER_KG = 2.20462
private const val HEART_RANGE_FLOOR = 40f
private const val HEART_RANGE_CEILING = 200f
private val TILE_GAUGE: Dp = 66.dp
private val MINI_GAUGE: Dp = 66.dp
private val MINI_STROKE: Dp = 7.dp
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Today's focus area. Fitbit's cardio preset puts Cardio load and Daily Readiness on one split ring; the others show
 * their first metric on a large ring. The rest sit small beneath it.
 */
@Composable
fun FocusSection(state: TodayUiState.Ready, layout: TodayLayout, onOpen: (MetricPage) -> Unit) {
    val metrics = layout.focusMetrics
    if (metrics.isEmpty()) return
    val first = metrics.first()
    val paired = layout.focus.paired.takeIf { layout.focus != FocusPreset.Custom }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (paired != null) {
            CardioReadinessRing(state, onOpen)
        } else {
            BigFocus(focusValue(first, state), onClick = first.page?.let { page -> { onOpen(page) } })
        }
        val small = metrics.drop(1).take(TodayLayout.CUSTOM_SLOTS - 1)
        if (small.isNotEmpty()) {
            Spacer(Modifier.height(27.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = maxWidth / (TodayLayout.CUSTOM_SLOTS - 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
                    small.forEach { tile ->
                        MiniMetric(focusValue(tile, state), Modifier.width(width), onClick = tile.page?.let { page -> { onOpen(page) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun CardioReadinessRing(state: TodayUiState.Ready, onOpen: (MetricPage) -> Unit) {
    val load = state.metrics.cardioLoad
    val range = state.cardioRange
    val readiness = state.readiness
    FocusRing(
        onTopClick = { onOpen(MetricPage.CardioLoad) },
        onBottomClick = { onOpen(MetricPage.Readiness) },
        top = FocusMetric(
            label = stringResource(R.string.cardio_load),
            icon = Icons.Outlined.MonitorHeart,
            value = AnnotatedString(
                when {
                    load == null -> stringResource(if (state.profile.age == null) R.string.focus_needs_age else R.string.no_data)
                    range == null -> stringResource(R.string.cardio_value_no_target, truncated(load))
                    else -> stringResource(R.string.cardio_value_range, truncated(load), truncated(range.low), truncated(range.high))
                },
            ),
            progress = range?.progress(load),
            color = OpenFitColors.Mint,
            range = range?.fractions(),
        ),
        bottom = FocusMetric(
            label = stringResource(R.string.readiness),
            icon = Icons.AutoMirrored.Outlined.DirectionsRun,
            value = AnnotatedString(
                readiness?.let { stringResource(R.string.readiness_value, it.score, stringResource(readinessLabel(it))) }
                    ?: stringResource(R.string.no_score),
            ),
            progress = readiness?.let { it.score / 100f },
            color = OpenFitColors.Lavender,
        ),
    )
}

/** A metric as the focus area draws it: a gauge, the value, and under it the unit or the metric's short name. */
class FocusValue(val value: AnnotatedString?, val caption: String, val gauge: @Composable (size: Dp, stroke: Dp, icon: Dp) -> Unit)

private val BIG_GAUGE = 164.dp
private val BIG_STROKE = 10.dp
private val BIG_ICON = 64.dp

/** Fitbit's single focus metric: a large ring around the icon, the value large beneath it, then its name. */
@Composable
private fun BigFocus(focus: FocusValue, onClick: (() -> Unit)?) {
    Column(
        Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        focus.gauge(BIG_GAUGE, BIG_STROKE, BIG_ICON)
        Spacer(Modifier.height(12.dp))
        Text(
            focus.value ?: AnnotatedString(stringResource(R.string.no_data)),
            style = if (focus.value == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayLarge,
            color = OpenFitColors.OnSurface,
            maxLines = 1,
        )
        Text(focus.caption, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurfaceVariant)
    }
}

@Composable
private fun MiniMetric(focus: FocusValue, modifier: Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        focus.gauge(MINI_GAUGE, MINI_STROKE, 27.dp)
        Box(Modifier.padding(top = 4.dp).height(34.dp), contentAlignment = Alignment.TopCenter) {
            if (focus.value == null) {
                Text(
                    stringResource(R.string.no_data),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = OpenFitColors.OnSurface,
                )
            } else {
                Text(focus.value, style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 36.sp), color = OpenFitColors.OnSurface, maxLines = 1)
            }
        }
        Text(focus.caption, style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
    }
}

/** A goal's ring (empty before there is data), or the icon alone without a goal, at any size. */
private fun goalGauge(icon: ImageVector, color: MetricColor, value: Double?, goal: Number?, iconRotation: Float = 0f): @Composable (Dp, Dp, Dp) -> Unit =
    { size, stroke, iconSize ->
        if (goal == null) {
            IconBadge(icon, color, size)
        } else {
            ArcGauge(
                icon, color, ((value ?: 0.0) / goal.toDouble()).toFloat(),
                background = OpenFitColors.Background, size = size, stroke = stroke, iconSize = iconSize, iconRotation = iconRotation,
            )
        }
    }

private fun arcGauge(icon: ImageVector, color: MetricColor, progress: Float?, from: Float = 0f, range: ClosedFloatingPointRange<Float>? = null): @Composable (Dp, Dp, Dp) -> Unit =
    { size, stroke, iconSize ->
        if (progress == null) {
            IconBadge(icon, color, size)
        } else {
            ArcGauge(icon, color, progress, from = from, range = range, background = OpenFitColors.Background, size = size, stroke = stroke, iconSize = iconSize)
        }
    }

private fun segmentGauge(icon: ImageVector, color: MetricColor, goal: Int?, done: Int, iconRotation: Float = 0f): @Composable (Dp, Dp, Dp) -> Unit =
    { size, stroke, iconSize ->
        if (goal == null) IconBadge(icon, color, size) else SegmentedGauge(icon, color, goal, done, size = size, stroke = stroke, iconSize = iconSize, iconRotation = iconRotation)
    }

@Composable
fun focusValue(tile: TodayTile, state: TodayUiState.Ready): FocusValue {
    val metrics = state.metrics
    val profile = state.profile
    val label = stringResource(tile.focusLabel)
    val imperial = profile.units == UnitSystem.Imperial
    return when (tile) {
        TodayTile.SleepDuration -> {
            val asleep = metrics.sleep?.asleep
            FocusValue(asleep?.let { valueText { duration(it) } }, label, goalGauge(OpenFitIcons.MoonSparkle, OpenFitColors.Lavender, asleep?.toMinutes()?.toDouble(), profile.sleepGoalMinutes))
        }
        TodayTile.Readiness -> {
            val readiness = state.readiness
            FocusValue(readiness?.let { AnnotatedString(it.score.toString()) }, label, arcGauge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Lavender, readiness?.let { it.score / 100f }))
        }
        TodayTile.EnergyBank -> {
            val level = state.energy?.latest
            FocusValue(level?.let { quantityText(it.roundToInt().toString(), "%") }, label, arcGauge(Icons.Outlined.BatteryChargingFull, OpenFitColors.Lavender, level?.let { (it / 100).toFloat() }))
        }
        TodayTile.CardioLoad -> {
            val range = state.cardioRange
            FocusValue(
                metrics.cardioLoad?.let { AnnotatedString(truncated(it)) },
                label,
                arcGauge(Icons.Outlined.MonitorHeart, OpenFitColors.Mint, range?.progress(metrics.cardioLoad) ?: 0f, range = range?.fractions()),
            )
        }
        TodayTile.ExerciseDays -> FocusValue(
            valueText { exerciseCount(state.exerciseDays.size, profile.weeklyExerciseDaysGoal, smallTotal = true) },
            label,
            segmentGauge(OpenFitIcons.Dumbbell, OpenFitColors.Mint, profile.weeklyExerciseDaysGoal, state.exerciseDays.size, iconRotation = -45f),
        )
        TodayTile.ZoneMinutes -> {
            val zone = metrics.zoneMinutes?.total
            FocusValue(
                zone?.let { AnnotatedString(whole(it)) },
                label,
                arcGauge(Icons.Outlined.KeyboardDoubleArrowUp, OpenFitColors.Mint, (zone ?: 0) / kotlin.math.ceil(profile.fitnessGoal.weeklyZoneMinutes / 7.0).toFloat()),
            )
        }
        TodayTile.Steps -> FocusValue(
            AnnotatedString(whole(metrics.steps ?: 0)),
            label,
            goalGauge(OpenFitIcons.Sneaker, OpenFitColors.Mint, (metrics.steps ?: 0).toDouble(), profile.dailyStepGoal, iconRotation = 40f),
        )
        TodayTile.HourlyActivity -> {
            val hourly = profile.activeHours?.let { hourlyActivity(metrics.hourlySteps, currentHour = if (state.isToday) LocalTime.now().hour else null, window = it) }
            FocusValue(
                hourly?.let { valueText { count(it.met, it.hours.size, smallTotal = true) } },
                label,
                segmentGauge(Icons.Outlined.AccessTime, OpenFitColors.Mint, hourly?.hours?.size, hourly?.met ?: 0),
            )
        }
        TodayTile.Distance -> {
            val meters = metrics.distanceMeters ?: 0.0
            FocusValue(
                AnnotatedString(twoDecimals(if (imperial) meters / METERS_PER_MILE else meters / 1_000)),
                stringResource(if (imperial) R.string.unit_mi else R.string.unit_km),
                goalGauge(Icons.Outlined.LocationOn, OpenFitColors.Mint, meters, profile.dailyDistanceGoalMeters),
            )
        }
        TodayTile.EnergyBurned -> {
            val calories = metrics.caloriesOutKcal ?: 0.0
            FocusValue(AnnotatedString(whole(calories)), stringResource(R.string.unit_cal), goalGauge(Icons.Outlined.LocalFireDepartment, OpenFitColors.Mint, calories, profile.dailyCaloriesOutGoal))
        }
        TodayTile.Floors -> {
            val floors = metrics.floors ?: 0.0
            FocusValue(AnnotatedString(whole(floors.toInt())), label, goalGauge(Icons.Outlined.Stairs, OpenFitColors.Mint, floors, profile.dailyFloorsGoal))
        }
        TodayTile.HealthMetrics -> {
            val readings = state.healthMetrics
            val inRange = readings.count { it.inRange == true }
            FocusValue(
                readings.takeIf { it.isNotEmpty() }?.let { valueText { count(inRange, it.size, smallTotal = true) } },
                label,
                segmentGauge(Icons.Outlined.BarChart, OpenFitColors.Blue, readings.size.takeIf { it > 0 }, inRange),
            )
        }
        TodayTile.Heart -> {
            val range = metrics.heartRateRange
            val latest = metrics.latestHeartRate?.takeIf { state.isToday }
            val resting = metrics.restingBpm?.value
            val span = HEART_RANGE_CEILING - HEART_RANGE_FLOOR
            val low = latest?.value ?: range?.first ?: resting
            val high = latest?.value ?: range?.last ?: low
            FocusValue(
                (latest?.value?.toString() ?: range?.let { "${it.first}–${it.last}" } ?: resting?.toString())?.let(::AnnotatedString),
                stringResource(R.string.unit_bpm),
                arcGauge(
                    Icons.Outlined.FavoriteBorder,
                    OpenFitColors.Blue,
                    high?.let { (it - HEART_RANGE_FLOOR) / span },
                    from = low?.let { (it - HEART_RANGE_FLOOR) / span } ?: 0f,
                ),
            )
        }
        TodayTile.Vo2Max -> {
            val fitness = state.fitness
            FocusValue(
                fitness?.let { AnnotatedString(oneDecimal(it.vo2Max)) },
                label,
                arcGauge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Blue, fitness?.let { (it.percentile / 100).toFloat() }),
            )
        }
        TodayTile.PulseAge -> FocusValue(state.pulseAge?.let { AnnotatedString(fixed(it.age)) }, label, { size, _, _ -> IconBadge(Icons.Outlined.Timelapse, OpenFitColors.Blue, size) })
        TodayTile.Weight -> {
            val weight = metrics.weightKg?.value
            val pounds = profile.weightUnit == WeightUnit.Pounds
            FocusValue(
                weight?.let { AnnotatedString(oneDecimal(if (pounds) it * POUNDS_PER_KG else it)) },
                stringResource(if (pounds) R.string.unit_lb else R.string.unit_kg),
                { size, _, _ -> IconBadge(Icons.Outlined.MonitorWeight, OpenFitColors.Blue, size) },
            )
        }
        TodayTile.Stress -> {
            val value = if (state.isToday) state.stress?.latest else state.stress?.average
            FocusValue(value?.let { AnnotatedString(it.toString()) }, label, arcGauge(Icons.Outlined.SelfImprovement, OpenFitColors.Lavender, value?.let { it / 100f }))
        }
        TodayTile.MindfulDays -> FocusValue(
            valueText { exerciseCount(state.mindfulDays.size, profile.weeklyMindfulDaysGoal, smallTotal = true) },
            label,
            segmentGauge(Icons.Outlined.Spa, OpenFitColors.Lavender, profile.weeklyMindfulDaysGoal, state.mindfulDays.size),
        )
        TodayTile.Food -> {
            val eaten = metrics.caloriesInKcal ?: 0.0
            FocusValue(AnnotatedString(whole(eaten)), stringResource(R.string.unit_cal), goalGauge(Icons.Outlined.Restaurant, OpenFitColors.Pink, eaten, profile.dailyCaloriesInGoal))
        }
        TodayTile.Water -> {
            val liters = metrics.waterLiters ?: 0.0
            FocusValue(
                AnnotatedString(if (imperial) oneDecimal(liters * FL_OZ_PER_LITER) else whole(liters * 1_000)),
                stringResource(if (imperial) R.string.unit_fl_oz else R.string.unit_ml),
                goalGauge(Icons.Outlined.LocalDrink, OpenFitColors.Blue, liters, profile.dailyWaterGoalLiters),
            )
        }
    }
}

/** Whether a card has anything to show: Health Metrics, Heart, VO2 max and Weight appear once there is data. */
fun tileHasData(tile: TodayTile, state: TodayUiState.Ready): Boolean {
    val metrics = state.metrics
    return when (tile) {
        TodayTile.HealthMetrics -> state.healthMetrics.isNotEmpty()
        TodayTile.Heart -> metrics.latestHeartRate != null || metrics.heartRateRange != null || metrics.restingBpm != null
        TodayTile.Vo2Max -> state.fitness != null
        TodayTile.Weight -> metrics.weightKg != null
        else -> true
    }
}

/** Today's sections in the user's order, without the cards they hid. */
fun LazyListScope.todaySections(state: TodayUiState.Ready, layout: TodayLayout, onOpen: (MetricPage) -> Unit, faded: Boolean = false) {
    layout.order.forEach { section -> sectionTiles(state, section, onOpen, hidden = layout.hidden, faded = faded) }
}

/** One section's cards; a section tab shows them all, under its own title instead of a header. */
fun LazyListScope.sectionTiles(
    state: TodayUiState.Ready,
    section: TodaySection,
    onOpen: (MetricPage) -> Unit,
    hidden: Set<TodayTile> = emptySet(),
    header: Boolean = true,
    /** The cards are an older day's, shown while the picked one loads. */
    faded: Boolean = false,
) {
    val tiles = TodayTile.entries.filter { it.section == section && it !in hidden }
    if (tiles.isEmpty()) return
    if (header) section(sectionTitle(section, state), key = "header:${section.name}")
    tiles.filter { tileHasData(it, state) }.forEach { tile ->
        item(key = tile.name) { Box(Modifier.alpha(if (faded) FADED_ALPHA else 1f)) { TileCard(tile, state, onOpen) } }
    }
}

const val FADED_ALPHA = 0.4f

/** Without a readiness score, a past day's Recovery is just its sleep, as on Fitbit. */
@StringRes
fun sectionTitle(section: TodaySection, state: TodayUiState.Ready): Int =
    if (section == TodaySection.Recovery && state.readiness == null && !state.isToday) R.string.section_sleep else section.title

@Composable
private fun TileCard(tile: TodayTile, state: TodayUiState.Ready, onOpen: (MetricPage) -> Unit) {
    val metrics = state.metrics
    val profile = state.profile
    val open: (() -> Unit)? = tile.page?.let { page -> { onOpen(page) } }
    when (tile) {
        TodayTile.SleepDuration -> {
            val asleep = metrics.sleep?.asleep
            MetricTile(
                title = stringResource(R.string.tile_sleep_duration),
                value = asleep?.let { valueText { duration(it) } } ?: noValue(stringResource(R.string.no_data)),
                subtitle = listOfNotNull(dayLabel(state), state.sleepScore?.let { stringResource(R.string.sleep_score_subtitle, it) }).joinToString(" • "),
                onClick = open,
            ) {
                GoalGauge(OpenFitIcons.MoonSparkle, OpenFitColors.Lavender, asleep?.toMinutes()?.toDouble(), profile.sleepGoalMinutes)
            }
        }
        TodayTile.Readiness -> {
            val readiness = state.readiness
            MetricTile(
                title = stringResource(R.string.readiness),
                value = readiness?.let { AnnotatedString(it.score.toString()) } ?: noValue(stringResource(R.string.no_score)),
                subtitle = listOfNotNull(dayLabel(state), readiness?.let { stringResource(readinessLabel(it)) }).joinToString(" • "),
                onClick = open,
            ) {
                if (readiness == null) {
                    IconBadge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Lavender)
                } else {
                    ArcGauge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Lavender, readiness.score / 100f)
                }
            }
        }
        TodayTile.EnergyBank -> {
            val level = state.energy?.latest
            MetricTile(
                title = stringResource(R.string.energy_title),
                value = level?.let { quantityText(it.roundToInt().toString(), "%") }
                    ?: noValue(stringResource(if (state.energyLoading) R.string.energy_loading else R.string.energy_calibrating_short)),
                subtitle = listOfNotNull(dayLabel(state), level?.let { stringResource(energyBandText(energyBand(it))) }).joinToString(" · "),
                onClick = open,
            ) {
                if (level == null) {
                    IconBadge(Icons.Outlined.BatteryChargingFull, OpenFitColors.Lavender)
                } else {
                    ArcGauge(Icons.Outlined.BatteryChargingFull, OpenFitColors.Lavender, (level / 100).toFloat())
                }
            }
        }
        TodayTile.CardioLoad -> {
            val range = state.cardioRange
            MetricTile(
                title = stringResource(R.string.cardio_load),
                value = AnnotatedString(truncated(metrics.cardioLoad ?: 0.0)),
                subtitle = listOfNotNull(
                    dayLabel(state),
                    when {
                        range == null -> stringResource(R.string.no_target)
                        else -> stringResource(R.string.target_range, truncated(range.low), truncated(range.high))
                    },
                ).joinToString(" • "),
                onClick = open,
            ) {
                ArcGauge(Icons.Outlined.MonitorHeart, OpenFitColors.Mint, range?.progress(metrics.cardioLoad) ?: 0f, range = range?.fractions())
            }
        }
        TodayTile.ExerciseDays -> {
            val labels = state.week.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, com.openfit.ui.currentLocale()) }
            val workout = state.latestExercise
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetricTile(
                    title = stringResource(R.string.tile_exercise_days),
                    value = valueText { exerciseCount(state.exerciseDays.size, profile.weeklyExerciseDaysGoal) },
                    subtitle = weekLabel(state),
                    onClick = open,
                    shape = if (workout == null) TILE_SHAPE else GROUP_TOP,
                ) {
                    WeekBars(labels, state.week.map { it in state.exerciseDays }, OpenFitColors.Mint, todayIndex = state.week.indexOf(LocalDate.now()).takeIf { it >= 0 })
                }
                if (workout != null) WorkoutRow(workout, profile, onClick = { onOpen(MetricPage.Exercise) })
            }
        }
        TodayTile.ZoneMinutes -> {
            val zoneMinutes = metrics.zoneMinutes?.total
            MetricTile(
                title = stringResource(R.string.tile_zone_minutes),
                value = zoneMinutes?.let { AnnotatedString(whole(it)) } ?: noValue(stringResource(R.string.no_data)),
                subtitle = if (zoneMinutes == null && profile.age == null) stringResource(R.string.zone_minutes_needs_age) else dayLabel(state),
                onClick = open,
            ) {
                // Fitbit's ring fills against the daily goal, the weekly one over seven days (150 -> 22), not what is left of the week.
                ArcGauge(Icons.Outlined.KeyboardDoubleArrowUp, OpenFitColors.Mint, (zoneMinutes ?: 0) / kotlin.math.ceil(profile.fitnessGoal.weeklyZoneMinutes / 7.0).toFloat())
            }
        }
        TodayTile.Steps -> MetricTile(stringResource(R.string.tile_steps), AnnotatedString(whole(metrics.steps ?: 0)), dayLabel(state), onClick = open) {
            GoalGauge(OpenFitIcons.Sneaker, OpenFitColors.Mint, (metrics.steps ?: 0).toDouble(), profile.dailyStepGoal, iconRotation = 40f)
        }
        TodayTile.HourlyActivity -> {
            val window = profile.activeHours
            val hourly = window?.let { hourlyActivity(metrics.hourlySteps, currentHour = if (state.isToday) LocalTime.now().hour else null, window = it) }
            MetricTile(
                title = stringResource(R.string.tile_hourly_activity),
                value = hourly?.let { valueText { count(it.met, it.hours.size) } } ?: noValue(stringResource(R.string.goal_no_goal)),
                subtitle = dayLabel(state),
                onClick = open,
            ) {
                if (hourly == null) {
                    IconBadge(Icons.Outlined.AccessTime, OpenFitColors.Mint)
                } else {
                    HourlyBars(hourly.hours, hourText(hourly.window.first), hourText(hourly.window.last), OpenFitColors.Mint)
                }
            }
        }
        TodayTile.Distance -> {
            val meters = metrics.distanceMeters ?: 0.0
            val imperial = profile.units == UnitSystem.Imperial
            MetricTile(
                title = stringResource(R.string.tile_distance),
                value = quantityText(
                    twoDecimals(if (imperial) meters / METERS_PER_MILE else meters / 1_000),
                    stringResource(if (imperial) R.string.unit_mi else R.string.unit_km),
                ),
                subtitle = dayLabel(state),
                onClick = open,
            ) {
                GoalGauge(Icons.Outlined.LocationOn, OpenFitColors.Mint, meters, profile.dailyDistanceGoalMeters)
            }
        }
        TodayTile.EnergyBurned -> {
            val calories = metrics.caloriesOutKcal ?: 0.0
            MetricTile(
                title = stringResource(R.string.tile_energy_burned),
                value = quantityText(whole(calories), stringResource(R.string.unit_cal)),
                subtitle = dayLabel(state),
                onClick = open,
            ) {
                GoalGauge(Icons.Outlined.LocalFireDepartment, OpenFitColors.Mint, calories, profile.dailyCaloriesOutGoal)
            }
        }
        TodayTile.Floors -> {
            val floors = metrics.floors ?: 0.0
            MetricTile(stringResource(R.string.tile_floors), AnnotatedString(whole(floors.toInt())), dayLabel(state), onClick = open) {
                GoalGauge(Icons.Outlined.Stairs, OpenFitColors.Mint, floors, profile.dailyFloorsGoal)
            }
        }
        TodayTile.HealthMetrics -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HealthMetricsTile(state.healthMetrics, onClick = { onOpen(MetricPage.HealthMetrics) })
            HealthMetricsSummary(state.healthMetrics, onClick = { onOpen(MetricPage.HealthMetrics) })
        }
        TodayTile.Heart -> HeartTile(state, onClick = { onOpen(MetricPage.Heart) })
        TodayTile.Vo2Max -> {
            val fitness = state.fitness ?: return
            MetricTile(
                title = stringResource(R.string.vo2_title),
                value = AnnotatedString(oneDecimal(fitness.vo2Max)),
                // One line: whether it was estimated shows on the VO2 max page.
                subtitle = listOf(
                    stringResource(fitnessBandName(fitness.band)),
                    stringResource(R.string.fitness_percentile, ordinal(fitness.percentile.toInt())),
                ).joinToString(" · "),
                onClick = open,
            ) {
                ArcGauge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Blue, (fitness.percentile / 100).toFloat())
            }
        }
        TodayTile.PulseAge -> {
            val age = state.pulseAge
            MetricTile(
                title = stringResource(R.string.age_title),
                value = age?.let { AnnotatedString(fixed(it.age)) } ?: noValue(
                    stringResource(
                        when {
                            profile.age == null || profile.sex == null -> R.string.age_tile_needs_profile
                            state.pulseAgeLoading -> R.string.energy_loading
                            else -> R.string.no_data
                        },
                    ),
                ),
                subtitle = age?.let { ageDifferenceShort(it.difference) } ?: stringResource(R.string.age_tile_window),
                onClick = open,
            ) {
                IconBadge(Icons.Outlined.Timelapse, OpenFitColors.Blue)
            }
        }
        TodayTile.Weight -> {
            val weight = metrics.weightKg ?: return
            val pounds = profile.weightUnit == WeightUnit.Pounds
            MetricTile(
                title = stringResource(R.string.tile_weight),
                value = quantityText(oneDecimal(if (pounds) weight.value * POUNDS_PER_KG else weight.value), stringResource(if (pounds) R.string.unit_lb else R.string.unit_kg)),
                subtitle = listOfNotNull(
                    readingDayLabel(state, weight.time),
                    metrics.bodyFatPercent?.let { stringResource(R.string.body_fat_detail, oneDecimal(it.value)) },
                    goalText(weight.value, profile),
                ).joinToString(" · "),
                onClick = open,
            ) {
                IconBadge(Icons.Outlined.MonitorWeight, OpenFitColors.Blue)
            }
        }
        TodayTile.Stress -> {
            val stress = state.stress
            // Today: the latest reading; past days: the day's average .
            val value = if (state.isToday) stress?.latest else stress?.average
            MetricTile(
                title = stringResource(R.string.stress_title),
                value = value?.let { AnnotatedString(it.toString()) }
                    ?: noValue(stringResource(if (state.energyLoading) R.string.energy_loading else R.string.energy_calibrating_short)),
                subtitle = listOfNotNull(dayLabel(state), value?.let { stringResource(stressBandName(stressBand(it))) }).joinToString(" • "),
                onClick = open,
            ) {
                if (value == null) {
                    IconBadge(Icons.Outlined.SelfImprovement, OpenFitColors.Lavender)
                } else {
                    ArcGauge(Icons.Outlined.SelfImprovement, OpenFitColors.Lavender, value / 100f)
                }
            }
        }
        TodayTile.MindfulDays -> {
            val labels = state.week.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, com.openfit.ui.currentLocale()) }
            MetricTile(
                title = stringResource(R.string.tile_mindful_days),
                value = valueText { exerciseCount(state.mindfulDays.size, profile.weeklyMindfulDaysGoal) },
                subtitle = weekLabel(state),
            ) {
                WeekBars(labels, state.week.map { it in state.mindfulDays }, OpenFitColors.Lavender, todayIndex = state.week.indexOf(LocalDate.now()).takeIf { it >= 0 })
            }
        }
        TodayTile.Food -> {
            val eaten = metrics.caloriesInKcal ?: 0.0
            val difference = profile.dailyCaloriesInGoal?.let { it - eaten }
            MetricTile(
                title = stringResource(R.string.tile_food),
                value = quantityText(whole(eaten), stringResource(R.string.unit_cal)),
                subtitle = listOfNotNull(
                    dayLabel(state),
                    difference?.let { stringResource(if (it >= 0) R.string.food_below_target else R.string.food_above_target, whole(abs(it))) },
                ).joinToString(" · "),
                onClick = open,
            ) {
                GoalGauge(Icons.Outlined.Restaurant, OpenFitColors.Pink, eaten, profile.dailyCaloriesInGoal)
            }
        }
        TodayTile.Water -> {
            val liters = metrics.waterLiters ?: 0.0
            val imperial = profile.units == UnitSystem.Imperial
            MetricTile(
                title = stringResource(R.string.tile_water),
                value = quantityText(
                    if (imperial) oneDecimal(liters * FL_OZ_PER_LITER) else whole(liters * 1_000),
                    stringResource(if (imperial) R.string.unit_fl_oz else R.string.unit_ml),
                ),
                subtitle = dayLabel(state),
                onClick = open,
            ) {
                GoalGauge(Icons.Outlined.LocalDrink, OpenFitColors.Blue, liters, profile.dailyWaterGoalLiters)
            }
        }
    }
}

/** Fitbit's "Health Metrics · 3 of 3 · In personal range" tile with a segment per metric. */
@Composable
private fun HealthMetricsTile(readings: List<MetricReading>, onClick: () -> Unit) {
    val ranged = readings.filter { it.inRange != null }
    val inRange = ranged.count { it.inRange == true }
    MetricTile(
        title = stringResource(R.string.hm_title),
        value = valueText { count(inRange, readings.size) },
        subtitle = stringResource(
            when {
                ranged.isEmpty() -> R.string.hm_keep_wearing
                inRange == ranged.size -> R.string.hm_in_personal_range
                else -> R.string.hm_out_of_personal_range
            },
        ),
        onClick = onClick,
    ) {
        SegmentedGauge(Icons.Outlined.BarChart, OpenFitColors.Blue, readings.size, inRange)
    }
}

/** The card under the tile: the metrics as abbreviations, then whether they are in range or which are not. */
@Composable
private fun HealthMetricsSummary(readings: List<MetricReading>, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // Fitbit: a check beside "BR, RHR, HRV" over "In personal range", or a line per metric out of range.
        val outside = readings.filter { it.inRange == false }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (outside.isEmpty()) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = OpenFitColors.Blue.accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(24.dp))
            }
            Column {
                Text(
                    readings.map { stringResource(abbreviation(it.metric)) }.joinToString(", "),
                    style = MaterialTheme.typography.titleSmall,
                    color = OpenFitColors.OnSurface,
                )
                if (outside.isEmpty()) {
                    Text(stringResource(R.string.hm_in_personal_range), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                } else {
                    outside.forEach { reading ->
                        val above = reading.range?.let { reading.value > it.high } == true
                        Text(
                            stringResource(R.string.hm_value_out_of_range, stringResource(abbreviation(reading.metric)), stringResource(if (above) R.string.hm_above_range else R.string.hm_below_range)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenFitColors.OnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeartTile(state: TodayUiState.Ready, onClick: () -> Unit) {
    val metrics = state.metrics
    val range = metrics.heartRateRange
    val resting = metrics.restingBpm?.value
    val latest = metrics.latestHeartRate?.takeIf { state.isToday }
    val restingText = resting?.let { stringResource(R.string.heart_resting, it) }
    MetricTile(
        title = stringResource(R.string.tile_heart),
        // Fitbit writes a range as "49-188 BPM" and a single reading as "72 bpm".
        value = quantityText(
            latest?.value?.toString() ?: range?.let { "${it.first}–${it.last}" } ?: resting.toString(),
            stringResource(if (latest == null && range != null) R.string.unit_bpm_range else R.string.unit_bpm),
        ),
        subtitle = listOfNotNull(latest?.let { sinceLabel(it.time) } ?: dayLabel(state), restingText).joinToString(" · "),
        onClick = onClick,
    ) {
        val span = HEART_RANGE_CEILING - HEART_RANGE_FLOOR
        val low = latest?.value ?: range?.first ?: resting ?: 0
        val high = latest?.value ?: range?.last ?: low
        ArcGauge(
            Icons.Outlined.FavoriteBorder,
            OpenFitColors.Blue,
            from = (low - HEART_RANGE_FLOOR) / span,
            progress = (high - HEART_RANGE_FLOOR) / span,
        )
    }
}

private fun LazyListScope.section(@StringRes title: Int, key: String) {
    item(key = key) {
        Text(
            stringResource(title),
            style = SectionHeaderStyle,
            color = OpenFitColors.OnSurface,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 4.dp),
        )
    }
}

private val TILE_SHAPE = RoundedCornerShape(16.dp)
private val GROUP_TOP = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
private val GROUP_BOTTOM = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
private val CLOCK_24H = DateTimeFormatter.ofPattern("HH:mm")

/** Fitbit's row under Exercise days: the day's last workout, its start, distance and length. */
@Composable
private fun WorkoutRow(workout: ExerciseSession, profile: Profile, onClick: () -> Unit) {
    val imperial = profile.units == UnitSystem.Imperial
    val details = listOfNotNull(
        workout.start.atZone(ZoneId.systemDefault()).format(CLOCK_24H),
        workout.distanceMeters?.let {
            "${twoDecimals(if (imperial) it / METERS_PER_MILE else it / 1_000)} ${stringResource(if (imperial) R.string.unit_mi else R.string.unit_km)}"
        },
        minutesLabel(workout.duration.toMinutes()),
    )
    Surface(shape = GROUP_BOTTOM, color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(exerciseIcon(workout.type), contentDescription = null, tint = OpenFitColors.OnSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(20.dp))
            Column {
                Text(workout.name, style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface)
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            }
        }
    }
}

@Composable
fun MetricTile(
    title: String,
    value: AnnotatedString,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    shape: Shape = TILE_SHAPE,
    trailing: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = OpenFitColors.Card,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OpenFitColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(value, style = MaterialTheme.typography.displaySmall, color = OpenFitColors.OnSurface, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(24.dp))
            trailing()
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: MetricColor, size: Dp = TILE_GAUGE) {
    Box(Modifier.size(size).clip(CircleShape).background(color.track), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color.accent, modifier = Modifier.size(size * 0.36f))
    }
}

private fun LoadRange.progress(load: Double?): Float = ((load ?: 0.0) / scale).toFloat()

private fun LoadRange.fractions(): ClosedFloatingPointRange<Float> = (low / scale).toFloat()..(high / scale).toFloat()

private fun noValue(text: String): AnnotatedString = AnnotatedString(text, SpanStyle(fontSize = 22.sp))

@Composable
private fun dayLabel(state: TodayUiState.Ready): String =
    relativeDay(state.date)

@Composable
private fun weekLabel(state: TodayUiState.Ready): String =
    if (LocalDate.now() in state.week) stringResource(R.string.this_week) else weekRange(state.week.first(), state.week.last())

@Composable
private fun readingDayLabel(state: TodayUiState.Ready, time: Instant): String {
    val day = time.atZone(ZoneId.systemDefault()).toLocalDate()
    return relativeDay(day)
}

@Composable
private fun sinceLabel(time: Instant): String {
    val minutes = Duration.between(time, Instant.now()).toMinutes()
    return when {
        minutes < 5 -> stringResource(R.string.just_now)
        minutes < 60 -> stringResource(R.string.minutes_ago, minutes)
        else -> time.atZone(ZoneId.systemDefault()).format(CLOCK)
    }
}

private fun readinessLabel(readiness: Readiness): Int = when {
    readiness.illness -> R.string.readiness_take_it_easy
    readiness.level == ReadinessLevel.Low -> R.string.readiness_low
    readiness.level == ReadinessLevel.High -> R.string.readiness_high
    else -> R.string.readiness_moderate
}

fun fitnessBandName(band: com.openfit.domain.FitnessBand): Int = when (band) {
    com.openfit.domain.FitnessBand.Poor -> R.string.fitness_poor
    com.openfit.domain.FitnessBand.Fair -> R.string.fitness_fair
    com.openfit.domain.FitnessBand.Good -> R.string.fitness_good
    com.openfit.domain.FitnessBand.Excellent -> R.string.fitness_excellent
    com.openfit.domain.FitnessBand.Superior -> R.string.fitness_superior
}

private fun energyBandText(band: EnergyBand): Int = when (band) {
    EnergyBand.Plenty -> R.string.energy_band_plenty
    EnergyBand.PaceYourself -> R.string.energy_band_pace
    EnergyBand.RunningLow -> R.string.energy_band_low
}

/** A goal's progress ring, or just its icon while the user has not set the goal (or there is nothing to measure). */
@Composable
private fun GoalGauge(icon: ImageVector, color: MetricColor, value: Double?, goal: Number?, iconRotation: Float = 0f) {
    if (goal == null) IconBadge(icon, color) else ArcGauge(icon, color, ((value ?: 0.0) / goal.toDouble()).toFloat(), iconRotation = iconRotation)
}

/** "3 of 5" exercise days, or just "3" without a weekly goal. */
private fun ValueText.exerciseCount(days: Int, goal: Int?, smallTotal: Boolean = false) {
    if (goal == null) big(days.toString()) else count(days, goal, smallTotal)
}

/** Fitbit's day words: "Today", "Yesterday", then "Wed, 14 Oct". */
@Composable
fun relativeDay(day: LocalDate): String = when (day) {
    LocalDate.now() -> stringResource(R.string.today)
    LocalDate.now().minusDays(1) -> stringResource(R.string.yesterday)
    else -> day.weekdayDayMonth()
}

/** "1st", "22nd", "73rd", "11th". */
fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}
