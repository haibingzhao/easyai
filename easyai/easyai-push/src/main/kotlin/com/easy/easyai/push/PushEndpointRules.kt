package com.easy.easyai.push

import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/**
 * Structural checks for browser-supplied push subscriptions.
 *
 * The endpoint is a URL the server will later POST to, so it is treated as untrusted input:
 * HTTPS only, an exact host from [PushProperties.allowedHosts], default port, and no
 * userinfo / fragment. Keys are checked structurally so a malformed value fails as a 400 at
 * the API boundary instead of exploding inside the encryption step.
 */
internal object PushEndpointRules {

    private const val MAX_ENDPOINT_LENGTH = 2048
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

    fun endpointProblem(endpoint: String, allowedHosts: Collection<String>): String? {
        if (endpoint.isBlank()) return "推送地址不能为空"
        if (endpoint.length > MAX_ENDPOINT_LENGTH) return "推送地址过长"
        // Reject control characters and whitespace before parsing: a capability URL is ASCII.
        if (endpoint.any { it.code < 0x21 || it.code > 0x7e }) return "推送地址包含非法字符"
        val uri = try {
            URI(endpoint)
        } catch (_: URISyntaxException) {
            return "推送地址不是合法 URL"
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return "推送地址必须使用 HTTPS"
        if (uri.userInfo != null) return "推送地址不能包含用户信息"
        if (uri.fragment != null) return "推送地址不能包含片段"
        val host = uri.host ?: return "推送地址缺少主机名"
        if (uri.port != -1 && uri.port != 443) return "推送地址端口必须是 443"
        if (allowedHosts.none { it.trim().equals(host, ignoreCase = true) }) return "不支持的推送服务：$host"
        return null
    }

    fun p256dhProblem(value: String): String? {
        val bytes = decodeBase64Url(value) ?: return "p256dh 不是合法的 base64url"
        if (bytes.size != 65 || bytes[0] != 0x04.toByte()) return "p256dh 必须是 65 字节的未压缩 P-256 公钥"
        return null
    }

    fun authProblem(value: String): String? {
        val bytes = decodeBase64Url(value) ?: return "auth 不是合法的 base64url"
        if (bytes.size != 16) return "auth 必须是 16 字节"
        return null
    }

    private fun decodeBase64Url(value: String): ByteArray? {
        if (value.isEmpty() || value.length > 255 || !BASE64URL.matches(value)) return null
        return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    }
}
