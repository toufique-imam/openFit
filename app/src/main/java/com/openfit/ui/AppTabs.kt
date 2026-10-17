package com.openfit.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.openfit.R
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.today.TodaySection

/**
 * The root tabs: Fitbit's Today and You, with the Journal where Fitbit's online Coach was. The [optional] ones are
 * added or removed in Customize Today; a section tab shows one of Today's sections on its own.
 */
enum class RootTab(@StringRes val label: Int, val icon: ImageVector, val optional: Boolean = false, val section: TodaySection? = null) {
    Today(R.string.tab_today, Icons.Outlined.WbTwilight),
    Journal(R.string.tab_journal, Icons.Outlined.EditNote, optional = true),
    Recovery(R.string.tab_recovery, Icons.Outlined.Bedtime, optional = true, section = TodaySection.Recovery),
    Activity(R.string.tab_activity, Icons.AutoMirrored.Outlined.DirectionsRun, optional = true, section = TodaySection.Activity),
    Health(R.string.tab_health, Icons.Outlined.FavoriteBorder, optional = true, section = TodaySection.Health),
    Stress(R.string.tab_stress, Icons.Outlined.SelfImprovement, optional = true, section = TodaySection.StressMindfulness),
    Nutrition(R.string.tab_nutrition, Icons.Outlined.Restaurant, optional = true, section = TodaySection.Nutrition),
    You(R.string.tab_you, Icons.Outlined.GridView),
}

/** Navigation uses the same surface and accent palette as the selected system theme. */
@Composable
fun BottomBar(tabs: List<RootTab>, selected: RootTab, onSelect: (RootTab) -> Unit) {
    NavigationBar(containerColor = OpenFitColors.Card) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = OpenFitColors.OnSurface,
                    selectedTextColor = OpenFitColors.OnSurface,
                    indicatorColor = OpenFitColors.PrimaryContainer,
                    unselectedIconColor = OpenFitColors.OnSurfaceVariant,
                    unselectedTextColor = OpenFitColors.OnSurfaceVariant,
                ),
            )
        }
    }
}
