package com.easy.easyai.core.watch

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Turns a trigger form into an absolute schedule and computes every following occurrence.
 *
 * Calendar arithmetic happens in the watch's zone with [ZonedDateTime], never as fixed millisecond
 * offsets: a daily 09:00 watch must stay at 09:00 local across a DST shift. `ZonedDateTime.of`
 * already has the semantics this needs — a local time inside a spring-forward gap moves forward by
 * the gap length, and an ambiguous autumn time takes the earlier offset.
 *
 * Purely functional and clock-free: every entry point takes the instant it should judge from, which
 * is what lets the scheduler, the tool preview and the tests agree on one answer.
 */
object WatchScheduleCalculator {

    /** Hard cap on recurrence search; a monthly rule always resolves inside two months. */
    private const val DAILY_SCAN_DAYS = 3L
    private const val WEEKLY_SCAN_DAYS = 8L
    private const val MONTHLY_SCAN_MONTHS = 3

    /**
     * Resolve the trigger form at [now] in [zone].
     *
     * A one-shot form becomes an [WatchScheduleKind.AT] schedule carrying the absolute anchor; a
     * recurring form becomes its own kind with the local time (weekday/day-of-month included) and
     * no anchor. The caller has already validated exclusivity and bounds.
     */
    fun resolve(input: WatchScheduleInput, zone: ZoneId, now: Instant): WatchSchedule = when (input) {
        is WatchScheduleInput.At -> oneShot(zone, input.instant)
        is WatchScheduleInput.In -> oneShot(zone, now.plus(input.duration))
        is WatchScheduleInput.DayOffset -> oneShot(
            zone,
            at(localDateTime(zone, now).toLocalDate().plusDays(input.days.toLong()), input.time, zone)
        )
        is WatchScheduleInput.WeekdayOnce ->
            oneShot(zone, nextWeekday(zone, now, input.weekday, input.time))
        is WatchScheduleInput.Every -> recurring(zone, now, input)
    }

    /** First moment this schedule should fire, strictly after [now] for a recurrence. */
    fun firstDue(schedule: WatchSchedule, now: Instant): Instant = when (schedule.kind) {
        WatchScheduleKind.AT -> schedule.anchorAt ?: missingAnchor()
        else -> nextAfter(schedule, now)
    }

    /**
     * The next occurrence strictly after [after].
     *
     * For an [WatchScheduleKind.AT] schedule this returns the anchor itself; callers only use that
     * when re-reading a row whose one-shot instant is already fixed.
     */
    fun nextAfter(schedule: WatchSchedule, after: Instant): Instant {
        if (schedule.kind == WatchScheduleKind.AT) return schedule.anchorAt ?: missingAnchor()
        val time = schedule.localTime ?: throw IllegalStateException(
            "Watch schedule ${schedule.kind} has no local time"
        )
        val zone = schedule.zone
        val baseDate = localDateTime(zone, after).toLocalDate()
        when (schedule.kind) {
            WatchScheduleKind.DAILY -> {
                for (offset in 0..DAILY_SCAN_DAYS) {
                    val candidate = at(baseDate.plusDays(offset), time, zone)
                    if (candidate.isAfter(after)) return candidate
                }
            }
            WatchScheduleKind.WEEKLY -> {
                val weekday = schedule.weekday ?: throw IllegalStateException(
                    "Weekly watch schedule has no weekday"
                )
                for (offset in 0..WEEKLY_SCAN_DAYS) {
                    val date = baseDate.plusDays(offset)
                    if (date.dayOfWeek != weekday) continue
                    val candidate = at(date, time, zone)
                    if (candidate.isAfter(after)) return candidate
                }
            }
            WatchScheduleKind.MONTHLY -> {
                val dayOfMonth = schedule.dayOfMonth ?: throw IllegalStateException(
                    "Monthly watch schedule has no day of month"
                )
                var month = YearMonth.from(baseDate)
                repeat(MONTHLY_SCAN_MONTHS) {
                    val date = month.atDay(dayOfMonth.coerceAtMost(month.lengthOfMonth()))
                    val candidate = at(date, time, zone)
                    if (candidate.isAfter(after)) return candidate
                    month = month.plusMonths(1)
                }
            }
            WatchScheduleKind.AT -> Unit
        }
        // Only reachable if the zone's rules refused every candidate, which no real ZoneId does.
        throw IllegalStateException("Unable to compute the next occurrence of watch schedule ${schedule.kind}")
    }

    /** End of [date] in [zone], which is what a bare `until` date means to a person. */
    fun endOfDay(date: LocalDate, zone: ZoneId): Instant =
        at(date, LocalTime.of(23, 59, 59), zone)

    /** Next occurrence of [weekday] at [time], strictly after [now]; today counts while still ahead. */
    fun nextWeekday(zone: ZoneId, now: Instant, weekday: DayOfWeek, time: LocalTime): Instant {
        val baseDate = localDateTime(zone, now).toLocalDate()
        for (offset in 0..WEEKLY_SCAN_DAYS) {
            val date = baseDate.plusDays(offset)
            if (date.dayOfWeek != weekday) continue
            val candidate = at(date, time, zone)
            if (candidate.isAfter(now)) return candidate
        }
        throw IllegalStateException("Unable to compute the next $weekday at $time")
    }

    /** Local wall clock of [instant] in [zone]; the only place this module reads a calendar. */
    fun localDateTime(zone: ZoneId, instant: Instant): ZonedDateTime = instant.atZone(zone)

    private fun recurring(zone: ZoneId, now: Instant, input: WatchScheduleInput.Every): WatchSchedule {
        val schedule = when (input.kind) {
            WatchScheduleKind.DAILY -> WatchSchedule(
                kind = WatchScheduleKind.DAILY,
                zone = zone,
                localTime = input.time
            )
            WatchScheduleKind.WEEKLY -> WatchSchedule(
                kind = WatchScheduleKind.WEEKLY,
                zone = zone,
                localTime = input.time,
                weekday = input.weekday
                    ?: throw IllegalArgumentException("weekly watches need a weekday")
            )
            WatchScheduleKind.MONTHLY -> WatchSchedule(
                kind = WatchScheduleKind.MONTHLY,
                zone = zone,
                localTime = input.time,
                // A monthly watch created on the 31st has to live with shorter months; the clamp is
                // applied on every occurrence, not frozen at creation.
                dayOfMonth = input.dayOfMonth ?: localDateTime(zone, now).toLocalDate().dayOfMonth
            )
            WatchScheduleKind.AT -> throw IllegalArgumentException("'at' is a one-shot trigger, not a recurrence")
        }
        return schedule
    }

    private fun oneShot(zone: ZoneId, instant: Instant): WatchSchedule =
        WatchSchedule(kind = WatchScheduleKind.AT, zone = zone, anchorAt = instant)

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()

    private fun missingAnchor(): Nothing =
        throw IllegalStateException("One-shot watch schedule has no anchor instant")
}
