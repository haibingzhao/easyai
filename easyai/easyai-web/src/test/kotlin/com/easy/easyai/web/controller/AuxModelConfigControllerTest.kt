package com.easy.easyai.web.controller

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
import com.easy.easyai.core.model.aux.AuxModelTask
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.util.context.Context
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `PUT /api/aux-models/{task}` must reject a model whose type the task's consumer cannot call.
 * Visibility alone let a CHAT-only purpose point at a SPEECH row, which saved fine and then failed
 * inside the consumer with nothing on the settings page to explain it.
 */
class AuxModelConfigControllerTest {

    private fun asUser(): Context = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>())
    )

    private fun config(id: String, modelType: ModelType) = ModelProviderConfig(
        id = id,
        name = id,
        protocol = Protocol.OPENAI,
        isCustom = true,
        modelId = "model-$id",
        modelType = modelType
    )

    private fun controllerFor(config: ModelProviderConfig): AuxModelConfigController {
        val settingsStore = mockk<AuxModelSettingsStore>(relaxed = true)
        val configStore = mockk<ModelProviderConfigStore>()
        coEvery { configStore.getConfig(config.id, any<Collection<String>>()) } returns config
        return AuxModelConfigController(settingsStore, configStore, null)
    }

    @Nested
    inner class TaskTypeGate {

        @Test
        fun `the tts task accepts a speech row`() {
            val dto = controllerFor(config("row-tts", ModelType.SPEECH))
                .save("tts", SaveAuxModelRequest("row-tts"))
                .contextWrite(asUser())
                .block()!!

            assertEquals("tts", dto.taskKey)
            assertEquals("row-tts", dto.modelConfigId)
        }

        @Test
        fun `the tts task rejects a chat row`() {
            val error = assertFailsWith<ResponseStatusException> {
                controllerFor(config("row-chat", ModelType.CHAT))
                    .save("tts", SaveAuxModelRequest("row-chat"))
                    .contextWrite(asUser())
                    .block()
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            assertEquals("Task 'tts' needs a SPEECH model; 'row-chat' is CHAT", error.reason)
        }

        @Test
        fun `a chat-only task rejects a speech row`() {
            val error = assertFailsWith<ResponseStatusException> {
                controllerFor(config("row-tts", ModelType.SPEECH))
                    .save("compaction", SaveAuxModelRequest("row-tts"))
                    .contextWrite(asUser())
                    .block()
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            assertTrue(error.reason!!.contains("needs a CHAT model"))
        }

        @Test
        fun `the asr task only accepts an asr row`() {
            assertFailsWith<ResponseStatusException> {
                controllerFor(config("row-speech", ModelType.SPEECH))
                    .save("asr", SaveAuxModelRequest("row-speech"))
                    .contextWrite(asUser())
                    .block()
            }
            val dto = controllerFor(config("row-asr", ModelType.ASR))
                .save("asr", SaveAuxModelRequest("row-asr"))
                .contextWrite(asUser())
                .block()!!
            assertEquals("row-asr", dto.modelConfigId)
        }

        @Test
        fun `clearing the choice needs no model at all`() {
            val settingsStore = mockk<AuxModelSettingsStore>(relaxed = true)
            val dto = AuxModelConfigController(settingsStore, null, null)
                .save("tts", SaveAuxModelRequest(null))
                .contextWrite(asUser())
                .block()!!

            assertEquals("", dto.modelConfigId)
            assertEquals("default", dto.effectiveSource)
        }

        @Test
        fun `every task that can be saved has a model type mapping`() {
            // An enum constant added without a TASK_MODEL_TYPE entry makes its task unsaveable:
            // every save is rejected with "no known model type". Probing every type proves a
            // mapping exists without duplicating the map here.
            for (task in AuxModelTask.entries) {
                val accepted = ModelType.entries.any { type ->
                    val id = "row-${task.key}-${type.name.lowercase()}"
                    runCatching {
                        controllerFor(config(id, type))
                            .save(task.key, SaveAuxModelRequest(id))
                            .contextWrite(asUser())
                            .block()
                    }.isSuccess
                }
                assertTrue(accepted, "task '${task.key}' accepts no model type; add it to TASK_MODEL_TYPE")
            }
        }
    }
}
