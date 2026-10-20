package dev.memoh.core.markdown

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.UUID

/** An artifact owns an isolated web origin; account credentials never enter its scripts. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HtmlFilePreview(
    html: String,
    documentPath: String,
    modifier: Modifier = Modifier,
    loadResource: (suspend (String) -> ByteArray)? = LocalMarkdownImageLoader.current,
) {
    val uriHandler = LocalUriHandler.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val background = MaterialTheme.colorScheme.surface
    // A new origin for each viewer prevents storage from leaking across artifacts or accounts.
    val host = remember(documentPath, loadResource) { "preview-${UUID.randomUUID()}.invalid" }
    val baseUrl = "https://$host/${Uri.encode(documentPath.trimStart('/'), "/")}"
    var retry by remember(html, documentPath) { mutableIntStateOf(0) }
    var loading by remember(html, documentPath, retry) { mutableStateOf(true) }
    var error by remember(html, documentPath, retry) { mutableStateOf<String?>(null) }
    Column(modifier) {
        error?.let { message ->
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(baseUrl, html, dark, retry) {
                AndroidView(factory = { context ->
                    WebView(ContextThemeWrapper(context, if (dark) android.R.style.Theme_Material_NoActionBar
                        else android.R.style.Theme_Material_Light_NoActionBar)).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(background.toArgb())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, progress: Int) { loading = progress < 100 }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.url.scheme == "https" && request.url.host == host) return false
                                if (request.isForMainFrame && request.hasGesture() && request.url.scheme in listOf("https", "http"))
                                    runCatching { uriHandler.openUri(request.url.toString()) }
                                return true
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.url.host != host) return null
                                val path = artifactResourcePath(documentPath, request.url.path.orEmpty())
                                if (path == null || request.url.scheme != "https" || request.method != "GET" || loadResource == null)
                                    return fileResponse(403, "Forbidden")
                                return try {
                                    val bytes = runBlocking { loadResource(path) }
                                    WebResourceResponse(artifactMime(path), "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), bytes.inputStream())
                                } catch (_: Exception) {
                                    view.post { error = "部分页面资源未能加载，请重试。" }
                                    fileResponse(404, "Not found")
                                }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (request.isForMainFrame) { error = "页面未能打开，请重试。"; loading = false }
                            }
                        }
                        loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
                    }
                }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
            }
            androidx.compose.animation.AnimatedVisibility(loading, Modifier.align(Alignment.TopCenter),
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/** File previews may read neighboring resources, never files above their own directory. */
private fun artifactResourcePath(document: String, requested: String): String? {
    fun normalize(path: String): String? {
        if (!path.startsWith('/') || '\\' in path || '\u0000' in path) return null
        val parts = mutableListOf<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
        return "/" + parts.joinToString("/")
    }
    val source = normalize(document) ?: return null
    val target = normalize(requested) ?: return null
    val directory = source.substringBeforeLast('/')
    return target.takeIf { it == source || it.startsWith("$directory/") }
}

private fun artifactMime(path: String): String = when (val extension = path.substringAfterLast('.').lowercase()) {
    "js", "mjs" -> "text/javascript"
    "css" -> "text/css"
    "html", "htm" -> "text/html"
    "json", "map" -> "application/json"
    "wasm" -> "application/wasm"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private fun fileResponse(code: Int, message: String) =
    WebResourceResponse("text/plain", "UTF-8", code, message, emptyMap(), ByteArrayInputStream(ByteArray(0)))
