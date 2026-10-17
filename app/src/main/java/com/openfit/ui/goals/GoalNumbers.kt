package com.openfit.ui.goals

import java.math.BigDecimal
import java.math.RoundingMode

internal fun goalNumberText(value: Double, decimals: Int = 2): String =
    BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** Accept decimal separators without deleting punctuation and silently changing the magnitude. */
internal fun parseGoalNumber(input: String, decimal: Boolean): Double? {
    val normalized = input.trim().map { char ->
        when {
            char.isDigit() -> ('0'.code + Character.digit(char, 10)).toChar()
            char == ',' || char == '\u066b' -> '.'
            else -> char
        }
    }.joinToString("")
    val pattern = if (decimal) DECIMAL else INTEGER
    return normalized.takeIf(pattern::matches)?.toDoubleOrNull()?.takeIf(Double::isFinite)
}

private val DECIMAL = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
private val INTEGER = Regex("[0-9]+")
