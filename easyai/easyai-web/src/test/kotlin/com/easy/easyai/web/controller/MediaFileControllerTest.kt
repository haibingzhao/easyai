package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Tests [MediaFileController]'s local fallback: users without per-user object storage are served
 * from the deployment-local media directory, but only keys namespaced to their own id.
 */
class MediaFileControllerTest {

    private val auth = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", null, emptyList())
    )

    private fun client(resolver: ObjectStorageResolver?, localStorage: ObjectStorage?): WebTestClient =
        WebTestClient.bindToController(MediaFileController(resolver, localStorage))
            .webFilter<WebTestClient.ControllerSpec>({ exchange, chain -> chain.filter(exchange).contextWrite(auth) })
            .build()

    private fun stubContent(key: String): ObjectContent {
        val bytes = "fake-png".toByteArray()
        return ObjectContent(ObjectMeta(key = key, size = bytes.size.toLong()), bytes)
    }

    private fun storageHolding(vararg keys: String): ObjectStorage {
        val storage = mockk<ObjectStorage>()
        keys.forEach { key -> coEvery { storage.get(key) } returns stubContent(key) }
        coEvery { storage.get(match { it !in keys }) } returns null
        return storage
    }

    @Test
    fun `local fallback serves an owned key when no user storage is configured`() {
        val key = "media/alice/2026-09-23/x.png"
        val local = storageHolding(key)
        val resolver = mockk<ObjectStorageResolver> { coEvery { resolve("alice") } returns null }

        client(resolver, local)
            .get().uri("/api/media/file?key=$key")
            .exchange().expectStatus().isOk
            .expectBody().returnResult()

        coVerify { local.get(key) }
    }

    @Test
    fun `local fallback refuses a key owned by another user`() {
        val local = mockk<ObjectStorage>()
        val resolver = mockk<ObjectStorageResolver> { coEvery { resolve("alice") } returns null }

        client(resolver, local)
            .get().uri("/api/media/file?key=media/bob/2026-09-23/x.png")
            .exchange().expectStatus().isNotFound

        coVerify(exactly = 0) { local.get(any()) }
    }

    @Test
    fun `a user storage miss still falls back to the local directory`() {
        val key = "media/alice/2026-09-23/legacy.png"
        val userStorage = storageHolding()
        val local = storageHolding(key)
        val resolver = mockk<ObjectStorageResolver> { coEvery { resolve("alice") } returns userStorage }

        client(resolver, local)
            .get().uri("/api/media/file?key=$key")
            .exchange().expectStatus().isOk

        coVerify { local.get(key) }
    }

    @Test
    fun `no storage layer at all is a service unavailable`() {
        val resolver = mockk<ObjectStorageResolver> { coEvery { resolve("alice") } returns null }

        client(resolver, null)
            .get().uri("/api/media/file?key=media/alice/2026-09-23/x.png")
            .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `chat-images keys stay reserved and traversal is rejected`() {
        val local = storageHolding("media/alice/chat-images/x.png")

        client(null, local)
            .get().uri("/api/media/file?key=media/alice/chat-images/x.png")
            .exchange().expectStatus().isNotFound
        client(null, local)
            .get().uri("/api/media/file?key=media/alice/../secrets")
            .exchange().expectStatus().isBadRequest
        coVerify(exactly = 0) { local.get(any()) }
    }
}
