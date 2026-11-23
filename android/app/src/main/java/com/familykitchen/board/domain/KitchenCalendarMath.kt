package com.familykitchen.board.domain

// Pure Howard Hinnant civil-date <-> epoch-day calendar math and live shelf-life aging helpers.
// Imports ONLY Kotlin stdlib (zero java.time, java.io, or Android dependencies in domain/).

private const val CAL_MATH_DAYS_PER_ERA: Long = 146097L
private const val CAL_MATH_UNIX_EPOCH_OFFSET: Long = 719468L

private val CAL_MATH_SHORT_WEEKDAYS: List<String> = listOf(
  "Mon",
  "Tue",
  "Wed",
  "Thu",
  "Fri",
  "Sat",
  "Sun",
)

private val CAL_MATH_SHORT_MONTHS: List<String> = listOf(
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
)

/**
 * Parses an ISO-8601 date (`"YYYY-MM-DD"`) or timestamp prefix (`"YYYY-MM-DDTHH:..."`) into days
 * relative to `1970-01-01` (`0L`) using Howard Hinnant's proleptic Gregorian algorithm.
 *
 * @param isoDate Candidate ISO date or timestamp string.
 * @return Epoch day (`1970-01-01 == 0L`), or `null` when [isoDate] is malformed or out of calendar range.
 */
fun isoDateToEpochDay(isoDate: String): Long? {
  // Accept both bare "YYYY-MM-DD" dates and full ISO timestamps ("YYYY-MM-DDTHH:MM:SS...").
  val trimmed = isoDate.trim()
  if (trimmed.length < 10 || trimmed[4] != '-' || trimmed[7] != '-') {
    return null
  }
  if (trimmed.length > 10 && trimmed[10] != 'T' && trimmed[10] != 't' && trimmed[10] != ' ') {
    return null
  }

  val year = calMathParseFixedDigits(trimmed, 0, 4) ?: return null
  val month = calMathParseFixedDigits(trimmed, 5, 7) ?: return null
  val day = calMathParseFixedDigits(trimmed, 8, 10) ?: return null

  // Reject impossible calendar dates such as Feb 29 on non-leap years or Apr 31.
  if (month !in 1..12) return null
  val maxDay = calMathDaysInMonth(year, month)
  if (day !in 1..maxDay) return null

  // Shift March to month 0 so leap-day February 29 sits cleanly at the end of each 400-year era.
  val adjustedYear = if (month <= 2) year.toLong() - 1L else year.toLong()
  val era = if (adjustedYear >= 0L) adjustedYear / 400L else (adjustedYear - 399L) / 400L
  val yearOfEra = adjustedYear - era * 400L
  val monthPrime = if (month > 2) month - 3 else month + 9
  val dayOfYear = (153L * monthPrime.toLong() + 2L) / 5L + day.toLong() - 1L
  val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
  return era * CAL_MATH_DAYS_PER_ERA + dayOfEra - CAL_MATH_UNIX_EPOCH_OFFSET
}

/**
 * Converts a Unix [epochDay] (`1970-01-01 == 0L`) into a canonical `"YYYY-MM-DD"` ISO-8601 date.
 *
 * @param epochDay Days elapsed since `1970-01-01` (supports negative pre-1970 dates).
 * @return Formatted `"YYYY-MM-DD"` calendar string.
 */
