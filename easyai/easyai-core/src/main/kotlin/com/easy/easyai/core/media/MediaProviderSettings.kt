package com.easy.easyai.core.media

/**
 * One user's media-generation provider credential for a single service kind, persisted in the
 * `media_provider_settings` table and editable from the frontend Settings page without a restart.
 *
 * The database is the only source of this configuration; there are no media properties. This is
 * intentionally a *separate* concept from `model_provider_config` (which feeds the ReAct ChatModel
 * only) — generation models are reached through tools, not the chat loop.
 *
 * [enabled] `false` is meaningful: an explicit disabled row shadows the shared `system` row.
 * Credentials live server-side only — a read endpoint never returns [apiKey]/[accessKeySecret]
 * verbatim, and a blank secret on save keeps the stored one.
 */
data class MediaProviderSettings(
    val enabled: Boolean = false,
    /** One of [SERVICE_KIND_SPEECH] / [SERVICE_KIND_IMAGE] / [SERVICE_KIND_VIDEO]. */
    val serviceKind: String = SERVICE_KIND_IMAGE,
    /** Vendor adapter selector, e.g. `openai`, `dashscope`, `kling`. */
    val providerType: String = PROVIDER_OPENAI,
    val baseUrl: String = "",
    val region: String = "",
    /** Single-secret auth (OpenAI / Anthropic style). */
    val apiKey: String = "",
    /** AK/SK pair auth (Aliyun / iFlytek / Azure style). */
    val accessKeyId: String = "",
    val accessKeySecret: String = "",
    val defaultModel: String = "",
    /** Provider-specific extra parameters as a JSON object string (size / voice / etc.). */
    val options: String = "",
    val timeoutSeconds: Long = 600L
) {
    companion object {
        const val SERVICE_KIND_SPEECH = "speech"
        const val SERVICE_KIND_IMAGE = "image"
        const val SERVICE_KIND_VIDEO = "video"

        val ALL_SERVICE_KINDS = listOf(SERVICE_KIND_SPEECH, SERVICE_KIND_IMAGE, SERVICE_KIND_VIDEO)

        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_DASHSCOPE = "dashscope"
        const val PROVIDER_KLING = "kling"
    }
}
