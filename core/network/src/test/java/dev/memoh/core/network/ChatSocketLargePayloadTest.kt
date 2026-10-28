package dev.memoh.core.network

import dev.memoh.core.model.WSClientMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ChatSocketLargePayloadTest {
    @Test fun oversizedFrameMustNotEnterReconnectLoop() {
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val statuses = LinkedBlockingQueue<SocketStatus>()
        val socket = ChatSocket(
            OkHttpClient(), Json { encodeDefaults = false; explicitNulls = false }, scope,
            { Request.Builder().url(server.url("/web/ws")).build() }, {}, { statuses.add(it) },
        )
        try {
            socket.connect("session")
            assertEquals(SocketStatus.Connecting, statuses.poll(5, TimeUnit.SECONDS))
            assertEquals(SocketStatus.Connected, statuses.poll(5, TimeUnit.SECONDS))
            val result = socket.send(WSClientMessage(
                type = WSClientMessage.MESSAGE, sessionId = "session", invocationId = "big",
                text = "x".repeat(16 * 1024 * 1024),
            ))
            assertEquals(SocketSendResult.TooLarge, result)
            assertFalse(socket.pendingKeys.contains("invocation:big"))
            assertNull("An unsendable frame must not disturb the connection", statuses.poll(3, TimeUnit.SECONDS))
        } finally {
            socket.close()
            scope.cancel()
            server.shutdown()
        }
    }
}
