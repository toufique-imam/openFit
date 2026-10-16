package com.openfit.ui.onboarding

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.Sex
import com.openfit.health.HealthConnectAvailability
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthSync
import com.openfit.health.ImportProgress
import com.openfit.health.ImportStage
import androidx.compose.runtime.remember
import com.openfit.profile.Profile
import com.openfit.profile.ProfileStore
import com.openfit.profile.UnitSystem
import com.openfit.profile.WeightUnit
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.TodayUiState
import com.openfit.ui.today.TodayViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Whether the user has been through onboarding. */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    var done: Boolean
        get() = prefs.getBoolean("done", false)
        set(value) = prefs.edit().putBoolean("done", value).apply()
}

private enum class Step { Welcome, Profile, Reads, Import }

/**
 * First run: what openFit is, the details Health Connect does not have (birth date, sex), what it reads and the
 * permission request, then a first load so Today opens with data.
 */
@Composable
fun OnboardingRoute(
    todayViewModel: TodayViewModel,
    sync: HealthSync,
    profileStore: ProfileStore,
    gateway: HealthConnectGateway,
    onOpenHealthConnectStore: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    BackHandler(enabled = step != Step.Welcome && step != Step.Import) {
        step = Step.entries[step.ordinal - 1]
    }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        gateway.invalidatePermissions()
        if (granted.isNotEmpty()) step = Step.Import
    }
    Box(Modifier.fillMaxSize().background(OpenFitColors.Background).systemBarsPadding()) {
        when (step) {
            Step.Welcome -> Welcome(onStart = { step = Step.Profile })
            Step.Profile -> ProfileStep(profileStore.load()) { profile ->
                profileStore.save(profile)
                step = Step.Reads
            }
            Step.Reads -> ReadsStep(
                availability = gateway.availability(),
                onConnect = {
                    when (gateway.availability()) {
                        HealthConnectAvailability.Available -> permissionLauncher.launch(gateway.requestablePermissions())
                        else -> onOpenHealthConnectStore()
                    }
                },
                onNotNow = onFinish,
            )
            Step.Import -> ImportStep(sync, todayViewModel, onFinish)
        }
    }
}

/** openFit's mark: the launcher ring, in mint, over a soft glow. */
@Composable
private fun Logo(size: Dp) {
    Canvas(Modifier.size(size)) {
        val mint = OpenFitColors.Mint.accent
        drawCircle(Brush.radialGradient(listOf(mint.copy(alpha = 0.22f), Color.Transparent)), radius = this.size.minDimension / 2)
        drawCircle(mint, radius = this.size.minDimension * 0.2f, style = Stroke(width = this.size.minDimension * 0.055f))
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Logo(220.dp)
        Text(stringResource(R.string.app_name), fontSize = 32.sp, color = OpenFitColors.OnSurface)
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = OpenFitColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        PrimaryButton(stringResource(R.string.onboarding_get_started), onClick = onStart)
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = OpenFitColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_private), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileStep(initial: Profile, onSave: (Profile) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var birthDay by rememberSaveable { mutableStateOf(initial.birthDate?.toEpochDay()) }
    var sex by rememberSaveable { mutableStateOf(initial.sex) }
    var units by rememberSaveable { mutableStateOf(initial.units) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val birthDate = birthDay?.let(LocalDate::ofEpochDay)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(32.dp))
            Heading(stringResource(R.string.onboarding_profile_title), stringResource(R.string.onboarding_profile_body))
            FieldLabel(stringResource(R.string.onboarding_name), optional = true)
            TextField(
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = { Text(stringResource(R.string.onboarding_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = OpenFitColors.Card,
                    unfocusedContainerColor = OpenFitColors.Card,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = OpenFitColors.Mint.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Helper(stringResource(R.string.onboarding_name_help))

            FieldLabel(stringResource(R.string.onboarding_birth_date))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = OpenFitColors.Card,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { picking = true },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        birthDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) ?: stringResource(R.string.onboarding_birth_date_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (birthDate == null) OpenFitColors.OnSurfaceVariant else OpenFitColors.OnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = OpenFitColors.OnSurfaceVariant)
                }
            }
            Helper(stringResource(R.string.onboarding_birth_date_help))

            FieldLabel(stringResource(R.string.onboarding_sex))
            Segmented(listOf(Sex.Male to R.string.sex_male, Sex.Female to R.string.sex_female), sex) { sex = it }
            Helper(stringResource(R.string.onboarding_sex_help))

            FieldLabel(stringResource(R.string.onboarding_units))
            Segmented(listOf(UnitSystem.Metric to R.string.units_metric, UnitSystem.Imperial to R.string.units_imperial), units) { units = it }
            Helper(stringResource(R.string.onboarding_units_help))
            Spacer(Modifier.height(24.dp))
        }
        Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            PrimaryButton(stringResource(R.string.onboarding_save_continue), enabled = birthDate != null && sex != null) {
                val date = birthDate ?: return@PrimaryButton
                onSave(
                    initial.copy(
                        name = name.trim(),
                        birthDate = date,
                        age = Period.between(date, LocalDate.now()).years,
                        sex = sex,
                        units = units,
                        weightUnit = if (units == UnitSystem.Imperial) WeightUnit.Pounds else WeightUnit.Kilograms,
                    ),
                )
            }
        }
    }

    if (picking) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            // Opens on a date that matches an age already entered, so confirming it keeps that age.
            initialSelectedDateMillis = (birthDate ?: today.minusYears(initial.age?.toLong() ?: DEFAULT_AGE)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDate(utcTimeMillis) <= today.minusYears(MIN_AGE)
                override fun isSelectableYear(year: Int): Boolean = year <= today.year - MIN_AGE
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { birthDay = utcDate(it).toEpochDay() }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state)
        }
    }
}

