package com.openfit.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GridView
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
import com.openfit.R
import com.openfit.ui.theme.OpenFitColors

/** The root tabs: Fitbit's Today and You, with the Journal where Fitbit's online Coach was. */
enum class RootTab(@StringRes val label: Int, val icon: ImageVector) {
    Today(R.string.tab_today, Icons.Outlined.WbTwilight),
    Journal(R.string.tab_journal, Icons.Outlined.EditNote),
    You(R.string.tab_you, Icons.Outlined.GridView),
}

private val BAR = Color(0xFF1E201F)
private val INDICATOR = Color(0xFF324B45)
private val SELECTED_ICON = Color(0xFFCDE8E1)

/** Fitbit's bottom bar: 80dp, a #324B45 pill behind the selected icon. */
@Composable
fun BottomBar(selected: RootTab, onSelect: (RootTab) -> Unit) {
    NavigationBar(containerColor = BAR) {
        RootTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelLarge) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SELECTED_ICON,
                    selectedTextColor = OpenFitColors.OnSurface,
                    indicatorColor = INDICATOR,
                    unselectedIconColor = OpenFitColors.OnSurfaceVariant,
                    unselectedTextColor = OpenFitColors.OnSurfaceVariant,
                ),
            )
        }
    }
}
