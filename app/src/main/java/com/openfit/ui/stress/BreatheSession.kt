package com.openfit.ui.stress

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openfit.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreatheSession(onDismiss: () -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(2) }
    var remaining by rememberSaveable { mutableLongStateOf(120_000) }
    var endsAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val running = endsAt != null
    val total = minutes * 60_000L
    LaunchedEffect(endsAt) {
        val end = endsAt ?: return@LaunchedEffect
        while (true) {
            remaining = (end - SystemClock.elapsedRealtime()).coerceIn(0, total)
            if (remaining == 0L) { endsAt = null; break }
            delay(200)
        }
    }
    val inhale = (total - remaining) % 8_000 < 4_000
    val scale by animateFloatAsState(if (running && inhale) 1f else 0.65f, tween(4_000, easing = LinearEasing), label = "breathing circle")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.breathe_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.breathe_body), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(180.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
                Text(stringResource(when { remaining == 0L -> R.string.breathe_done; !running -> R.string.breathe_ready; inhale -> R.string.breathe_in; else -> R.string.breathe_out }),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge)
            }
            val seconds = (remaining + 999) / 1_000
            Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { minutes--; remaining = minutes * 60_000L }, enabled = !running && minutes > 1) { Text(stringResource(R.string.planner_less)) }
                Text(stringResource(R.string.breathe_minutes, minutes))
                TextButton(onClick = { minutes++; remaining = minutes * 60_000L }, enabled = !running && minutes < 10) { Text(stringResource(R.string.planner_more)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val end = endsAt
                    if (end != null) {
                        remaining = (end - SystemClock.elapsedRealtime()).coerceIn(0, total)
                        endsAt = null
                    } else {
                        if (remaining == 0L) remaining = total
                        endsAt = SystemClock.elapsedRealtime() + remaining
                    }
                }) { Text(stringResource(if (running) R.string.breathe_pause else R.string.breathe_start)) }
                OutlinedButton(onClick = { endsAt = null; remaining = total }) { Text(stringResource(R.string.breathe_reset)) }
            }
        }
    }
}
