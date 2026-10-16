package com.openfit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openfit.googlehealth.GoogleHealthAuth
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.profile.ProfileStore
import com.openfit.ui.BottomBar
import com.openfit.ui.MetricPage
import com.openfit.ui.RootTab
import com.openfit.ui.cardio.CardioLoadRoute
import com.openfit.ui.cardio.CardioLoadViewModel
import com.openfit.ui.coach.CoachRoute
import com.openfit.ui.exercise.ExerciseRoute
import com.openfit.ui.exercise.ExerciseViewModel
import com.openfit.ui.goal.GoalMetric
import com.openfit.ui.goal.GoalMetricRoute
import com.openfit.ui.goal.GoalMetricViewModel
import com.openfit.ui.heart.HeartRoute
import com.openfit.ui.heart.HeartViewModel
import com.openfit.ui.hourly.HourlyRoute
import com.openfit.ui.hourly.HourlyViewModel
import com.openfit.ui.readiness.ReadinessRoute
import com.openfit.ui.readiness.ReadinessViewModel
import com.openfit.ui.sleep.SleepRoute
import com.openfit.ui.weight.WeightRoute
import com.openfit.ui.weight.WeightViewModel
import com.openfit.ui.vitals.HealthMetricsRoute
import com.openfit.ui.vitals.HealthMetricsViewModel
import com.openfit.ui.sleep.SleepViewModel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.OpenFitTheme
import com.openfit.ui.today.TodayRoute
import com.openfit.ui.today.TodayViewModel
import com.openfit.ui.you.YouRoute
import java.time.LocalDate

private sealed interface Screen {
    data class Tab(val tab: RootTab) : Screen
    data class Metric(val page: MetricPage, val date: LocalDate) : Screen
}

private val BackStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                is Screen.Tab -> "tab:${it.tab.name}"
                is Screen.Metric -> "${it.page.name}:${it.date}"
            }
        }
    },
    restore = { saved ->
        mutableStateListOf<Screen>().apply {
            saved.forEach { entry ->
                val (first, second) = entry.split(':', limit = 2)
                add(if (first == "tab") Screen.Tab(RootTab.valueOf(second)) else Screen.Metric(MetricPage.valueOf(first), LocalDate.parse(second)))
            }
        }
    },
)

class MainActivity : ComponentActivity() {

    private lateinit var gateway: HealthConnectGateway
    private lateinit var profileStore: ProfileStore
    private lateinit var dayLoadCache: DayLoadCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        gateway = HealthConnectGateway(applicationContext)
        profileStore = ProfileStore(applicationContext)
        dayLoadCache = DayLoadCache(applicationContext)
        setContent {
            OpenFitTheme {
                val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Screen>(Screen.Tab(RootTab.Today)) }
                val todayViewModel = viewModel { TodayViewModel(gateway, profileStore, GoogleHealthAuth(applicationContext), dayLoadCache) }
                val top = backStack.last()
                val showTab: (RootTab) -> Unit = { tab ->
                    backStack.clear()
                    backStack.add(Screen.Tab(tab))
                }
                val pop = {
                    backStack.removeAt(backStack.lastIndex)
                    todayViewModel.refresh()
                }
                // Back closes a metric screen, and from Coach or You returns to Today, as in Fitbit.
                BackHandler(enabled = backStack.size > 1 || top != Screen.Tab(RootTab.Today)) {
                    if (backStack.size > 1) pop() else showTab(RootTab.Today)
                }
                Scaffold(
                    containerColor = OpenFitColors.Background,
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = { if (top is Screen.Tab) BottomBar(top.tab, showTab) },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        when (top) {
                            is Screen.Tab -> TabScreen(top.tab, todayViewModel) { page, date -> backStack.add(Screen.Metric(page, date)) }
                            is Screen.Metric -> MetricScreen(top, pop)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TabScreen(tab: RootTab, todayViewModel: TodayViewModel, onOpen: (MetricPage, LocalDate) -> Unit) {
        when (tab) {
            RootTab.Today -> TodayRoute(viewModel = todayViewModel, onOpenHealthConnectStore = ::openHealthConnectStore, onOpen = onOpen)
            RootTab.Coach -> CoachRoute(todayViewModel)
            RootTab.You -> YouRoute(todayViewModel, onOpenHealthConnect = ::openHealthConnectSettings)
        }
    }

    @Composable
    private fun MetricScreen(screen: Screen.Metric, onBack: () -> Unit) {
        when (screen.page) {
            MetricPage.CardioLoad -> {
                val model = viewModel { CardioLoadViewModel(gateway, profileStore, dayLoadCache) }
                LaunchedEffect(screen) { model.open(screen.date) }
                CardioLoadRoute(model, onBack)
            }
            MetricPage.Readiness -> {
                val model = viewModel { ReadinessViewModel(gateway, profileStore, dayLoadCache) }
                LaunchedEffect(screen) { model.open(screen.date) }
                ReadinessRoute(model, onBack)
            }
            MetricPage.Steps, MetricPage.Distance, MetricPage.Floors, MetricPage.Energy, MetricPage.ZoneMinutes, MetricPage.Food, MetricPage.Water -> {
                val metric = GoalMetric.valueOf(screen.page.name)
                val model = viewModel(key = metric.name) { GoalMetricViewModel(metric, gateway, profileStore, dayLoadCache) }
                LaunchedEffect(screen) { model.open(screen.date) }
                GoalMetricRoute(model, onBack)
            }
            MetricPage.Heart -> {
                val model = viewModel { HeartViewModel(gateway, profileStore, dayLoadCache) }
                LaunchedEffect(screen) { model.open(screen.date) }
                HeartRoute(model, onBack)
            }
            MetricPage.Exercise -> {
                val model = viewModel { ExerciseViewModel(gateway, profileStore) }
                LaunchedEffect(screen) { model.open(screen.date) }
                ExerciseRoute(model, onBack)
            }
            MetricPage.Hourly -> {
                val model = viewModel { HourlyViewModel(gateway) }
                LaunchedEffect(screen) { model.open(screen.date) }
                HourlyRoute(model, onBack)
            }
            MetricPage.HealthMetrics -> {
                val model = viewModel { HealthMetricsViewModel(gateway, profileStore) }
                LaunchedEffect(screen) { model.open(screen.date) }
                HealthMetricsRoute(model, onBack)
            }
            MetricPage.Weight -> {
                val model = viewModel { WeightViewModel(gateway, profileStore) }
                LaunchedEffect(screen) { model.open(screen.date) }
                WeightRoute(model, onBack)
            }
            MetricPage.Sleep -> {
                val model = viewModel { SleepViewModel(gateway, profileStore) }
                LaunchedEffect(screen) { model.open(screen.date) }
                SleepRoute(model, onBack)
            }
        }
    }

    private fun openHealthConnectSettings() {
        runCatching { startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")) }
    }

    private fun openHealthConnectStore() {
        val uri = Uri.parse("market://details?id=${HealthConnectGateway.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding")
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
    }
}
