package dev.memoh.android

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CrashReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun recordedExceptionCanBeCopiedFromVersionSettingsWithoutItsMessage() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "crash-report-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val store = CrashReportStore(File(root, "diagnostics/last-crash.txt"), "App: fixture\nAndroid: 16\nDevice: fixture")
        store.handler(null, Thread.currentThread()).uncaughtException(Thread.currentThread(),
            IllegalStateException("private conversation and token=secret"))
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { MemohTheme {
            AppUpdateSettings(AppUpdateState("fixture"), false, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("复制崩溃日志").assertIsDisplayed().performClick()
        compose.onNodeWithText("崩溃日志已复制").assertIsDisplayed()
        compose.runOnIdle {
            val copied = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
            assertTrue(copied.contains("java.lang.IllegalStateException"))
            assertTrue(copied.contains("CrashReportUiTest"))
            assertFalse(copied.contains("private conversation"))
            assertFalse(copied.contains("secret"))
        }
        File(base.getExternalFilesDir(null), "crash-report-copied.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
