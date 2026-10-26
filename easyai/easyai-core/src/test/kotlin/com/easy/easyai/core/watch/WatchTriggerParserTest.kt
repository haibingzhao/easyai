package com.easy.easyai.core.watch

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trigger grammar is shared by the tool and the watch page, so these tests pin the rejections
 * the model sees: one form at a time, `time` only where it means something, and a message that says
 * what to change instead.
 */
class WatchTriggerParserTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun parse(
        at: String? = null,
        within: String? = null,
        dayOffset: Int? = null,
        every: String? = null,
        time: String? = null,
        weekday: String? = null,
        dayOfMonth: Int? = null,
        require: Boolean = true
    ) = WatchTriggerParser.parse(
        WatchTriggerParser.Fields(at, within, dayOffset, every, time, weekday, dayOfMonth),
        shanghai,
        require
    )

    @Nested
    inner class `accepted forms` {

        @Test
        fun `absolute instants keep their own offset`() {
            val input = parse(at = "2026-11-01T09:00:00+08:00")
            assertEquals(WatchScheduleInput.At(Instant.parse("2026-11-01T01:00:00Z")), input)
        }

        @Test
        fun `a local date-time is interpreted in the watch zone`() {
            val input = parse(at = "2026-11-01T09:00")
            assertEquals(WatchScheduleInput.At(Instant.parse("2026-11-01T01:00:00Z")), input)
        }

        @Test
        fun `durations accept m h and d`() {
            assertEquals(WatchScheduleInput.In(Duration.ofMinutes(30)), parse(within = "30m"))
            assertEquals(WatchScheduleInput.In(Duration.ofHours(2)), parse(within = "2h"))
            assertEquals(WatchScheduleInput.In(Duration.ofDays(3)), parse(within = "3d"))
        }

        @Test
        fun `a lone weekday is a one-shot at the given time`() {
            assertEquals(
                WatchScheduleInput.WeekdayOnce(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
                parse(weekday = "mon", time = "09:00")
            )
        }

        @Test
        fun `a weekly recurrence keeps weekday as its recurrence parameter`() {
            assertEquals(
                WatchScheduleInput.Every(
                    kind = WatchScheduleKind.WEEKLY,
                    time = LocalTime.of(9, 0),
                    weekday = DayOfWeek.MONDAY
                ),
                parse(every = "weekly", weekday = "MONDAY", time = "09:00")
            )
        }

        @Test
        fun `an empty edit without a time leaves the schedule alone`() {
            assertNull(parse(require = false))
        }
    }

    @Nested
    inner class `rejections name the fix` {

        @Test
        fun `two trigger forms are refused together`() {
            val error = assertThrows<WatchException> { parse(at = "2026-11-01T09:00:00Z", within = "2h") }
            assertEquals(true, error.message!!.contains("exactly one trigger form"))
        }

        @Test
        fun `a bare time is not a trigger`() {
            val error = assertThrows<WatchException> { parse(time = "09:00") }
            assertEquals(true, error.message!!.contains("'time' only makes sense"))
        }

        @Test
        fun `a missing trigger is refused with the full list`() {
            val error = assertThrows<WatchException> { parse() }
            assertEquals(true, error.message!!.contains("'within'"))
            assertEquals(true, error.message!!.contains("'every'"))
        }

        @Test
        fun `recurrences need a time`() {
            val error = assertThrows<WatchException> { parse(every = "daily") }
            assertEquals(true, error.message!!.contains("needs 'time'"))
        }

        @Test
        fun `an unknown recurrence is refused`() {
            val error = assertThrows<WatchException> { parse(every = "hourly", time = "09:00") }
            assertEquals(true, error.message!!.contains("daily, weekly or monthly"))
        }

        @Test
        fun `an unknown weekday is refused`() {
            val error = assertThrows<WatchException> { parse(weekday = "FUNDAY", time = "09:00") }
            assertEquals(true, error.message!!.contains("weekday"))
        }

        @Test
        fun `an unparsable duration is refused`() {
            val error = assertThrows<WatchException> { parse(within = "soon") }
            assertEquals(true, error.message!!.contains("30m, 2h or 3d"))
        }

        @Test
        fun `a time with seconds is refused instead of overflowing the schedule column`() {
            val error = assertThrows<WatchException> { parse(every = "daily", time = "09:00:30") }
            assertEquals(true, error.message!!.contains("HH:mm"))
        }

        @Test
        fun `fields belonging to another form are refused instead of ignored`() {
            assertTrue(
                assertThrows<WatchException> {
                    parse(at = "2026-11-01T09:00:00+08:00", time = "09:00")
                }.message!!.contains("absolute trigger")
            )
            assertTrue(
                assertThrows<WatchException> {
                    parse(weekday = "MONDAY", time = "09:00", dayOfMonth = 3)
                }.message!!.contains("day_of_month")
            )
            assertTrue(
                assertThrows<WatchException> {
                    parse(every = "monthly", time = "09:00", weekday = "MONDAY")
                }.message!!.contains("weekday")
            )
            assertTrue(
                assertThrows<WatchException> {
                    parse(every = "weekly", weekday = "MONDAY", time = "09:00", dayOfMonth = 3)
                }.message!!.contains("day_of_month")
            )
        }
    }
}
