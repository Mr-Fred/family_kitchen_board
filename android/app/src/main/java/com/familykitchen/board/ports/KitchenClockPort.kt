package com.familykitchen.board.ports

import com.familykitchen.board.domain.dayOfWeekIndexZeroMonday
import com.familykitchen.board.domain.mondayOfIsoDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

// Hexagonal wall-clock port isolating system timezone reads from SavorAI's pure domain/ engines.
// Production shells inject SystemKitchenClockAdapter; hermetic JUnit suites inject FixedKitchenClock.

/**
 * Immutable wall-clock snapshot sampled at the Imperative Shell boundary and passed as pure ISO
 * strings and integer indices into `domain/` engines.
 *
 * @property todayIso Local calendar date formatted as `"YYYY-MM-DD"`.
 * @property mondayOfCurrentWeekIso ISO-8601 Monday (`"YYYY-MM-DD"`) anchoring the current week.
 * @property dayOfWeekIndex 0-based weekday index (`0 = Monday` .. `6 = Sunday`).
 * @property currentHour24 Local 24-hour clock hour (`0..23`), driving 8:30 PM (`>= 20`) night prep.
 * @property nowTimestampIso Full ISO-8601 timestamp stamped onto mutated ledger and memory records.
 */
@Serializable
data class KitchenClockSnapshot(
  val todayIso: String,
  val mondayOfCurrentWeekIso: String,
  val dayOfWeekIndex: Int,
  val currentHour24: Int,
  val nowTimestampIso: String,
)

/**
 * Hexagonal port providing the current household local calendar date, Monday anchor, weekday index,
 * and 24-hour clock value without leaking `java.time` into `com.familykitchen.board.domain`.
 */
fun interface KitchenClockPort {
  /**
   * Samples the current local [KitchenClockSnapshot].
   *
   * @return Immutable [KitchenClockSnapshot] for the active timezone or deterministic test fixture.
   */
  fun now(): KitchenClockSnapshot
}

/**
 * Production [KitchenClockPort] adapter backed by [ZonedDateTime.now] in the household's local
 * [ZoneId] (defaulting to [ZoneId.systemDefault]).
 *
 * @property zoneId Timezone used to resolve local calendar dates and evening prep hours.
 */
class SystemKitchenClockAdapter(
  private val zoneId: ZoneId = ZoneId.systemDefault(),
) : KitchenClockPort {
  /** Samples the system clock in [zoneId] and normalizes Monday-first weekday indices (`0..6`). */
  override fun now(): KitchenClockSnapshot {
    // Capture a single ZonedDateTime instant so date, weekday, hour, and timestamp never tear.
    val current = ZonedDateTime.now(zoneId)
    val todayIso = DateTimeFormatter.ISO_LOCAL_DATE.format(current)
    // java.time DayOfWeek.value is 1 (Monday) .. 7 (Sunday); shift by -1 for SavorAI 0..6 indexing.
    val dayIndex = (current.dayOfWeek.value - 1).coerceIn(0, 6)
    // Anchor week headers to the local Monday so Sunday nights stay in the active Mon-Sun span.
    val mondayIso = DateTimeFormatter.ISO_LOCAL_DATE.format(
      current.toLocalDate().minusDays(dayIndex.toLong()),
    )
    // Preserve local offset in nowTimestampIso so date prefixes match todayIso near midnight.
    return KitchenClockSnapshot(
      todayIso = todayIso,
      mondayOfCurrentWeekIso = mondayIso,
      dayOfWeekIndex = dayIndex,
      currentHour24 = current.hour,
      nowTimestampIso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(current),
    )
  }
}

/**
 * Deterministic hermetic [KitchenClockPort] returning a fixed [KitchenClockSnapshot] in JUnit and
 * Compose `@Preview` environments without consulting the host wall clock.
 *
 * @property snapshot Precomputed immutable [KitchenClockSnapshot] returned on every [now] call.
 */
class FixedKitchenClock(
  private val snapshot: KitchenClockSnapshot,
) : KitchenClockPort {
  /**
   * Convenience secondary constructor deriving Monday anchor and `0..6` weekday index purely from
   * [todayIso] via `KitchenCalendarMath`.
   *
   * @param todayIso Fixed `"YYYY-MM-DD"` calendar date.
   * @param currentHour24 Simulated 24-hour clock hour (`0..23`, defaults to `8` AM).
   * @param nowTimestampIso Optional explicit ISO-8601 timestamp string.
   */
  constructor(
    todayIso: String,
    currentHour24: Int = 8,
    nowTimestampIso: String = "${todayIso}T${currentHour24.coerceIn(0, 23).toString().padStart(2, '0')}:00:00Z",
  ) : this(
    KitchenClockSnapshot(
      todayIso = todayIso,
      mondayOfCurrentWeekIso = mondayOfIsoDate(todayIso),
      dayOfWeekIndex = dayOfWeekIndexZeroMonday(todayIso),
      currentHour24 = currentHour24.coerceIn(0, 23),
      nowTimestampIso = nowTimestampIso,
    ),
  )

  /** Returns the deterministic [snapshot] verbatim. */
  override fun now(): KitchenClockSnapshot = snapshot
}
