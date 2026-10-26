package com.easy.easyai.core.watch

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One parser for the trigger forms the `watch` tool and the watch page both accept.
 *
 * Keeping this in core is what stops the two entry points from disagreeing about "next Monday" or
 * about which combinations are legal. Every rejection is a [WatchException] whose message tells the
 * caller what to change; those messages reach the model verbatim, so they must be actionable.
 */
object WatchTriggerParser {

    /** The raw trigger fields as supplied by a caller, before any calendar arithmetic. */
    data class Fields(
        val at: String? = null,
        /** Relative duration such as `30m`, `2h`, `3d`. */
        val within: String? = null,
        val dayOffset: Int? = null,
        val every: String? = null,
        val time: String? = null,
        val weekday: String? = null,
        val dayOfMonth: Int? = null
    )

    /**
     * Resolve the fields to one trigger form in [zone].
     *
     * @param require when true an empty form is an error; when false (a text-only edit) null means
     *   "leave the schedule alone".
     */
    fun parse(fields: Fields, zone: ZoneId, require: Boolean): WatchScheduleInput? {
        val every = fields.every?.trim()?.takeIf { it.isNotEmpty() }
        val weekdayOnce = fields.weekday?.trim()?.takeIf { it.isNotEmpty() }?.takeIf { every == null }
        val at = fields.at?.trim()?.takeIf { it.isNotEmpty() }
        val within = fields.within?.trim()?.takeIf { it.isNotEmpty() }
        val forms = listOfNotNull(
            at,
            within,
            fields.dayOffset?.let { "dayOffset" },
            weekdayOnce,
            every
        )
        if (forms.size > 1) {
            throw WatchException(
                "Use exactly one trigger form: 'at', 'within', 'dayOffset', 'weekday' (one-shot), " +
                    "or 'every' (recurring). For a weekly recurrence, 'weekday' belongs with 'every'=\"weekly\"."
            )
        }
        if (forms.isEmpty()) {
            if (fields.time != null) {
                throw WatchException(
                    "'time' only makes sense with 'dayOffset', 'weekday' or 'every'; " +
                        "use 'at' or 'within' for an absolute trigger"
                )
            }
            if (require) {
                throw WatchException(
                    "A new watch needs one trigger: 'at', 'within', 'dayOffset', 'weekday' (one-shot), " +
                        "or 'every' with a 'time' (daily/weekly/monthly)"
                )
            }
            return null
        }
        // Fields that belong to another form are refused instead of silently ignored: a model that
        // sends `at` + `time` believes the time applies, and quietly dropping it schedules the wrong run.
        val timeText = fields.time?.trim()?.takeIf { it.isNotEmpty() }
        val dayOfMonth = fields.dayOfMonth
        val weekdayText = fields.weekday?.trim()?.takeIf { it.isNotEmpty() }
        val dayOffset = fields.dayOffset
        if (at != null || within != null) {
            val extras = listOfNotNull(
                timeText?.let { "'time'" },
                weekdayText?.let { "'weekday'" },
                dayOfMonth?.let { "'day_of_month'" }
            )
            if (extras.isNotEmpty()) {
                throw WatchException(
                    "${extras.joinToString(", ")} only apply to dayOffset, weekday or every; " +
                        "remove them for an absolute trigger"
                )
            }
        }
        if (dayOffset != null && dayOfMonth != null) {
            throw WatchException("'day_of_month' only applies to monthly watches")
        }
        if (every == null && weekdayOnce != null && dayOfMonth != null) {
            throw WatchException("'day_of_month' only applies to monthly watches")
        }
        if (every != null) {
            when (every.lowercase()) {
                "daily" -> if (weekdayText != null || dayOfMonth != null) {
                    throw WatchException("A daily watch takes only 'time'; drop weekday/day_of_month")
                }
                "weekly" -> if (dayOfMonth != null) {
                    throw WatchException("'day_of_month' only applies to monthly watches")
                }
                "monthly" -> if (weekdayText != null) {
                    throw WatchException("'weekday' only applies to weekly watches")
                }
            }
        }
        if (at != null) return WatchScheduleInput.At(parseInstant(at, zone))
        if (within != null) return WatchScheduleInput.In(parseDuration(within))
        if (dayOffset != null) {
            return WatchScheduleInput.DayOffset(dayOffset, requireTime(fields.time))
        }
        if (every == null && weekdayOnce != null) {
            return WatchScheduleInput.WeekdayOnce(parseWeekday(weekdayOnce), requireTime(fields.time))
        }
        if (every != null) {
            val kind = when (every.lowercase()) {
                "daily" -> WatchScheduleKind.DAILY
                "weekly" -> WatchScheduleKind.WEEKLY
                "monthly" -> WatchScheduleKind.MONTHLY
                else -> throw WatchException("'every' must be daily, weekly or monthly")
            }
            return WatchScheduleInput.Every(
                kind = kind,
                time = requireTime(fields.time),
                weekday = fields.weekday?.let(::parseWeekday),
                dayOfMonth = fields.dayOfMonth
            )
        }
        return null
    }

    /**
     * A watch time is `HH:mm` only: the schedule column stores minutes, and accepting seconds or
     * nanos would either silently truncate the user's intent or overflow the stored value.
     */
    fun parseTime(raw: String): LocalTime {
        val time = runCatching { LocalTime.parse(raw) }.getOrElse {
            try {
                LocalTime.parse(raw, TIME_FORMAT)
            } catch (_: DateTimeParseException) {
                throw WatchException("'time' must be HH:mm in 24-hour form (for example 09:00)")
            }
        }
        if (time.second != 0 || time.nano != 0) {
            throw WatchException("'time' must be HH:mm in 24-hour form (for example 09:00)")
        }
        return time
    }

    fun parseWeekday(raw: String): DayOfWeek {
        val value = raw.trim().uppercase()
        DayOfWeek.entries.firstOrNull { it.name == value }?.let { return it }
        if (value.length >= 3) {
            DayOfWeek.entries.firstOrNull { it.name.startsWith(value) }?.let { return it }
        }
        throw WatchException("'weekday' must be a weekday name such as MONDAY or MON")
    }

    private fun parseInstant(raw: String, zone: ZoneId): Instant {
        fun attempt(block: () -> Instant): Instant? = try {
            block()
        } catch (_: DateTimeParseException) {
            null
        }
        return attempt { OffsetDateTime.parse(raw).toInstant() }
            ?: attempt { Instant.parse(raw) }
            ?: attempt { LocalDateTime.parse(raw).atZone(zone).toInstant() }
            ?: throw WatchException(
                "'at' must be an ISO-8601 date-time with an offset (2026-11-01T09:00:00+08:00) " +
                    "or a local date-time when 'timezone' is known"
            )
    }

    private fun parseDuration(raw: String): Duration {
        val match = DURATION_PATTERN.matchEntire(raw.trim().lowercase())
            ?: throw WatchException("'within' must look like 30m, 2h or 3d")
        val amount = match.groupValues[1].toLongOrNull()
            ?: throw WatchException("'within' must look like 30m, 2h or 3d")
        return when (match.groupValues[2]) {
            "m" -> Duration.ofMinutes(amount)
            "h" -> Duration.ofHours(amount)
            else -> Duration.ofDays(amount)
        }
    }

    private fun requireTime(raw: String?): LocalTime {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw WatchException("This trigger needs 'time' as HH:mm (for example 09:00)")
        return parseTime(value)
    }

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val DURATION_PATTERN = Regex("^(\\d{1,4})\\s*([mhd])$")
}
