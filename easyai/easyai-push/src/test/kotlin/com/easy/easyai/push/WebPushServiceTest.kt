package com.easy.easyai.push

import com.easy.easyai.repository.push.PushSubscription
import com.easy.easyai.repository.push.PushSubscriptionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.Base64
import java.util.Collections

/**
 * The best-effort delivery side of push.
 *
 * These tests deliberately call [WebPushService.deliver] instead of `enqueue`, so the
 * assertions are synchronous: `enqueue` is fire-and-forget by design and would need an
 * `advanceUntilIdle` on an IO dispatcher to be observed. The transport is a recording fake —
 * no test here may reach Apple's push service.
 */
class WebPushServiceTest {

    private val properties = PushProperties().apply {
        enabled = true
        allowedHosts = listOf("web.push.apple.com")
    }
    private val sender = RecordingSender()
    private val subscriptions = mockk<PushSubscriptionStore>(relaxed = true)
    private val service = WebPushService(properties, sender, subscriptions)

    private val message = PushMessage(
        title = "户主转移请求",
        body = "张三 想把「家」的户主身份转移给你",
        url = "/mobile.html#/home/notifications",
        tag = "n1",
        type = "OWNER_TRANSFER",
    )

    // ==================== enablement ====================

    @Test
    fun `the property switch turns the whole channel off`() = runBlocking<Unit> {
        properties.enabled = false

        val testFailure = runCatching { service.sendTest("user-b") }.exceptionOrNull()

        assertAll(
            { assertThat(service.config()).isEqualTo(PushConfig(false, null, "手机推送未启用")) },
            { assertThat(testFailure).isInstanceOf(PushConflictException::class.java) },
        )
    }

    @Test
    fun `a missing key pair turns the channel off even when the property is on`() = runBlocking<Unit> {
        sender.configured = false

        val config = service.config()

        // "enabled" is the effective state, not just the property: a UI that trusted the flag
        // alone would announce a channel that cannot encrypt anything.
        assertAll(
            { assertThat(config.enabled).isFalse() },
            { assertThat(config.publicKey).isNull() },
            { assertThat(config.reason).contains("VAPID") },
        )
        service.deliver("user-b", message)
        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `a negative TTL turns the channel off instead of failing every send`() = runBlocking<Unit> {
        properties.ttlSeconds = -1

        assertThat(service.config())
            .isEqualTo(PushConfig(false, null, "easyai.push.ttl-seconds 不能为负数"))
    }

    @Test
    fun `a blank allowlist turns the channel off`() = runBlocking<Unit> {
        properties.allowedHosts = listOf(" ")

        assertThat(service.config())
            .isEqualTo(PushConfig(false, null, "easyai.push.allowed-hosts 不能为空"))
    }

    // ==================== delivery ====================

    @Test
    fun `an account without devices is not an error`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns emptyList()

        service.deliver("user-b", message)

        assertThat(sender.sent).isEmpty()
    }

    @Test
    fun `every device of the account receives the notification payload`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns
            listOf(subscription("sub-1"), subscription("sub-2"))

        service.deliver("user-b", message)

        assertAll(
            { assertThat(sender.sent.map { it.first.id }).containsExactlyInAnyOrder("sub-1", "sub-2") },
            { assertThat(sender.sent.first().second).contains("\"title\":\"户主转移请求\"") },
            { assertThat(sender.sent.first().second).contains("\"notificationId\":\"n1\"") },
            { assertThat(sender.sent.first().second).contains("\"type\":\"OWNER_TRANSFER\"") },
            { assertThat(sender.sent.first().second).contains("\"url\":\"/mobile.html#/home/notifications\"") },
            { assertThat(sender.sent.first().second.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(3000) },
        )
    }

    @Test
    fun `a CJK and emoji payload stays inside the encrypted size ceiling`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns listOf(subscription("sub-1"))

        service.deliver("user-b", message.copy(title = "户".repeat(200), body = "😀".repeat(280)))

        assertThat(sender.sent.single().second.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(3000)
    }

    @Test
    fun `a blank message falls back to the channel defaults`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns listOf(subscription("sub-1"))

        service.deliver("user-b", PushMessage(title = "  ", body = " ", url = ""))

