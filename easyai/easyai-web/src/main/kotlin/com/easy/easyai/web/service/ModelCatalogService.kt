package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelCatalogStore
import com.easy.easyai.api.model.CatalogCapabilities
import com.easy.easyai.api.model.CatalogOptions
import com.easy.easyai.api.model.ModelCatalogEntry
import com.easy.easyai.api.model.ModelPricing
import com.easy.easyai.api.model.StructuredOutputSupport
import com.easy.easyai.common.util.SharedObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Counts of one catalog refresh pass. */
data class CatalogRefreshResult(val inserted: Int, val updated: Int)

/** One parsed gateway page: the mapped entries plus the upstream total group count. */
data class CatalogPage(val total: Int, val entries: List<ModelCatalogEntry>)

/**
 * Transport seam for the Bailian model-marketplace gateway so paging and mapping can be
 * tested without a network round-trip.
 */
interface BailianCatalogGateway {
    /** Fetch one page of the foundation-model catalog; returns the raw gateway JSON envelope. */
    suspend fun fetchPage(pageNo: Int, pageSize: Int): String
}

/**
 * Global model catalog: reads for the add-model prefill and the management page, plus a
 * refresh that pulls the full Bailian model marketplace into `model_catalog`.
 *
 * The store is optional so deployments without the catalog table still wire this bean;
 * reads then come back empty and a refresh fails loudly.
 */
@Service
class ModelCatalogService(
    @param:Autowired(required = false)
    private val catalogStore: ModelCatalogStore? = null,
    private val gateway: BailianCatalogGateway = HttpBailianCatalogGateway(),
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun lookup(modelId: String): ModelCatalogEntry? =
        catalogStore?.getByModelId(modelId)

    suspend fun list(): List<ModelCatalogEntry> =
        catalogStore?.listAll() ?: emptyList()

    /**
     * Pull every catalog page from Bailian and upsert by model id. Rows the upstream retired are
     * kept on purpose — they may back existing user configs, and the catalog only serves prefills.
     */
    suspend fun refreshFromBailian(): CatalogRefreshResult {
        val store = catalogStore
            ?: throw IllegalStateException("Model catalog store is not available in this deployment")
        var pageNo = 1
        var inserted = 0
        var updated = 0
        while (true) {
            val page = parsePage(gateway.fetchPage(pageNo, PAGE_SIZE))
            for (entry in page.entries) {
                if (store.upsert(entry)) inserted++ else updated++
            }
            if (page.entries.isEmpty() || pageNo * PAGE_SIZE >= page.total || pageNo >= MAX_PAGES) {
                if (pageNo >= MAX_PAGES) {
                    logger.warn("Bailian catalog refresh stopped at {} pages (total={})", MAX_PAGES, page.total)
                }
                break
            }
            pageNo++
        }
        logger.info("Model catalog refresh done: {} inserted, {} updated", inserted, updated)
        return CatalogRefreshResult(inserted, updated)
    }

    internal fun parsePage(response: String): CatalogPage {
        val root = objectMapper.readTree(response)
        val inner = root.path("data").path("DataV2").path("data")
        val payload = inner.path("data")
        if (payload.isMissingNode || payload.isNull) {
            // The gateway answers HTTP 200 even on business errors; surface the upstream message
            // (e.g. "请求参数不合法: model") instead of an opaque envelope complaint.
            val upstream = text(inner, "message") ?: text(inner, "codeEnum") ?: "unexpected response envelope"
            throw IllegalStateException("Bailian catalog gateway error: $upstream")
        }
        val total = payload.path("total").asInt(0)
        val groups = payload.path("list")
        val entries = if (!groups.isArray) emptyList() else groups.mapNotNull { group ->
            val items = group.path("items")
            if (!items.isArray || items.isEmpty()) return@mapNotNull null
            val item = items.firstOrNull { text(it, "versionTag") == "MAJOR" } ?: items.first()
            mapItem(item)
        }
        return CatalogPage(total, entries)
    }

    /**
     * One vendor item → catalog row. Bailian's `structured-outputs` flag carries no
     * JSON_SCHEMA/JSON_OBJECT granularity, so a present flag maps to the strongest tier;
     * per-gateway downgrades stay a manual form choice on the model config itself.
     */
    internal fun mapItem(item: JsonNode): ModelCatalogEntry? {
        val modelId = text(item, "model") ?: return null
        val features = stringSet(item.path("features"))
        val requestModality = stringSet(item.path("inferenceMetadata").path("request_modality"))
        val capabilities = stringSet(item.path("capabilities"))
        return ModelCatalogEntry(
            modelId = modelId,
            vendor = VENDOR_BAILIAN,
            displayName = text(item, "name") ?: modelId,
            description = text(item, "shortDescription") ?: text(item, "description"),
            capabilities = CatalogCapabilities(
                vision = "Image" in requestModality,
                structuredOutput =
                if ("structured-outputs" in features) StructuredOutputSupport.JSON_SCHEMA
                else StructuredOutputSupport.NONE,
                supportsToolCalling = "function-calling" in features,
                thinking = "Reasoning" in capabilities
            ),
            defaultOptions = CatalogOptions(
                maxTokens = intOf(item, "maxOutputTokens"),
                maxContextTokens = intOf(item, "contextWindow")
            ),
            pricing = mapPricing(item.path("prices")),
            source = SOURCE_BAILIAN_REFRESH,
            rawPayload = item.toString()
        )
    }

    /** Standard time-band per-million-token prices; batch/cache-creation bands stay in rawPayload. */
    private fun mapPricing(prices: JsonNode): ModelPricing? {
        if (!prices.isArray || prices.isEmpty()) return null
        var input: Double? = null
        var output: Double? = null
        var cached: Double? = null
        for (p in prices) {
            if (text(p, "timeBand")?.takeIf { it != "standard" } != null) continue
            val value = text(p, "price")?.toDoubleOrNull() ?: continue
            when (text(p, "type")) {
                "input_token" -> input = value
                "output_token" -> output = value
                "input_token_cache" -> cached = value
            }
        }
        return if (input == null && output == null && cached == null) null
        else ModelPricing(
            currency = CURRENCY_CNY,
            inputPerMTok = input,
            outputPerMTok = output,
            cachedInputPerMTok = cached
        )
    }

    /** Jackson 3 `asString()` throws on non-text nodes, so scalars are coerced via toString. */
    private fun text(node: JsonNode, field: String): String? {
        val v = node.path(field)
        return when {
            v.isMissingNode || v.isNull -> null
            v.isString -> v.asString()
            v.isNumber -> v.toString()
            else -> null
        }
    }

    private fun intOf(node: JsonNode, field: String): Int? {
        val v = node.path(field)
        return if (v.isNumber) v.intValue() else null
    }

    private fun stringSet(node: JsonNode): Set<String> =
        if (!node.isArray) emptySet() else node.mapNotNull { if (it.isString) it.asString() else null }.toSet()

    companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 100
        const val VENDOR_BAILIAN = "bailian"
        const val SOURCE_BAILIAN_REFRESH = "bailian-refresh"
        const val CURRENCY_CNY = "CNY"
    }
}

