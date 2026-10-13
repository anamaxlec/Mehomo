package dev.memoh.core.model

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Kept in memory for an explicit confirmation, never in a navigation URL. */
class ProviderImport(
    val name: String, val baseUrl: String, val apiKey: String,
    val clientType: String, val template: String? = null,
) {
    val maskedKey: String get() = if (apiKey.length <= 4) "••••" else "••••" + apiKey.takeLast(4)
    override fun toString(): String = "ProviderImport(name=$name, clientType=$clientType, apiKey=[redacted])"
    companion object {
        fun isLink(raw: String): Boolean = runCatching {
            val uri = URI(raw.trim().substringBefore('#').substringBefore('?'))
            uri.path == "/providers/connect" && !uri.host.isNullOrBlank() && uri.scheme?.lowercase() in listOf("https", "http") ||
                uri.scheme == "memoh" && uri.host == "providers" && uri.path == "/connect"
        }.getOrDefault(false)

        fun parseLink(raw: String): ProviderImport {
            require(raw.length <= 32768 && isLink(raw)) { "这不是有效的 Memoh 供应商导入链接" }
            val fragment = runCatching { URI(raw.trim()).rawFragment.orEmpty() }.getOrNull()
                ?: throw IllegalArgumentException("导入链接格式无效，请重新生成链接")
            val encoded = fragment.split('&').firstOrNull { it.substringBefore('=') == "payload" }?.substringAfter('=', "").orEmpty()
            require(encoded.isNotBlank()) { "链接缺少 payload，请重新生成导入链接" }
            val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
                ?: throw IllegalArgumentException("无法解析导入内容，请重新生成链接")
            return parse(decoded)
        }

        fun parse(encoded: String): ProviderImport {
            require(encoded.length <= 32768) { "导入内容过长" }
            val value = runCatching { Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(encoded.trim()), Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: throw IllegalArgumentException("无法解析导入内容，请重新生成链接")
            fun string(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            require(string("kind") == "memoh-provider-import") { "不支持此导入类型" }
            require((value["v"] as? JsonPrimitive)?.intOrNull == 1) { "不支持此导入版本" }
            val base = string("base_url").trimEnd('/')
            val uri = runCatching { URI(base) }.getOrNull()
            require(uri != null && uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "供应商地址必须是有效的 HTTP 或 HTTPS 地址" }
            val key = string("api_key")
            require(key.isNotBlank()) { "导入内容缺少 API key" }
            val type = string("client_type").ifBlank { "openai-completions" }
            require(type in listOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai")) { "此接口类型不支持通过链接导入" }
            return ProviderImport(string("name").ifBlank { uri!!.host }, base, key, type, string("template").takeIf(String::isNotBlank))
        }
    }
}
