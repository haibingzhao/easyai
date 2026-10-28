package com.easy.easyai.web.controller

import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.watch.WatchConflictException
import com.easy.easyai.core.watch.WatchException
import com.easy.easyai.core.watch.WatchForeignHostException
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotFoundException
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchScheduleInput
import com.easy.easyai.core.watch.WatchService
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchTag
import com.easy.easyai.core.watch.WatchTriggerParser
import com.easy.easyai.core.watch.WatchUnavailableException
import com.easy.easyai.core.watch.WatchUpdateInput
import com.easy.easyai.web.model.WatchDtos
import com.easy.easyai.web.model.WatchDto
import com.easy.easyai.web.model.WatchFindingTaskDto
import com.easy.easyai.web.model.WatchScheduleRequest
import com.easy.easyai.web.model.WatchUpdateRequest
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * REST surface over watch definitions: the page's list/detail/edit controls plus the findings
 * timeline.
 *
 * Every by-id operation goes through [WatchService], which checks `user_id` before returning or
 * mutating a row, so this controller never has to re-derive ownership. Findings are read from the
 * shared task table by the watch's hidden session — a watch's history is exactly those task rows.
 */
@RestController
@RequestMapping("/api/watches")
class WatchController(
    @param:Autowired(required = false) private val watchService: WatchService? = null,
    @param:Autowired(required = false) private val taskStore: AsyncTaskStore? = null
) {

    @GetMapping
    fun listWatches(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") limit: Int
    ): Mono<List<WatchDto>> = mono {
        val watches = service().list(
            userId = getCurrentUserId(),
            statuses = parseStatuses(status),
            limit = limit.coerceIn(1, 200)
        )
        watches.map(WatchDtos::toDto)
    }

    @GetMapping("/{id}")
    fun getWatch(@PathVariable id: String): Mono<WatchDto> = mono {
        onWatch { WatchDtos.toDto(service().get(getCurrentUserId(), id)) }
    }

    /** Both check findings and expiry reviews; the UI branches on `result.kind`. */
    @GetMapping("/{id}/findings")
    fun listFindings(
        @PathVariable id: String,
        @RequestParam(defaultValue = "50") limit: Int
    ): Mono<List<WatchFindingTaskDto>> = mono {
        val userId = getCurrentUserId()
        val watch = onWatch { service().get(userId, id) }
        val store = taskStore ?: throw ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Task store is not available"
        )
        store.listByUser(
            userId = userId,
            type = AsyncTaskType.WATCH,
            sessionId = watch.runSessionId,
            limit = limit.coerceIn(1, 200)
        ).map { task ->
            WatchFindingTaskDto(
                taskId = task.id,
                status = task.status.name.lowercase(),
                createdAt = task.createdAt,
                completedAt = task.completedAt,
                error = task.error,
                result = task.result
            )
        }
    }

    @PatchMapping("/{id}")
    fun updateWatch(
        @PathVariable id: String,
        @RequestBody request: WatchUpdateRequest
    ): Mono<WatchDto> = mono {
        val userId = getCurrentUserId()
        val current = onWatch { service().get(userId, id) }
        val requestZone = request.timezone?.let(::parseZone)
        val zone = requestZone ?: current.schedule.zone
        val input = WatchUpdateInput(
            title = request.title,
            condition = request.condition,
            mode = request.mode?.let(::parseMode),
            reminder = request.reminder,
            clearReminder = request.clearReminder ?: false,
            schedule = request.schedule?.let { schedule(it, zone) },
            timezone = requestZone,
            until = parseUntil(request.until),
            clearUntil = request.clearUntil ?: false,
            tags = request.tags?.map { WatchTag(it.key, it.label, it.description) },
            completeOnMet = request.completeOnMet,
            notifyPolicy = request.notifyPolicy?.let(::parseNotifyPolicy),
            notifyTags = request.notifyTags?.toSet(),
            sourceHints = request.sourceHints,
            clearSourceHints = request.clearSourceHints ?: false
        )
        onWatch { WatchDtos.toDto(service().update(userId, id, input).watch) }
    }

    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: String): Mono<WatchDto> = mono {
        onWatch { WatchDtos.toDto(service().pause(getCurrentUserId(), id)) }
    }

    @PostMapping("/{id}/resume")
    fun resume(@PathVariable id: String): Mono<WatchDto> = mono {
        onWatch { WatchDtos.toDto(service().resume(getCurrentUserId(), id)) }
    }

    /** Stop future firings but keep the definition and its history. */
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: String): Mono<WatchDto> = mono {
        onWatch { WatchDtos.toDto(service().cancel(getCurrentUserId(), id)) }
    }

    /** Queue a check now without moving the schedule; used by the detail page and E2E tests. */
    @PostMapping("/{id}/run-now")
    fun runNow(@PathVariable id: String): Mono<Map<String, Any?>> = mono {
        val task = onWatch { service().runNow(getCurrentUserId(), id) }
        mapOf("taskId" to task.id, "status" to "pending")
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Mono<Map<String, Boolean>> = mono {
        mapOf("deleted" to onWatch { service().delete(getCurrentUserId(), id) })
    }

    private fun service(): WatchService = watchService ?: throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Watch scheduling is not enabled in this deployment"
    )

    /** Translate the service's domain errors into HTTP once, so every endpoint answers consistently. */
    private suspend fun <T> onWatch(block: suspend () -> T): T = try {
        block()
    } catch (e: WatchException) {
        throw when (e) {
            is WatchNotFoundException -> ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
            is WatchConflictException -> ResponseStatusException(HttpStatus.CONFLICT, e.message)
            // Not a malformed request: the row is fine, it just is not this application's to touch.
            is WatchForeignHostException -> ResponseStatusException(HttpStatus.CONFLICT, e.message)
            is WatchUnavailableException -> ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message)
            else -> ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }

    private fun schedule(request: WatchScheduleRequest, zone: ZoneId): WatchScheduleInput = try {
        WatchTriggerParser.parse(
            fields = WatchTriggerParser.Fields(
                at = request.at,
                within = request.within,
                dayOffset = request.dayOffset,
                every = request.every,
                time = request.time,
                weekday = request.weekday,
                dayOfMonth = request.dayOfMonth
            ),
            zone = zone,
            require = true
        ) ?: throw WatchException("A new schedule needs at least one trigger field")
    } catch (e: WatchException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
    }

    private fun parseStatuses(raw: String?): Set<WatchStatus>? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.equals("all", ignoreCase = true)) return null
        return value.split(',').mapNotNull { token ->
            WatchStatus.entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown watch status '${token.trim()}'"
                )
        }.toSet().takeIf { it.isNotEmpty() }
    }

    private fun parseZone(raw: String): ZoneId = try {
        ZoneId.of(raw)
    } catch (e: DateTimeException) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid timezone '$raw'; use an IANA zone such as Asia/Shanghai"
        )
    }

    private fun parseMode(raw: String): WatchMode = when (raw.trim().lowercase()) {
        "check" -> WatchMode.CHECK
        "remind" -> WatchMode.REMIND
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "'mode' must be check or remind")
    }

    private fun parseNotifyPolicy(raw: String): WatchNotifyPolicy = when (
        raw.trim().lowercase().replace('-', '_')
    ) {
        "on_change" -> WatchNotifyPolicy.ON_CHANGE
        "on_complete" -> WatchNotifyPolicy.ON_COMPLETE
        "every" -> WatchNotifyPolicy.EVERY
        "never" -> WatchNotifyPolicy.NEVER
        else -> throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "'notifyPolicy' must be on_change, on_complete, every or never"
        )
    }

    private fun parseUntil(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "'until' must be a date like 2026-12-31"
            )
        }
    }
}
