package com.easy.easyai.core.watch

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * Pins the calendar arithmetic down: every recurrence is computed in the watch's zone with
 * `ZonedDateTime`, so a daily 09:00 stays at 09:00 across DST, a monthly 31st clamps to short
 * months, and an overdue row moves to the next *future* occurrence instead of replaying missed ones.
 */
class WatchScheduleTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    private fun daily(time: String, zone: ZoneId = shanghai) = WatchSchedule(
        kind = WatchScheduleKind.DAILY,
        zone = zone,
        localTime = LocalTime.parse(time)
    )

    private fun instant(raw: String): Instant = Instant.parse(raw)

    @Nested
    inner class `one-shot forms` {

        @Test
        fun `day offset resolves today's wall clock in the watch zone`() {
            val now = instant("2026-10-25T00:30:00Z") // 08:30 in Shanghai
            val schedule = WatchScheduleCalculator.resolve(
                WatchScheduleInput.DayOffset(0, LocalTime.of(9, 0)),
                shanghai,
                now
            )
            assertEquals(WatchScheduleKind.AT, schedule.kind)
            assertEquals(instant("2026-10-25T01:00:00Z"), schedule.anchorAt)
        }

        @Test
        fun `weekday once picks the next occurrence, today included while still ahead`() {
            // 2026-10-25 is a Sunday; 09:00 Shanghai has not happened yet at 08:00 local.
            val now = instant("2026-10-25T00:00:00Z")
            val schedule = WatchScheduleCalculator.resolve(
                WatchScheduleInput.WeekdayOnce(DayOfWeek.SUNDAY, LocalTime.of(9, 0)),
                shanghai,
                now
            )
            assertEquals(instant("2026-10-25T01:00:00Z"), schedule.anchorAt)
        }

        @Test
        fun `weekday once rolls a week when today's time already passed`() {
            val now = instant("2026-10-25T02:00:00Z") // 10:00 Sunday in Shanghai
            val schedule = WatchScheduleCalculator.resolve(
                WatchScheduleInput.WeekdayOnce(DayOfWeek.SUNDAY, LocalTime.of(9, 0)),
                shanghai,
                now
            )
            assertEquals(instant("2026-11-01T01:00:00Z"), schedule.anchorAt)
        }

        @Test
        fun `in is resolved against the supplied clock, not the system clock`() {
            val now = instant("2026-10-25T00:00:00Z")
            val schedule = WatchScheduleCalculator.resolve(
                WatchScheduleInput.In(Duration.ofHours(2)),
                shanghai,
                now
            )
            assertEquals(instant("2026-10-25T02:00:00Z"), schedule.anchorAt)
        }
    }

    @Nested
    inner class `recurrences` {

        @Test
        fun `daily next occurrence is strictly in the future`() {
            val schedule = daily("09:00")
            assertEquals(
                instant("2026-10-26T01:00:00Z"),
                WatchScheduleCalculator.nextAfter(schedule, instant("2026-10-25T01:00:00Z"))
            )
        }

        @Test
        fun `a long outage advances once instead of replaying missed days`() {
            val schedule = daily("09:00")
            assertEquals(
                instant("2026-10-28T01:00:00Z"),
                WatchScheduleCalculator.nextAfter(schedule, instant("2026-10-27T12:00:00Z"))
            )
        }

        @Test
        fun `weekly requires and honours its weekday`() {
            val schedule = WatchSchedule(
                kind = WatchScheduleKind.WEEKLY,
                zone = shanghai,
                localTime = LocalTime.of(9, 0),
                weekday = DayOfWeek.MONDAY
            )
            // 2026-10-25 is Sunday; the next Monday is the 26th.
            assertEquals(
                instant("2026-10-26T01:00:00Z"),
                WatchScheduleCalculator.nextAfter(schedule, instant("2026-10-25T03:00:00Z"))
            )
        }

        @Test
        fun `monthly clamps the 31st to shorter months`() {
            val schedule = WatchSchedule(
                kind = WatchScheduleKind.MONTHLY,
                zone = shanghai,
                localTime = LocalTime.of(9, 0),
                dayOfMonth = 31
            )
            // After Jan 31, the next firing is Feb 28, then Mar 31 again.
            assertEquals(
                instant("2026-02-28T01:00:00Z"),
                WatchScheduleCalculator.nextAfter(schedule, instant("2026-01-31T02:00:00Z"))
            )
            assertEquals(
                instant("2026-03-31T01:00:00Z"),
                WatchScheduleCalculator.nextAfter(schedule, instant("2026-03-01T00:00:00Z"))
            )
        }

        @Test
        fun `monthly defaults the day to the creation date in the watch zone`() {
            val now = instant("2026-10-25T20:00:00Z") // 04:00 on the 26th in Shanghai
            val schedule = WatchScheduleCalculator.resolve(
                WatchScheduleInput.Every(WatchScheduleKind.MONTHLY, LocalTime.of(9, 0)),
                shanghai,
                now
            )
            assertEquals(26, schedule.dayOfMonth)
            assertEquals(instant("2026-10-26T01:00:00Z"), WatchScheduleCalculator.firstDue(schedule, now))
        }

        @Test
        fun `daily stays at 09 00 local across the spring-forward gap`() {
            // 2026-03-08 02:30 does not exist in New York; ZonedDateTime moves it to 03:30 EDT.
            val schedule = daily("02:30", newYork)
            assertEquals(
                instant("2026-03-08T07:30:00Z"),
                WatchScheduleCalculator.nextAfter(schedule, instant("2026-03-07T12:00:00Z"))
            )
        }

        @Test
        fun `an ambiguous autumn time takes the earlier offset`() {
            // 2026-11-01 01:30 occurs twice; the earlier occurrence is still EDT (-04:00).
            val schedule = daily("01:30", newYork)
            assertEquals(
                instant("2026-11-01T05:30:00Z"),
                WatchScheduleCalculator.nextAfter(schedule, instant("2026-10-31T12:00:00Z"))
            )
        }

        @Test
        fun `a daily schedule without a time is a programming error, not a silent default`() {
            assertThrows<IllegalStateException> {
                WatchScheduleCalculator.nextAfter(
                    WatchSchedule(kind = WatchScheduleKind.DAILY, zone = shanghai),
                    Instant.now()
                )
            }
        }
    }

    @Nested
    inner class `until handling` {

        @Test
        fun `a bare until date means the end of that day in the watch zone`() {
            assertEquals(
                instant("2026-12-31T15:59:59Z"),
                WatchScheduleCalculator.endOfDay(LocalDate.parse("2026-12-31"), shanghai)
            )
        }
    }
}
