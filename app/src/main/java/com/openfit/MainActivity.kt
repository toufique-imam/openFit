package com.openfit

import android.content.Intent
import android.widget.Toast
import com.openfit.ui.Notices
import androidx.lifecycle.lifecycleScope
import com.openfit.data.OpenFitDatabase
import com.openfit.ui.profile.ProfileRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.openfit.data.SnapshotStore
import com.openfit.ui.today.CustomizeTodayRoute
import com.openfit.ui.today.TodayLayout
import com.openfit.ui.today.TodayLayoutStore
import com.openfit.ui.onboarding.OnboardingRoute
import com.openfit.ui.onboarding.OnboardingStore
import com.openfit.ui.vo2.Vo2MaxRoute
import com.openfit.ui.vo2.Vo2MaxViewModel
import java.util.UUID
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openfit.googlehealth.GoogleHealthAuth
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.health.HealthSync
import com.openfit.health.SyncWorker
import com.openfit.profile.ProfileStore
import com.openfit.ui.BottomBar
import com.openfit.ui.MetricPage
import com.openfit.ui.RootTab
import com.openfit.ui.cardio.CardioLoadRoute
import com.openfit.ui.cardio.CardioLoadViewModel
import com.openfit.journal.JournalStore
import com.openfit.health.SlotCache
import com.openfit.ui.energy.EnergyRoute
import com.openfit.ui.stress.StressRoute
import com.openfit.ui.stress.StressViewModel
import com.openfit.ui.age.PulseAgeRoute
import com.openfit.ui.age.PulseAgeViewModel
import com.openfit.ui.energy.EnergyViewModel
import com.openfit.ui.journal.BehavioursRoute
import com.openfit.ui.journal.JournalRoute
import com.openfit.ui.journal.JournalViewModel
import com.openfit.ui.exercise.ExerciseRoute
import com.openfit.ui.exercise.ExerciseViewModel
import com.openfit.ui.goal.GoalMetric
import com.openfit.ui.goal.GoalMetricRoute
import com.openfit.ui.goal.GoalMetricViewModel
import com.openfit.ui.goals.GoalsRoute
import com.openfit.ui.goals.GoalsViewModel
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
import com.openfit.ui.vitals.MetricDetailRoute
import com.openfit.ui.vitals.MetricDetailViewModel
import com.openfit.ui.vitals.metricOf
import com.openfit.ui.vitals.page
import com.openfit.ui.sleep.SleepViewModel
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.OpenFitTheme
import com.openfit.ui.today.TodayRoute
import com.openfit.ui.today.TodayViewModel
import com.openfit.ui.you.YouRoute
import java.time.LocalDate

/** Every preferences file openFit writes, for Delete everything. */
// The quota model (hc_budget) stays: Health Connect's own count of openFit's reads does not reset with it.
private val LOCAL_PREFS = listOf("profile", "today_layout", "journal", "day_load", "day_slots", "google_health", "onboarding", "hc_changes", "hc_import")

/** Tells this process apart from an earlier one whose saved state was restored. */
private val PROCESS = UUID.randomUUID().toString()

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
            // Entries from an older version (a removed tab or page) are dropped rather than crashing the restore.
            saved.forEach { entry ->
                val (first, second) = entry.split(':', limit = 2)
                runCatching {
                    if (first == "tab") Screen.Tab(RootTab.valueOf(second)) else Screen.Metric(MetricPage.valueOf(first), LocalDate.parse(second))
                }.onSuccess(::add)
            }
            if (isEmpty()) add(Screen.Tab(RootTab.Today))
        }
    },
)

class MainActivity : ComponentActivity() {

