package dev.memoh.feature.chat

import dev.memoh.core.model.*
import dev.memoh.core.network.SocketStatus
import org.junit.Assert.*
import org.junit.Test

class ComposerActionTest {
    private val ready = ChatUiState(session = Session("session", runtimeType = "claude-code"),
        sessionLoading = false, historyLoading = false, awaitingSnapshot = false,
        socketStatus = SocketStatus.Connected)
    private val running = ready.copy(runtime = RuntimeState(run = RuntimeCurrentRunView(runId = "run", turnId = "turn")))
    private val commands = RuntimeControls(commands = listOf(
        RuntimeCommand(name = "status", kind = "read"), RuntimeCommand(name = "compact", kind = "operation"),
        RuntimeCommand(name = "dataviz", kind = "turn", skill = true),
        RuntimeCommand(name = "reload-skills", kind = "turn")))

    @Test fun `running text enqueues but only an empty button can stop`() {
        val action = running.copy(draft = "补充一点").composerAction()
        assertEquals(ComposerActionKind.Enqueue, action.kind)
        assertEquals("加入后续队列", action.description)
        assertTrue(action.enabled)
        val stop = running.copy(busy = true, draftRestoring = true).composerAction()
        assertEquals(ComposerActionKind.Stop, stop.kind)
        assertTrue(stop.enabled)
        assertEquals(ComposerActionKind.Idle, ready.composerAction().kind)
    }

    @Test fun `a loading attachment remains a disabled send rather than a stop`() {
        val action = running.copy(attachmentLoading = true).composerAction()
        assertEquals(ComposerActionKind.Enqueue, action.kind)
        assertFalse(action.enabled)
        assertTrue(action.blockedReason!!.contains("仅支持文字"))
    }

    @Test fun `read commands stay distinct from queues and mutating commands block during runs`() {
        val state = running.copy(controls = commands)
        assertEquals(ComposerActionKind.RuntimeCommand, state.composerAction("/status").kind)
        assertTrue(state.composerAction("/status").enabled)
        assertFalse(state.composerAction("/compact").enabled)
        assertFalse(state.composerAction("/dataviz compare data").enabled)
        assertEquals(ComposerActionKind.Send, ready.copy(controls = commands).composerAction("/dataviz compare data").kind)
    }

    @Test fun `queue shortcuts require text and steer does not wait for capability polling`() {
        assertFalse(ready.composerAction("/queue").enabled)
        assertFalse(ready.composerAction("/steer 补充").enabled)
        val action = running.copy(queue = SessionQueueResponse(steerSupported = true)).composerAction("/steer 补充")
        assertTrue(action.enabled)
        assertTrue(action.steer)
        assertEquals("补充", action.text)
        assertTrue(running.composerAction("/steer 首次轮询前的补充").enabled)
        val owned = ready.copy(controls = RuntimeControls(commands = listOf(RuntimeCommand(name = "queue", kind = "turn"))))
        assertEquals(ComposerActionKind.Send, owned.composerAction("/queue task").kind)
    }

    @Test fun `temporary configuration and first-send waiting never become stop actions`() {
        assertFalse(running.copy(draft = "保留草稿", busy = true).composerAction().enabled)
        val waiting = ready.copy(pending = listOf(PendingTurn("invocation", "第一个问题"))).composerAction("下一个问题")
        assertEquals(ComposerActionKind.Waiting, waiting.kind)
        assertFalse(waiting.enabled)
        assertFalse(running.copy(session = ready.session!!.copy(channelType = "telegram")).composerAction("text").enabled)
    }

    @Test fun `completion filters selectors and preserves the server skill marker`() {
        assertEquals(listOf("dataviz"), runtimeCommandSuggestions("/DATA", commands.commands!!).map { it.name })
        assertTrue(runtimeCommandSuggestions("/dataviz prompt", commands.commands!!).isEmpty())
        assertTrue(runtimeCommandSuggestions("hello /data", commands.commands!!).isEmpty())
        assertTrue(runtimeCommandSuggestions("/data", commands.commands!!).single().skill)
        assertFalse(runtimeCommandSuggestions("/reload", commands.commands!!).single().skill)
    }

    @Test fun `native skills search names display names and descriptions without requiring runtime controls`() {
        val skills = listOf(SkillCatalogItem("dataviz", displayName = "图表助手", description = "绘制数据图表"),
            SkillCatalogItem("writing", description = "修改文章"))
        assertEquals(2, nativeSkillSuggestions("/", skills).size)
        assertEquals("dataviz", nativeSkillSuggestions("  /DATA", skills).single().name)
        assertEquals("dataviz", nativeSkillSuggestions("/图表", skills).single().name)
        assertEquals("writing", nativeSkillSuggestions("/文章", skills).single().name)
        assertTrue(nativeSkillSuggestions("/dataviz prompt", skills).isEmpty())
        assertNull(slashMenuQuery("\n/"))
        assertNull(slashMenuQuery("hello /"))
    }

    @Test fun `native skill invocation sends a turn and cannot become a queued slash or carry attachments`() {
        val state = ready.copy(session = Session("native", runtimeType = "model"), controls = null,
            skillCatalog = listOf(SkillCatalogItem("dataviz")))
        assertTrue(state.nativeSkillsEnabled)
        assertEquals(ComposerActionKind.Send, state.composerAction("/dataviz 绘图").kind)
        assertTrue(state.composerAction("/dataviz 绘图").enabled)
        assertFalse(state.copy(attachmentLoading = true).composerAction("/dataviz 绘图").enabled)
        assertFalse(state.copy(runtime = running.runtime).composerAction("/dataviz 绘图").enabled)
        assertFalse(ready.copy(skillCatalog = state.skillCatalog).nativeSkillsEnabled)
    }

    @Test fun `native shortcuts exist without waiting for either remote catalogue`() {
        val state = ready.copy(session = Session("native", runtimeType = "model"), controls = null,
            socketStatus = SocketStatus.Reconnecting)
        assertEquals(listOf(ComposerQuickAction.Help, ComposerQuickAction.Skills, ComposerQuickAction.New, ComposerQuickAction.Model),
            state.composerQuickActions())
        assertEquals(ComposerQuickAction.Model, state.composerQuickAction(" /MODEL "))
        assertEquals(ComposerActionKind.QuickAction, state.composerAction("/model").kind)
        assertTrue(state.composerAction("/model").enabled)
        assertEquals(listOf(ComposerQuickAction.Model), quickActionSuggestions("/模型", state.composerQuickActions()))
        assertFalse(state.copy(attachmentLoading = true).composerAction("/help").enabled)
    }

    @Test fun `external shortcuts follow advertised runtime capabilities`() {
        val state = ready.copy(controls = RuntimeControls(capabilities = RuntimeControlCapabilities(planMode = true, goal = true),
            modes = RuntimeModeState(availableModes = listOf(RuntimeMode(id = "default")))))
        assertFalse(state.composerQuickActions().contains(ComposerQuickAction.Skills))
        assertFalse(state.composerQuickActions().contains(ComposerQuickAction.Model))
        assertTrue(state.composerQuickActions().containsAll(listOf(ComposerQuickAction.Permission, ComposerQuickAction.Plan, ComposerQuickAction.Goal)))
        assertTrue(running.composerAction("/new").enabled)
        assertEquals(ComposerActionKind.RuntimeCommand, ready.copy(controls = commands).composerAction("/status").kind)
        assertNull(state.composerQuickAction("/model"))
    }
}
