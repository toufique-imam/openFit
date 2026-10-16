package com.openfit.ui.journal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.journal.JournalStore
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.theme.OpenFitColors

/** Choose what the journal asks, and add behaviours of your own. Past answers stay when a behaviour is unticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehavioursRoute(viewModel: JournalViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val asked = state.asked.map { it.key }.toSet()
    Scaffold(
        containerColor = OpenFitColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_behaviours_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OpenFitColors.Background,
                    titleContentColor = OpenFitColors.OnSurface,
                    navigationIconContentColor = OpenFitColors.OnSurface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Text(
                    stringResource(R.string.journal_behaviours_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PagePadding),
                )
            }
            item { AddOwn(onAdd = viewModel::addCustom) }
            JournalSection.entries.forEach { section ->
                val behaviours = state.behaviours.filter { it.section == section }
                if (behaviours.isEmpty()) return@forEach
                item(key = "title-${section.name}") {
                    Text(
                        stringResource(section.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = OpenFitColors.OnSurface,
                        modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = 32.dp, bottom = 12.dp),
                    )
                }
                itemsIndexed(behaviours, key = { _, behaviour -> behaviour.key }) { index, behaviour ->
                    val checked = behaviour.key in asked
                    GroupedCard(index, behaviours.size, minHeight = 64.dp, onClick = { viewModel.setAsked(behaviour.key, !checked) }) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.setAsked(behaviour.key, it) },
                                colors = CheckboxDefaults.colors(checkedColor = OpenFitColors.Mint.accent, checkmarkColor = OpenFitColors.Background),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(behaviour.labelText(), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                                Text(behaviour.questionText(), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddOwn(onAdd: (String) -> Boolean) {
    var label by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    val add = {
        error = !onAdd(label)
        if (!error) label = ""
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = label,
            onValueChange = {
                label = it.take(JournalStore.MAX_LABEL_LENGTH)
                error = false
            },
            label = { Text(stringResource(R.string.journal_add_own)) },
            isError = error,
            supportingText = if (error) {
                { Text(stringResource(R.string.journal_add_own_error)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = add, enabled = label.isNotBlank()) { Text(stringResource(R.string.journal_add)) }
    }
}
