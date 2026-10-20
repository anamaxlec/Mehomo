package dev.memoh.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.RequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One workspace preview, with its own website cookies and navigation origin. */
class BrowserPreviewClient internal constructor(
    private val initialUrl: String,
    private val cloudLaunch: Boolean,
    private val request: suspend (HttpUrl, String, Map<String, String>, CookieJar, Boolean, RequestBody?) -> Response,
) {
    @Volatile private var originUrl = initialUrl
    @Volatile private var nativeAuthAllowed = true
    private val websiteCookies = MemoryCookieJar()

    fun allows(location: String): Boolean = isBrowserPreviewUrl(originUrl, location)

    suspend fun load(location: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Response {
        require(method in listOf("GET", "HEAD")) { "不支持这种预览请求" }
        require(allows(location)) { "预览地址超出当前工作区" }
        var url = location.toHttpUrl()
        // Only the authenticated Cloud launch document may establish a new preview origin.
        // Subsequent links and assets remain within that origin.
        val launching = cloudLaunch && url == initialUrl.toHttpUrl()
        var accountOrigin = nativeAuthAllowed && isBrowserPreviewUrl(initialUrl, location)
        var requestMethod = method
        var body: RequestBody? = null
        var bootstrapSent = false
        repeat(9) { redirects ->
            val requestHeaders = if (body != null) headers + ("Origin" to initialUrl.toHttpUrl().resolve("/").toString().trimEnd('/')) else headers
            val response = request(url, requestMethod, requestHeaders, websiteCookies, accountOrigin, body)
            if (response.code !in listOf(301, 302, 303, 307, 308)) {
                // Cloud now returns an auto-submitting HTML form instead of an HTTP redirect.
                // Complete its one-time exchange natively; no login cookies enter the WebView.
                val bootstrap = try {
                    if (launching && accountOrigin && !bootstrapSent && response.isSuccessful)
                        withContext(Dispatchers.IO) { browserBootstrap(response, url) } else null
                } catch (e: Exception) { response.close(); throw e }
                if (bootstrap != null) {
                    response.close()
                    if (redirects == 8) throw UserFacingException("预览页面重复跳转，请检查网页配置")
                    requirePreviewTarget(url, bootstrap.first, launching = true)
                    url = bootstrap.first
                    body = bootstrap.second
                    requestMethod = "POST"
                    accountOrigin = false
                    bootstrapSent = true
                    return@repeat
                }
                websiteCookies.saveFromResponse(url, Cookie.parseAll(url, response.headers))
                if (launching) {
                    nativeAuthAllowed = accountOrigin
                    originUrl = url.toString()
                }
                return response
            }
            websiteCookies.saveFromResponse(url, Cookie.parseAll(url, response.headers))
            val target = response.header("Location")?.let(url::resolve)
            val redirectCode = response.code
            response.close()
            requirePreviewTarget(url, target, launching && !bootstrapSent)
            if (redirects == 8) throw UserFacingException("预览页面重复跳转，请检查网页配置")
            // Account auth is removed as soon as the launch leaves the original origin,
            // even if a later redirect attempts to return to it.
            accountOrigin = accountOrigin && isBrowserPreviewUrl(initialUrl, target.toString())
            if (redirectCode == 303 && requestMethod != "HEAD" || redirectCode in listOf(301, 302) && requestMethod == "POST") {
                requestMethod = "GET"
                body = null
            }
            url = requireNotNull(target)
        }
        error("unreachable")
    }

    private fun requirePreviewTarget(from: HttpUrl, target: HttpUrl?, launching: Boolean) {
        if (target == null || target.username.isNotEmpty() || target.password.isNotEmpty() ||
            (!isBrowserPreviewUrl(from.toString(), target.toString()) && (!launching || target.scheme != initialUrl.toHttpUrl().scheme)))
            throw UserFacingException("预览页面跳转到工作区之外，已停止加载")
    }
}

/** Only called for the authenticated Cloud launch response, never a workspace website. */
private fun browserBootstrap(response: Response, source: HttpUrl): Pair<HttpUrl, FormBody>? {
    if (response.body.contentType()?.let { "${it.type}/${it.subtype}" } != "text/html") return null
    val html = response.peekBody(256 * 1024).string()
    val forms = Regex("<form\\b([^>]*)>([\\s\\S]*?)</form\\s*>", RegexOption.IGNORE_CASE)
    for (form in forms.findAll(html)) {
        val attributes = htmlAttributes(form.groupValues[1])
        if (!attributes["method"].equals("post", true)) continue
        val action = attributes["action"]?.let(source::resolve) ?: continue
        if (action.encodedPath != "/_memoh/bootstrap") continue
        val fields = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(form.groupValues[2])
            .map { htmlAttributes(it.groupValues[1]) }.filter { it["type"].equals("hidden", true) }.toList()
        val code = fields.singleOrNull { it["name"] == "code" }?.get("value")
            ?.takeIf { it.isNotBlank() && it.length <= 16_384 }
            ?: throw UserFacingException("预览启动参数无效，请重新打开预览")
        return action to FormBody.Builder().add("code", code).build()
    }
    return null
}

private fun htmlAttributes(source: String): Map<String, String> =
    Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""").findAll(source).associate { match ->
        match.groupValues[1].lowercase() to htmlAttributeValue(match.groups[2]?.value ?: match.groups[3]?.value ?: match.groupValues[4])
    }

private fun htmlAttributeValue(value: String): String = Regex("&(#x[0-9a-f]+|#[0-9]+|amp|quot|apos|lt|gt);", RegexOption.IGNORE_CASE)
    .replace(value) { match ->
        when (val entity = match.groupValues[1].lowercase()) {
            "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"
            else -> {
                val code = if (entity.startsWith("#x")) entity.substring(2).toIntOrNull(16) else entity.substring(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
            }
        }
    }
