package com.easy.easyai.web.service

import com.easy.easyai.api.model.AsrCapabilities
import com.easy.easyai.api.model.AsrFeatures
import com.easy.easyai.api.model.AsrAudioSpec
import com.easy.easyai.api.model.AsrParamSpec
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.media.MediaProviderSettings
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.net.URI

/** Which of the two recognition transports a configuration must use. */
enum class AsrTransport { WEBSOCKET, HTTP, NONE }

/**
 * The one runtime verdict about a configured ASR row.
 *
 * [transport] is what the browser routes on and nothing else may decide it: the console holds no
 * copy of this table, so a model can never be "recognized" there while this says otherwise.
 * [parameters] are already validated and defaulted, so the speech layer only has to encode them.
 */
data class AsrRoute(
    val transport: AsrTransport,
    /** `REALTIME_STREAMING` / `REALTIME_MESSAGE` / `HTTP_SEGMENT` / `NONE`; labels text, never routing. */
    val capability: String,
    /** Where [capability] came from: the catalog block or the built-in fallback table. */
    val capabilitySource: String,
    val modelId: String?,
    val reason: String?,
    val hint: String?,
    /** `run-task` parameters resolved from the row's `media_options` plus the capability defaults. */
    val parameters: Map<String, Any?> = emptyMap()
) {
    val usable: Boolean get() = transport != AsrTransport.NONE
}

/**
 * Decides how a configured ASR row is reached, using the model catalog's `asr` block when the row
 * exists there and the built-in table in [AsrBuiltinCatalog] otherwise.
 *
 * The order matters and mirrors the design: configuration completeness, then row type, then
 * parameter validity, then whitelist + endpoint trust, then the real-time family exclusions, then
 * the legacy HTTP fallback. Every step that can fail returns a specific [AsrRoute.reason] instead
 * of a generic "unavailable", because the console has to tell "not configured" from "configured
 * but this model only does files".
 *
 * Nothing here touches the network: no ticket, no upstream connection, no charge.
 */
