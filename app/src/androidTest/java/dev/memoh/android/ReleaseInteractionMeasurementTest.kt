package dev.memoh.android

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.memoh.core.designsystem.theme.MemohTheme
import dev.memoh.core.model.Bot
import dev.memoh.core.model.RunStatus
import dev.memoh.core.model.RuntimeCurrentRunView
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.Session
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UITurn
import dev.memoh.core.network.SocketStatus
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatUiState
import dev.memoh.feature.sessions.SessionsScreen
import dev.memoh.feature.sessions.SessionsUiState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Run with -PmehomoBenchmark=true on a device. All data is synthetic and local. */
@RunWith(AndroidJUnit4::class)
class ReleaseInteractionMeasurementTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun scrollTwoThousandSessions() = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        val sessions = List(2_000) { index ->
            Session("session-$index", title = "会话 $index · 文档与工具任务",
                channelType = "local", updatedAt = "2026-11-23T10:00:00+08:00")
        }
        scenario.onActivity { activity ->
            activity.setContent { MemohTheme(darkTheme = false) {
                SessionsScreen(SessionsUiState(initialized = true, bot = Bot("bot", "Memoh"), sessions = sessions),
                    {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
            } }
        }
        val firstTitle = requireNotNull(sessions.first().title)
        awaitText(firstTitle)
        var x = 0
        var bottom = 0
        var top = 0
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            x = decor.width / 2
            bottom = decor.height * 3 / 4
            top = decor.height / 4
        }
        measure(scenario, "sessionScroll") {
            repeat(8) {
                val command = automation.executeShellCommand("input swipe $x $bottom $x $top 250")
                ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
            }
        }
        assertTrue("Expected the list to scroll past its first session", !hasText(firstTitle))
    }

    @Test fun streamLongMarkdownWithLoadedHistory() = ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
        val history = List(180) {
            UITurn("history-$it", id = "entry-$it", role = "assistant", messages = listOf(
                UIMessage(0, "reasoning", "Previously delivered explanation $it"),
                UIMessage(1, "text", "**Historical answer $it** with several blocks."),
            ))
        }
        val state = mutableStateOf(ChatUiState(session = Session("markdown-fixture"), history = history,
            historyLoading = false, hasMoreHistory = false, awaitingSnapshot = false,
            socketStatus = SocketStatus.Connected, modelCatalogLoading = false))
        scenario.onActivity { activity ->
            activity.setContent { MemohTheme(darkTheme = false) {
                ChatScreen(state.value, {}, {}, {}, {}, {}, onApprove = { _, _, _ -> },
                    onReject = { _, _, _ -> }, onUserInput = { _, _, _ -> }, onCancelUserInput = { _, _ -> },
                    onDismissError = {})
            } }
        }
        awaitText("Historical answer 179")

        val revisions = buildList {
            val buffer = StringBuilder("## Synthetic streaming reply\n\n")
            repeat(96) { index ->
                buffer.append("Step ").append(index).append(" shows **Markdown** and `inline code` in a partial response. ")
                if (index % 8 == 7) buffer.append("\n\n")
                add(buffer.toString())
            }
        }
        val completed = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val finalText = "Streaming fixture complete"
        fun setReply(text: String, status: String) {
            state.value = state.value.copy(runtime = RuntimeState(run = RuntimeCurrentRunView(
                runId = "stream", turnId = "stream", status = status, messages = listOf(UIMessage(0, "text", text)),
            )))
        }
        measure(scenario, "markdownStream") {
            val start = SystemClock.uptimeMillis()
            revisions.forEachIndexed { index, text ->
                main.postAtTime({ setReply(text, RunStatus.RUNNING) }, start + index * 45L)
            }
            main.postAtTime({
                setReply(revisions.last() + "\n\n" + finalText, RunStatus.COMPLETED)
                completed.countDown()
            }, start + revisions.size * 45L)
            assertTrue("The main thread must consume the entire fixture", completed.await(15, TimeUnit.SECONDS))
        }
        awaitText(finalText)
    }

    private fun hasText(text: String): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.contains(text) == true) return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::visit) == true }
        }
        return automation.rootInActiveWindow?.let(::visit) == true
    }

    private fun awaitText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!hasText(text) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Expected visible text: $text", hasText(text))
    }

    private fun measure(activity: ActivityScenario<ComponentActivity>, scenario: String, action: () -> Unit) {
        val durations = CopyOnWriteArrayList<Long>()
        val dropped = AtomicInteger()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration >= 0) durations.add(duration)
            dropped.addAndGet(lost)
        }
        activity.onActivity {
            it.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }
        val start = SystemClock.elapsedRealtime()
        try {
            action()
            instrumentation.waitForIdleSync()
            // Allow Android to report the final draw after the main queue is idle.
            SystemClock.sleep(150)
        } finally {
            activity.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        }
        val frames = durations.sorted()
        assertTrue("Expected frame data for $scenario", frames.size >= 20)
        val label = InstrumentationRegistry.getArguments().getString("measurementLabel", "sample")
        val p50 = frames[frames.lastIndex / 2] / 1_000_000.0
        val p95 = frames[(frames.lastIndex * .95).toInt()] / 1_000_000.0
        Log.i("MehomoPerf", "label=$label scenario=$scenario frames=${frames.size} p50Ms=$p50 p95Ms=$p95 " +
            "over32Ms=${frames.count { it > 32_000_000 }} droppedCallbacks=${dropped.get()} " +
            "elapsedMs=${SystemClock.elapsedRealtime() - start}")
    }
}
