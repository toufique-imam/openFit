package com.openfit.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfit.R
import com.openfit.ui.RootTab
import com.openfit.ui.theme.MetricColor
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle

private sealed interface CustomizePage {
    data object Main : CustomizePage
    data object CustomFocus : CustomizePage
    data class PickMetric(val slot: Int) : CustomizePage
}

/**
 * Fitbit's Customize Today: pick a focus (or build one), move sections, show or hide cards. openFit adds the bottom-bar
 * tabs here too. Close discards the changes; Save keeps them.
 */
@Composable
fun CustomizeTodayRoute(layout: TodayLayout, onSave: (TodayLayout) -> Unit, onClose: () -> Unit, onOpenHealthConnect: () -> Unit) {
    var draft by remember { mutableStateOf(layout) }
    var page by remember { mutableStateOf<CustomizePage>(CustomizePage.Main) }
    var slots by remember { mutableStateOf(layout.custom) }

    BackHandler(enabled = page != CustomizePage.Main) {
        page = if (page is CustomizePage.PickMetric) CustomizePage.CustomFocus else CustomizePage.Main
    }

    when (val current = page) {
        CustomizePage.Main -> CustomizeMain(
            draft = draft,
            onChange = { draft = it },
            onEditCustom = {
                slots = draft.custom
                page = CustomizePage.CustomFocus
            },
            onSave = { onSave(draft) },
            onClose = onClose,
            onOpenHealthConnect = onOpenHealthConnect,
        )
        CustomizePage.CustomFocus -> CustomFocusEditor(
            slots = slots,
            onPick = { page = CustomizePage.PickMetric(it) },
            onSave = {
                draft = draft.copy(custom = slots, focus = FocusPreset.Custom)
                page = CustomizePage.Main
            },
            onClose = { page = CustomizePage.Main },
        )
        is CustomizePage.PickMetric -> MetricPicker(
            slot = current.slot,
            selected = slots[current.slot],
            taken = slots.filterIndexed { index, tile -> index != current.slot && tile != null }.filterNotNull().toSet(),
            onPick = { tile ->
                val updated = slots.toMutableList().apply { this[current.slot] = tile }
                // Slots fill in order, so clearing one moves the later ones up.
                slots = updated.filterNotNull().let { filled -> List(TodayLayout.CUSTOM_SLOTS) { filled.getOrNull(it) } }
                page = CustomizePage.CustomFocus
            },
            onBack = { page = CustomizePage.CustomFocus },
        )
    }
}

@Composable
private fun CustomizeMain(
    draft: TodayLayout,
    onChange: (TodayLayout) -> Unit,
    onEditCustom: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(OpenFitColors.Background).systemBarsPadding()) {
        TopBar(
            title = stringResource(R.string.customize_title),
            navigation = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = OpenFitColors.OnSurface) } },
            action = stringResource(R.string.save) to onSave,
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "focus") {
                Heading(stringResource(R.string.customize_focus), Modifier.padding(start = 24.dp, top = 16.dp, bottom = 16.dp))
                // Opens on the chosen preset and snaps card by card, as Fitbit's carousel does.
                val carousel = rememberLazyListState(initialFirstVisibleItemIndex = FocusPreset.entries.indexOf(draft.focus))
                LazyRow(
                    state = carousel,
                    flingBehavior = rememberSnapFlingBehavior(carousel, SnapPosition.Start),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(FocusPreset.entries) { preset ->
                        PresetCard(
                            preset = preset,
                            custom = draft.custom,
                            selected = draft.focus == preset,
                            onSelect = {
                                if (preset == FocusPreset.Custom && draft.custom.all { it == null }) onEditCustom() else onChange(draft.copy(focus = preset))
                            },
                            onEdit = onEditCustom,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 24.dp), color = OpenFitColors.OnSurfaceVariant.copy(alpha = 0.3f))
                Heading(stringResource(R.string.customize_show_hide), Modifier.padding(start = 16.dp, top = 32.dp, bottom = 8.dp))
            }
            draft.order.forEachIndexed { index, section ->
                item(key = "section:${section.name}") {
                    val title = stringResource(section.title)
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = SectionHeaderStyle, color = OpenFitColors.OnSurfaceVariant, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(draft.moved(section, -1)) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.customize_move_up, title), tint = arrowTint(index > 0))
                        }
                        IconButton(onClick = { onChange(draft.moved(section, 1)) }, enabled = index < draft.order.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.customize_move_down, title), tint = arrowTint(index < draft.order.lastIndex))
                        }
                    }
                }
                TodayTile.entries.filter { it.section == section }.forEach { tile ->
                    item(key = "tile:${tile.name}") {
                        SwitchRow(stringResource(tile.label), checked = tile !in draft.hidden) { shown ->
                            onChange(draft.copy(hidden = if (shown) draft.hidden - tile else draft.hidden + tile))
                        }
                    }
                }
            }
            tabRows(draft, onChange)
            item(key = "note") {
                Text(
                    stringResource(R.string.customize_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp),
                )
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenHealthConnect).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.customize_health_connect), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                        Text(stringResource(R.string.customize_health_connect_body), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = OpenFitColors.OnSurfaceVariant)
                }
            }
        }
    }
}