        val payload = sender.sent.single().second
        assertAll(
            { assertThat(payload).contains("\"title\":\"新通知\"") },
            { assertThat(payload).contains("\"body\":\"你有一条新通知\"") },
            { assertThat(payload).contains("\"url\":\"/\"") },
            { assertThat(payload).doesNotContain("notificationId") },
            { assertThat(payload).doesNotContain("\"type\"") },
        )
    }

    @Test
    fun `a throwing sender does not stop the other devices`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns
            listOf(subscription("sub-1"), subscription("sub-2"))
        sender.failureFor = { subscription ->
            if (subscription.id == "sub-1") IllegalStateException("boom") else null
        }

        service.deliver("user-b", message)

        assertThat(sender.sent.map { it.first.id }).containsExactly("sub-2")
    }

    @Test
    fun `a gone subscription is deleted while a transient failure is kept`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns
            listOf(subscription("sub-1"), subscription("sub-2"))
        sender.resultFor = { subscription ->
            if (subscription.id == "sub-1") PushSendResult.Gone
            else PushSendResult.Failed(status = 503, reason = "try later")
        }

        service.deliver("user-b", message)

        assertAll(
            { coVerify(exactly = 1) { subscriptions.deleteById("sub-1") } },
            { coVerify(exactly = 0) { subscriptions.deleteById("sub-2") } },
        )
    }

    // ==================== test notification ====================

    @Test
    fun `a test notification with no device reports zeros`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns emptyList()

        assertThat(service.sendTest("user-b")).isEqualTo(PushTestResult(sent = 0, failed = 0, gone = 0))
    }

    @Test
    fun `a test notification fans out to every device`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns
            listOf(subscription("sub-1"), subscription("sub-2"))

        assertThat(service.sendTest("user-b")).isEqualTo(PushTestResult(sent = 2, failed = 0, gone = 0))
        assertThat(sender.sent.map { it.second }).allMatch { it.contains("测试通知") }
    }

    @Test
    fun `a test notification cleans up a device Apple no longer knows`() = runBlocking<Unit> {
        coEvery { subscriptions.findByUserId("user-b") } returns
            listOf(subscription("sub-1"), subscription("sub-2"))
        sender.resultFor = { subscription ->
            if (subscription.id == "sub-1") PushSendResult.Gone else PushSendResult.Sent
        }

        val result = service.sendTest("user-b")

        assertAll(
            { assertThat(result).isEqualTo(PushTestResult(sent = 1, failed = 0, gone = 1)) },
            { coVerify(exactly = 1) { subscriptions.deleteById("sub-1") } },
        )
    }

    @Test
    fun `a test notification is refused while the channel is disabled`() {
        properties.enabled = false

        assertThatThrownBy { runBlocking { service.sendTest("user-b") } }
            .isInstanceOf(PushConflictException::class.java)
            .hasMessageContaining("手机推送未启用")
    }

    // ==================== subscription validation ====================

    @Test
    fun `an endpoint outside the allowlist or using another transport is a 400`() {
        val invalid = listOf(
            "http://web.push.apple.com/abc",
            "https://evil.example.com/abc",
            "https://user@web.push.apple.com/abc",
            "https://web.push.apple.com:8443/abc",
            "https://web.push.apple.com/abc#fragment",
            "https://web.push.apple.com/" + "a".repeat(2048),
        )

        invalid.forEach { endpoint ->
            assertThatThrownBy {
                runBlocking { service.subscribe("user-b", endpoint, VALID_P256DH, VALID_AUTH, null) }
            }
                .describedAs(endpoint)
                .isInstanceOf(PushValidationException::class.java)
        }
        coVerify(exactly = 0) { subscriptions.upsert(any()) }
    }

    @Test
    fun `structurally invalid keys are a 400 instead of an encryption failure later`() {
        val badP256dh = listOf(
            "not base64!",
            base64Url(ByteArray(64) { 0x01 }),
            base64Url(ByteArray(65)),
        )
        val badAuth = listOf("", base64Url(ByteArray(15)))

        badP256dh.forEach { value ->
            assertThatThrownBy {
                runBlocking { service.subscribe("user-b", ENDPOINT, value, VALID_AUTH, null) }
            }
                .describedAs("p256dh=$value")
                .isInstanceOf(PushValidationException::class.java)
        }
        badAuth.forEach { value ->
            assertThatThrownBy {
                runBlocking { service.subscribe("user-b", ENDPOINT, VALID_P256DH, value, null) }
            }
                .describedAs("auth=$value")
                .isInstanceOf(PushValidationException::class.java)
        }
    }

    @Test
    fun `a subscription is stored even while the channel is disabled`() = runBlocking<Unit> {
        properties.enabled = false

        service.subscribe("user-b", ENDPOINT, VALID_P256DH, VALID_AUTH, "iPhone")

        // The subscription is a fact about the device; keeping it means enabling the server
        // later does not force the user to grant permission again.
        coVerify(exactly = 1) { subscriptions.upsert(any()) }
    }

    @Test
    fun `an allowlist entry with surrounding spaces still matches`() = runBlocking<Unit> {
        properties.allowedHosts = listOf(" web.push.apple.com ")

        service.subscribe("user-b", ENDPOINT, VALID_P256DH, VALID_AUTH, null)

        coVerify(exactly = 1) { subscriptions.upsert(any()) }
    }

    @Test
    fun `a device at the cap may refresh its own endpoint`() = runBlocking<Unit> {
        val existing = subscription("sub-1", endpoint = ENDPOINT)
        coEvery { subscriptions.findByEndpoint(ENDPOINT) } returns existing
        coEvery { subscriptions.countByUserId("user-b") } returns 20
        val saved = slot<PushSubscription>()

        service.subscribe("user-b", existing.endpoint, VALID_P256DH, VALID_AUTH, "iPhone")

        coVerify(exactly = 1) { subscriptions.upsert(capture(saved)) }
        assertThat(saved.captured.endpoint).isEqualTo(existing.endpoint)
    }

    @Test
    fun `a 21st new device is refused with a conflict`() {
        coEvery { subscriptions.findByEndpoint(ENDPOINT) } returns null
        coEvery { subscriptions.countByUserId("user-b") } returns 20

        assertThatThrownBy {
            runBlocking { service.subscribe("user-b", ENDPOINT, VALID_P256DH, VALID_AUTH, null) }
        }
            .isInstanceOf(PushConflictException::class.java)
            .hasMessageContaining("上限")
        coVerify(exactly = 0) { subscriptions.upsert(any()) }
    }

    @Test
    fun `a user agent longer than the column is truncated`() = runBlocking<Unit> {
        val saved = slot<PushSubscription>()

        service.subscribe("user-b", ENDPOINT, VALID_P256DH, VALID_AUTH, "x".repeat(900))

        coVerify(exactly = 1) { subscriptions.upsert(capture(saved)) }
        assertThat(saved.captured.userAgent).hasSize(500)
    }

    // ==================== helpers ====================

    private class RecordingSender(
        var configured: Boolean = true,
    ) : WebPushSender {
        var resultFor: (PushSubscription) -> PushSendResult = { PushSendResult.Sent }
        var failureFor: (PushSubscription) -> Exception? = { null }
        val sent = Collections.synchronizedList(mutableListOf<Pair<PushSubscription, String>>())

        override fun isConfigured(): Boolean = configured

        override fun publicKey(): String? = if (configured) PUBLIC_KEY else null

        override suspend fun send(subscription: PushSubscription, payloadJson: String): PushSendResult {
            failureFor(subscription)?.let { throw it }
            sent += subscription to payloadJson
            return resultFor(subscription)
        }
    }

    private fun subscription(
        id: String,
        endpoint: String = "https://web.push.apple.com/$id",
    ) = PushSubscription(
        id = id,
        userId = "user-b",
        endpoint = endpoint,
        p256dh = VALID_P256DH,
        auth = VALID_AUTH,
    )

    private companion object {
        const val ENDPOINT = "https://web.push.apple.com/abc"
        const val PUBLIC_KEY = "BPublicKey"

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        val VALID_P256DH: String = base64Url(byteArrayOf(0x04) + ByteArray(64) { (it + 1).toByte() })
        val VALID_AUTH: String = base64Url(ByteArray(16) { (it + 1).toByte() })
    }
}
