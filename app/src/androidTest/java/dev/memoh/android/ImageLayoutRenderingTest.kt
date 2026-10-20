package dev.memoh.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.markdown.LocalMarkdownImageLoader
import dev.memoh.core.markdown.MarkdownImage
import dev.memoh.core.markdown.MemohMarkdown
import dev.memoh.core.model.Session
import dev.memoh.core.model.UIAttachment
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UITurn
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageLayoutRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mediaColor = android.graphics.Color.rgb(27, 153, 121)

    private fun imageBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(mediaColor)
        val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 18f }
        for (y in 40 until height step 80) canvas.drawText("Image row $y", 12f, y.toFloat(), paint)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun waitForImage(description: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    /** Compare the painted pixels with the frame: measured bounds alone miss drawing overflow. */
    private fun assertContained(description: String, screenshot: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$screenshot.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val image = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("Image exceeded the 360 dp transcript frame: $image", image.height <= 360 * density + 1)
        val frame = Rect(image.left - 1, image.top - 1, image.right + 1, image.bottom + 1)
        var painted = 0
        var escaped = 0
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
            if (bitmap.getPixel(x, y) == mediaColor) {
                painted++
                if (x < frame.left || x >= frame.right || y < frame.top || y >= frame.bottom) escaped++
            }
        }
        bitmap.recycle()
        assertTrue("The fixture image did not render", painted > 20)
        assertEquals("Image painted over its caption, another message or the composer", 0, escaped)
    }

    @Test fun tallUserAttachmentStaysInsideItsFrameAndLeavesFollowingMessagesReadable() {
        val bytes = imageBytes(240, 1440)
        val name = "long-attachment.png"
        val state = ChatUiState(session = Session("tall-image"), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false, socketStatus = SocketStatus.Connected,
            modelCatalogLoading = false, history = listOf(
                UITurn("question", role = "user", text = "这样会影响我的会话啊", attachments = listOf(
                    UIAttachment(type = "image", mime = "image/png", name = name, url = "https://example.test/long.png"))),
                UITurn("reply", role = "assistant", messages = listOf(UIMessage(0, "text", content = "这段回复不能被图片覆盖。"))),
            ))
        compose.setContent { MemohTheme(darkTheme = false) {
            CompositionLocalProvider(LocalMarkdownImageLoader provides { _: String -> bytes }) {
                ChatScreen(state, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> }, onReject = { _, _, _ -> },
                    onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> }, onDismissError = {})
            }
        } }
        waitForImage(name)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        assertContained(name, "tall-user-image")
        val image = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        val caption = compose.onNodeWithText(name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val question = compose.onNodeWithText("这样会影响我的会话啊").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val answer = compose.onNodeWithText("这段回复不能被图片覆盖。", substring = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(caption.top >= image.bottom)
        assertTrue(question.top >= caption.bottom)
        assertTrue(answer.top >= question.bottom)
        compose.onNodeWithContentDescription(name).performClick()
        compose.onNodeWithContentDescription("关闭图片").assertIsDisplayed().performClick()
        compose.onNodeWithText("这样会影响我的会话啊").assertIsDisplayed()
    }

    @Test fun aDelayedInlineLongImageStaysBetweenItsSurroundingParagraphs() {
        val loaded = CompletableDeferred<ByteArray>()
        val name = "inline-long.png"
        compose.setContent { MemohTheme(darkTheme = false) { Surface {
            CompositionLocalProvider(LocalMarkdownImageLoader provides { _: String -> loaded.await() }) {
                Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
                    MemohMarkdown.Markdown("图片前的文字\n\n![$name](https://example.test/inline.png)\n\n图片后的文字必须清楚。")
                }
            }
        } } }
        compose.onNodeWithContentDescription("正在加载图片").assertIsDisplayed()
        compose.onNodeWithText("图片后的文字必须清楚。", substring = true).assertIsDisplayed()
        loaded.complete(imageBytes(160, 1920))
        waitForImage(name)
        assertContained(name, "tall-inline-image")
        val image = compose.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
        val after = compose.onNodeWithText("图片后的文字必须清楚。", substring = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(after.top >= image.bottom)
        compose.onNodeWithContentDescription("正在加载图片").assertDoesNotExist()
    }

    @Test fun landscapeImagesKeepTheirRatioAndComposerThumbnailsKeepTheirFixedHeight() {
        val wide = imageBytes(640, 160)
        val tall = imageBytes(160, 1920)
        compose.setContent { MemohTheme(darkTheme = false) { Surface {
            CompositionLocalProvider(LocalMarkdownImageLoader provides { source: String -> if (source == "wide") wide else tall }) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    MarkdownImage("wide", "landscape", Modifier.width(320.dp), showCaption = false)
                    MarkdownImage("tall", "composer thumbnail", Modifier.width(168.dp), height = 96.dp, showCaption = false)
                }
            }
        } } }
        waitForImage("landscape")
        waitForImage("composer thumbnail")
        val landscape = compose.onNodeWithContentDescription("landscape").fetchSemanticsNode().boundsInRoot
        val thumbnail = compose.onNodeWithContentDescription("composer thumbnail").fetchSemanticsNode().boundsInRoot
        assertEquals(4f, landscape.width / landscape.height, 0.03f)
        assertEquals(96 * compose.density.density, thumbnail.height, 1f)
    }
}
