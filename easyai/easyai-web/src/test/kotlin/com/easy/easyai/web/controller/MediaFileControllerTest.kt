package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaFileControllerTest {
    @ParameterizedTest
    @ValueSource(strings = [
        "chat-images/YWxpY2U/session/image.png", "/chat-images/YWxpY2U/session/image.png",
        "./chat-images/YWxpY2U/session/image.png", "//./CHAT-IMAGES/YWxpY2U/session/image.png",
        "chat-images\\YWxpY2U\\session\\image.png"
    ])
    fun `chat images cannot bypass session authorization`(key: String) = runTest {
        val resolver = mockk<ObjectStorageResolver>()
        val error = assertFailsWith<ResponseStatusException> {
            MediaFileController(resolver).serve(key).awaitSingle()
        }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `generated media remains accessible`() = runTest {
        val resolver = mockk<ObjectStorageResolver>()
        val storage = mockk<ObjectStorage>()
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { resolver.resolve("system") } returns storage
        coEvery { storage.get("generated/image.png") } returns ObjectContent(ObjectMeta("generated/image.png", 3), bytes)
        val response = MediaFileController(resolver).serve("generated/image.png").awaitSingle()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertContentEquals(bytes, response.body!!.byteArray)
    }
}