@Service
class AsrCapabilityResolver(
    @param:Autowired(required = false)
    private val catalog: ModelCatalogService? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun resolve(config: ModelProviderConfig?): AsrRoute {
        if (config == null) return off(REASON_NOT_CONFIGURED)
        val modelId = config.modelId.trim()
        val name = modelId.takeIf { it.isNotBlank() }
        if (name == null || config.baseUrl.isNullOrBlank() || config.apiKey.isNullOrBlank()) {
            return off(REASON_INCOMPLETE_CONFIG, name)
        }
        if (config.modelType != ModelType.ASR) return off(REASON_UNSUPPORTED_MODEL, name)
        if (!config.enabled) return off(REASON_DISABLED, name)

        val (block, source) = lookup(modelId)
        val resolved = if (block == null) {
            ResolvedParameters(emptyMap<String, Any?>(), null)
        } else {
            resolveParameters(block, config.mediaOptions.orEmpty()) ?: run {
                logger.warn("ASR row '{}' has media_options the model does not accept; refusing to call upstream", config.id)
                return off(REASON_INVALID_MODEL_OPTIONS, name, source)
            }
        }
        val params = resolved.values
        return when (block?.channel ?: builtinChannel(modelId)) {
            AsrCapabilities.CHANNEL_WEBSOCKET ->
                if (isTrustedEndpoint(config.baseUrl.orEmpty())) {
                    AsrRoute(
                        transport = AsrTransport.WEBSOCKET,
                        capability = realtimeCapability(modelId),
                        capabilitySource = source,
                        modelId = modelId,
                        reason = null,
                        hint = null,
                        parameters = params
                    )
                } else {
                    // The model id is right but the host is not a workspace we can send the key to.
                    off(REASON_UNVERIFIED_ENDPOINT, name, source)
                }

            AsrCapabilities.CHANNEL_HTTP ->
                if (!supportsCompatibleTranscription(config.protocol)) {
                    off(REASON_UNSUPPORTED_PROVIDER, name, source)
                } else if (modelId in MULTIPART_UNPROBED_MODELS) {
                    // Documented entry for these two is URL/Base64 one-shot; the compatible-mode
                    // multipart path this backend uses is unverified until the P0 probe runs.
                    off(REASON_UNVERIFIED_ENDPOINT, name, source)
                } else {
                    AsrRoute(
                        transport = AsrTransport.HTTP,
                        capability = CAPABILITY_HTTP_SEGMENT,
                        capabilitySource = source,
                        modelId = modelId,
                        reason = null,
                        hint = null
                    )
                }

            else ->
                if (modelId.contains("filetrans")) {
                    off(REASON_FILE_ONLY_MODEL, name, source, HINT_SELECT_REALTIME_MODEL)
                } else {
                    off(REASON_UNSUPPORTED_REALTIME_VARIANT, name, source, HINT_SELECT_REALTIME_MODEL)
                }
        }
    }

    /**
     * The catalog block for [modelId], or the built-in one.
     *
     * A catalog row without an `asr` block (every TTS row, and every ASR row seeded before this
     * block existed) falls through to the built-in table rather than being treated as "no
     * capability": the fallback is what keeps an old deployment usable.
     */
    private suspend fun lookup(modelId: String): Pair<AsrCapabilities?, String> {
        val fromCatalog = catalog?.lookup(modelId)?.speechCapabilities?.asr
        if (fromCatalog != null) return fromCatalog to SOURCE_CATALOG
        return AsrBuiltinCatalog.find(modelId) to SOURCE_BUILTIN
    }

    /** Channel for models the built-in table knows; unknown ids fall back to the legacy HTTP path. */
    private fun builtinChannel(modelId: String): String =
        AsrBuiltinCatalog.find(modelId)?.channel ?: legacyChannel(modelId)

    /**
     * Unknown ids only reach this after the catalog and the built-in table both declined.
     *
     * A legacy OpenAI-compatible transcription row stays on HTTP (that is what it does today), but
     * anything that looks like a real-time model is refused: guessing a protocol from a name would
     * send audio to an endpoint that never answers it.
     */
    private fun legacyChannel(modelId: String): String = when {
        modelId.contains("filetrans") -> AsrCapabilities.CHANNEL_NONE
        modelId.contains("realtime") -> AsrCapabilities.CHANNEL_NONE
        modelId.endsWith("-streaming") || modelId.endsWith("-message") -> AsrCapabilities.CHANNEL_NONE
        else -> AsrCapabilities.CHANNEL_HTTP
    }

    private fun realtimeCapability(modelId: String): String =
        if (modelId.endsWith("-message")) CAPABILITY_REALTIME_MESSAGE else CAPABILITY_REALTIME_STREAMING

    private fun supportsCompatibleTranscription(protocol: Protocol): Boolean =
        protocol == Protocol.OPENAI || protocol == Protocol.DASHSCOPE

    private fun off(
        reason: String,
        modelId: String? = null,
        source: String = SOURCE_BUILTIN,
        hint: String? = null
    ): AsrRoute = AsrRoute(
        transport = AsrTransport.NONE,
        capability = CAPABILITY_NONE,
        capabilitySource = source,
        modelId = modelId,
        reason = reason,
        hint = hint
    )

    /**
     * A base URL is only trusted when its host is a Bailian workspace endpoint.
     *
     * Border matching is on the parsed host, not on the string: `evil-maas.aliyuncs.com.attacker.tld`
     * and `x@maas.aliyuncs.com` both contain the trusted name, and neither may receive the key.
     * Userinfo, query and fragment are rejected outright because they are how such a URL is
     * disguised.
     */
    private fun isTrustedEndpoint(baseUrl: String): Boolean {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
        if (uri.userInfo != null || uri.fragment != null || uri.query != null) return false
        if (uri.port != -1 && uri.port !in TRUSTED_PORTS) return false
        val host = uri.host?.lowercase() ?: return false
        return isLoopback(host) || TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

    /**
     * Validate the row's options against the block and fill in defaults.
     *
     * Returns null when the row sets something the model cannot take: an unknown key, an enum value
     * outside the list, a number outside its range, or JSON that is not an object. Refusing here
     * keeps a paid request from being sent with parameters the service would reject or ignore.
     *
     * A [AsrParamSpec.locked] key is the exception — its default always wins and a stored value only
     * produces a warning, because the product, not the row, fixes it.
     */
    internal fun resolveParameters(block: AsrCapabilities, options: String): ResolvedParameters? {
        val node = parseOptions(options) ?: return null
        val values = LinkedHashMap<String, Any?>()
        for ((name, spec) in block.params) {
            if (!AsrParamSpec.SUPPORTED_TYPES.contains(spec.type)) {
                logger.warn("ASR capability declares unsupported parameter type '{}' for '{}'", spec.type, name)
                return null
            }
            val stored = node.get(name)
            if (spec.locked) {
                if (stored != null && !stored.isNull) {
                    logger.warn("ASR parameter '{}' is fixed by the product; ignoring the row's value", name)
                }
                spec.default?.let { values[name] = it }
                continue
            }
            if (stored == null || stored.isNull) {
                spec.default?.let { values[name] = it }
                continue
            }
            val coerced = coerce(name, spec, stored) ?: return null
            values[name] = coerced
        }
        // Unknown keys are only illegal when a capability block is present: it declares the whole
        // surface this project sends, so a key outside it would silently do nothing.
        for (field in node.propertyNames()) {
            if (!block.params.containsKey(field)) {
                logger.warn("ASR model does not accept parameter '{}'", field)
                return null
            }
        }
        return ResolvedParameters(values, block)
    }

    private fun parseOptions(options: String): JsonNode? {
        if (options.isBlank()) return SharedObjectMapper.instance.createObjectNode()
        val node = runCatching { SharedObjectMapper.instance.readTree(options) }.getOrNull() ?: return null
        return node.takeIf { it.isObject }
    }

    private fun coerce(name: String, spec: AsrParamSpec, value: JsonNode): Any? = when (spec.type) {
        AsrParamSpec.TYPE_ENUM ->
            value.takeIf { it.isString }?.asString()?.takeIf { it in spec.options }
        AsrParamSpec.TYPE_INT -> {
            val min = spec.min
            val max = spec.max
            if (!value.isNumber) null
            else value.intValue().takeIf { min == null || it >= min }
                ?.takeIf { max == null || it <= max }
        }

        AsrParamSpec.TYPE_BOOL -> value.takeIf { it.isBoolean }?.booleanValue()
        AsrParamSpec.TYPE_STRING -> value.takeIf { it.isString }?.asString()
        AsrParamSpec.TYPE_STRING_ARRAY -> {
            val maxItems = spec.maxItems
            if (!value.isArray || (maxItems != null && value.size() > maxItems)) null
            else value.mapNotNull { if (it.isString) it.asString() else null }
                .takeIf { it.size == value.size() }
        }

        AsrParamSpec.TYPE_MAP -> value.takeIf { it.isObject }?.let { obj ->
            obj.propertyNames().associateWith { key -> nodeValue(obj.get(key)) }
        }

        else -> null
    }.also { accepted ->
        if (accepted == null) logger.warn("ASR parameter '{}' has an unusable value", name)
    }

    private fun nodeValue(node: JsonNode): Any? = when {
        node.isString -> node.asString()
        node.isNumber -> node.intValue()
        node.isBoolean -> node.booleanValue()
        else -> null
    }

    internal data class ResolvedParameters(
        val values: Map<String, Any?>,
        val block: AsrCapabilities?
    )

    companion object {
        const val CAPABILITY_REALTIME_STREAMING = "REALTIME_STREAMING"
        const val CAPABILITY_REALTIME_MESSAGE = "REALTIME_MESSAGE"
        const val CAPABILITY_HTTP_SEGMENT = "HTTP_SEGMENT"
        const val CAPABILITY_NONE = "NONE"

        const val SOURCE_CATALOG = "CATALOG"
        const val SOURCE_BUILTIN = "BUILTIN"

        const val REASON_NOT_CONFIGURED = "NOT_CONFIGURED"
        const val REASON_DISABLED = "DISABLED"
        const val REASON_INCOMPLETE_CONFIG = "INCOMPLETE_CONFIG"
        const val REASON_INVALID_MODEL_OPTIONS = "INVALID_MODEL_OPTIONS"
        const val REASON_UNVERIFIED_ENDPOINT = "UNVERIFIED_ENDPOINT"
        const val REASON_UNSUPPORTED_PROVIDER = "UNSUPPORTED_PROVIDER"
        const val REASON_UNSUPPORTED_MODEL = "UNSUPPORTED_MODEL"
        const val REASON_FILE_ONLY_MODEL = "FILE_ONLY_MODEL"
        const val REASON_UNSUPPORTED_REALTIME_VARIANT = "UNSUPPORTED_REALTIME_VARIANT"

        const val HINT_SELECT_REALTIME_MODEL = "SELECT_REALTIME_MODEL"

        /**
         * Models whose documented HTTP entry is URL/Base64 one-shot while this backend speaks the
         * OpenAI-compatible multipart route. They are reported as [REASON_UNVERIFIED_ENDPOINT]
         * until the P0 multipart probe passes against a real account; keeping the gate here means
         * the switch is one constant, not a scattering of call sites.
         */
        val MULTIPART_UNPROBED_MODELS = setOf(
            "qwen-audio-3.0-asr-flash",
            "qwen-audio-3.1-asr-flash"
        )

        private val TRUSTED_HOSTS = listOf(
            "maas.aliyuncs.com",
            "dashscope.aliyuncs.com",
            "dashscope-intl.aliyuncs.com"
        )
        private val TRUSTED_PORTS = setOf(80, 443)

        /** The ASR task points at a model_provider_config row; the speech module reads MediaProviderSettings. */
        @JvmStatic
        fun asSettings(config: ModelProviderConfig): MediaProviderSettings = MediaProviderSettings(
            id = config.id,
            displayName = config.name,
            enabled = true,
            serviceKind = MediaProviderSettings.SERVICE_KIND_ASR,
            providerType = config.protocol.name.lowercase(),
            baseUrl = config.baseUrl.orEmpty(),
            apiKey = config.apiKey.orEmpty(),
            defaultModel = config.modelId,
            options = config.mediaOptions.orEmpty(),
            timeoutSeconds = config.timeoutSeconds,
            isDefault = true
        )
    }
}

/**
 * The fallback capability table, used when the catalog has no `asr` block for a model.
 *
 * It exists so a deployment whose `model_catalog` was seeded before this feature — or whose row a
 * user created by hand — still routes correctly. Values come from the vendor's parameter pages,
 * except `default`, which is this project's choice for short dictation (see each note).
 */
internal object AsrBuiltinCatalog {

    fun find(modelId: String): AsrCapabilities? = MODELS[modelId]

    private val SILENCE = AsrParamSpec(
        type = AsrParamSpec.TYPE_INT,
        min = 200,
        max = 6000,
        unit = "ms",
        default = 400,
        vendorDefault = 800,
        note = "Short dictation closes a sentence sooner than the vendor default"
    )

    private val VAD = AsrParamSpec(
        type = AsrParamSpec.TYPE_ENUM,
        options = listOf("near_meeting_16k", "far_field_meeting_16k"),
        default = "near_meeting_16k",
        vendorDefault = "far_field_meeting_16k",
        note = "Chat dictation is near-field"
    )

    private val KEEP_DIALECT = AsrParamSpec(
        type = AsrParamSpec.TYPE_BOOL,
        default = false,
        finalOnly = true
    )

    private val INTERMEDIATE = AsrParamSpec(
        type = AsrParamSpec.TYPE_BOOL,
        default = true,
        locked = true
    )

    private val DISFLUENCY = AsrParamSpec(type = AsrParamSpec.TYPE_BOOL, default = false)

    private val LANGUAGE_HINTS = AsrParamSpec(
        type = AsrParamSpec.TYPE_STRING_ARRAY,
        maxItems = 4,
        finalOnly = true
    )

    private val VOCABULARY = AsrParamSpec(type = AsrParamSpec.TYPE_MAP, maxSuper = 50, maxMerged = 2000)

    private val VOCABULARY_ID = AsrParamSpec(type = AsrParamSpec.TYPE_STRING)

    private val AUDIO_16K = AsrAudioSpec(formats = listOf("pcm"), sampleRates = listOf(16000))

    /** Recognized but not usable for microphone input: the documented entry is a file URL. */
    private val HTTP_ONLY = AsrCapabilities(channel = AsrCapabilities.CHANNEL_HTTP, audio = AUDIO_16K)

    private fun streaming31(): AsrCapabilities = AsrCapabilities(
        channel = AsrCapabilities.CHANNEL_WEBSOCKET,
        docsUrl = STREAMING_DOCS,
        audio = AUDIO_16K,
        params = linkedMapOf(
            "vad_model" to VAD,
            "max_sentence_silence" to SILENCE,
            "keep_dialect" to KEEP_DIALECT,
            "language_hints" to LANGUAGE_HINTS,
            "vocabulary" to VOCABULARY,
            "vocabulary_id" to VOCABULARY_ID
        ),
        features = AsrFeatures(context = "initial")
    )

    private val MODELS: Map<String, AsrCapabilities> = mapOf(
        "qwen-audio-3.0-asr-flash-streaming" to AsrCapabilities(
            channel = AsrCapabilities.CHANNEL_WEBSOCKET,
            docsUrl = STREAMING_DOCS,
            audio = AUDIO_16K,
            // `vad_model` and `keep_dialect` are documented for 3.1 only; sending them to 3.0
            // would be a 4xx or a silent no-op, so the block does not declare them at all.
            params = linkedMapOf(
                "max_sentence_silence" to SILENCE,
                "language_hints" to LANGUAGE_HINTS,
                "vocabulary" to VOCABULARY,
                "vocabulary_id" to VOCABULARY_ID
            ),
            features = AsrFeatures(context = "initial")
        ),
        "qwen-audio-3.1-asr-flash-streaming" to streaming31(),
        "qwen-audio-3.1-asr-flash-message" to streaming31().copy(
            params = linkedMapOf(
                "intermediate_result_enabled" to INTERMEDIATE,
                "disfluency_removal_enabled" to DISFLUENCY,
                "vad_model" to VAD,
                "max_sentence_silence" to SILENCE,
                "keep_dialect" to KEEP_DIALECT,
                "language_hints" to LANGUAGE_HINTS,
                "vocabulary" to VOCABULARY,
                "vocabulary_id" to VOCABULARY_ID
            ),
            features = AsrFeatures(context = "initial", continueTask = true)
        ),
        "qwen-audio-3.0-asr-flash" to HTTP_ONLY,
        "qwen-audio-3.1-asr-flash" to HTTP_ONLY,
        "qwen-audio-3.1-asr-flash-filetrans" to AsrCapabilities(channel = AsrCapabilities.CHANNEL_NONE)
    )

    private const val STREAMING_DOCS =
        "https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-audio-asr-streaming-websocket-api"
}