    private lateinit var gateway: HealthConnectGateway
    private lateinit var profileStore: ProfileStore
    private lateinit var dayLoadCache: DayLoadCache
    private lateinit var journalStore: JournalStore
    private lateinit var slotCache: SlotCache
    private lateinit var snapshotStore: SnapshotStore
    private lateinit var layoutStore: TodayLayoutStore
    private lateinit var onboardingStore: OnboardingStore
    private lateinit var healthSync: HealthSync

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        gateway = HealthConnectGateway(applicationContext)
        profileStore = ProfileStore(applicationContext)
        dayLoadCache = DayLoadCache(applicationContext)
        journalStore = JournalStore(applicationContext)
        slotCache = SlotCache(applicationContext)
        snapshotStore = SnapshotStore(applicationContext)
        layoutStore = TodayLayoutStore(applicationContext)
        onboardingStore = OnboardingStore(applicationContext)
        healthSync = HealthSync(applicationContext, gateway, dayLoadCache, slotCache, profileStore)
        SyncWorker.schedule(applicationContext)
        setContent {
            OpenFitTheme {
                val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Screen>(Screen.Tab(RootTab.Today)) }
                var layout by remember { mutableStateOf(layoutStore.load()) }
                // Each screen keeps its saved state (scroll position, selected period) while it is on the back stack.
                val screenStates = rememberSaveableStateHolder()
                val todayViewModel = viewModel {
                    TodayViewModel(gateway, profileStore, GoogleHealthAuth(applicationContext), dayLoadCache, slotCache, snapshotStore, healthSync)
                }
                LaunchedEffect(Unit) {
                    Notices.messages.collect { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
                }
                var onboarded by remember { mutableStateOf(onboardingStore.done) }
                if (!onboarded) {
                    OnboardingRoute(todayViewModel, healthSync, profileStore, gateway, onOpenHealthConnectStore = ::openHealthConnectStore) {
                        onboardingStore.done = true
                        onboarded = true
                    }
                    return@OpenFitTheme
                }
                val top = backStack.last()
                val showTab: (RootTab) -> Unit = { tab ->
                    backStack.forEachIndexed { index, screen -> if (screen is Screen.Metric) screenStates.removeState(stateKey(index, screen)) }
                    backStack.clear()
                    backStack.add(Screen.Tab(tab))
                }
                val pop = {
                    screenStates.removeState(stateKey(backStack.lastIndex, backStack.last()))
                    backStack.removeAt(backStack.lastIndex)
                    // Today keeps what it showed; it reloads only if that is stale or a goal changed.
                    todayViewModel.refreshIfStale()
                }
                val open: (MetricPage, LocalDate) -> Unit = { page, date -> backStack.add(Screen.Metric(page, date)) }
                // A tab taken off the bottom bar in Customize Today.
                if (top is Screen.Tab && top.tab !in layout.bar) {
                    LaunchedEffect(top) { showTab(RootTab.Today) }
                }
                // Back closes a metric screen, and from another tab returns to Today, as in Fitbit.
                BackHandler(enabled = backStack.size > 1 || top != Screen.Tab(RootTab.Today)) {
                    if (backStack.size > 1) pop() else showTab(RootTab.Today)
                }
                Scaffold(
                    containerColor = OpenFitColors.Background,
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = { if (top is Screen.Tab) BottomBar(layout.bar, top.tab, showTab) },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        screenStates.SaveableStateProvider(stateKey(backStack.lastIndex, top)) {
                            when (top) {
                                is Screen.Tab -> TabScreen(top.tab, todayViewModel, layout, open)
                                is Screen.Metric -> MetricScreen(
                                    screen = top,
                                    todayViewModel = todayViewModel,
                                    onBack = pop,
                                    onOpen = open,
                                    layout = layout,
                                    onSaveLayout = {
                                        layoutStore.save(it)
                                        layout = it
                                        pop()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Back-stack entries are unique by position, so the same page opened twice keeps two states. */
    private fun stateKey(index: Int, screen: Screen): String = when (screen) {
        is Screen.Tab -> "tab:${screen.tab.name}"
        is Screen.Metric -> "$index:${screen.page.name}:${screen.date}"
    }

    @Composable
    private fun TabScreen(tab: RootTab, todayViewModel: TodayViewModel, layout: TodayLayout, onOpen: (MetricPage, LocalDate) -> Unit) {
        when (tab) {
            RootTab.Journal -> JournalRoute(journalViewModel(), onEditBehaviours = { onOpen(MetricPage.JournalBehaviours, LocalDate.now()) })
            RootTab.You -> YouRoute(
                todayViewModel,
                onOpenHealthConnect = ::openHealthConnectSettings,
                onOpenGoals = { onOpen(MetricPage.Goals, LocalDate.now()) },
                onOpenCustomize = { onOpen(MetricPage.CustomizeToday, LocalDate.now()) },
                onOpenJournal = { onOpen(MetricPage.Journal, LocalDate.now()) },
                onOpenProfile = { onOpen(MetricPage.Profile, LocalDate.now()) },
            )
            // Today, and the section tabs: one section of Today on its own.
            else -> TodayRoute(
                viewModel = todayViewModel,
                layout = layout,
                onOpenHealthConnectStore = ::openHealthConnectStore,
                onOpen = onOpen,
                section = tab.section,
            )
        }
    }

    @Composable
    private fun MetricScreen(
        screen: Screen.Metric,
        todayViewModel: TodayViewModel,
        onBack: () -> Unit,
        onOpen: (MetricPage, LocalDate) -> Unit,
        layout: TodayLayout,
        onSaveLayout: (TodayLayout) -> Unit,
    ) {
        when (screen.page) {
            MetricPage.Profile -> ProfileRoute(todayViewModel, onBack, onDeleteEverything = ::deleteEverything)
            MetricPage.CustomizeToday -> CustomizeTodayRoute(layout, onSave = onSaveLayout, onClose = onBack, onOpenHealthConnect = ::openHealthConnectSettings)
            MetricPage.CardioLoad -> {
                val model = viewModel { CardioLoadViewModel(gateway, profileStore, dayLoadCache, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                CardioLoadRoute(model, onBack)
            }
            MetricPage.Readiness -> {
                val model = viewModel { ReadinessViewModel(gateway, profileStore, dayLoadCache, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                ReadinessRoute(model, onBack)
            }
            MetricPage.Steps, MetricPage.Distance, MetricPage.Floors, MetricPage.Energy, MetricPage.ZoneMinutes, MetricPage.Food, MetricPage.Water -> {
                val metric = GoalMetric.valueOf(screen.page.name)
                val model = viewModel(key = metric.name) { GoalMetricViewModel(metric, gateway, profileStore, dayLoadCache, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                GoalMetricRoute(model, onBack)
            }
            MetricPage.Heart -> {
                val model = viewModel { HeartViewModel(gateway, profileStore, dayLoadCache, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                HeartRoute(model, onBack)
            }
            MetricPage.Exercise -> {
                val model = viewModel { ExerciseViewModel(gateway, profileStore, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                ExerciseRoute(model, onBack)
            }
            MetricPage.Hourly -> {
                val model = viewModel { HourlyViewModel(gateway, profileStore, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                HourlyRoute(model, onBack)
            }
            MetricPage.HealthMetrics -> {
                val model = viewModel { HealthMetricsViewModel(gateway, profileStore, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                HealthMetricsRoute(model, onBack, onOpenMetric = { metric -> onOpen(page(metric), screen.date) })
            }
            MetricPage.MetricBreathingRate, MetricPage.MetricRestingHeartRate, MetricPage.MetricHrv, MetricPage.MetricBloodOxygen,
            MetricPage.MetricSkinTemperature -> {
                val metric = metricOf(screen.page) ?: return
                val model = viewModel(key = metric.name) { MetricDetailViewModel(metric, gateway, profileStore, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                MetricDetailRoute(model, metric, onBack)
            }
            MetricPage.Weight -> {
                val model = viewModel { WeightViewModel(gateway, profileStore, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                WeightRoute(model, onBack)
            }
            MetricPage.Sleep -> {
                val model = viewModel { SleepViewModel(gateway, profileStore, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                SleepRoute(model, onBack)
            }
            MetricPage.JournalBehaviours -> BehavioursRoute(journalViewModel(), onBack)
            MetricPage.Journal -> JournalRoute(journalViewModel(), onEditBehaviours = { onOpen(MetricPage.JournalBehaviours, LocalDate.now()) }, onBack = onBack)
            MetricPage.Vo2Max -> {
                val model = viewModel { Vo2MaxViewModel(gateway, profileStore, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                Vo2MaxRoute(model, onBack)
            }
            MetricPage.PulseAge -> {
                val model = viewModel { PulseAgeViewModel(gateway, profileStore, dayLoadCache, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                PulseAgeRoute(model, onBack)
            }
            MetricPage.Stress -> {
                val model = viewModel { StressViewModel(gateway, slotCache, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                StressRoute(model, onBack)
            }
            MetricPage.EnergyBank -> {
                val model = viewModel { EnergyViewModel(gateway, slotCache, snapshotStore) }
                OpenOnce { model.open(screen.date) }
                EnergyRoute(model, onBack)
            }
            MetricPage.Goals -> {
                val model = viewModel { GoalsViewModel(profileStore) }
                OpenOnce { model.reload() }
                GoalsRoute(model, onBack)
            }
        }
    }

    /**
     * Loads a screen when it is pushed, but not again when the user comes back to it from a screen above: its model still
     * holds what it showed. A new process (or a fresh visit) has an empty model, so it loads.
     */
    @Composable
    private fun OpenOnce(open: () -> Unit) {
        var openedIn by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            if (openedIn != PROCESS) {
                open()
                openedIn = PROCESS
            }
        }
    }

    /** One journal model for the tab and its Behaviours page, so a change on one shows on the other. */
    @Composable
    private fun journalViewModel(): JournalViewModel = viewModel { JournalViewModel(gateway, profileStore, dayLoadCache, journalStore) }

    /**
     * Deletes everything openFit keeps on this phone (profile, goals, layout, journal, caches and saved screens) and
     * starts again from onboarding. Health Connect's data is not openFit's to delete.
     */
    private fun deleteEverything() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { OpenFitDatabase.get(applicationContext).clearAllTables() }
            LOCAL_PREFS.forEach { getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }
            finish()
            startActivity(Intent(this@MainActivity, MainActivity::class.java))
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
