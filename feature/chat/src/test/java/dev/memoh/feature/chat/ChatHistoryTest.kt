package dev.memoh.feature.chat

import dev.memoh.core.model.UITurn
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UIUserTurn
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.RuntimeCurrentRunView
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryTest {
    @Test fun `a saved live reply resolves to the newest persisted record for its turn`() {
        val provisional = dev.memoh.core.data.SavedChatItem("bot", "session", "assistant:turn", "回复", provisionalTurnId = "turn")
        val older = UITurn("turn", id = "old", role = "assistant")
        val latest = older.copy(id = "new")
        assertEquals(latest, dev.memoh.core.data.resolveSavedChatTurn(provisional, listOf(older, latest)))
        val exact = provisional.copy(turnKey = older.listKey, provisionalTurnId = null)
        assertEquals(older, dev.memoh.core.data.resolveSavedChatTurn(exact, listOf(older, latest)))
    }

    @Test fun `completed runtime cannot replace published history with original workspace image text`() {
        val archived = "![产物](/bots/b/media/${"a".repeat(64)})"
        val persisted = UITurn("turn", id = "persisted", role = "assistant",
            messages = listOf(UIMessage(0, "text", content = archived)))
        val snapshot = RuntimeState(run = RuntimeCurrentRunView(runId = "run", turnId = "turn", status = "completed",
            messages = listOf(UIMessage(0, "text", content = "![产物](/data/image.png)"))))
        val updated = ChatUiState(history = listOf(persisted)).withRuntime(snapshot)
        assertEquals(archived, updated.settledHistory.single().safeMessages.single().content)
        assertTrue(updated.liveMessages.isEmpty())
        val transient = persisted.copy(id = null)
        val transientUpdate = ChatUiState(history = listOf(transient)).withRuntime(snapshot)
        assertEquals(snapshot.run!!.safeMessages, transientUpdate.history.single().safeMessages)
    }
    @Test fun `assistant records sharing a turn have distinct body and action keys`() {
        val messages = listOf(UIMessage(0, "reasoning", "Thinking"), UIMessage(1, "text", "Reply"))
        val turns = listOf(
            UITurn("shared-turn", id = "first-record", role = "assistant", messages = messages),
            UITurn("shared-turn", id = "second-record", role = "assistant", messages = messages),
        )
        val identities = assistantHistoryKeys(turns)
        val keys = turns.flatMap { turn ->
            val identity = identities.getValue(turn.listKey)
            assistantRenderItems(identity, turn.safeMessages).map { it.key } + "assistant-$identity:actions"
        }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `prepending assistant pages preserves keys for already visible records`() {
        val first = UITurn("shared-turn", id = "first-record", role = "assistant")
        val middle = first.copy(id = "middle-record")
        val latest = first.copy(id = "latest-record")
        val initial = assistantHistoryKeys(listOf(middle, latest))
        val paginated = assistantHistoryKeys(listOf(first, middle, latest))
        initial.forEach { (record, key) -> assertEquals(key, paginated[record]) }
        assertEquals(latest.turnId, paginated[latest.listKey])
        assertEquals(latest.turnId, assistantHistoryKeys(listOf(latest))[latest.listKey])
    }

    @Test fun `user records do not replace the live assistant identity`() {
        val assistant = UITurn("turn", id = "assistant", role = "assistant")
        val user = UITurn("turn", id = "user", role = "user")
        val keys = assistantHistoryKeys(listOf(assistant, user))
        assertEquals(mapOf(assistant.listKey to assistant.turnId), keys)
    }

    private fun ChatUiState.folder() = capabilities({}, {}, {}).firstOrNull { it.label == "文件夹" && it.visible }

    @Test fun `empty conversation folder remains visible across reconnect and capability refresh`() {
        val empty = ChatUiState(session = dev.memoh.core.model.Session("empty"), historyLoading = false,
            hasMoreHistory = false, awaitingSnapshot = false,
            workdirs = listOf(dev.memoh.core.model.Workdir("project", name = "项目")))
        assertNotNull(empty.folder())
        for (status in dev.memoh.core.network.SocketStatus.entries) {
            val refreshing = empty.copy(socketStatus = status, awaitingSnapshot = true, modelCatalogLoading = true,
                historyLoading = true)
            assertTrue(refreshing.isEmptyConversation)
            assertTrue(refreshing.hasResolvedHistory)
            assertEquals(empty.folder()?.options, refreshing.folder()?.options)
            assertTrue(refreshing.folder()!!.enabled)
        }
        assertNotNull(empty.copy(historyLoading = true, historyError = "暂时离线").folder())
    }

    @Test fun `unbound folder is hidden for unknown history and actual conversation content`() {
        assertNull(ChatUiState().folder())
        val empty = ChatUiState(historyLoading = false, hasMoreHistory = false)
        assertNotNull(empty.folder())
        for (withContent in listOf(empty.copy(history = listOf(user)), empty.copy(runtime = run("running", emptyList())),
            empty.copy(pending = listOf(PendingTurn("pending", "问题"))),
            empty.copy(runtime = run("completed", listOf(UIMessage(0, "text", content = "回答")))))) {
            assertFalse(withContent.isEmptyConversation)
            assertNull(withContent.folder())
            assertNotNull(withContent.copy(selectedWorkdirId = "project").folder())
        }
    }

    @Test fun `Grok runtime commands wait for an idle native session`() {
        val grok = ChatUiState(session = dev.memoh.core.model.Session("s", runtimeType = "grok-build"))
        assertTrue(grok.runtimeCommandsAvailable)
        assertFalse(grok.copy(busy = true).runtimeCommandsAvailable)
        val running = grok.copy(runtime = run("running", emptyList()))
        assertFalse(running.runtimeCommandsAvailable)
        assertTrue(running.copy(session = running.session!!.copy(runtimeType = "codex")).runtimeCommandsAvailable)
    }
    @Test fun `external forks follow each durable turn flag including Grok latest prompt anchors`() {
        val turn = UITurn(turnId = "latest", role = "assistant", runtimeForkable = true)
        for (runtime in listOf("codex", "claude-code", "grok-build", "acp_agent")) {
            val state = ChatUiState(session = dev.memoh.core.model.Session("s", runtimeType = runtime))
            assertTrue("$runtime can fork an anchored turn", state.canFork(turn))
            assertFalse(state.canFork(turn.copy(runtimeForkable = false)))
            assertFalse(state.canFork(turn.copy(runtimeForkable = null)))
            assertFalse(state.copy(session = state.session!!.copy(channelType = "telegram")).canFork(turn))
            assertFalse(state.copy(runtime = run("running", emptyList())).canFork(turn))
        }
        assertFalse(ChatUiState(session = dev.memoh.core.model.Session("s", runtimeType = "future-runtime")).canFork(turn))
    }
    @Test fun `retry admission decodes UUID history anchors and replaces only the assistant tail`() {
        val operation = kotlinx.serialization.json.Json.decodeFromString<dev.memoh.core.model.RuntimeRunOperation>(
            """{"kind":"retry","replace_from_message_id":"550e8400-e29b-41d4-a716-446655440000"}""")
        val original = assistant.copy(id = operation.replaceFromMessageId)
        val current = ChatUiState(history = listOf(user, original), runtime = run("completed", original.messages),
            regeneration = PendingRegeneration("retry-invocation", "turn"), draft = "Keep my draft")
        val admitted = current.withRuntime(RuntimeState(run = RuntimeCurrentRunView(runId = "retry-run",
            turnId = "retry-turn", invocationId = "retry-invocation", operation = operation, messages = emptyList())))
        assertEquals(listOf(user), admitted.settledHistory)
        assertNull(admitted.regeneration)
        assertEquals("Keep my draft", admitted.draft)
        assertTrue(admitted.isRunning)
        val done = admitted.withRuntime(RuntimeState(run = admitted.runtime.run!!.copy(status = "completed",
            messages = listOf(UIMessage(0, "text", content = "Replacement reply")))))
        assertEquals(listOf("turn", "retry-turn"), done.settledHistory.map { it.turnId })
        assertEquals("Replacement reply", done.settledHistory.last().safeMessages.single().content)
    }
    @Test fun `a replayed replacement anchor absent from history cannot erase newer messages`() {
        val current = ChatUiState(history = listOf(user, assistant))
        val next = current.withRuntime(RuntimeState(run = RuntimeCurrentRunView(runId = "old-retry", turnId = "old",
            status = "completed", operation = dev.memoh.core.model.RuntimeRunOperation("retry", "absent"))))
        assertEquals(listOf(user, assistant), next.settledHistory)
    }
    @Test fun `retry is limited to the latest local model reply while idle and connected`() {
        val ready = ChatUiState(session = dev.memoh.core.model.Session("session", runtimeType = "model"),
            history = listOf(user, assistant), historyLoading = false, awaitingSnapshot = false,
            socketStatus = dev.memoh.core.network.SocketStatus.Connected)
        assertEquals("turn", ready.retryableTurnId)
        assertNull(ready.copy(session = ready.session!!.copy(channelType = "telegram")).retryableTurnId)
        assertNull(ready.copy(session = ready.session!!.copy(runtimeType = "codex")).retryableTurnId)
        assertNull(ready.copy(regeneration = PendingRegeneration("i", "turn")).retryableTurnId)
        assertNull(ready.copy(runtime = run("running", emptyList())).retryableTurnId)
        for (blocked in listOf(ready.copy(session = null), ready.copy(sessionLoading = true),
            ready.copy(busy = true), ready.copy(historyLoading = true), ready.copy(awaitingSnapshot = true),
            ready.copy(runtime = ready.runtime.copy(needsSnapshot = true)),
            ready.copy(pending = listOf(PendingTurn("pending", "Next question"))))) {
            assertNull(blocked.retryableTurnId)
        }
        for (status in dev.memoh.core.network.SocketStatus.entries.filter { it != dev.memoh.core.network.SocketStatus.Connected }) {
            assertNull(ready.copy(socketStatus = status).retryableTurnId)
        }
    }
    @Test fun `retry stays ready while the unrelated composer draft or attachments load`() {
        val ready = ChatUiState(session = dev.memoh.core.model.Session("session", runtimeType = "model"),
            history = listOf(user, assistant), historyLoading = false, awaitingSnapshot = false,
            socketStatus = dev.memoh.core.network.SocketStatus.Connected)
        for (loading in listOf(ready.copy(draftRestoring = true), ready.copy(attachmentLoading = true),
            ready.copy(draftRestoring = true, attachmentLoading = true))) {
            assertFalse(loading.canSend)
            assertEquals("turn", loading.retryableTurnId)
        }
    }
    @Test fun `message copying preserves Markdown while excluding tools and reasoning`() {
        assertEquals("**Answer**\n\nCommand result", dev.memoh.feature.chat.components.replyCopyText(listOf(
            UIMessage(0, "reasoning", content = "Private reasoning"),
            UIMessage(1, "text", content = "**Answer**"),
            UIMessage(2, "tool", content = "Tool output"),
            UIMessage(3, "command", content = "Command result"))))
    }
    @Test fun `a newest-page refresh retains older pages and replaces its overlapping tail`() {
        val older = (1..20).map { UITurn(turnId = "turn-$it", turnPosition = it, role = "assistant", id = "entry-$it") }
        val refreshed = older.takeLast(4).map { it.copy(messages = listOf(UIMessage(id = 0, type = "text", content = "Stored"))) }
        val next = UITurn(turnId = "next", turnPosition = 21, role = "assistant", id = "entry-next")
        val merged = mergeLatestHistory(older, refreshed + next)
        assertEquals(21, merged.size)
        assertEquals(older.take(16), merged.take(16))
        assertEquals(refreshed, merged.drop(16).dropLast(1))
        assertEquals(next, merged.last())
    }
    @Test fun `a delayed empty history refresh cannot erase completed streamed output`() {
        val previous = listOf(UITurn(turnId = "turn", role = "assistant", messages = listOf(UIMessage(0, "text", content = "Done"))))
        assertEquals(previous, mergeLatestHistory(previous, emptyList()))
        val persisted = previous.single().copy(id = "stored-id")
        assertEquals(listOf(persisted), mergeLatestHistory(previous, listOf(persisted)))
    }
    @Test fun `attachments use the owning bot media path before fallback URLs`() {
        val attachment = dev.memoh.core.model.UIAttachment(contentHash = "sha256:asset/1", botId = "owner bot", url = "https://example.com/stale")
        assertEquals("/bots/owner%20bot/media/sha256%3Aasset%2F1",
            dev.memoh.feature.chat.components.attachmentSource(attachment, "current-bot"))
        assertEquals("/bots/current-bot/media/sha256%3Aasset%2F1",
            dev.memoh.feature.chat.components.attachmentSource(attachment.copy(botId = null), "current-bot"))
    }
    @Test fun `inline and workspace attachments retain their usable sources`() {
        assertEquals("data:image/png;base64,cGlj", dev.memoh.feature.chat.components.attachmentSource(
            dev.memoh.core.model.UIAttachment(base64 = "cGlj", mime = "image/png"), "bot"))
        assertEquals("/bots/bot/container/fs/download?path=%2Fdata%2Fa%20b.txt", dev.memoh.feature.chat.components.attachmentSource(
            dev.memoh.core.model.UIAttachment(path = "/data/a b.txt"), "bot"))
    }
    @Test fun `a user and assistant sharing a turn retain separate rows`() {
        val user = UITurn(turnId = "shared-turn", role = "user", text = "Question")
        val assistant = UITurn(turnId = "shared-turn", role = "assistant")
        assertNotEquals(user.listKey, assistant.listKey)
        assertEquals(2, listOf(user, assistant, user).distinctBy { it.listKey }.size)
    }
    @Test fun `separate stored user entries in a turn retain their message identity`() {
        val first = UITurn(turnId = "shared-turn", role = "user", id = "first")
        val second = UITurn(turnId = "shared-turn", role = "user", id = "second")
        assertNotEquals(first.listKey, second.listKey)
    }
    private val user = UITurn(turnId = "turn", role = "user", id = "user", text = "Question")
    private val assistant = UITurn(turnId = "turn", role = "assistant", id = "assistant",
        messages = listOf(UIMessage(id = 1, type = "text", content = "A long persisted reply")))
    private fun run(status: String, messages: List<UIMessage>?, configurationOnly: Boolean = false) =
        RuntimeState(run = RuntimeCurrentRunView(runId = "run", turnId = "turn", status = status,
            messages = messages, configurationOnly = configurationOnly))

    @Test fun `an idle snapshot with null blocks preserves the whole persisted conversation`() {
        val state = ChatUiState(history = listOf(user, assistant), runtime = run("completed", null))
        assertEquals(listOf(user, assistant), state.settledHistory)
        assertTrue(state.liveMessages.isEmpty())
    }
    @Test fun `a streaming reply replaces only assistant output and keeps its user question`() {
        val live = listOf(UIMessage(id = 1, type = "text", content = "Live reply"))
        val state = ChatUiState(history = listOf(user, assistant), runtime = run("running", live))
        assertEquals(listOf(user), state.settledHistory)
        assertEquals(live, state.liveMessages)
    }
    @Test fun `configuration-only runs leave history visible and have no typing state`() {
        val state = ChatUiState(history = listOf(user, assistant), runtime = run("running", emptyList(), true))
        assertEquals(listOf(user, assistant), state.settledHistory)
        assertFalse(state.isRunning)
    }
    @Test fun `a received user turn survives the optimistic acknowledgment until history arrives`() {
        val runtime = RuntimeState(run = RuntimeCurrentRunView(runId = "run", turnId = "turn",
            requestUserTurn = UIUserTurn(turnId = "turn", id = "user", text = "Question")))
        assertEquals("Question", ChatUiState(runtime = runtime).liveUserTurns.single().text)
        assertTrue(ChatUiState(runtime = runtime, history = listOf(user)).liveUserTurns.isEmpty())
    }
    @Test fun `an accepted send remains visible until its user turn arrives`() {
        val pending = PendingTurn("invocation", "Question", acceptedTurnId = "turn")
        val accepted = ChatUiState(pending = listOf(pending)).withRuntime(RuntimeState.EMPTY)
        assertEquals(listOf(pending), accepted.pending)
        val visible = accepted.withRuntime(RuntimeState(run = RuntimeCurrentRunView(
            runId = "run", turnId = "turn", invocationId = "invocation",
            requestUserTurn = UIUserTurn(turnId = "turn", id = "user", text = "Question"))))
        assertTrue(visible.pending.isEmpty())
        assertEquals("Question", visible.liveUserTurns.single().text)
    }
    @Test fun `a snapshot preceding the acknowledgment removes only its matching pending send`() {
        val pending = PendingTurn("invocation", "Question")
        val unrelated = PendingTurn("other", "Another question")
        val visible = ChatUiState(pending = listOf(pending, unrelated)).withRuntime(RuntimeState(run = RuntimeCurrentRunView(
            runId = "run", turnId = "turn", invocationId = "invocation",
            requestUserTurn = UIUserTurn(turnId = "turn", id = "user", text = "Question"))))
        assertEquals(listOf(unrelated), visible.pending)
    }
    @Test fun `completion preserves output immediately before REST history arrives`() {
        val live = listOf(UIMessage(id = 1, type = "text", content = "Final reply"))
        val completed = ChatUiState(runtime = run("running", live)).withRuntime(run("completed", live))
        assertEquals("Final reply", completed.settledHistory.single().safeMessages.single().content)
        assertTrue(completed.liveMessages.isEmpty())
    }
    @Test fun `an empty terminal snapshot keeps the last streamed output`() {
        val live = listOf(UIMessage(id = 1, type = "text", content = "Final reply"))
        val completed = ChatUiState(runtime = run("running", live)).withRuntime(run("completed", null))
        assertEquals(live, completed.settledHistory.single().safeMessages)
    }
    @Test fun `a new run cannot temporarily remove the preceding reply`() {
        val live = listOf(UIMessage(id = 1, type = "text", content = "Previous reply"))
        val current = ChatUiState(runtime = run("running", live)).withRuntime(RuntimeState(run = RuntimeCurrentRunView(
            runId = "next-run", turnId = "next-turn", status = "running", messages = emptyList())))
        assertEquals(live, current.settledHistory.single().safeMessages)
    }
    @Test fun `completion updates existing partial history instead of duplicating the assistant`() {
        val live = listOf(UIMessage(id = 1, type = "text", content = "Final reply"))
        val completed = ChatUiState(history = listOf(user, assistant), runtime = run("running", live))
            .withRuntime(run("completed", live))
        assertEquals(2, completed.settledHistory.size)
        assertEquals("assistant", completed.settledHistory.last().id)
        assertEquals(live, completed.settledHistory.last().safeMessages)
    }
    @Test fun `history renumbering cannot replace a text row with the preceding reasoning row`() {
        val live = listOf(
            UIMessage(id = 0, type = "status", name = "running"),
            UIMessage(id = 1, type = "reasoning", content = "Thinking"),
            UIMessage(id = 2, type = "text", content = "Reply"),
        )
        val stored = listOf(live[1].copy(id = 0), live[2].copy(id = 1))
        assertEquals(assistantBlockKeys("turn", live).drop(1), assistantBlockKeys("turn", stored))
    }
    @Test fun `repeated blocks remain distinct while tools retain their call identity`() {
        val live = listOf(
            UIMessage(id = 3, type = "text", content = "Before"),
            UIMessage(id = 4, type = "tool", toolCallId = "call-1"),
            UIMessage(id = 5, type = "text", content = "Between"),
            UIMessage(id = 6, type = "tool", toolCallId = "call-2"),
            UIMessage(id = 7, type = "text", content = "After"),
        )
        val keys = assistantBlockKeys("turn", live)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys, assistantBlockKeys("turn", live.mapIndexed { i, message -> message.copy(id = i) }))
        assertNotEquals(keys, assistantBlockKeys("another-turn", live))
    }
}
