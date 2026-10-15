package com.openfit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openfit.health.HealthConnectGateway
import com.openfit.profile.ProfileStore
import com.openfit.ui.theme.OpenFitTheme
import com.openfit.ui.today.TodayRoute
import com.openfit.ui.today.TodayViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenFitTheme {
                TodayRoute(
                    viewModel = viewModel { TodayViewModel(HealthConnectGateway(applicationContext), ProfileStore(applicationContext)) },
                    onOpenHealthConnectStore = ::openHealthConnectStore,
                )
            }
        }
    }

    private fun openHealthConnectStore() {
        val uri = Uri.parse("market://details?id=${HealthConnectGateway.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding")
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
    }
}