fun epochDayToIsoDate(epochDay: Long): String {
  // Inverse Hinnant civil-from-days conversion across 400-year Gregorian eras (146,097 days each).
  val shifted = epochDay + CAL_MATH_UNIX_EPOCH_OFFSET
  val era = if (shifted >= 0L) {
    shifted / CAL_MATH_DAYS_PER_ERA
  } else {
    (shifted - (CAL_MATH_DAYS_PER_ERA - 1L)) / CAL_MATH_DAYS_PER_ERA
  }
  val dayOfEra = shifted - era * CAL_MATH_DAYS_PER_ERA
  val yearOfEra =
    (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L
  val rawYear = yearOfEra + era * 400L
  val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
  val monthPrime = (5L * dayOfYear + 2L) / 153L
  val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt()
  val month = (if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L).toInt()
  val civilYear = (if (month <= 2) rawYear + 1L else rawYear).toInt()
  return calMathFormatIsoYmd(civilYear, month, day)
}

/**
 * Shifts [isoDate] by [deltaDays] calendar days, returning `"YYYY-MM-DD"` or [isoDate] unchanged
 * if [isoDate] cannot be parsed.
 *
 * @param isoDate Base ISO date (`"YYYY-MM-DD"` or ISO timestamp prefix).
 * @param deltaDays Signed day offset to add (positive for future, negative for past).
 * @return Shifted `"YYYY-MM-DD"` date string, or [isoDate] unchanged when unparseable.
 */
fun addDaysIso(isoDate: String, deltaDays: Int): String {
  // Preserve unparseable strings verbatim so callers never crash on legacy/blank test fixtures.
  val baseEpoch = isoDateToEpochDay(isoDate) ?: return isoDate
  return epochDayToIsoDate(baseEpoch + deltaDays.toLong())
}

/**
 * Computes the 0-based weekday index (`0 = Monday` .. `6 = Sunday`) for [isoDate].
 *
 * @param isoDate Target ISO date (`"YYYY-MM-DD"` or ISO timestamp prefix).
 * @return Weekday index in `0..6` (`0` when [isoDate] is malformed).
 */
fun dayOfWeekIndexZeroMonday(isoDate: String): Int {
  // 1970-01-01 (epochDay 0) was a Thursday (index 3), so (epochDay + 3) mod 7 yields 0 = Monday.
  val epochDay = isoDateToEpochDay(isoDate) ?: return 0
  val mod = (epochDay + 3L) % 7L
  return (if (mod < 0L) mod + 7L else mod).toInt()
}

/**
 * Resolves the `"YYYY-MM-DD"` Monday anchoring the week that contains [isoDate].
 *
 * @param isoDate Any ISO date or timestamp within the target week.
 * @return `"YYYY-MM-DD"` of that week's Monday, or [isoDate] unchanged if unparseable.
 */
fun mondayOfIsoDate(isoDate: String): String {
  // Subtract the 0..6 Monday-based weekday offset to land deterministically on Monday.
  if (isoDateToEpochDay(isoDate) == null) return isoDate
  return addDaysIso(isoDate, -dayOfWeekIndexZeroMonday(isoDate))
}

/**
 * Formats a compact weekday chip label (`"Mon 23"` .. `"Sun 29"`) with zero slashes or parentheses.
 *
 * @param weekStartDateIso ISO-8601 Monday start date of the displayed week.
 * @param dayIndex 0-based weekday index (`0 = Monday` .. `6 = Sunday`).
 * @return Clean chip label such as `"Mon 23"` or `"Sun 29"`.
 */
fun formatDayChipDateLabel(weekStartDateIso: String, dayIndex: Int): String {
  val safeIndex = dayIndex.coerceIn(0, 6)
  val weekdayName = CAL_MATH_SHORT_WEEKDAYS[safeIndex]
  val startEpoch = isoDateToEpochDay(weekStartDateIso) ?: return weekdayName
  val targetIso = epochDayToIsoDate(startEpoch + safeIndex.toLong())
  val dayOfMonth = calMathParseFixedDigits(targetIso, 8, 10) ?: return weekdayName
  return "$weekdayName $dayOfMonth"
}

/**
 * Formats a human-readable 7-day header span (`"Nov 23 – Nov 29"` or `"Oct 28 – Nov 3"`).
 *
 * @param weekStartDateIso ISO-8601 Monday start date of the displayed week.
 * @return Formatted week range string using an en-dash separator, or [weekStartDateIso] if invalid.
 */
fun formatWeekRangeLabel(weekStartDateIso: String): String {
  val startEpoch = isoDateToEpochDay(weekStartDateIso) ?: return weekStartDateIso
  val startPart = calMathFormatMonthDayLabel(epochDayToIsoDate(startEpoch)) ?: return weekStartDateIso
  val endPart = calMathFormatMonthDayLabel(epochDayToIsoDate(startEpoch + 6L)) ?: return weekStartDateIso
  return "$startPart – $endPart"
}

/**
 * Computes live remaining shelf-life days for a [PantryLedgerEntry] against [todayIso].
 *
 * Depleted items (`isOutOfStock`, Tier-1 `<= 0.0`, or Tier-2 `OUT`) always return `null` so empty
 * fridge rows never display phantom freshness countdowns. When [todayIso] and
 * [PantryLedgerEntry.expiryDateIso] are both valid ISO dates, returns their exact epoch-day delta;
 * otherwise falls back cleanly to [PantryLedgerEntry.expiresInDays].
 *
 * @param entry Household pantry ledger record to inspect.
 * @param todayIso Optional current calendar date (`"YYYY-MM-DD"`); `null` preserves static `expiresInDays`.
 * @return Remaining days until expiry (negative when past due), or `null` when depleted/untracked.
 */
fun computeRemainingShelfLifeDays(entry: PantryLedgerEntry, todayIso: String? = null): Int? {
  // Guard against the Empty-Fridge Mirage: 0g or OUT items have no active shelf-life countdown.
  val isDepletedTier1 = entry.tier == IngredientTier.TIER_1_QUANTITATIVE && entry.currentQuantity <= 0.0
  val isDepletedTier2 = entry.tier == IngredientTier.TIER_2_STAPLE && entry.stapleStatus == StapleStatus.OUT
  if (entry.isOutOfStock || isDepletedTier1 || isDepletedTier2) {
    return null
  }

  // Compute live calendar delta whenever both todayIso and expiryDateIso are present and valid.
  if (todayIso != null && !entry.expiryDateIso.isNullOrBlank()) {
    val expiryEpoch = isoDateToEpochDay(entry.expiryDateIso)
    val todayEpoch = isoDateToEpochDay(todayIso)
    if (expiryEpoch != null && todayEpoch != null) {
      val delta = (expiryEpoch - todayEpoch).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
      return delta.toInt()
    }
  }

  return entry.expiresInDays
}

// Parse strictly ASCII digits in [startInclusive, endExclusive) without allocating substrings when invalid.
private fun calMathParseFixedDigits(text: String, startInclusive: Int, endExclusive: Int): Int? {
  if (startInclusive < 0 || endExclusive > text.length || startInclusive >= endExclusive) return null
  var acc = 0
  for (idx in startInclusive until endExclusive) {
    val ch = text[idx]
    if (ch !in '0'..'9') return null
    acc = acc * 10 + (ch - '0')
  }
  return acc
}

// Check proleptic Gregorian leap-year divisibility rules (divisible by 4, except centuries not / 400).
private fun calMathIsLeapYear(year: Int): Boolean =
  (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

// Return exact maximum day count for (year, month) to reject malformed ISO calendar dates.
private fun calMathDaysInMonth(year: Int, month: Int): Int =
  when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (calMathIsLeapYear(year)) 29 else 28
    else -> 0
  }

// Format civil (year, month, day) as zero-padded "YYYY-MM-DD" without java.util.Locale dependencies.
private fun calMathFormatIsoYmd(year: Int, month: Int, day: Int): String {
  val yearText = when {
    year < 0 -> "-" + (-year).toString().padStart(4, '0')
    else -> year.toString().padStart(4, '0')
  }
  val monthText = month.toString().padStart(2, '0')
  val dayText = day.toString().padStart(2, '0')
  return "$yearText-$monthText-$dayText"
}

// Render "Nov 23" or "Nov 3" from a canonical "YYYY-MM-DD" string.
private fun calMathFormatMonthDayLabel(canonicalIso: String): String? {
  val month = calMathParseFixedDigits(canonicalIso, 5, 7) ?: return null
  val day = calMathParseFixedDigits(canonicalIso, 8, 10) ?: return null
  val monthName = CAL_MATH_SHORT_MONTHS.getOrNull(month - 1) ?: return null
  return "$monthName $day"
}

/** Object namespace exposing pure calendar math helpers for qualified `KitchenCalendarMath.*` calls. */
object KitchenCalendarMath {
  /** Delegates to [com.familykitchen.board.domain.isoDateToEpochDay]. */
  fun isoDateToEpochDay(isoDate: String): Long? =
    com.familykitchen.board.domain.isoDateToEpochDay(isoDate)

  /** Delegates to [com.familykitchen.board.domain.epochDayToIsoDate]. */
  fun epochDayToIsoDate(epochDay: Long): String =
    com.familykitchen.board.domain.epochDayToIsoDate(epochDay)

  /** Delegates to [com.familykitchen.board.domain.addDaysIso]. */
  fun addDaysIso(isoDate: String, deltaDays: Int): String =
    com.familykitchen.board.domain.addDaysIso(isoDate, deltaDays)

  /** Delegates to [com.familykitchen.board.domain.dayOfWeekIndexZeroMonday]. */
  fun dayOfWeekIndexZeroMonday(isoDate: String): Int =
    com.familykitchen.board.domain.dayOfWeekIndexZeroMonday(isoDate)

  /** Delegates to [com.familykitchen.board.domain.mondayOfIsoDate]. */
  fun mondayOfIsoDate(isoDate: String): String =
    com.familykitchen.board.domain.mondayOfIsoDate(isoDate)

  /** Delegates to [com.familykitchen.board.domain.formatDayChipDateLabel]. */
  fun formatDayChipDateLabel(weekStartDateIso: String, dayIndex: Int): String =
    com.familykitchen.board.domain.formatDayChipDateLabel(weekStartDateIso, dayIndex)

  /** Delegates to [com.familykitchen.board.domain.formatWeekRangeLabel]. */
  fun formatWeekRangeLabel(weekStartDateIso: String): String =
    com.familykitchen.board.domain.formatWeekRangeLabel(weekStartDateIso)

  /** Delegates to [com.familykitchen.board.domain.computeRemainingShelfLifeDays]. */
  fun computeRemainingShelfLifeDays(entry: PantryLedgerEntry, todayIso: String? = null): Int? =
    com.familykitchen.board.domain.computeRemainingShelfLifeDays(entry, todayIso)
}
