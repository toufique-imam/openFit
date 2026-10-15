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
import com.openfit.ui.theme.OpenFitTheme
import com.openfit.ui.today.TodayRoute
import com.openfit.ui.today.TodayViewModel
import java.time.LocalDate

private sealed interface Screen {
    data object Today : Screen
    data class CardioLoad(val date: LocalDate) : Screen
}

private val BackStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { if (it is Screen.CardioLoad) "cardio:${it.date}" else "today" } },
    restore = { saved ->
        mutableStateListOf<Screen>().apply {
            saved.forEach { add(if (it.startsWith("cardio:")) Screen.CardioLoad(LocalDate.parse(it.removePrefix("cardio:"))) else Screen.Today) }
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
                        onOpenCardioLoad = { date -> backStack.add(Screen.CardioLoad(date)) },
                    )
                    is Screen.CardioLoad -> {
                        val cardioViewModel = viewModel { CardioLoadViewModel(gateway, profileStore, dayLoadCache) }
                        LaunchedEffect(screen) { cardioViewModel.open(screen.date) }
                        CardioLoadRoute(viewModel = cardioViewModel, onBack = pop)
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
