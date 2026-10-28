package dev.memoh.core.network

import dev.memoh.core.model.WSClientMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ChatSocketMobileRecoveryTest {
    @Test fun aMessageQueuedDuringConnectionIsNotSentTwice() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val frames = LinkedBlockingQueue<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
        }))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sent = AtomicBoolean()
        lateinit var socket: ChatSocket
        socket = ChatSocket(OkHttpClient(), json, scope,
            buildRequest = { Request.Builder().url(server.url("/web/ws")).build() },
            onEvent = {}, onStatus = { status ->
                if (status == SocketStatus.Connected && sent.compareAndSet(false, true)) {
                    socket.send(WSClientMessage(type = WSClientMessage.MESSAGE, sessionId = "s", invocationId = "one", text = "hello"))
                }
            })
        try {
            socket.connect("s")
            val first = frames.poll(5, TimeUnit.SECONDS)
            val second = frames.poll(5, TimeUnit.SECONDS)
            assertNotNull(first)
            assertNotNull(second)
            val all = listOfNotNull(first, second, frames.poll(1, TimeUnit.SECONDS))
                .map { json.parseToJsonElement(it).jsonObject }
            assertEquals("Each unacknowledged message should be sent once on a connection", 1,
                all.count { it["type"]?.jsonPrimitive?.content == WSClientMessage.MESSAGE })
            assertEquals(1, all.count { it["type"]?.jsonPrimitive?.content == WSClientMessage.RUNTIME_SUBSCRIBE })
        } finally { socket.close(); scope.cancel(); server.shutdown() }
    }
}
