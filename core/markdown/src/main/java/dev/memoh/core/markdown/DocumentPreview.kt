package dev.memoh.core.markdown

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohPageTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

private sealed interface DocumentState {
    data object Loading : DocumentState
    data class Ready(val file: File, val bytes: ByteArray, val pages: Int = 0, val text: String? = null) : DocumentState
    data class Failed(val message: String) : DocumentState
}

@Composable
fun DocumentPreview(source: String, name: String, mime: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = LocalMarkdownImageLoader.current
    var actionError by remember { mutableStateOf<String?>(null) }
    val state by produceState<DocumentState>(DocumentState.Loading, source) {
        try {
            val bytes = requireNotNull(loader) { "文件加载不可用" }.invoke(source)
            value = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "document_preview").apply { mkdirs() }
                dir.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }.forEach { it.delete() }
                val extension = name.substringAfterLast('.', "bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                val file = File.createTempFile("preview-", ".$extension", dir).apply { writeBytes(bytes) }
                if (extension == "pdf" || mime == "application/pdf") {
                    val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }
                    DocumentState.Ready(file, bytes, pages)
                } else DocumentState.Ready(file, bytes, text = when {
                    extension in setOf("docx", "pptx", "odt") -> officeText(bytes, extension)
                    extension in setOf("txt", "md", "markdown", "json", "csv", "log", "xml", "html", "svg", "mmd", "tex") || mime?.startsWith("text/") == true -> bytes.toString(Charsets.UTF_8)
                    else -> null
                })
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { value = DocumentState.Failed(e.message ?: "文件加载失败") }
    }
    val ready = state as? DocumentState.Ready
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime ?: "application/octet-stream")) { uri ->
        if (uri != null && ready != null) scope.launch {
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(ready.bytes) } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { actionError = "保存失败" }
        }
    }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, topBar = { MemohPageTopBar(name, onDismiss) },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MemohActionButton("保存", Icons.Filled.SaveAlt, { saver.launch(name) }, enabled = ready != null)
                            MemohActionButton("用其他应用打开", Icons.Filled.OpenInNew, {
                                try {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", requireNotNull(ready).file)
                                    val type = mime ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "打开 $name"))
                                } catch (_: Exception) { actionError = "没有可打开此文件的应用" }
                            }, enabled = ready != null, primary = true)
                        }
                        actionError?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }) { padding ->
            when (val document = state) {
                DocumentState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
                is DocumentState.Failed -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) { Text(document.message, color = MaterialTheme.colorScheme.error) }
                is DocumentState.Ready -> if (document.pages > 0) LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(document.pages) { page -> PdfPage(document.file, page, document.pages) }
                } else Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val extension = name.substringAfterLast('.').lowercase()
                    if (document.text == null) Text("此格式可保存或用其他应用打开。", style = MaterialTheme.typography.bodyLarge)
                    else when (extension) {
                        "svg" -> RichContent(RichFormat.Svg, document.text)
                        "mmd" -> RichContent(RichFormat.Mermaid, document.text)
                        "tex" -> RichContent(RichFormat.Math, document.text)
                        "md", "markdown" -> MemohMarkdown.Markdown(document.text)
                        "docx", "pptx", "odt" -> { Text("文档文本预览", style = MaterialTheme.typography.labelLarge); Text(document.text, style = MaterialTheme.typography.bodyLarge) }
                        else -> MemohMarkdown.Code(document.text, extension)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(file: File, index: Int, total: Int) {
    var error by remember(file, index) { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, file, index) {
        try { value = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.openPage(index).use { page ->
                    val width = page.width.coerceAtMost(1400).coerceAtLeast(600)
                    val height = (page.height.toFloat() / page.width * width).toInt().coerceIn(1, 5000)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } }
            }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "此页无法预览，请用其他应用打开文件" }
    }
    // Compose can draw a retained layer after disposal; the bitmap's lifetime follows that layer.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${index + 1} / $total", style = MaterialTheme.typography.labelSmall)
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        else bitmap?.let { Image(it.asImageBitmap(), "第 ${index + 1} 页", Modifier.fillMaxWidth().padding(top = 4.dp)) }
            ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

private fun officeText(bytes: ByteArray, extension: String): String {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == "word/document.xml" || extension == "odt" && entry.name == "content.xml" || extension == "pptx" && entry.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))) {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 10 * 1024 * 1024) { "文档文本过大，请用其他应用打开" }
                    output.write(buffer, 0, count)
                }
                val data = output.toByteArray()
                entries += entry.name to data
            }
        }
    }
    return entries.sortedBy { Regex("[0-9]+").find(it.first)?.value?.toIntOrNull() ?: 0 }.joinToString("\n\n") { (_, xml) ->
        val parser = Xml.newPullParser().apply { setInput(xml.inputStream(), "UTF-8") }
        buildString {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.TEXT -> append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') in listOf("p", "tr")) append('\n')
                }
                parser.next()
            }
        }.trim()
    }
}
