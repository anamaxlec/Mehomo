package dev.memoh.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.markdown.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ContentRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun views(root: View): List<WebView> = if (root is WebView) listOf(root) else if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun evaluate(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { webView.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        return result
    }
    private fun rendered(predicate: (WebView) -> Boolean): WebView {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            val candidates = mutableListOf<WebView>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { candidates += views(compose.activity.window.decorView) }
            candidates.firstOrNull { predicate(it) && evaluate(it, "typeof finished !== 'undefined' && finished") == "true" }?.let {
                val drawn = CountDownLatch(1)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    it.postVisualStateCallback(1, object : WebView.VisualStateCallback() { override fun onComplete(id: Long) { drawn.countDown() } })
                }
                assertTrue("WebView did not commit its rendered content", drawn.await(5, TimeUnit.SECONDS))
                compose.waitForIdle()
                Thread.sleep(300)
                return it
            }
            Thread.sleep(150)
        }
        error("Rich renderer did not produce the expected DOM")
    }
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun formulasRenderWithBundledFontsInDarkThemeAndLargeText() {
        compose.setContent { MemohTheme(darkTheme = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                Surface { Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
                    MemohMarkdown.Markdown("## 公式预览\n\n行内 \\(\\frac{a}{b}\\) 与链接 [Memoh](https://app.memoh.net)。\n\n\\[\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\\]")
                } }
            }
        } }
        val web = rendered { evaluate(it, "document.querySelectorAll('.katex').length >= 1 && document.fonts.status === 'loaded'") == "true" }
        assertEquals("false", evaluate(web, "document.getElementById('content').textContent.includes('MEMOHMATHEXPRESSION')"))
        screenshot("render-math-dark")
    }
    @Test fun mermaidRendersLocallyWithoutHtmlLabels() {
        compose.setContent { MemohTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            RichContent(RichFormat.Mermaid, "flowchart LR\n A[输入] --> B[执行任务]\n B --> C[完成提醒]")
        } } } }
        val web = rendered { evaluate(it, "document.querySelector('#content svg') !== null") == "true" }
        assertEquals("0", evaluate(web, "document.querySelectorAll('foreignObject').length"))
        screenshot("render-mermaid")
    }
    @Test fun svgRetainsLocalGradientAndRemovesScriptsExternalReferencesAndEvents() {
        compose.setContent { MemohTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp)) {
            RichContent(RichFormat.Svg, """<svg xmlns="http://www.w3.org/2000/svg" width="320" height="150" viewBox="0 0 320 150"><defs><linearGradient id="g"><stop stop-color="#8248ed"/><stop offset="1" stop-color="#ffc96b"/></linearGradient></defs><rect width="320" height="150" rx="30" fill="url(#g)" onclick="alert(1)"/><text x="45" y="85" fill="white" font-size="24">Memoh SVG</text><script>document.body.remove()</script><foreignObject><div>bad</div></foreignObject><use href="https://invalid.test/external.svg#x"/></svg>""")
        } } } }
        val web = rendered { evaluate(it, "document.querySelector('#content svg rect') !== null") == "true" }
        val result = evaluate(web, "JSON.stringify({fill:document.querySelector('rect').getAttribute('fill'),unsafe:document.querySelectorAll('#content script,foreignObject,[onclick],[href^=http]').length})")
        val fields = JSONObject(org.json.JSONTokener(result).nextValue().toString())
        assertEquals("url(#g)", fields.getString("fill")); assertEquals(0, fields.getInt("unsafe"))
        screenshot("render-svg")
    }
    @Test fun pdfAttachmentRendersWithTheNativeDocumentViewer() {
        val output = java.io.ByteArrayOutputStream()
        val document = android.graphics.pdf.PdfDocument()
        try {
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(320, 240, 1).create())
            page.canvas.drawColor(android.graphics.Color.WHITE)
            page.canvas.drawText("Mehomo PDF", 25f, 75f, android.graphics.Paint().apply { textSize = 26f; color = android.graphics.Color.rgb(120, 70, 220) })
            document.finishPage(page); document.writeTo(output)
        } finally { document.close() }
        val bytes = output.toByteArray()
        compose.setContent { MemohTheme { CompositionLocalProvider(LocalMarkdownImageLoader provides { _: String -> bytes }) {
            DocumentPreview("/fixture.pdf", "fixture.pdf", "application/pdf") {}
        } } }
        compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("第 1 页")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
        screenshot("render-pdf")
    }
    @Test fun invalidFormulaShowsReadableSourceInsteadOfLeavingAnEmptyBlock() {
        compose.setContent { MemohTheme { Surface { RichContent(RichFormat.Math, "\\notAnExistingKaTeXCommand{x}") } } }
        compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasText("预览内容无效")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("\\notAnExistingKaTeXCommand{x}").assertExists()
    }
}
