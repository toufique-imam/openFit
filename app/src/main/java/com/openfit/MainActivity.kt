package com.openfit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openfit.googlehealth.GoogleHealthAuth
import com.openfit.health.DayLoadCache
import com.openfit.health.HealthConnectGateway
import com.openfit.profile.ProfileStore
import com.openfit.ui.cardio.CardioLoadRoute
import com.openfit.ui.cardio.CardioLoadViewModel
import com.openfit.ui.MetricPage
import com.openfit.ui.goal.GoalMetric
import com.openfit.ui.goal.GoalMetricRoute
import com.openfit.ui.goal.GoalMetricViewModel
import com.openfit.ui.readiness.ReadinessRoute
import com.openfit.ui.readiness.ReadinessViewModel
import com.openfit.ui.theme.OpenFitTheme
import com.openfit.ui.today.TodayRoute
import com.openfit.ui.today.TodayViewModel
import java.time.LocalDate

private sealed interface Screen {
    data object Today : Screen
    data class Metric(val page: MetricPage, val date: LocalDate) : Screen
}

private val BackStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { if (it is Screen.Metric) "${it.page.name}:${it.date}" else "today" } },
    restore = { saved ->
        mutableStateListOf<Screen>().apply {
            saved.forEach { entry ->
                val (page, date) = entry.split(':').let { it.first() to it.getOrNull(1) }
                add(if (date == null) Screen.Today else Screen.Metric(MetricPage.valueOf(page), LocalDate.parse(date)))
            }
        }
    },
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val gateway = HealthConnectGateway(applicationContext)
        val profileStore = ProfileStore(applicationContext)
        val dayLoadCache = DayLoadCache(applicationContext)
        setContent {
            OpenFitTheme {
                val backStack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Screen>(Screen.Today) }
                val todayViewModel = viewModel { TodayViewModel(gateway, profileStore, GoogleHealthAuth(applicationContext), dayLoadCache) }
                val pop = {
                    backStack.removeAt(backStack.lastIndex)
                    todayViewModel.refresh()
                }
                BackHandler(enabled = backStack.size > 1, onBack = pop)
                when (val screen = backStack.last()) {
                    Screen.Today -> TodayRoute(
                        viewModel = todayViewModel,
                        onOpenHealthConnectStore = ::openHealthConnectStore,
                        onOpen = { page, date -> backStack.add(Screen.Metric(page, date)) },
                    )
                    is Screen.Metric -> when (screen.page) {
                        MetricPage.CardioLoad -> {
                            val cardioViewModel = viewModel { CardioLoadViewModel(gateway, profileStore, dayLoadCache) }
                            LaunchedEffect(screen) { cardioViewModel.open(screen.date) }
                            CardioLoadRoute(viewModel = cardioViewModel, onBack = pop)
                        }
                        MetricPage.Steps -> {
                            val stepsViewModel = viewModel { GoalMetricViewModel(GoalMetric.Steps, gateway, profileStore) }
                            LaunchedEffect(screen) { stepsViewModel.open(screen.date) }
                            GoalMetricRoute(viewModel = stepsViewModel, onBack = pop)
                        }
                        MetricPage.Readiness -> {
                            val readinessViewModel = viewModel { ReadinessViewModel(gateway, profileStore, dayLoadCache) }
                            LaunchedEffect(screen) { readinessViewModel.open(screen.date) }
                            ReadinessRoute(viewModel = readinessViewModel, onBack = pop)
                        }
                    }
                }
            }
        }
    }

    private fun openHealthConnectStore() {
        val uri = Uri.parse("market://details?id=${HealthConnectGateway.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding")
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
    }
}
