package com.easy.easyai.autoconfigure.media

import com.easy.easyai.core.media.MediaProviderSettings

/**
 * The single place that structurally validates a generation model entry
 * ([MediaProviderSettings] projected from a `model_provider_config` row).
 *
 * Two callers share it so validation can never drift between them: the model-config write path
 * (dry-run before persist) and the resolver (which refuses to serve a corrupt stored row).
 * Structural problems return a human-readable complaint; vendor reachability is checked by the
 * tools at call time, keeping this module free of any HTTP/vendor SDK dependency.
 */
object MediaProviderFactory {

    /** Protocols the media tools can drive; everything else is chat-only. */
    private val SUPPORTED_PROVIDERS = setOf(
        MediaProviderSettings.PROVIDER_OPENAI,
        MediaProviderSettings.PROVIDER_DASHSCOPE,
        MediaProviderSettings.PROVIDER_KLING
    )

    /**
     * @return null when the configuration is usable, otherwise the human-readable complaint
     */
    @JvmStatic
    fun validate(settings: MediaProviderSettings): String? {
        if (settings.serviceKind !in MediaProviderSettings.ALL_SERVICE_KINDS) {
            return "unknown media service kind '${settings.serviceKind}'"
        }
        if (settings.apiKey.isBlank()) {
            return "a media model needs an api key (on the entry itself or its group)"
        }
        if (settings.providerType.lowercase() !in SUPPORTED_PROVIDERS) {
            return "unsupported media protocol '${settings.providerType}'"
        }
        if (settings.baseUrl.isBlank()) {
            return "a media model needs a base url — media endpoints are not inferred from the protocol"
        }
        if (settings.defaultModel.isBlank()) {
            return "a media model needs a model id"
        }
        return null
    }
}
