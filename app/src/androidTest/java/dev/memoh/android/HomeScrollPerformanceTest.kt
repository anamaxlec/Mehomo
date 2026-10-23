package dev.memoh.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.Bot
import dev.memoh.core.model.Session
import dev.memoh.feature.sessions.SessionsScreen
import dev.memoh.feature.sessions.SessionsUiState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Virtual-clock gesture diagnostics on a large list; not an app FPS benchmark or CI timing gate. */
@RunWith(AndroidJUnit4::class)
class HomeScrollPerformanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun scrollLargeSessionList() {
        val rows = List(2_000) { index -> Session("session-$index", title = "会话 $index · 中文 HTML 与工具任务",
            channelType = "local", updatedAt = "2026-10-18T10:00:00+08:00") }
        compose.setContent { MemohTheme(darkTheme = false) {
            SessionsScreen(SessionsUiState(initialized = true, bot = Bot("b", "性能测试"), sessions = rows),
                {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText(rows.first().title!!).assertIsDisplayed()
        val durations = CopyOnWriteArrayList<Long>()
        val dropped = AtomicInteger()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            dropped.addAndGet(lost)
        }
        compose.runOnIdle { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
        val started = SystemClock.elapsedRealtime()
        try {
            repeat(8) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 250) } }
            compose.waitForIdle()
        } finally {
            compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
        }
        assertTrue("Scrolling must produce frame measurements", durations.isNotEmpty())
        val frames = durations.sorted()
        val label = InstrumentationRegistry.getArguments().getString("measurementLabel", "sample")
        val result = buildJsonObject {
            put("label", label); put("rows", rows.size); put("swipes", 8)
            put("frames", frames.size); put("droppedMetricCallbacks", dropped.get())
            put("p50Millis", frames[frames.lastIndex / 2] / 1_000_000.0)
            put("p95Millis", frames[(frames.lastIndex * .95).toInt()] / 1_000_000.0)
            put("over32Millis", frames.count { it > 32_000_000 })
            put("elapsedMillis", SystemClock.elapsedRealtime() - started)
            put("scope", "Compose virtual-clock gestures; Android 16 emulator and isolated debug app; not release FPS")
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "home-scroll-$label.json").writeText(result.toString())
    }
}