/** The optional bottom-bar tabs; at most [TodayLayout.MAX_EXTRA_TABS] fit beside Today and You. */
private fun LazyListScope.tabRows(draft: TodayLayout, onChange: (TodayLayout) -> Unit) {
    item(key = "tabs") {
        Heading(stringResource(R.string.customize_tabs), Modifier.padding(start = 24.dp, top = 32.dp))
        Text(
            stringResource(R.string.customize_tabs_body, TodayLayout.MAX_EXTRA_TABS),
            style = MaterialTheme.typography.bodyMedium,
            color = OpenFitColors.OnSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
        )
    }
    RootTab.entries.filter { it.optional }.forEach { tab ->
        item(key = "tab:${tab.name}") {
            val checked = tab in draft.tabs
            SwitchRow(
                label = stringResource(tab.section?.title ?: tab.label),
                checked = checked,
                enabled = checked || draft.tabs.size < TodayLayout.MAX_EXTRA_TABS,
            ) { on -> onChange(draft.copy(tabs = if (on) draft.tabs + tab else draft.tabs - tab)) }
        }
    }
}

@Composable
private fun arrowTint(enabled: Boolean) = if (enabled) OpenFitColors.OnSurface else OpenFitColors.OnSurfaceVariant.copy(alpha = 0.4f)

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = OpenFitColors.OnSurface, modifier = modifier)
}

@Composable
private fun TopBar(title: String, navigation: @Composable () -> Unit, action: Pair<String, () -> Unit>? = null, actionEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        navigation()
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = OpenFitColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick, enabled = actionEnabled) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = if (actionEnabled) OpenFitColors.Mint.accent else OpenFitColors.OnSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenFitColors.Card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) OpenFitColors.OnSurface else OpenFitColors.OnSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = OpenFitColors.Mint.accent,
                    checkedThumbColor = SWITCH_THUMB,
                    checkedBorderColor = OpenFitColors.Mint.accent,
                ),
            )
        }
    }
}

private val CARD_WIDTH = 296.dp
private val SWITCH_THUMB = Color(0xFF00382E)
private val PREVIEW_LARGE = 104.dp
private val PREVIEW_SMALL = 66.dp

@Composable
private fun PresetCard(preset: FocusPreset, custom: List<TodayTile?>, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    val metrics = if (preset == FocusPreset.Custom) custom else preset.metrics
    val empty = metrics.all { it == null }
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = OpenFitColors.Card,
        border = if (selected) BorderStroke(2.dp, OpenFitColors.Mint.accent) else null,
        modifier = Modifier.width(CARD_WIDTH).clip(RoundedCornerShape(32.dp)).clickable(onClick = onSelect),
    ) {
        Box {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FocusPreview(metrics, preset.paired, numbered = false)
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(if (preset == FocusPreset.Custom && empty) R.string.customize_focus else preset.title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenFitColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (empty) stringResource(R.string.focus_add_metrics) else metrics.filterNotNull().map { stringResource(it.focusLabel) }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OpenFitColors.OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = OpenFitColors.Mint.accent, unselectedColor = OpenFitColors.OnSurfaceVariant),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
            if (preset == FocusPreset.Custom) {
                IconButton(onClick = onEdit, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.focus_custom), tint = OpenFitColors.OnSurface)
                }
            }
        }
    }
}

/** The focus layout in miniature: a large circle and three small ones, with each metric's icon or its slot number. */
@Composable
private fun FocusPreview(metrics: List<TodayTile?>, paired: TodayTile?, numbered: Boolean, large: Dp = PREVIEW_LARGE) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val first = metrics.getOrNull(0)
        if (first != null && paired != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HalfCircle(first, large, top = true)
                HalfCircle(paired, large, top = false)
            }
        } else {
            PreviewCircle(first, large, if (numbered) 1 else null)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            (1 until TodayLayout.CUSTOM_SLOTS).forEach { slot ->
                if (numbered || slot < metrics.size) {
                    PreviewCircle(metrics.getOrNull(slot), PREVIEW_SMALL, if (numbered) slot + 1 else null)
                }
            }
        }
    }
}

