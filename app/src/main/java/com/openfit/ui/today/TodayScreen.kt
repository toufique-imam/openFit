package com.openfit.ui.today

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.health.SyncState
import com.openfit.ui.MetricPage
import com.openfit.profile.Profile
import com.openfit.ui.theme.OpenFitColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun TodayRoute(
    viewModel: TodayViewModel,
    layout: TodayLayout,
    onOpenHealthConnectStore: () -> Unit,
    onOpen: (MetricPage, LocalDate) -> Unit,
    /** A section tab: that section's cards under its title, without the focus area. */
    section: TodaySection? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { viewModel.refresh() }
    val requestPermissions = { permissions: Set<String> -> permissionLauncher.launch(permissions) }

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }


    Scaffold(containerColor = OpenFitColors.Background) { padding ->
        Column(Modifier.padding(padding)) {
            when (val current = state) {
                TodayUiState.Loading -> CenteredMessage { CircularProgressIndicator() }
                is TodayUiState.Unavailable -> CenteredMessage(
                    text = stringResource(if (current.updateRequired) R.string.hc_update_required else R.string.hc_unavailable),
                    action = if (current.updateRequired) stringResource(R.string.hc_open_store) to onOpenHealthConnectStore else null,
                )
                is TodayUiState.PermissionsRequired -> CenteredMessage(
                    text = stringResource(R.string.permissions_rationale),
                    action = stringResource(R.string.permissions_grant) to { requestPermissions(current.permissions) },
                )
                is TodayUiState.Failed -> CenteredMessage(
                    // Health Connect caps how many reads an app makes in a while; its own message reads like a crash.
                    text = if (current.message.contains("quota", ignoreCase = true)) stringResource(R.string.hc_rate_limited) else current.message,
                    action = stringResource(R.string.retry) to viewModel::refresh,
                )
                is TodayUiState.Ready -> TodayScreen(
                    state = current,
                    syncState = syncState,
                    layout = layout,
                    section = section,
                    onRefresh = viewModel::refresh,
                    onShowDate = viewModel::showDate,
                    onRequestPermissions = { requestPermissions(current.missingPermissions) },
                    onOpen = { page -> onOpen(page, current.date) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayScreen(
    state: TodayUiState.Ready,
    syncState: SyncState,
    layout: TodayLayout,
    section: TodaySection?,
    onRefresh: () -> Unit,
    onShowDate: (LocalDate) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpen: (MetricPage) -> Unit,
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    // While a date with no snapshot loads, the bar already names it and the old day's cards fade.
    val shownDate = state.pendingDate ?: state.date
    val copy = copyStatus(syncState)
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") { Header(title = section?.let { stringResource(it.title) }, onOpenProfile = { onOpen(MetricPage.Profile) }) }
            item(key = "date") {
                Column {
                    DateBar(
                        date = shownDate,
                        onPrevious = { onShowDate(shownDate.minusDays(1)) },
                        onNext = { onShowDate(shownDate.plusDays(1)) },
                        onToday = { onShowDate(LocalDate.now()) },
                        onPick = { pickingDate = true },
                        onCustomize = if (section == null) ({ onOpen(MetricPage.CustomizeToday) }) else null,
                    )
                    UpdatingLine(state.updating || state.pendingDate != null)
                }
            }
            copy?.let { status ->
                item(key = "copy") { Notice(status, null, null) }
            }
            if (state.missingPermissions.isNotEmpty()) {
                item(key = "permissions") { Notice(stringResource(R.string.permissions_partial), stringResource(R.string.permissions_grant), onRequestPermissions) }
            }
            val faded = state.pendingDate != null
            if (section == null) {
                item(key = "focus") { Box(Modifier.alpha(if (faded) FADED_ALPHA else 1f)) { FocusSection(state, layout, onOpen) } }
                todaySections(state, layout, onOpen, faded)
            } else {
                sectionTiles(state, section, onOpen, header = false, faded = faded)
            }
        }
    }

    if (pickingDate) {
        DatePickerSheet(
            selected = shownDate,
            onDismiss = { pickingDate = false },
            onPick = {
                pickingDate = false
                onShowDate(it)
            },
        )
    }
}

@Composable
private fun Header(title: String?, onOpenProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(48.dp))
        Text(
            title ?: stringResource(R.string.app_name),
            fontSize = 26.sp,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenProfile) {
            Icon(Icons.Outlined.AccountCircle, stringResource(R.string.profile), tint = OpenFitColors.OnSurface, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun DateBar(
    date: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onPick: () -> Unit,
    onCustomize: (() -> Unit)?,
) {
    val isToday = date >= LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(DATE_BAR_SLOT)) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day), tint = OpenFitColors.OnSurface)
            }
        }
        Text(
            relativeDay(date),
            style = MaterialTheme.typography.titleMedium,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.pick_date), onClick = onPick).padding(vertical = 12.dp),
        )
        Row(Modifier.width(DATE_BAR_SLOT), horizontalArrangement = Arrangement.End) {
            if (!isToday) {
                IconButton(onClick = onToday) {
                    Icon(Icons.Outlined.CalendarToday, stringResource(R.string.jump_to_today), tint = OpenFitColors.OnSurface)
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day), tint = OpenFitColors.OnSurface)
                }
            } else if (onCustomize != null) {
                // Fitbit's pencil beside "Today" opens Customize Today.
                IconButton(onClick = onCustomize) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.customize_title), tint = OpenFitColors.OnSurface)
                }
            }
        }
    }
}

private val DATE_BAR_SLOT = 96.dp

/** A thin line under the date while the cards on screen are being reloaded. */
@Composable
private fun UpdatingLine(visible: Boolean) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (visible) LinearProgressIndicator(Modifier.fillMaxWidth(), color = OpenFitColors.Mint.accent, trackColor = OpenFitColors.Mint.track)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(selected: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDate(utcTimeMillis) <= today
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onPick(utcDate(it)) } ?: onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(pickerState)
    }
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun Notice(message: String, action: String?, onAction: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(24.dp), color = OpenFitColors.CardHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = if (action == null) 16.dp else 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurface)
            if (action != null && onAction != null) TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

/**
 * While openFit's copy of Health Connect is incomplete, what Today says about it: pages read only the copy, so a day
 * not copied yet shows as no data. Null once the copy is complete.
 */
@Composable
private fun copyStatus(state: SyncState): String? = when (state) {
    is SyncState.Importing -> stringResource(R.string.copy_importing, state.progress.percent)
    is SyncState.Waiting -> stringResource(
        R.string.copy_waiting,
        state.resumeAt.atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)),
    )
    is SyncState.Idle -> if (state.imported) null else stringResource(R.string.copy_not_done)
    SyncState.Syncing -> null
}

internal class NoticeText(@StringRes val messageRes: Int, val message: String? = null)

internal fun googleHealthNotice(status: GoogleHealthStatus): NoticeText? = when (status) {
    GoogleHealthStatus.Connected -> null
    GoogleHealthStatus.Off -> null
    is GoogleHealthStatus.NeedsConsent -> NoticeText(R.string.google_health_reconnect)
    is GoogleHealthStatus.Failed -> NoticeText(R.string.google_health_reconnect, status.message)
}

@Composable
private fun CenteredMessage(
    text: String? = null,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
        text?.let { Text(it, textAlign = TextAlign.Center, color = OpenFitColors.OnSurface) }
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(label) }
        }
    }
}