/**
 * Real gateway: the Bailian console's model-marketplace endpoint. Verified callable
 * anonymously (no cookie, no API key) — form POST to the console gateway, which proxies
 * the internal `listFoundationModels` zelda API.
 */
class HttpBailianCatalogGateway(
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : BailianCatalogGateway {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override suspend fun fetchPage(pageNo: Int, pageSize: Int): String = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .header("content-type", "application/x-www-form-urlencoded")
            .header("user-agent", USER_AGENT)
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(buildFormBody(pageNo, pageSize), StandardCharsets.UTF_8))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        check(response.statusCode() == 200) { "Bailian catalog gateway returned HTTP ${response.statusCode()}" }
        response.body()
    }

    private fun buildFormBody(pageNo: Int, pageSize: Int): String {
        val input = objectMapper.createObjectNode()
        input.put("pageNo", pageNo)
        input.put("pageSize", pageSize)
        input.put("group", true)
        // queryGroupByModel is only accepted together with a single-model lookup; sending it
        // on the full-catalog pull fails upstream with "请求参数不合法: model".
        input.put("queryPrice", true)
        input.put("queryQpmInfo", true)
        val data = objectMapper.createObjectNode()
        data.set("input", input)
        val cornerstone = objectMapper.createObjectNode()
        cornerstone.put("protocol", "V2")
        cornerstone.put("console", "ONE_CONSOLE")
        cornerstone.put("productCode", "p_efm")
        cornerstone.put("domain", "bailian.console.aliyun.com")
        cornerstone.put("consoleSite", "BAILIAN_ALIYUN")
        cornerstone.put("xsp_lang", "zh-CN")
        data.set("cornerstoneParam", cornerstone)
        val params = objectMapper.createObjectNode()
        params.put("Api", ZELDA_API)
        params.put("V", "1.0")
        params.set("Data", data)
        return "params=" + URLEncoder.encode(params.toString(), StandardCharsets.UTF_8) + "&region=" + REGION
    }

    companion object {
        private const val ZELDA_API = "zeldaHttp.dashscopeModel./zelda/api/v1/modelCenter/listFoundationModels"
        private const val ENDPOINT =
            "https://bailian-cs.console.aliyun.com/data/api.json" +
                "?action=BroadScopeAspnGateway&product=sfm_bailian&api=$ZELDA_API&_v="
        private const val REGION = "cn-beijing"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    }
}
