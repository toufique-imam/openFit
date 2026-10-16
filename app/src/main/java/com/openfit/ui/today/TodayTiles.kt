package com.openfit.ui.today

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.openfit.domain.DailyMetrics
import com.openfit.domain.LoadRange
import com.openfit.domain.Reading
import com.openfit.domain.Readiness
import com.openfit.domain.ReadinessLevel
import com.openfit.domain.hourlyActivity
import com.openfit.profile.Profile
import com.openfit.profile.UnitSystem
import com.openfit.profile.WeightUnit
import com.openfit.ui.theme.MetricColor
import com.openfit.ui.theme.OpenFitColors
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
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun FocusSection(state: TodayUiState.Ready, onOpen: (MetricPage) -> Unit) {
    val metrics = state.metrics
    val profile = state.profile
    val asleep = metrics.sleep?.asleep
    val load = metrics.cardioLoad
    val range = state.cardioRange
    val readiness = state.readiness
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusRing(
            onTopClick = { onOpen(MetricPage.CardioLoad) },
            onBottomClick = { onOpen(MetricPage.Readiness) },
            top = FocusMetric(
                label = stringResource(R.string.cardio_load),
                icon = Icons.Outlined.MonitorHeart,
                value = AnnotatedString(
                    when {
                        load == null -> stringResource(if (profile.age == null) R.string.focus_needs_age else R.string.no_data)
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
        Spacer(Modifier.height(27.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            MiniMetric(
                label = stringResource(R.string.tile_exercise_days),
                value = valueText { count(state.exerciseDays.size, profile.weeklyExerciseDaysGoal, smallTotal = true) },
                onClick = { onOpen(MetricPage.Exercise) },
            ) {
                SegmentedGauge(
                    Icons.Outlined.FitnessCenter,
                    OpenFitColors.Mint,
                    profile.weeklyExerciseDaysGoal,
                    state.exerciseDays.size,
                    iconRotation = 90f,
                )
            }
            MiniMetric(
                label = stringResource(R.string.focus_sleep),
                value = asleep?.let { valueText { duration(it) } },
                onClick = { onOpen(MetricPage.Sleep) },
            ) {
                if (asleep == null) {
                    IconBadge(Icons.Outlined.Bedtime, OpenFitColors.Lavender, MINI_GAUGE)
                } else {
                    ArcGauge(Icons.Outlined.Bedtime, OpenFitColors.Lavender, progress(asleep, profile.sleepGoalMinutes))
                }
            }
            MiniMetric(
                label = stringResource(R.string.tile_steps),
                value = AnnotatedString(whole(metrics.steps ?: 0)),
                onClick = { onOpen(MetricPage.Steps) },
            ) {
                ArcGauge(
                    Icons.AutoMirrored.Outlined.DirectionsWalk,
                    OpenFitColors.Mint,
                    (metrics.steps ?: 0).toFloat() / profile.dailyStepGoal,
                )
            }
        }
    }
}

@Composable
private fun RowScope.MiniMetric(label: String, value: AnnotatedString?, onClick: (() -> Unit)? = null, gauge: @Composable () -> Unit) {
    Column(
        Modifier.weight(1f).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        gauge()
        Box(Modifier.padding(top = 4.dp).height(34.dp), contentAlignment = Alignment.TopCenter) {
            if (value == null) {
                Text(
                    stringResource(R.string.no_data),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = OpenFitColors.OnSurface,
                )
            } else {
                Text(value, style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 36.sp), color = OpenFitColors.OnSurface, maxLines = 1)
            }
        }
        Text(label, style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
    }
}

fun LazyListScope.todaySections(state: TodayUiState.Ready, onOpen: (MetricPage) -> Unit) {
    val metrics = state.metrics
    val profile = state.profile

    section(if (state.readiness != null || state.isToday) R.string.section_recovery else R.string.section_sleep)
    item {
        val asleep = metrics.sleep?.asleep
        MetricTile(
            title = stringResource(R.string.tile_sleep_duration),
            value = asleep?.let { valueText { duration(it) } } ?: noValue(stringResource(R.string.no_data)),
            subtitle = listOfNotNull(dayLabel(state), state.sleepScore?.let { stringResource(R.string.sleep_score_subtitle, it) }).joinToString(" · "),
            onClick = { onOpen(MetricPage.Sleep) },
        ) {
            if (asleep == null) {
                IconBadge(Icons.Outlined.Bedtime, OpenFitColors.Lavender)
            } else {
                ArcGauge(Icons.Outlined.Bedtime, OpenFitColors.Lavender, progress(asleep, profile.sleepGoalMinutes))
            }
        }
    }
    item {
        val readiness = state.readiness
        MetricTile(
            title = stringResource(R.string.readiness),
            value = readiness?.let { AnnotatedString(it.score.toString()) } ?: noValue(stringResource(R.string.no_score)),
            subtitle = listOfNotNull(dayLabel(state), readiness?.let { stringResource(readinessLabel(it)) }).joinToString(" • "),
            onClick = { onOpen(MetricPage.Readiness) },
        ) {
            if (readiness == null) {
                IconBadge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Lavender)
            } else {
                ArcGauge(Icons.AutoMirrored.Outlined.DirectionsRun, OpenFitColors.Lavender, readiness.score / 100f)
            }
        }
    }

    section(R.string.section_activity)
    item {
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
            onClick = { onOpen(MetricPage.CardioLoad) },
        ) {
            ArcGauge(Icons.Outlined.MonitorHeart, OpenFitColors.Mint, range?.progress(metrics.cardioLoad) ?: 0f, range = range?.fractions())
        }
    }
    item {
        val labels = state.week.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
        MetricTile(
            title = stringResource(R.string.tile_exercise_days),
            value = valueText { count(state.exerciseDays.size, profile.weeklyExerciseDaysGoal) },
            subtitle = weekLabel(state),
            onClick = { onOpen(MetricPage.Exercise) },
        ) {
            WeekBars(labels, state.week.map { it in state.exerciseDays }, OpenFitColors.Mint)
        }
    }
    item {
        val zoneMinutes = metrics.zoneMinutes?.total
        val target = state.zoneTarget
        MetricTile(
            title = stringResource(R.string.tile_zone_minutes),
            value = zoneMinutes?.let { AnnotatedString(whole(it)) } ?: noValue(stringResource(R.string.no_data)),
            subtitle = if (zoneMinutes == null && profile.age == null) {
                stringResource(R.string.zone_minutes_needs_age)
            } else {
                listOf(dayLabel(state), stringResource(R.string.week_progress, whole(target.doneThisWeek), whole(target.weekly))).joinToString(" • ")
            },
            onClick = { onOpen(MetricPage.ZoneMinutes) },
        ) {
            ArcGauge(Icons.Outlined.KeyboardDoubleArrowUp, OpenFitColors.Mint, (zoneMinutes ?: 0) / target.daily.coerceAtLeast(1.0).toFloat())
        }
    }
    item {
        MetricTile(stringResource(R.string.tile_steps), AnnotatedString(whole(metrics.steps ?: 0)), dayLabel(state), onClick = { onOpen(MetricPage.Steps) }) {
            ArcGauge(Icons.AutoMirrored.Outlined.DirectionsWalk, OpenFitColors.Mint, (metrics.steps ?: 0).toFloat() / profile.dailyStepGoal)
        }
    }
    item {
        val hourly = hourlyActivity(metrics.hourlySteps, currentHour = if (state.isToday) LocalTime.now().hour else null)
        MetricTile(
            title = stringResource(R.string.tile_hourly_activity),
            value = valueText { count(hourly.met, hourly.hours.size) },
            subtitle = dayLabel(state),
            onClick = { onOpen(MetricPage.Hourly) },
        ) {
            HourlyBars(hourly.hours, "%02d:00".format(hourly.window.first), "%02d:00".format(hourly.window.last), OpenFitColors.Mint)
        }
    }
    item {
        val meters = metrics.distanceMeters ?: 0.0
        val imperial = profile.units == UnitSystem.Imperial
        MetricTile(
            title = stringResource(R.string.tile_distance),
            value = quantityText(
                twoDecimals(if (imperial) meters / METERS_PER_MILE else meters / 1_000),
                stringResource(if (imperial) R.string.unit_mi else R.string.unit_km),
            ),
            subtitle = dayLabel(state),
            onClick = { onOpen(MetricPage.Distance) },
        ) {
            ArcGauge(Icons.Outlined.LocationOn, OpenFitColors.Mint, (meters / profile.dailyDistanceGoalMeters).toFloat())
        }
    }
    item {
        val calories = metrics.caloriesOutKcal ?: 0.0
        MetricTile(
            title = stringResource(R.string.tile_energy_burned),
            value = quantityText(whole(calories), stringResource(R.string.unit_cal)),
            subtitle = dayLabel(state),
            onClick = { onOpen(MetricPage.Energy) },
        ) {
            ArcGauge(Icons.Outlined.LocalFireDepartment, OpenFitColors.Mint, (calories / profile.dailyCaloriesOutGoal).toFloat())
        }
    }
    item {
        val floors = metrics.floors ?: 0.0
        MetricTile(stringResource(R.string.tile_floors), AnnotatedString(whole(floors.toInt())), dayLabel(state), onClick = { onOpen(MetricPage.Floors) }) {
            ArcGauge(Icons.Outlined.Stairs, OpenFitColors.Mint, (floors / profile.dailyFloorsGoal).toFloat())
        }
    }

    val health = healthTiles(metrics, profile)
    val showHeart = metrics.latestHeartRate != null || metrics.heartRateRange != null || metrics.restingBpm != null
    val readings = state.healthMetrics
    if (health.isNotEmpty() || showHeart || readings.isNotEmpty()) section(R.string.section_health)
    if (readings.isNotEmpty()) {
        item { HealthMetricsTile(readings, onClick = { onOpen(MetricPage.HealthMetrics) }) }
        item { HealthMetricsSummary(readings, onClick = { onOpen(MetricPage.HealthMetrics) }) }
    }
    if (showHeart) item { HeartTile(state, onClick = { onOpen(MetricPage.Heart) }) }
    health.forEach { tile ->
        item {
            val weight = tile.title == R.string.tile_weight
            MetricTile(
                title = stringResource(tile.title),
                value = quantityText(tile.value, stringResource(tile.unit)),
                subtitle = listOfNotNull(
                    readingDayLabel(state, tile.reading.time),
                    tile.bodyFat?.let { stringResource(R.string.body_fat_detail, it) },
                    if (weight) metrics.weightKg?.let { goalText(it.value, profile) } else null,
                ).joinToString(" · "),
                onClick = if (weight) ({ onOpen(MetricPage.Weight) }) else null,
            ) {
                IconBadge(tile.icon, OpenFitColors.Blue)
            }
        }
    }

    section(R.string.section_nutrition)
    item {
        val eaten = metrics.caloriesInKcal ?: 0.0
        val difference = profile.dailyCaloriesInGoal - eaten
        MetricTile(
            title = stringResource(R.string.tile_food),
            value = quantityText(whole(eaten), stringResource(R.string.unit_cal)),
            subtitle = listOf(
                dayLabel(state),
                stringResource(if (difference >= 0) R.string.food_below_target else R.string.food_above_target, whole(abs(difference))),
            ).joinToString(" · "),
            onClick = { onOpen(MetricPage.Food) },
        ) {
            ArcGauge(Icons.Outlined.Restaurant, OpenFitColors.Pink, (eaten / profile.dailyCaloriesInGoal).toFloat())
        }
    }
    item {
        val liters = metrics.waterLiters ?: 0.0
        val imperial = profile.units == UnitSystem.Imperial
        MetricTile(
            title = stringResource(R.string.tile_water),
            value = quantityText(
                if (imperial) oneDecimal(liters * FL_OZ_PER_LITER) else whole(liters * 1_000),
                stringResource(if (imperial) R.string.unit_fl_oz else R.string.unit_ml),
            ),
            subtitle = dayLabel(state),
            onClick = { onOpen(MetricPage.Water) },
        ) {
            ArcGauge(Icons.Outlined.LocalDrink, OpenFitColors.Blue, (liters / profile.dailyWaterGoalLiters).toFloat())
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
        Column(Modifier.padding(16.dp)) {
            Text(
                readings.map { stringResource(abbreviation(it.metric)) }.joinToString(", "),
                style = MaterialTheme.typography.titleSmall,
                color = OpenFitColors.OnSurface,
            )
            val outside = readings.filter { it.inRange == false }
            if (outside.isEmpty()) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = OpenFitColors.Blue.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.hm_in_personal_range), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                }
            } else {
                outside.forEach { reading ->
                    val above = reading.range?.let { reading.value > it.high } == true
                    Text(
                        stringResource(R.string.hm_value_out_of_range, stringResource(abbreviation(reading.metric)), stringResource(if (above) R.string.hm_above_range else R.string.hm_below_range)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenFitColors.OnSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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
        value = quantityText(
            latest?.value?.toString() ?: range?.let { "${it.first}–${it.last}" } ?: resting.toString(),
            stringResource(R.string.unit_bpm),
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

private class HealthTile(
    @StringRes val title: Int,
    val reading: Reading<Double>,
    val value: String,
    @StringRes val unit: Int,
    val icon: ImageVector,
    val bodyFat: String? = null,
)

/** Health tiles besides Heart: Fitbit shows HRV, SpO2, breathing rate and skin temperature inside Health Metrics instead. */
private fun healthTiles(metrics: DailyMetrics, profile: Profile): List<HealthTile> = listOfNotNull(
    metrics.vo2Max?.let { HealthTile(R.string.tile_cardio_fitness, it, whole(it.value), R.string.unit_vo2, Icons.AutoMirrored.Outlined.DirectionsRun) },
    metrics.weightKg?.let { weight ->
        val pounds = profile.weightUnit == WeightUnit.Pounds
        HealthTile(
            title = R.string.tile_weight,
            reading = weight,
            value = oneDecimal(if (pounds) weight.value * POUNDS_PER_KG else weight.value),
            unit = if (pounds) R.string.unit_lb else R.string.unit_kg,
            icon = Icons.Outlined.MonitorWeight,
            bodyFat = metrics.bodyFatPercent?.let { oneDecimal(it.value) },
        )
    },
)

private fun LazyListScope.section(@StringRes title: Int) {
    item {
        Text(
            stringResource(title),
            style = SectionHeaderStyle,
            color = OpenFitColors.OnSurface,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 4.dp),
        )
    }
}

@Composable
fun MetricTile(
    title: String,
    value: AnnotatedString,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
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
    if (state.isToday) stringResource(R.string.today) else state.date.weekdayDayMonth()

@Composable
private fun weekLabel(state: TodayUiState.Ready): String =
    if (LocalDate.now() in state.week) stringResource(R.string.this_week) else weekRange(state.week.first(), state.week.last())

@Composable
private fun readingDayLabel(state: TodayUiState.Ready, time: Instant): String {
    val day = time.atZone(ZoneId.systemDefault()).toLocalDate()
    return if (day == LocalDate.now()) stringResource(R.string.today) else if (day == state.date) dayLabel(state) else day.weekdayDayMonth()
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

private fun progress(asleep: Duration?, goalMinutes: Int): Float = (asleep?.toMinutes() ?: 0).toFloat() / goalMinutes
