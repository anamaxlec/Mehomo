package dev.memoh.core.markdown

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject

enum class RichFormat(val label: String, val extension: String) { Math("数学公式", "tex"), Mermaid("Mermaid", "mmd"), Svg("SVG", "svg"), Paragraph("", "txt") }
private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"

@Composable
fun RichContent(format: RichFormat, source: String, modifier: Modifier = Modifier, expressions: Map<String, MathExpression> = emptyMap(), onLinkClick: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    var fullScreen by remember(source) { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (format == RichFormat.Svg) "image/svg+xml" else "text/plain")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(source.toByteArray()) } }.onFailure { exportError = "导出失败" }
    }
    Column(modifier.fillMaxWidth()) {
        if (format != RichFormat.Paragraph) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(format.label, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ fullScreen = true }) { Icon(Icons.Filled.OpenInFull, "全屏预览") }
            IconButton({ exporter.launch("Mehomo.${format.extension}") }) { Icon(Icons.Filled.SaveAlt, "导出${format.label}") }
        }
        RichWebView(format, source, expressions, Modifier.fillMaxWidth(), onLinkClick = onLinkClick)
        exportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (fullScreen) Dialog({ fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                Row { Text(format.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); IconButton({ fullScreen = false }) { Icon(Icons.Filled.Close, "关闭预览") } }
                RichWebView(format, source, expressions, Modifier.fillMaxSize(), expanded = true, onLinkClick = onLinkClick)
            }
        }
    }
}

private class RenderBridge(val height: (Int) -> Unit, val failed: () -> Unit, val link: (String) -> Unit) {
    @JavascriptInterface fun height(value: Int) = height.invoke(value)
    @JavascriptInterface fun failed() = failed.invoke()
    @JavascriptInterface fun link(url: String) = link.invoke(url)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RichWebView(format: RichFormat, source: String, expressions: Map<String, MathExpression>, modifier: Modifier, expanded: Boolean = false, onLinkClick: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val linkCallback by rememberUpdatedState(onLinkClick)
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val textSize = MaterialTheme.typography.bodyMedium.fontSize.value * fontScale
    val dark = scheme.surface.toArgb().let { AndroidColor.red(it) + AndroidColor.green(it) + AndroidColor.blue(it) < 384 }
    val html = remember(format, source, expressions, scheme, fontScale) { richHtml(format, source, expressions, dark, scheme.onSurface, scheme.primary, textSize) }
    var height by remember(html) { mutableIntStateOf(64) }
    var ready by remember(html) { mutableStateOf(false) }
    var failed by remember(html) { mutableStateOf(false) }
    val onLoading = LocalMarkdownImageLoading.current
    if (!ready && onLoading != null) DisposableEffect(onLoading, html) { onLoading(true); onDispose { onLoading(false) } }
    if (failed) {
        Text("预览内容无效", color = scheme.error, style = MaterialTheme.typography.labelMedium)
        Text(source, style = MaterialTheme.typography.bodySmall)
        return
    }
    AndroidView(modifier = (if (expanded) modifier else modifier.height(height.dp)).clip(MaterialTheme.shapes.medium), factory = {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false; settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val path = uri.path.orEmpty().removePrefix("/assets/rich/")
                    if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.path.orEmpty().startsWith("/assets/rich/") && !path.contains("..")) {
                        val mime = when (path.substringAfterLast('.')) { "js" -> "application/javascript"; "css" -> "text/css"; "woff2" -> "font/woff2"; else -> "application/octet-stream" }
                        return runCatching { WebResourceResponse(mime, if (mime.startsWith("font")) null else "UTF-8", context.assets.open("rich/$path")) }
                            .getOrElse { WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))) }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (request.hasGesture() && request.url.scheme in listOf("http", "https", "mailto")) runCatching { uriHandler.openUri(url) }
                    return true
                }
            }
        }
    }, update = { view ->
        view.removeJavascriptInterface("MemohRender")
        view.addJavascriptInterface(RenderBridge({ measured -> view.post { height = measured.coerceIn(24, 1600); ready = true } }, { view.post { failed = true; ready = true } }, { url ->
            view.post {
                val scheme = android.net.Uri.parse(url).scheme
                if (scheme == null || scheme in listOf("http", "https", "mailto")) runCatching {
                    linkCallback?.invoke(url) ?: if (scheme != null) uriHandler.openUri(url) else Unit
                }
            }
        }), "MemohRender")
        if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL("$ASSET_ORIGIN/assets/rich/", html, "text/html", "UTF-8", null) }
    }, onRelease = { view -> view.removeJavascriptInterface("MemohRender"); view.stopLoading(); view.destroy() })
}