@Composable
private fun PreviewCircle(tile: TodayTile?, size: Dp, number: Int?) {
    val color = tile?.color ?: OpenFitColors.Mint
    Box(Modifier.size(size).clip(CircleShape).background(color.track), contentAlignment = Alignment.Center) {
        when {
            tile != null -> Icon(tile.icon, contentDescription = null, tint = color.accent, modifier = Modifier.size(size * 0.36f))
            number != null -> Text(
                number.toString(),
                fontSize = (size.value * 0.45f).sp,
                color = if (number == 1) OpenFitColors.OnSurface else OpenFitColors.OnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HalfCircle(tile: TodayTile, size: Dp, top: Boolean) {
    val shape = if (top) RoundedCornerShape(topStartPercent = 100, topEndPercent = 100) else RoundedCornerShape(bottomStartPercent = 100, bottomEndPercent = 100)
    Box(Modifier.size(width = size, height = size / 2 - 2.dp).clip(shape).background(tile.color.track), contentAlignment = Alignment.Center) {
        Icon(tile.icon, contentDescription = null, tint = tile.color.accent, modifier = Modifier.size(size * 0.24f))
    }
}

@Composable
private fun CustomFocusEditor(slots: List<TodayTile?>, onPick: (Int) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(OpenFitColors.Background).systemBarsPadding()) {
        TopBar(
            title = stringResource(R.string.focus_custom),
            navigation = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = OpenFitColors.OnSurface) } },
            action = stringResource(R.string.save) to onSave,
            actionEnabled = slots.first() != null,
        )
        Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FocusPreview(slots, paired = null, numbered = true, large = 160.dp)
        }
        slots.forEachIndexed { index, tile ->
            // Fitbit fills the slots in order: a slot opens once the one before it is set.
            val enabled = index == 0 || slots[index - 1] != null
            val tint = if (enabled) OpenFitColors.OnSurface else OpenFitColors.OnSurfaceVariant.copy(alpha = 0.5f)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { onPick(index) }.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.custom_metric, index + 1), style = MaterialTheme.typography.titleMedium, color = tint, modifier = Modifier.weight(1f))
                Text(tile?.let { stringResource(it.focusLabel) } ?: stringResource(R.string.custom_off), style = MaterialTheme.typography.titleMedium, color = tint)
            }
        }
    }
}

@Composable
private fun MetricPicker(slot: Int, selected: TodayTile?, taken: Set<TodayTile>, onPick: (TodayTile?) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(OpenFitColors.Background).systemBarsPadding()) {
        TopBar(
            title = stringResource(R.string.custom_metric, slot + 1),
            navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = OpenFitColors.OnSurface) } },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected != null) item(key = "off") { PickRow(stringResource(R.string.custom_off), selected = false) { onPick(null) } }
            TodaySection.entries.forEach { section ->
                val tiles = TodayTile.entries.filter { it.section == section && it !in taken }
                if (tiles.isEmpty()) return@forEach
                item(key = "section:${section.name}") {
                    Text(
                        stringResource(section.title),
                        style = SectionHeaderStyle,
                        color = OpenFitColors.OnSurface,
                        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                tiles.forEach { tile -> item(key = tile.name) { PickRow(stringResource(tile.focusLabel), selected = tile == selected) { onPick(tile) } } }
            }
        }
    }
}

@Composable
private fun PickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenFitColors.Card,
        border = if (selected) BorderStroke(2.dp, OpenFitColors.Mint.accent) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
    }
}

/** Each card's icon and colour, as on its Today card. */
val TodayTile.icon: ImageVector
    get() = when (this) {
        TodayTile.SleepDuration -> Icons.Outlined.Bedtime
        TodayTile.Readiness -> Icons.AutoMirrored.Outlined.DirectionsRun
        TodayTile.EnergyBank -> Icons.Outlined.BatteryChargingFull
        TodayTile.CardioLoad -> Icons.Outlined.MonitorHeart
        TodayTile.ExerciseDays -> Icons.Outlined.FitnessCenter
        TodayTile.ZoneMinutes -> Icons.Outlined.KeyboardDoubleArrowUp
        TodayTile.Steps -> Icons.AutoMirrored.Outlined.DirectionsWalk
        TodayTile.HourlyActivity -> Icons.Outlined.AccessTime
        TodayTile.Distance -> Icons.Outlined.LocationOn
        TodayTile.EnergyBurned -> Icons.Outlined.LocalFireDepartment
        TodayTile.Floors -> Icons.Outlined.Stairs
        TodayTile.HealthMetrics -> Icons.Outlined.BarChart
        TodayTile.Heart -> Icons.Outlined.FavoriteBorder
        TodayTile.Vo2Max -> Icons.AutoMirrored.Outlined.DirectionsRun
        TodayTile.PulseAge -> Icons.Outlined.Timelapse
        TodayTile.Weight -> Icons.Outlined.MonitorWeight
        TodayTile.Stress -> Icons.Outlined.SelfImprovement
        TodayTile.MindfulDays -> Icons.Outlined.Spa
        TodayTile.Food -> Icons.Outlined.Restaurant
        TodayTile.Water -> Icons.Outlined.LocalDrink
    }

val TodayTile.color: MetricColor
    get() = when (this) {
        TodayTile.SleepDuration, TodayTile.Readiness, TodayTile.EnergyBank, TodayTile.Stress, TodayTile.MindfulDays -> OpenFitColors.Lavender
        TodayTile.HealthMetrics, TodayTile.Heart, TodayTile.Vo2Max, TodayTile.PulseAge, TodayTile.Weight, TodayTile.Water -> OpenFitColors.Blue
        TodayTile.Food -> OpenFitColors.Pink
        else -> OpenFitColors.Mint
    }
