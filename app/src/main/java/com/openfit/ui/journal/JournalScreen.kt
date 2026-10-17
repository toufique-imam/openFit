package com.openfit.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfit.R
import com.openfit.domain.BehaviourImpact
import com.openfit.domain.ImpactVerdict
import com.openfit.domain.MIN_DAYS_PER_ANSWER
import com.openfit.ui.detail.GroupedCard
import com.openfit.ui.detail.PagePadding
import com.openfit.ui.theme.OpenFitColors
import com.openfit.ui.theme.SectionHeaderStyle
import com.openfit.ui.today.WeekBars
import com.openfit.ui.today.weekdayDayMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val ACCENT @Composable get() = OpenFitColors.Mint
private val CHIP_SELECTED @Composable get() = OpenFitColors.PrimaryContainer

/** The Journal tab: the day's check-in, then how each behaviour goes with the next day's scores. */
@Composable
fun JournalRoute(viewModel: JournalViewModel, onEditBehaviours: () -> Unit, onBack: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(OpenFitColors.Background).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            if (onBack == null) {
                Text(
                    stringResource(R.string.app_name),
                    fontSize = 26.sp,
                    color = OpenFitColors.OnSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                )
            } else {
                // Opened from You while Journal is not on the bottom bar.
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = OpenFitColors.OnSurface)
                    }
                    Text(stringResource(R.string.tab_journal), style = MaterialTheme.typography.headlineSmall, color = OpenFitColors.OnSurface, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        item { DateBar(state, onPrevious = { viewModel.showDate(state.date.minusDays(1)) }, onNext = { viewModel.showDate(state.date.plusDays(1)) }) }
        if (state.asked.isEmpty()) {
            item { SetupCard(onEditBehaviours) }
            return@LazyColumn
        }
        item {
            Text(
                if (state.isToday) stringResource(R.string.journal_heading_today) else stringResource(R.string.journal_heading_past, state.date.weekdayDayMonth()),
                style = MaterialTheme.typography.titleLarge,
                color = OpenFitColors.OnSurface,
                modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = 8.dp),
            )
        }
        item {
            val labels = state.week.map { it.first.dayOfWeek.getDisplayName(TextStyle.NARROW, com.openfit.ui.currentLocale()) }
            Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.journal_week_count, state.week.count { it.second }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenFitColors.OnSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                WeekBars(labels, state.week.map { it.second }, ACCENT)
            }
        }
        JournalSection.entries.forEach { section ->
            val questions = state.asked.filter { it.section == section }
            if (questions.isEmpty()) return@forEach
            item(key = "section-${section.name}") {
                Text(
                    stringResource(section.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenFitColors.OnSurface,
                    modifier = Modifier.padding(start = PagePadding, end = PagePadding, top = 32.dp, bottom = 12.dp),
                )
            }
            itemsIndexed(questions, key = { _, behaviour -> "q-${behaviour.key}" }) { index, behaviour ->
                QuestionRow(behaviour, state.answers[behaviour.key], index, questions.size) { viewModel.answer(behaviour.key, it) }
            }
        }
        insights(state, viewModel::selectOutcome)
        item {
            TextButton(onClick = onEditBehaviours, modifier = Modifier.padding(start = 12.dp, top = 16.dp)) {
                Text(stringResource(R.string.journal_edit_behaviours), color = ACCENT.accent)
            }
        }
    }
}

@Composable
private fun DateBar(state: JournalUiState, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day), tint = OpenFitColors.OnSurface)
        }
        Text(
            if (state.isToday) stringResource(R.string.today) else state.date.weekdayDayMonth(),
            style = MaterialTheme.typography.titleMedium,
            color = OpenFitColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, enabled = !state.isToday) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                stringResource(R.string.next_day),
                tint = if (state.isToday) OpenFitColors.OnSurface.copy(alpha = 0.38f) else OpenFitColors.OnSurface,
            )
        }
    }
}

/** Shown until the user chooses what the journal asks: openFit asks nothing on its own. */
@Composable
private fun SetupCard(onChoose: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = OpenFitColors.Card, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.journal_setup_title), style = MaterialTheme.typography.titleLarge, color = OpenFitColors.OnSurface)
            Text(
                stringResource(R.string.journal_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = onChoose,
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT.accent, contentColor = OpenFitColors.Background),
                modifier = Modifier.padding(top = 20.dp),
            ) { Text(stringResource(R.string.journal_choose_behaviours)) }
        }
    }
}

