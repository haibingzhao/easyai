package com.easy.easyai.autoconfigure.notification

import com.easy.easyai.notification.NotificationConflictException
import com.easy.easyai.notification.NotificationDecision
import com.easy.easyai.notification.NotificationForbiddenException
import com.easy.easyai.notification.NotificationNotFoundException
import com.easy.easyai.notification.NotificationPage
import com.easy.easyai.notification.NotificationService
import com.easy.easyai.notification.NotificationValidationException
import com.easy.easyai.notification.NotificationView
import com.easy.easyai.repository.notification.NotificationSummary
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * The account-level notification API shared by EasyAI applications.
 *
 * Every query is scoped to [getCurrentUserId] and to the deployment's configured
 * `easyai.notification.source`: the inbox is what a login account sees in one product, so no route
 * takes a user id, a family id, or a source. There is deliberately **no publish endpoint** — only
 * server-side business code may create a notification, otherwise any client could write into another
 * account's inbox.
 *
 * Responding addresses the caller's own copy by its row id; the framework resolves the whole event
 * from there, so the client never has to know that two copies exist.
 */
@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notifications: NotificationService,
) {

    @GetMapping
    fun list(
        @RequestParam(value = "cursor", required = false) cursor: String?,
        @RequestParam(value = "limit", required = false) limit: Int?,
    ): Mono<NotificationPage> = mono {
        val userId = getCurrentUserId()
        notifications.inbox(userId, cursor, limit)
    }

    @GetMapping("/summary")
    fun summary(): Mono<NotificationSummary> = mono {
        notifications.summary(getCurrentUserId())
    }

    @PostMapping("/{id}/read")
    fun markRead(@PathVariable("id") id: String): Mono<NotificationView> = mono {
        mapped { notifications.markRead(getCurrentUserId(), id) }
    }

    @PostMapping("/read-all")
    fun markAllRead(): Mono<Map<String, Int>> = mono {
        mapOf("read" to notifications.markAllRead(getCurrentUserId()))
    }

    @PostMapping("/{id}/accept")
    fun accept(@PathVariable("id") id: String): Mono<NotificationView> = respond(id, NotificationDecision.ACCEPT)

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable("id") id: String): Mono<NotificationView> = respond(id, NotificationDecision.REJECT)

    @PostMapping("/{id}/withdraw")
    fun withdraw(@PathVariable("id") id: String): Mono<NotificationView> = respond(id, NotificationDecision.WITHDRAW)

    private fun respond(id: String, decision: NotificationDecision): Mono<NotificationView> = mono {
        mapped { notifications.respond(getCurrentUserId(), id, decision) }
    }

    /** Maps the domain errors onto the API's `{ "error": ... }` contract. */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (error: NotificationValidationException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    } catch (error: NotificationForbiddenException) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, error.message)
    } catch (error: NotificationNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, error.message)
    } catch (error: NotificationConflictException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }
}
