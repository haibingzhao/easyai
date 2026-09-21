package com.easy.easyai.autoconfigure.media

import com.easy.easyai.core.media.MediaProviderSettings

/**
 * The single place that structurally validates a [MediaProviderSettings] draft.
 *
 * Two callers share it so validation can never drift between them: the settings service (dry-run
 * before persist) and the resolver (which refuses to serve a corrupt stored row). Structural
 * problems return a human-readable complaint; vendor reachability is checked by the tools at call
 * time, keeping this module free of any HTTP/vendor SDK dependency.
 */
object MediaProviderFactory {

    /**
     * @return null when the configuration is usable, otherwise the human-readable complaint
     */
    @JvmStatic
    fun validate(settings: MediaProviderSettings): String? {
        if (settings.serviceKind !in MediaProviderSettings.ALL_SERVICE_KINDS) {
            return "unknown media service kind '${settings.serviceKind}'"
        }
        val hasApiKey = settings.apiKey.isNotBlank()
        val hasAkSk = settings.accessKeyId.isNotBlank() && settings.accessKeySecret.isNotBlank()
        if (!hasApiKey && !hasAkSk) {
            return "a media provider needs either an API key or an access-key id/secret pair"
        }
        return when (settings.providerType.lowercase()) {
            MediaProviderSettings.PROVIDER_OPENAI -> null
            MediaProviderSettings.PROVIDER_DASHSCOPE -> null
            MediaProviderSettings.PROVIDER_KLING -> null
            else -> "unknown media provider type '${settings.providerType}'"
        }
    }
}
