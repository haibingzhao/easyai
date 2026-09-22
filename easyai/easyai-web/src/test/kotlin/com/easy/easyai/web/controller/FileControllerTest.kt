package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StoredFileReference
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.web.service.FileStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileControllerTest {
    @TempDir
    lateinit var root: Path

    private val store = mockk<AsyncSessionStore>()
    private val auth = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", null, emptyList())
    )

    private fun part(bytes: ByteArray, name: String = "shot.png", mime: MediaType = MediaType.IMAGE_PNG): FilePart = mockk {
        every { filename() } returns name
        every { headers() } returns HttpHeaders().apply { contentType = mime }
        every { content() } answers { Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes)) }
    }

    @Test
    fun `HTTP multipart upload resolves the session from query parameters`() {
        val service = FileStorageService(root.toString())
        coEvery { store.isSessionOwnedByUser("session-1", "alice") } returns true
        val client = WebTestClient.bindToController(FileController(service, store))
            .webFilter<WebTestClient.ControllerSpec>({ exchange, chain -> chain.filter(exchange).contextWrite(auth) })
            .build()
        val multipart = MultipartBodyBuilder()
        multipart.part("file", ByteArrayResource(byteArrayOf(1, 2, 3)))
            .filename("shot.png").contentType(MediaType.IMAGE_PNG)
        val result = client.post().uri("/api/files/upload?sessionId=session-1")
            .body(BodyInserters.fromMultipartData(multipart.build()))
            .exchange().expectStatus().isOk
            .expectBody(object : ParameterizedTypeReference<Map<String, String>>() {})
            .returnResult().responseBody!!
        assertEquals("shot.png", result["name"])
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(Path.of(result.getValue("filePath"))))
        val image = client.get().uri(URI.create(result.getValue("url"))).exchange().expectStatus().isOk
            .expectHeader().contentType(MediaType.IMAGE_PNG)
            .expectBody().returnResult().responseBody
        assertContentEquals(byteArrayOf(1, 2, 3), image)
    }

    @Test
    fun `uploads and serves local image with explicit user ownership`() = runTest {
        val service = FileStorageService(root.toString())
        val controller = FileController(service, store)
        coEvery { store.isSessionOwnedByUser("session-1", "alice") } returns true
        val bytes = byteArrayOf(1, 2, 3)
        val result = controller.uploadFile(part(bytes), "session-1").contextWrite(auth).awaitSingle()
        val path = result.getValue("filePath")
        assertContentEquals(bytes, Files.readAllBytes(Path.of(path)))
        assertEquals("image/png", result["mimeType"])
        assertTrue(result.getValue("url").startsWith("/api/files/serve?path="))
        val response = controller.serveFile(path).contextWrite(auth).awaitSingle()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertContentEquals(bytes, response.body!!.inputStream.use { it.readBytes() })
    }

    @Test
    fun `rejects upload and serve from another users session`() = runTest {
        val service = FileStorageService(root.toString())
        val controller = FileController(service, store)
        coEvery { store.isSessionOwnedByUser("other-session", "alice") } returns false
        val uploadError = assertFailsWith<ResponseStatusException> {
            controller.uploadFile(part(byteArrayOf(1)), "other-session").contextWrite(auth).awaitSingle()
        }
        assertEquals(HttpStatus.FORBIDDEN, uploadError.statusCode)
        val serveError = assertFailsWith<ResponseStatusException> {
            controller.serveFile(service.imagesRoot.resolve("other-session/image.png").toString()).contextWrite(auth).awaitSingle()
        }
        assertEquals(HttpStatus.FORBIDDEN, serveError.statusCode)
        assertFalse(Files.exists(service.imagesRoot.resolve("other-session")))
    }

    @Test
    fun `rejects foreign storage reference without resolving storage`() = runTest {
        val resolver = mockk<ObjectStorageResolver>()
        val controller = FileController(FileStorageService(root.toString(), resolver), store)
        val reference = StoredFileReference.create("bob", "session-1", "png")
        val error = assertFailsWith<ResponseStatusException> {
            controller.serveFile(reference).contextWrite(auth).awaitSingle()
        }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `rejects oversized and unsupported image upload before writing`() = runTest {
        val resolver = mockk<ObjectStorageResolver>()
        val controller = FileController(FileStorageService(root.toString(), resolver), store)
        coEvery { store.isSessionOwnedByUser("session-1", "alice") } returns true
        val oversized = assertFailsWith<ResponseStatusException> {
            controller.uploadFile(part(ByteArray(6 * 1024 * 1024 + 1)), "session-1").contextWrite(auth).awaitSingle()
        }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, oversized.statusCode)
        val unsupported = assertFailsWith<ResponseStatusException> {
            controller.uploadFile(part(byteArrayOf(1), "image.svg", MediaType.parseMediaType("image/svg+xml")), "session-1")
                .contextWrite(auth).awaitSingle()
        }
        assertEquals(HttpStatus.BAD_REQUEST, unsupported.statusCode)
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `stored upload survives signing failure and proxy reads the image`() = runTest {
        val resolver = mockk<ObjectStorageResolver>()
        val storage = mockk<ObjectStorage>()
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.put(any(), any(), any()) } answers { ObjectMeta(firstArg(), 3) }
        coEvery { storage.head(any()) } answers { ObjectMeta(firstArg(), 3) }
        coEvery { storage.get(any()) } answers { ObjectContent(ObjectMeta(firstArg(), 3), bytes) }
        coEvery { storage.presignedGetUrl(any(), any()) } throws ObjectStorageException("signing unavailable")
        coEvery { store.isSessionOwnedByUser("session-1", "alice") } returns true
        val service = FileStorageService(root.toString(), resolver)
        val controller = FileController(service, store)
        val result = controller.uploadFile(part(bytes), "session-1").contextWrite(auth).awaitSingle()
        val reference = result.getValue("filePath")
        assertTrue(StoredFileReference.isStored(reference))
        assertEquals(service.proxyUrl(reference), result["url"])
        assertFalse(Files.exists(service.imagesRoot.resolve("session-1")))
        val response = controller.serveFile(reference).contextWrite(auth).awaitSingle()
        assertContentEquals(bytes, response.body!!.inputStream.use { it.readBytes() })
        coVerify(exactly = 1) { storage.put(any(), bytes, "image/png") }
    }

    @Test
    fun `object upload failure is returned rather than saved locally`() = runTest {
        val resolver = mockk<ObjectStorageResolver>()
        val storage = mockk<ObjectStorage>()
        coEvery { resolver.resolve("alice") } returns storage
        coEvery { storage.put(any(), any(), any()) } throws ObjectStorageException("offline")
        coEvery { store.isSessionOwnedByUser("session-1", "alice") } returns true
        val service = FileStorageService(root.toString(), resolver)
        val error = assertFailsWith<ResponseStatusException> {
            FileController(service, store).uploadFile(part(byteArrayOf(1)), "session-1").contextWrite(auth).awaitSingle()
        }
        assertEquals(HttpStatus.BAD_GATEWAY, error.statusCode)
        assertFalse(Files.exists(service.imagesRoot.resolve("session-1")))
    }
}
