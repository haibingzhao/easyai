package com.easy.easyai.autoconfigure.push

import com.easy.easyai.push.PushProperties
import com.easy.easyai.push.PushSendResult
import com.easy.easyai.push.WebPushService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * The HTTP surface of the shared channel, exercised without security (auth disabled path):
 * [com.easy.easyai.web.security.getCurrentUserId] falls back to `system`, which is enough to
 * prove the ownership scoping of subscribe/unsubscribe.
 */
class PushControllerTest {

    private val properties = PushProperties().apply { enabled = true }
    private val sender = FakeSender()
    private val store = MemoryStore()
    private val service = WebPushService(properties, sender, store)
    private val client = WebTestClient.bindToController(PushController(service)).build()

    // ==================== config ====================

    @Test
    fun `config advertises the key when the channel is ready`() {
        client.get().uri("/api/push/config").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(true)
            .jsonPath("$.publicKey").isEqualTo(FakeSender.PUBLIC_KEY)
            .jsonPath("$.reason").doesNotExist()
    }

    @Test
    fun `config hides the key while the channel is switched off`() {
        properties.enabled = false

        client.get().uri("/api/push/config").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(false)
            .jsonPath("$.publicKey").doesNotExist()
            .jsonPath("$.reason").isNotEmpty
    }

    // ==================== subscriptions ====================

    @Test
    fun `a valid subscription is stored for the caller`() {
        subscribe().expectStatus().isOk.expectBody().jsonPath("$.subscribed").isEqualTo(true)

        val saved = runBlocking { store.findByEndpoint(TEST_ENDPOINT) }
        assertThat(saved).isNotNull
        assertThat(saved!!.userId).isEqualTo("system")
    }

    @Test
    fun `an endpoint outside the allowlist is a 400`() {
        client.post().uri("/api/push/subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "endpoint" to "https://evil.example.com/abc",
                    "keys" to mapOf("p256dh" to TEST_P256DH, "auth" to TEST_AUTH),
                ),
            )
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `unsubscribe removes the caller's device and is idempotent`() {
        subscribe().expectStatus().isOk

        client.post().uri("/api/push/unsubscribe")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("endpoint" to TEST_ENDPOINT))
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.removed").isEqualTo(1)

        client.post().uri("/api/push/unsubscribe")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("endpoint" to TEST_ENDPOINT))
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.removed").isEqualTo(0)
    }

    // ==================== test notification ====================

    @Test
    fun `a test notification reports one accepted device`() {
        subscribe().expectStatus().isOk

        client.post().uri("/api/push/test").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.sent").isEqualTo(1)
            .jsonPath("$.failed").isEqualTo(0)
            .jsonPath("$.gone").isEqualTo(0)
    }

    @Test
    fun `a test notification is refused with a conflict while disabled`() {
        properties.enabled = false

        client.post().uri("/api/push/test").exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `a dead device is reported and cleaned up by the test notification`() {
        subscribe().expectStatus().isOk
        sender.result = PushSendResult.Gone

        client.post().uri("/api/push/test").exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.gone").isEqualTo(1)

        assertThat(runBlocking { store.findByEndpoint(TEST_ENDPOINT) }).isNull()
    }

    // ==================== service worker ====================

    @Test
    fun `the service worker is public, root-scoped and never cached`() {
        client.get().uri("/api/push/sw.js").exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Service-Worker-Allowed", "/")
            .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*no-store.*")
            .expectHeader().contentTypeCompatibleWith("text/javascript")
            .expectBody(String::class.java)
            .value { script ->
                assertThat(script).contains("notificationclick")
                assertThat(script).contains("showNotification")
            }
    }

    private fun subscribe() = client.post().uri("/api/push/subscriptions")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            mapOf(
                "endpoint" to TEST_ENDPOINT,
                "keys" to mapOf("p256dh" to TEST_P256DH, "auth" to TEST_AUTH),
            ),
        )
        .exchange()
}