private const val MIN_AGE = 5L
/** The share of the progress bar the history import takes. */
private const val IMPORT_SHARE = 0.9f
private const val DEFAULT_AGE = 30L

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun ReadsStep(availability: HealthConnectAvailability, onConnect: () -> Unit, onNotNow: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(48.dp))
        Heading(stringResource(R.string.onboarding_reads_title), stringResource(R.string.onboarding_reads_body))
        Surface(shape = RoundedCornerShape(24.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                READS.forEachIndexed { index, (icon, text) ->
                    if (index > 0) HorizontalDivider(color = OpenFitColors.OnSurfaceVariant.copy(alpha = 0.15f))
                    Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, tint = OpenFitColors.Mint.accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurface)
                    }
                }
            }
        }
        if (availability != HealthConnectAvailability.Available) {
            Text(
                stringResource(if (availability == HealthConnectAvailability.UpdateRequired) R.string.hc_update_required else R.string.hc_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.Pink.accent,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton(
            stringResource(if (availability == HealthConnectAvailability.Available) R.string.onboarding_connect else R.string.hc_open_store),
            icon = Icons.Outlined.HealthAndSafety,
            onClick = onConnect,
        )
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.onboarding_not_now), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.Mint.accent)
        }
    }
}

private val READS = listOf(
    Icons.AutoMirrored.Outlined.DirectionsWalk to R.string.onboarding_reads_activity,
    Icons.Outlined.FavoriteBorder to R.string.onboarding_reads_heart,
    Icons.Outlined.Bedtime to R.string.onboarding_reads_sleep,
    Icons.Outlined.MonitorHeart to R.string.onboarding_reads_vitals,
    Icons.Outlined.Restaurant to R.string.onboarding_reads_nutrition,
    Icons.Outlined.MonitorWeight to R.string.onboarding_reads_weight,
    Icons.Outlined.SelfImprovement to R.string.onboarding_reads_mindfulness,
)

/** The first load: today, then Energy Bank, then Pulse Age; Today opens from the snapshot it leaves. */
@Composable
private fun ImportStep(sync: HealthSync, todayViewModel: TodayViewModel, onFinish: () -> Unit) {
    var imported by remember { mutableStateOf<ImportProgress?>(null) }
    var importDone by remember { mutableStateOf(false) }
    val state by todayViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        // The history first (it stops early if Health Connect's quota runs low), then Today from the saved copy.
        sync.importHistory { imported = it }
        importDone = true
        todayViewModel.refresh()
    }
    val ready = (state as? TodayUiState.Ready)?.takeIf { importDone && !it.refreshing && it.pendingDate == null }
    // The import is most of the work; Today's own load is the last tenth.
    val importShare = imported?.let { it.done.toFloat() / it.total.coerceAtLeast(1) } ?: 0f
    val progress = when {
        !importDone -> IMPORT_SHARE * importShare
        ready == null -> IMPORT_SHARE
        ready.energyLoading || ready.pulseAgeLoading -> (1 + IMPORT_SHARE) / 2
        else -> 1f
    }
    val stage = when {
        !importDone -> when (imported?.stage) {
            ImportStage.HeartRate -> stringResource(R.string.onboarding_import_heart, imported!!.done, imported!!.total)
            ImportStage.Scoring -> stringResource(R.string.onboarding_import_scoring)
            else -> stringResource(R.string.onboarding_import_reading)
        }
        else -> stringResource(R.string.onboarding_import_finishing)
    }
    val stopped = importDone && (state is TodayUiState.Failed || state is TodayUiState.PermissionsRequired || state is TodayUiState.Unavailable)
    LaunchedEffect(progress, stopped) {
        if (progress >= 1f || stopped) {
            delay(400)
            onFinish()
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Logo(160.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_import_title), style = MaterialTheme.typography.headlineSmall, color = OpenFitColors.OnSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_import_body), style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(48.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = OpenFitColors.Mint.accent,
            trackColor = OpenFitColors.Mint.track,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(16.dp))
        Text(stage, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
    }
}

@Composable
private fun Heading(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, color = OpenFitColors.OnSurface)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun FieldLabel(text: String, optional: Boolean = false) {
    Row(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = OpenFitColors.OnSurface)
        if (optional) {
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_optional), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
    }
}

@Composable
private fun Helper(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** Fitbit's two-way switch: the chosen option is a mint pill inside a card-coloured track. */
@Composable
private fun <T> Segmented(options: List<Pair<T, Int>>, selected: T?, onSelect: (T) -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp)) {
            options.forEach { (value, label) ->
                val chosen = value == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (chosen) OpenFitColors.Mint.accent else Color.Transparent)
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (chosen) ON_MINT else OpenFitColors.OnSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val ON_MINT = Color(0xFF00382E)

@Composable
private fun PrimaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OpenFitColors.Mint.accent,
            contentColor = ON_MINT,
            disabledContainerColor = OpenFitColors.Card,
            disabledContentColor = OpenFitColors.OnSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
