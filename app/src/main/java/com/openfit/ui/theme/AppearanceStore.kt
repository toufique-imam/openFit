package com.openfit.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppearanceSettings(val appearance: Appearance = Appearance.System, val theme: ColorTheme = ColorTheme.Ocean)

/**
 * The look the user picked, apart from the profile: changing it should not make every saved screen stale. One per
 * process, so every activity follows a change at once.
 */
class AppearanceStore private constructor(private val prefs: SharedPreferences) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppearanceSettings> = _settings.asStateFlow()

    fun save(settings: AppearanceSettings) {
        prefs.edit().putString(KEY_APPEARANCE, settings.appearance.name).putString(KEY_THEME, settings.theme.name).apply()
        _settings.value = settings
    }

    private fun load() = AppearanceSettings(
        appearance = prefs.getString(KEY_APPEARANCE, null)?.let { name -> Appearance.entries.firstOrNull { it.name == name } } ?: Appearance.System,
        theme = prefs.getString(KEY_THEME, null)?.let { name -> ColorTheme.entries.firstOrNull { it.name == name } }
            ?.takeIf { it != ColorTheme.Dynamic || dynamicColorAvailable } ?: ColorTheme.Ocean,
    )

    companion object {
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_THEME = "color_theme"

        @Volatile private var instance: AppearanceStore? = null

        fun get(context: Context): AppearanceStore = instance ?: synchronized(this) {
            instance ?: AppearanceStore(context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}

/** [OpenFitTheme] in the look the user saved, with status and navigation bar icons that match it rather than the system. */
@Composable
fun ComponentActivity.SavedOpenFitTheme(content: @Composable () -> Unit) {
    val look by AppearanceStore.get(this).settings.collectAsState()
    val dark = look.appearance.isDark()
    DisposableEffect(dark) {
        // The scrims androidx.activity uses by default for three-button navigation.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF), AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)) { dark },
        )
        onDispose {}
    }
    OpenFitTheme(look.appearance, look.theme, content)
}
