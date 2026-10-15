package com.openfit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object MetricColors {
    val Steps = Color(0xFF2EC4B6)
    val ZoneMinutes = Color(0xFFF5A623)
    val HeartRate = Color(0xFFE94F64)
    val Sleep = Color(0xFF7B6CF6)
    val Track = Color(0x33888888)
}

@Composable
fun OpenFitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
