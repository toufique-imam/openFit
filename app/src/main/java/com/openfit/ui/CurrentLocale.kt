package com.openfit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** Observes app-language and system-language changes, including per-app locales. */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
