package com.openfit.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.Sex
import com.openfit.domain.maxHeartRate
import com.openfit.profile.Profile
import com.openfit.profile.UnitSystem
import com.openfit.profile.WeightUnit
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.GoogleHealthStatus
import com.openfit.ui.today.TodayUiState
import com.openfit.ui.today.TodayViewModel
import com.openfit.ui.today.googleHealthNotice
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class Editing { None, Name, BirthDate, Sex, Units, WeightUnit, Delete }

/**
 * The user's details, laid out as a list (after Pulse's profile page): each row opens its own editor. Age follows the
 * birth date; heart rate zones use 220 − age as the maximum.
 */
@Composable
fun ProfileRoute(viewModel: TodayViewModel, onBack: () -> Unit, onDeleteEverything: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? TodayUiState.Ready
    val profile = ready?.profile ?: viewModel.profile()
    var editing by rememberSaveable { mutableStateOf(Editing.None) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { viewModel.refresh() }
    val scope = rememberCoroutineScope()
    val save: (Profile) -> Unit = {
        editing = Editing.None
        viewModel.saveProfile(it)
    }

    Column(Modifier.fillMaxSize().background(OpenFitColors.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = OpenFitColors.OnSurface) }
            Text(stringResource(R.string.profile), style = MaterialTheme.typography.headlineSmall, color = OpenFitColors.OnSurface, modifier = Modifier.padding(start = 12.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    NameHeader(profile) { editing = Editing.Name }
                    val birthDate = profile.birthDate
                    Divider()
                    InfoRow(stringResource(R.string.profile_birth_date), birthDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: stringResource(R.string.profile_not_set)) {
                        editing = Editing.BirthDate
                    }
                    Divider()
                    InfoRow(stringResource(R.string.profile_age), profile.age?.toString() ?: stringResource(R.string.profile_not_set))
                    Divider()
                    InfoRow(stringResource(R.string.profile_sex), profile.sex?.let { stringResource(sexLabel(it)) } ?: stringResource(R.string.profile_not_set)) {
                        editing = Editing.Sex
                    }
                    Divider()
                    InfoRow(
                        stringResource(R.string.profile_max_heart_rate),
                        profile.age?.let { stringResource(R.string.profile_max_heart_rate_value, maxHeartRate(it)) } ?: stringResource(R.string.profile_not_set),
                    )
                    Divider()
                    InfoRow(stringResource(R.string.units_distance), stringResource(if (profile.units == UnitSystem.Imperial) R.string.units_imperial else R.string.units_metric)) {
                        editing = Editing.Units
                    }
                    Divider()
                    InfoRow(stringResource(R.string.units_weight), stringResource(if (profile.weightUnit == WeightUnit.Pounds) R.string.unit_lb else R.string.unit_kg)) {
                        editing = Editing.WeightUnit
                    }
                    Divider()
                    InfoRow(stringResource(R.string.profile_time_zone), ZoneId.systemDefault().id)
                    Text(
                        stringResource(R.string.profile_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenFitColors.OnSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
            }

            ready?.let { current ->
                Spacer(Modifier.height(16.dp))
                GoogleHealthCard(
                    status = current.googleHealth,
                    onConnect = { scope.launch { viewModel.connectGoogleHealth()?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) } } },
                    onDisconnect = viewModel::disconnectGoogleHealth,
                )
            }

            OutlinedButton(
                onClick = { editing = Editing.Delete },
                border = BorderStroke(1.dp, OpenFitColors.Pink.accent),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 32.dp, bottom = 32.dp).height(52.dp),
            ) {
                Text(stringResource(R.string.profile_delete_everything), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.Pink.accent)
            }
        }
    }

    when (editing) {
        Editing.None -> Unit
        Editing.Name -> NameDialog(profile.name, onDismiss = { editing = Editing.None }) { save(profile.copy(name = it)) }
        Editing.BirthDate -> BirthDateDialog(profile, onDismiss = { editing = Editing.None }) { date ->
            save(profile.copy(birthDate = date, age = Period.between(date, LocalDate.now()).years))
        }
        Editing.Sex -> ChoiceDialog(
            title = stringResource(R.string.profile_sex),
            options = listOf(Sex.Male to stringResource(R.string.sex_male), Sex.Female to stringResource(R.string.sex_female)),
            selected = profile.sex,
            onDismiss = { editing = Editing.None },
        ) { save(profile.copy(sex = it)) }
        Editing.Units -> ChoiceDialog(
            title = stringResource(R.string.units_distance),
            options = listOf(UnitSystem.Metric to stringResource(R.string.units_metric), UnitSystem.Imperial to stringResource(R.string.units_imperial)),
            selected = profile.units,
            onDismiss = { editing = Editing.None },
        ) { save(profile.copy(units = it)) }
        Editing.WeightUnit -> ChoiceDialog(
            title = stringResource(R.string.units_weight),
            options = listOf(WeightUnit.Kilograms to stringResource(R.string.unit_kg), WeightUnit.Pounds to stringResource(R.string.unit_lb)),
            selected = profile.weightUnit,
            onDismiss = { editing = Editing.None },
        ) { save(profile.copy(weightUnit = it)) }
        Editing.Delete -> AlertDialog(
            onDismissRequest = { editing = Editing.None },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    editing = Editing.None
                    onDeleteEverything()
                }) { Text(stringResource(R.string.profile_delete_confirm), color = OpenFitColors.Pink.accent) }
            },
            dismissButton = { TextButton(onClick = { editing = Editing.None }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NameHeader(profile: Profile, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(OpenFitColors.Mint.track), contentAlignment = Alignment.Center) {
            Text(
                profile.name.trim().firstOrNull()?.uppercase() ?: "?",
                fontSize = 28.sp,
                color = OpenFitColors.Mint.accent,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name.ifBlank { stringResource(R.string.you_default_name) },
                style = MaterialTheme.typography.titleLarge,
                color = OpenFitColors.OnSurface,
            )
            Text(stringResource(R.string.profile_stays_here), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
        }
        TextButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = null, tint = OpenFitColors.Mint.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.profile_edit), color = OpenFitColors.Mint.accent)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) OpenFitColors.Mint.accent else OpenFitColors.OnSurfaceVariant)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = OpenFitColors.OnSurfaceVariant.copy(alpha = 0.15f))
}

