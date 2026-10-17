package com.openfit.ui

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openfit.PermissionsRationaleActivity
import com.openfit.R

@Composable
fun PrivacyPolicyLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = { context.startActivity(Intent(context, PermissionsRationaleActivity::class.java)) },
        modifier = modifier,
    ) { Text(stringResource(R.string.privacy_policy_title)) }
}
