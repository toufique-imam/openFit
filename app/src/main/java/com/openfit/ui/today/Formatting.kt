package com.openfit.ui.today

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import java.text.DecimalFormat
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ValueText {
    private val builder = AnnotatedString.Builder()

    fun big(text: String) = builder.append(text)

    fun small(text: String) = builder.withStyle(SpanStyle(fontSize = 0.611.em)) { append(text) }

    fun build(): AnnotatedString = builder.toAnnotatedString()
}

fun valueText(block: ValueText.() -> Unit): AnnotatedString = ValueText().apply(block).build()

fun ValueText.duration(duration: Duration) {
    big(duration.toHours().toString())
    small("h ")
    big(duration.toMinutesPart().toString())
    small("m")
}

fun ValueText.count(value: Int, total: Int, smallTotal: Boolean = false) {
    big(value.toString())
    small(" of ")
    if (smallTotal) small(total.toString()) else big(total.toString())
}

fun ValueText.quantity(value: String, unit: String) {
    big(value)
    if (unit.isNotEmpty()) small(" $unit")
}

fun quantityText(value: String, unit: String): AnnotatedString = valueText { quantity(value, unit) }

private val WHOLE = DecimalFormat("#,##0")
private val ONE_DECIMAL = DecimalFormat("#,##0.#")
private val TWO_DECIMALS = DecimalFormat("#,##0.##")

fun whole(value: Number): String = WHOLE.format(value)
fun oneDecimal(value: Double): String = ONE_DECIMAL.format(value)
fun twoDecimals(value: Double): String = TWO_DECIMALS.format(value)

fun shortDuration(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM")
private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE, d MMM")
private val DAY = DateTimeFormatter.ofPattern("d")

fun LocalDate.dayMonth(): String = format(DAY_MONTH)
fun LocalDate.weekdayDayMonth(): String = format(WEEKDAY_DAY_MONTH)

fun weekRange(first: LocalDate, last: LocalDate): String =
    if (first.month == last.month) "${first.format(DAY)}–${last.dayMonth()}" else "${first.dayMonth()} – ${last.dayMonth()}"