@Composable
private fun GoogleHealthCard(status: GoogleHealthStatus, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val problem = googleHealthNotice(status)?.let { it.message ?: stringResource(it.messageRes) }
    Surface(shape = RoundedCornerShape(24.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.profile_google_health), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
            Text(
                problem ?: stringResource(if (status == GoogleHealthStatus.Off) R.string.google_health_offer else R.string.profile_google_health_on),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (status == GoogleHealthStatus.Off || problem != null) {
                    TextButton(onClick = onConnect) { Text(stringResource(R.string.google_health_connect), color = OpenFitColors.Mint.accent) }
                }
                if (status != GoogleHealthStatus.Off) {
                    TextButton(onClick = onDisconnect) { Text(stringResource(R.string.google_health_disconnect), color = OpenFitColors.Mint.accent) }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_name)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(MAX_NAME_LENGTH) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX_NAME_LENGTH = 40
private const val MIN_AGE = 5L
private const val DEFAULT_AGE = 30L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateDialog(profile: Profile, onDismiss: () -> Unit, onSave: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    // Without a birth date yet, the picker opens on one that matches the age already entered.
    val initial = profile.birthDate ?: today.minusYears(profile.age?.toLong() ?: DEFAULT_AGE)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDate(utcTimeMillis) <= today.minusYears(MIN_AGE)
            override fun isSelectableYear(year: Int): Boolean = year <= today.year - MIN_AGE
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onSave(utcDate(it)) } ?: onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state)
    }
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T?, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = value == selected,
                            onClick = { onPick(value) },
                            colors = RadioButtonDefaults.colors(selectedColor = OpenFitColors.Mint.accent),
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun sexLabel(sex: Sex): Int = when (sex) {
    Sex.Male -> R.string.sex_male
    Sex.Female -> R.string.sex_female
}
