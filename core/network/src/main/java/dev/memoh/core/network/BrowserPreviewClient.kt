package dev.memoh.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

/** One workspace preview, with its own website cookies and navigation origin. */
class BrowserPreviewClient internal constructor(
    private val initialUrl: String,
    private val cloudLaunch: Boolean,
    private val request: suspend (HttpUrl, String, Map<String, String>, CookieJar, Boolean) -> Response,
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
        repeat(9) { redirects ->
            val response = request(url, method, headers, websiteCookies, accountOrigin)
            // The launch may issue a delegated website cookie. Keep response cookies,
            // never copy the account's existing login cookie jar into this preview.
            websiteCookies.saveFromResponse(url, Cookie.parseAll(url, response.headers))
            if (response.code !in listOf(301, 302, 303, 307, 308)) {
                if (launching) {
                    nativeAuthAllowed = accountOrigin
                    originUrl = url.toString()
                }
                return response
            }
            val target = response.header("Location")?.let(url::resolve)
            response.close()
            if (target == null || target.username.isNotEmpty() || target.password.isNotEmpty() ||
                (!isBrowserPreviewUrl(url.toString(), target.toString()) && (!launching || target.scheme != initialUrl.toHttpUrl().scheme)))
                throw UserFacingException("预览页面跳转到工作区之外，已停止加载")
            if (redirects == 8) throw UserFacingException("预览页面重复跳转，请检查网页配置")
            // Account auth is removed as soon as the launch leaves the original origin,
            // even if a later redirect attempts to return to it.
            accountOrigin = accountOrigin && isBrowserPreviewUrl(initialUrl, target.toString())
            url = target
        }
        error("unreachable")
    }
}
