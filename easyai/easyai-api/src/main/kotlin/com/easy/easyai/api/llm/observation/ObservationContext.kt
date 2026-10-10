package com.easy.easyai.api.llm.observation

import com.easy.easyai.api.llm.ChatResponse
import com.easy.easyai.api.llm.Prompt
import io.micrometer.observation.Observation

/**
 * easyai's own chat-model observation context, replacing spring-ai's
 * `ChatModelObservationContext`. A mapper (wrapped by [ObservationChatModel]) starts an
 * [Observation] with this context around each call/stream; the observability module's
 * `ChatModelObservationFilter` reads [request] and [response] to enrich the span with
 * OpenTelemetry GenAI semantic-convention key-values.
 */
class EasyAiChatModelObservationContext(
    val request: Prompt
) : Observation.Context() {

    /**
     * The response observed for this call. For a blocking [ObservationChatModel.call] it is the
     * aggregate; for a stream it is the last chunk seen at stop time (providers carry the final
     * usage on the terminal chunk, which is what the enrichment reads).
     */
    var response: ChatResponse? = null
}
