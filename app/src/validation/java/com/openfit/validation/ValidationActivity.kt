package com.openfit.validation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Present only with -PopenfitValidation=true. It never connects a real health-data source. */
class ValidationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.openfit.validation.debug")
        enableEdgeToEdge()
        current = this
        renderScene()
    }

    /** The same composition root is used before and after recreation so saved-state keys match. */
    fun renderScene() {
        setContent { content() }
    }
    companion object {
        var current: ValidationActivity? = null
        var content: @Composable () -> Unit by mutableStateOf({})
    }
}
