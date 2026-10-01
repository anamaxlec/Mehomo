package dev.memoh.core.network

import dev.memoh.core.model.WSClientMessage
import dev.memoh.core.model.WSUserInputAnswer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatSocketIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @Test fun `sidebar observes several sessions replaces its set and restores subscriptions after reconnect`() {
        val server = MockWebServer()
        val frames = LinkedBlockingQueue<String>()
        val connections = LinkedBlockingQueue<WebSocket>()
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { connections.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
        })) }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        val socket = ChatSocket(OkHttpClient(), json, scope, buildRequest = {
            attempts.incrementAndGet()
            Request.Builder().url(server.url("/web/ws")).build()
        }, onEvent = {})
        try {
            socket.observeSessions(setOf("a", "b"))
            socket.connect()
            val initial = listOf(next(frames), next(frames))
            assertEquals(setOf("a", "b"), initial.map { it["session_id"]!!.jsonPrimitive.content }.toSet())
            assertTrue(initial.all { it["type"]!!.jsonPrimitive.content == "runtime_subscribe" })
            socket.observeSessions(setOf("b", "c"))
            val replaced = listOf(next(frames), next(frames))
            assertTrue(replaced.any { it["type"]!!.jsonPrimitive.content == "runtime_unsubscribe" && it["session_id"]!!.jsonPrimitive.content == "a" })
            assertTrue(replaced.any { it["type"]!!.jsonPrimitive.content == "runtime_subscribe" && it["session_id"]!!.jsonPrimitive.content == "c" })
            connections.poll(5, TimeUnit.SECONDS)!!.close(1001, "fixture reconnect")
            val restored = listOf(next(frames), next(frames))
            assertEquals(setOf("b", "c"), restored.map { it["session_id"]!!.jsonPrimitive.content }.toSet())
            assertTrue(restored.all { it["type"]!!.jsonPrimitive.content == "runtime_subscribe" })
            assertEquals(2, attempts.get())
        } finally { socket.close(); scope.cancel(); server.shutdown() }
    }
    @Test fun `model target and decision answers travel over the real socket with acknowledgments`() {
        val server = MockWebServer()
        val frames = LinkedBlockingQueue<String>()
        val events = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.add(text)
                val frame = json.parseToJsonElement(text).jsonObject
                val control = frame["control_id"]?.jsonPrimitive?.content
                if (control != null) webSocket.send("""{"type":"control_ack","control_id":"$control","applied":true}""")
                if (frame["type"]?.jsonPrimitive?.content == "retry_message") {
                    val invocation = frame["invocation_id"]!!.jsonPrimitive.content
                    webSocket.send("""{"type":"run_accepted","invocation_id":"$invocation","turn_id":"new-turn"}""")
                }
            }
        }))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = ChatSocket(OkHttpClient(), json, scope,
            buildRequest = { Request.Builder().url(server.url("/web/ws")).build() },
            onEvent = { events.add(it.type) })
        try {
            socket.connect("session")
            assertEquals("runtime_subscribe", next(frames)["type"]!!.jsonPrimitive.content)
            socket.send(WSClientMessage(type = "message", sessionId = "session", invocationId = "invocation", text = "Task", modelId = "model", reasoningEffort = "high", workspaceTargetId = "computer"))
            val message = next(frames)
            assertEquals("model", message["model_id"]!!.jsonPrimitive.content)
            assertEquals("high", message["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals("computer", message["workspace_target_id"]!!.jsonPrimitive.content)
            socket.send(WSClientMessage(type = WSClientMessage.RETRY_MESSAGE, sessionId = "session",
                invocationId = "retry", turnId = "previous-turn", modelId = "ds-v4", reasoningEffort = "low", workspaceTargetId = "computer"))
            val retry = next(frames)
            assertEquals("retry_message", retry["type"]!!.jsonPrimitive.content)
            assertEquals("previous-turn", retry["turn_id"]!!.jsonPrimitive.content)
            assertEquals("ds-v4", retry["model_id"]!!.jsonPrimitive.content)
            assertEquals("low", retry["reasoning_effort"]!!.jsonPrimitive.content)
            assertNull(retry["text"])
            assertEquals("run_accepted", events.poll(5, TimeUnit.SECONDS))
            socket.respondToApproval("run", "session", "approval", "approve", "allow-once")
            val approval = next(frames)
            assertEquals("tool_approval_response", approval["type"]!!.jsonPrimitive.content)
            assertEquals("allow-once", approval["option_id"]!!.jsonPrimitive.content)
            assertNotNull(approval["control_id"])
            assertEquals("control_ack", events.poll(5, TimeUnit.SECONDS))
            socket.respondToUserInput("run", "session", "question", listOf(WSUserInputAnswer("q", optionIds = listOf("a"))))
            val input = next(frames)
            assertEquals("user_input_response", input["type"]!!.jsonPrimitive.content)
            assertEquals("a", input["answers"]!!.jsonArray.single().jsonObject["option_ids"]!!.jsonArray.single().jsonPrimitive.content)
            assertEquals("control_ack", events.poll(5, TimeUnit.SECONDS))
        } finally { socket.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun `a failed asynchronous handshake retries and rebuilds authentication`() {
        val server = MockWebServer()
        val frames = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
        }))
        server.start()
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val socket = ChatSocket(OkHttpClient(), json, scope, buildRequest = {
            if (attempts.incrementAndGet() <= 2) throw IOException("Temporary ticket failure")
            Request.Builder().url(server.url("/web/ws")).header("Authorization", "Bearer renewed").build()
        }, onEvent = {})
        try {
            socket.connect("session")
            assertEquals("runtime_subscribe", next(frames)["type"]!!.jsonPrimitive.content)
            assertEquals(3, attempts.get())
            assertEquals("Bearer renewed", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        } finally { socket.close(); scope.cancel(); server.shutdown() }
    }
    private fun next(queue: LinkedBlockingQueue<String>): JsonObject =
        json.parseToJsonElement(queue.poll(8, TimeUnit.SECONDS) ?: throw AssertionError("No WebSocket frame received")).jsonObject
}