@Composable
private fun QuestionRow(behaviour: Behaviour, answer: Boolean?, index: Int, count: Int, onAnswer: (Boolean?) -> Unit) {
    GroupedCard(index, count, minHeight = 64.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(behaviour.questionText(), style = MaterialTheme.typography.bodyLarge, color = OpenFitColors.OnSurface, modifier = Modifier.weight(1f))
            AnswerChip(stringResource(R.string.journal_yes), answer == true) { onAnswer(if (answer == true) null else true) }
            Spacer(Modifier.width(8.dp))
            AnswerChip(stringResource(R.string.journal_no), answer == false) { onAnswer(if (answer == false) null else false) }
        }
    }
}

@Composable
private fun AnswerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleSmall) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = ACCENT.accent) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = CHIP_SELECTED,
            selectedLabelColor = ACCENT.accent,
            labelColor = OpenFitColors.OnSurface,
        ),
    )
}

private fun LazyListScope.insights(state: JournalUiState, onOutcome: (JournalOutcome) -> Unit) {
    item {
        Column(Modifier.padding(start = PagePadding, end = PagePadding, top = 40.dp)) {
            Text(stringResource(R.string.journal_insights), style = SectionHeaderStyle, color = OpenFitColors.OnSurface)
            Text(
                stringResource(R.string.journal_insights_body),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalOutcome.entries.forEach { outcome ->
                AnswerChip(stringResource(outcome.title), state.outcome == outcome) { onOutcome(outcome) }
            }
        }
    }
    val impacts = state.impacts
    if (impacts == null) {
        item {
            Text(
                stringResource(R.string.journal_insights_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenFitColors.OnSurfaceVariant,
                modifier = Modifier.padding(horizontal = PagePadding),
            )
        }
        return
    }
    itemsIndexed(state.asked, key = { _, behaviour -> "i-${behaviour.key}" }) { index, behaviour ->
        impacts[behaviour.key]?.let { ImpactRow(behaviour, it, state.outcome, index, state.asked.size) }
    }
}

@Composable
private fun ImpactRow(behaviour: Behaviour, impact: BehaviourImpact, outcome: JournalOutcome, index: Int, count: Int) {
    GroupedCard(index, count, minHeight = 72.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(behaviour.labelText(), style = MaterialTheme.typography.titleMedium, color = OpenFitColors.OnSurface)
                Text(verdictText(impact, outcome), style = MaterialTheme.typography.bodyMedium, color = OpenFitColors.OnSurfaceVariant)
            }
            val difference = impact.difference
            if (difference != null) {
                Text(
                    signed(difference) + if (outcome == JournalOutcome.Hrv) " ${stringResource(R.string.unit_ms)}" else "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = when (impact.verdict) {
                        ImpactVerdict.Positive -> ACCENT.accent
                        ImpactVerdict.Negative -> OpenFitColors.Pink.accent
                        else -> OpenFitColors.OnSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun verdictText(impact: BehaviourImpact, outcome: JournalOutcome): String {
    val name = stringResource(outcome.title)
    return when (impact.verdict) {
        ImpactVerdict.Positive -> stringResource(R.string.journal_verdict_higher, name)
        ImpactVerdict.Negative -> stringResource(R.string.journal_verdict_lower, name)
        ImpactVerdict.NoClearEffect -> stringResource(R.string.journal_verdict_unclear)
        ImpactVerdict.NeedsMoreData -> stringResource(R.string.journal_verdict_more_data, impact.yesDays, impact.noDays, MIN_DAYS_PER_ANSWER)
    }
}

/** "+4", "−12", "0": whole points for scores, as Fitbit shows them. */
private fun signed(value: Double): String {
    val rounded = value.roundToInt()
    return when {
        rounded > 0 -> "+$rounded"
        rounded < 0 -> "−${abs(rounded)}"
        else -> "0"
    }
}

private val JournalOutcome.title: Int
    get() = when (this) {
        JournalOutcome.Readiness -> R.string.journal_outcome_readiness
        JournalOutcome.SleepScore -> R.string.sleep_score_title
        JournalOutcome.Hrv -> R.string.journal_outcome_hrv
    }
