package com.openfit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openfit.ui.theme.OpenFitTheme

class PermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenFitTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding()) {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.close)) }
                        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Text(stringResource(R.string.privacy_policy), Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
    }
}
