package com.openfit.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.profile.Profile
import com.openfit.ui.goals.GoalCategory
import com.openfit.ui.goals.setGoals
import androidx.compose.material.icons.outlined.Restaurant
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle
import com.openfit.ui.today.TodayUiState
import com.openfit.ui.today.TodayViewModel
import com.openfit.ui.today.GoogleHealthStatus
import com.openfit.ui.today.ProfileDialog
import com.openfit.ui.today.googleHealthNotice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** A goal category on the You tab and how many of its goals are set. */
private data class GoalGroup(val title: Int, val icon: ImageVector, val active: Int)

/**
 * Fitbit's You tab, limited to what openFit can show: the profile card, goals and Health Connect. Fitbit's community,
 * Premium and badges depend on Fitbit's services.
 */
@Composable
fun YouRoute(viewModel: TodayViewModel, onOpenHealthConnect: () -> Unit, onOpenGoals: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? TodayUiState.Ready
    val profile = ready?.profile
    var editing by rememberSaveable { mutableStateOf(false) }
    val onEditProfile = { editing = true }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { viewModel.refresh() }
    val scope = rememberCoroutineScope()
    if (editing && ready != null) {
        ProfileDialog(
            profile = ready.profile,
            googleHealthConnected = ready.googleHealth != GoogleHealthStatus.Off,
            googleHealthProblem = googleHealthNotice(ready.googleHealth)?.let { it.message ?: stringResource(it.messageRes) },
            onConnectGoogleHealth = {
                editing = false
                scope.launch { viewModel.connectGoogleHealth()?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
            },
            onDisconnectGoogleHealth = {
                editing = false
                viewModel.disconnectGoogleHealth()
            },
            onDismiss = { editing = false },
            onSave = {
                editing = false
                viewModel.saveProfile(it)
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(OpenFitColors.Background).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                fontSize = 26.sp,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        item { ProfileCard(profile, onEditProfile) }
        // Fitbit's order is profile, Records, Goals; its Premium, Community, assessments and Badges sections are online.
        item {
            Text(
                stringResource(R.string.you_records),
                style = SectionHeaderStyle,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp),
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = OpenFitColors.Card,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpenHealthConnect),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = OpenFitColors.OnSurface, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.you_health_connect), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                        Text(stringResource(R.string.you_health_connect_body), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.you_goals), style = SectionHeaderStyle, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.you_see_all),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenFitColors.Mint.accent,
                    modifier = Modifier.clickable(onClick = onOpenGoals),
                )
            }
        }
        item {
            val groups = GoalCategory.entries.mapNotNull { category ->
                val count = profile?.let { setGoals(it, category) } ?: 0
                if (count == 0) null else GoalGroup(category.title, category.icon, count)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groups.isEmpty()) item { SetGoalCard(onOpenGoals) }
                items(groups) { group -> GoalCard(group, onOpenGoals) }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Profile?, onEditProfile: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp)) {
            Icon(Icons.Outlined.Hub, contentDescription = null, tint = OpenFitColors.OnSurface, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(56.dp))
            Text(
                profile?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.you_default_name),
                style = MaterialTheme.typography.displaySmall,
                color = OpenFitColors.OnSurface,
            )
            profile?.age?.let {
                Text(pluralStringResource(R.plurals.you_age, it, it), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
            }
            Text(
                stringResource(R.string.you_edit_profile),
                style = MaterialTheme.typography.titleMedium,
                color = OpenFitColors.Mint.accent,
                modifier = Modifier.padding(top = 4.dp).clickable(onClick = onEditProfile),
            )
        }
    }
}

@Composable
private fun GoalCard(group: GoalGroup, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenFitColors.Card,
        modifier = Modifier.size(width = 114.dp, height = 154.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(group.icon, contentDescription = null, tint = OpenFitColors.OnSurface, modifier = Modifier.size(24.dp))
            Column {
                Text(stringResource(group.title), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                Text(pluralStringResource(R.plurals.you_goal_count, group.active, group.active), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            }
        }
    }
}

private val GoalCategory.icon: ImageVector
    get() = when (this) {
        GoalCategory.Sleep -> Icons.Outlined.Bedtime
        GoalCategory.Activity -> Icons.AutoMirrored.Outlined.DirectionsWalk
        GoalCategory.Nutrition -> Icons.Outlined.Restaurant
        GoalCategory.Health -> Icons.Outlined.BarChart
    }

/** Shown in place of the cards while no goal is set (Fitbit's "Set a goal"). */
@Composable
private fun SetGoalCard(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = OpenFitColors.Card,
        modifier = Modifier.size(width = 114.dp, height = 154.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Bottom) {
            Text(stringResource(R.string.goals_set_a_goal), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.Mint.accent)
        }
    }
}