internal fun richHtml(format: RichFormat, source: String, expressions: Map<String, MathExpression>, dark: Boolean, text: Color, primary: Color, textSize: Float): String {
    fun css(color: Color) = "#%06x".format(color.toArgb() and 0xffffff)
    fun quoted(value: String) = JSONObject.quote(value).replace("<", "\\u003c")
    val math = JSONArray().apply { expressions.forEach { (token, value) -> put(JSONObject().put("token", token).put("source", value.source).put("display", value.display)) } }.toString().replace("<", "\\u003c")
    val resources = when (format) {
        RichFormat.Math, RichFormat.Paragraph -> "<link rel=\"stylesheet\" href=\"katex.min.css\"><script src=\"katex.min.js\"></script>"
        RichFormat.Mermaid -> "<script src=\"mermaid.min.js\"></script>"
        RichFormat.Svg -> ""
    }
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        $resources<style>html,body{margin:0;background:transparent;color:${css(text)};font: ${textSize}px/1.5 sans-serif}#content{overflow:auto;padding:4px}p{margin:0}.katex-display{margin:.5em 0}svg{max-width:100%;height:auto}a{color:${css(primary)}}pre{white-space:pre-wrap}</style>
        </head><body><div id="content"></div><script>
        const source=${quoted(source)}, format=${quoted(format.name)}, math=$math, node=document.getElementById('content');
        let finished=false;const report=()=>{if(finished)MemohRender.height(Math.ceil(node.getBoundingClientRect().height));};
        const observer=new ResizeObserver(report);observer.observe(node);
        node.addEventListener('click',event=>{const a=event.target.closest('a');if(a&&event.isTrusted){event.preventDefault();MemohRender.link(a.getAttribute('href')||'');}});
        async function render(){try{
          if(format==='Math'){katex.render(source,node,{displayMode:true,throwOnError:true,trust:false,maxExpand:1000});}
          else if(format==='Mermaid'){mermaid.initialize({startOnLoad:false,securityLevel:'strict',htmlLabels:false,theme:'${if (dark) "dark" else "default"}',flowchart:{htmlLabels:false}});const result=await mermaid.render('memohChart',source);node.innerHTML=result.svg;}
          else if(format==='Svg'){
            const doc=new DOMParser().parseFromString(source,'image/svg+xml');
            if(doc.querySelector('parsererror')||doc.documentElement.localName!=='svg')throw new Error('Invalid SVG');
            const allowed=new Set(['svg','g','defs','path','line','polyline','polygon','rect','circle','ellipse','text','tspan','title','desc','linearGradient','radialGradient','stop','clipPath','mask','pattern','use','symbol','marker']);
            [...doc.querySelectorAll('*')].forEach(el=>{if(!allowed.has(el.localName)){el.remove();return;}[...el.attributes].forEach(a=>{const external=[...a.value.matchAll(/url\s*\(([^)]*)\)/gi)].some(m=>!m[1].trim().replace(/^['"]|['"]$/g,'').startsWith('#'));if(a.name.toLowerCase().startsWith('on')||(a.localName==='href'&&!a.value.startsWith('#'))||external)el.removeAttributeNode(a);});});
            node.appendChild(document.importNode(doc.documentElement,true));
          }else{node.innerHTML=source;math.forEach((item,index)=>{const walker=document.createTreeWalker(node,NodeFilter.SHOW_TEXT);let current;const texts=[];while(current=walker.nextNode())texts.push(current);texts.forEach(t=>{if(!t.textContent.includes(item.token))return;const parts=t.textContent.split(item.token);const fragment=document.createDocumentFragment();parts.forEach((part,i)=>{fragment.appendChild(document.createTextNode(part));if(i<parts.length-1){const span=document.createElement('span');fragment.appendChild(span);katex.render(item.source,span,{displayMode:item.display,throwOnError:true,trust:false,maxExpand:1000});}});t.replaceWith(fragment);});});}
          await document.fonts.ready;finished=true;report();
        }catch(error){node.textContent=source;MemohRender.failed();}}render();
        </script></body></html>""".trimIndent()
}
