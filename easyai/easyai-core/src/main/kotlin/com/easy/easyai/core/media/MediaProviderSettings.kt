package com.easy.easyai.core.media

/**
 * One media-generation model entry, projected from a `model_provider_config` row whose
 * `model_type` names a [serviceKind]. Generation models live in the same table as chat models
 * and share its group credential mechanism; auth is apiKey-only, exactly like chat providers
 * (the retired `media_provider_settings` AK/SK path is gone).
 *
 * Credentials live server-side only — read endpoints never return [apiKey] verbatim, and a blank
 * key on save keeps the stored one.
 */
data class MediaProviderSettings(
    val id: String = "",
    /** Human-readable entry label (the config row's `name`). */
    val displayName: String = "",
    val enabled: Boolean = false,
    /** One of [SERVICE_KIND_SPEECH] / [SERVICE_KIND_IMAGE] / [SERVICE_KIND_VIDEO] / [SERVICE_KIND_MUSIC] / [SERVICE_KIND_ASR]. */
    val serviceKind: String = SERVICE_KIND_IMAGE,
    /** Protocol adapter selector, the lowercased protocol name: `openai`, `dashscope`, `kling`. */
    val providerType: String = PROVIDER_OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    /** The generation model id (config row's `modelId`); tools match their `model` argument against it. */
    val defaultModel: String = "",
    /** Provider-specific extra parameters as a JSON object string (size / voice / etc.). */
    val options: String = "",
    val timeoutSeconds: Long = 600L,
    /** True for at most one entry per owner + kind: the fallback when a tool names no model. */
    val isDefault: Boolean = false
) {
    companion object {
        const val SERVICE_KIND_SPEECH = "speech"
        const val SERVICE_KIND_IMAGE = "image"
        const val SERVICE_KIND_VIDEO = "video"
        const val SERVICE_KIND_MUSIC = "music"
        const val SERVICE_KIND_ASR = "asr"

        val ALL_SERVICE_KINDS = listOf(
            SERVICE_KIND_SPEECH, SERVICE_KIND_IMAGE, SERVICE_KIND_VIDEO,
            SERVICE_KIND_MUSIC, SERVICE_KIND_ASR
        )

        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_DASHSCOPE = "dashscope"
        const val PROVIDER_KLING = "kling"
    }
}
